package rain.fox.gtetcore.client.mui

import brachy.modularui.drawable.Rectangle
import brachy.modularui.overlay.OverlayStack
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.ModularScreen
import brachy.modularui.screen.viewport.ModularGuiContext
import brachy.modularui.utils.Color
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.Level
import org.lwjgl.glfw.GLFW
import rain.fox.gtetcore.GTETSCore

/**
 * 多方块 3D 预览的全屏化。
 *
 * 走 MUI 自带的 overlay 体系，与 MUI 自己的 `DebugOverlay`（brachy/modularui/overlay/DebugOverlay.java）同一路：
 * `ModularScreen.constructOverlay(Screen)` → `OverlayStack.open`。overlay 叠在 JEI 配方界面上，
 * JEI 那一屏完全不动，退出后自然回到原样。
 *
 * overlay 的输入分发：`ClientScreenHandler.doAction` 先 `OverlayStack.interact(predicate, true)` 再轮到
 * `currentScreen`（ClientScreenHandler.java:keyPressedEvent / onScreenMousePressed），所以全屏里
 * 鼠标事件与 ESC 都先到这边。
 *
 * @author rain fox
 */
object MultiblockPreviewFullscreen {

    @JvmStatic
    fun open(definition: MultiblockMachineDefinition) {
        val mc = Minecraft.getInstance()
        // constructOverlay(Screen) 对参数做非空校验（ModularScreen.constructOverlay 里 NPE）：
        // JEI 没有 Minecraft Screen 时宁可不做，也不能把它变成一次崩溃
        val parent = mc.screen ?: return
        try {
            val screen = MultiblockPreviewFullscreenScreen(definition)
            screen.constructOverlay(parent)
            OverlayStack.open(screen)
            // 覆盖层是点击后才建的，错过了 ScreenEvent.Init.Post 那轮 OverlayStack.foreach(onResize)；
            // 补这一次 onResize 才会走 PanelManager.tryInit 开面板（面板本身在构造器里就建好了）
            screen.onResize(mc.window.guiScaledWidth, mc.window.guiScaledHeight)
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 打开多方块全屏预览失败", t)
        }
    }

    /** overlay 从栈里摘掉 + 关面板 + dispose（`ModularScreen.close(true)` 对 overlay 是空操作，不能用）。 */
    @JvmStatic
    fun close(screen: ModularScreen) {
        try {
            OverlayStack.close(screen)
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 关闭多方块全屏预览失败", t)
        }
    }
}

/**
 * 全屏预览那一屏：灰色半透明遮罩 + 同款 [MultiblockPreviewWidget]。
 *
 * ⚠️ `definition` 只走构造器参数、面板只由 [buildFullscreenPanel] 经 lambda 闭包建：
 * `ModularScreen` 的**构造器里**就会调 `buildUI` —— javap：`ModularScreen.<init>` 偏移 100-128，
 * 有 factory 时 `factory.apply(context)`，否则虚调 `buildUI(context)`；无 factory 时基类实现直接抛
 * `UnsupportedOperationException`，所以 `CustomModularScreen` 的覆写必定是在 super() 里被调的。
 * 而 Kotlin 的实例字段在 super() 之后才赋值 —— 面板里读实例字段只能拿到 null（这就是实机里
 * `MultiblockPreviewWidget.<init>` 第 101 行 `definition.getRotationState()` 空指针的成因）。
 *
 * @author rain fox
 */
class MultiblockPreviewFullscreenScreen(
    definition: MultiblockMachineDefinition
) : ModularScreen(OWNER, { context -> buildFullscreenPanel(definition, context) }) {

    /** 遮罩铺满整屏，空白处的点击也吃掉，不许穿透到底下的 JEI 界面。 */
    override fun mousePressed(button: Int): Boolean {
        super.mousePressed(button)
        return true
    }

    override fun mouseReleased(button: Int): Boolean {
        super.mouseReleased(button)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double): Boolean {
        super.mouseScrolled(mouseX, mouseY)
        return true
    }

    override fun mouseDragged(button: Int, dragX: Double, dragY: Double): Boolean {
        super.mouseDragged(button, dragX, dragY)
        return true
    }

    /** ESC 只负责退出这一层；不拦的话 MUI 会去关它下面那一屏（JEI）的面板。 */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    fun closeOverlay() {
        // 控件树的释放由 MUI 自己做：OverlayStack.close → PanelManager.closeAll + dispose
        // → ModularPanel.dispose → ParentWidget 递归 dispose → SchemaWidget.dispose 放掉渲染器
        MultiblockPreviewFullscreen.close(this)
    }
}

/** 见 [MultiblockPreviewFullscreenScreen] 的类注释：只能从构造器参数闭包进来，不能读实例字段。 */
private fun buildFullscreenPanel(definition: MultiblockMachineDefinition,
                                 context: ModularGuiContext): ModularPanel<*> {
    val window = Minecraft.getInstance().window
    val preview = createPreview(
        definition,
        (window.guiScaledWidth - PREVIEW_MARGIN_X).coerceAtLeast(MIN_PREVIEW_SIZE),
        (window.guiScaledHeight - PREVIEW_MARGIN_Y).coerceAtLeast(MIN_PREVIEW_SIZE)
    )
    val panel = FullscreenPanel().sizeRel(1f).background(Rectangle().color(BACKDROP_COLOR))
    panel.child(preview.center())
    return panel
}

private fun createPreview(definition: MultiblockMachineDefinition, width: Int, height: Int): MultiblockPreviewWidget {
    PreviewControls.beginFullscreen()
    try {
        // 3D 取景 / 旋转 / 缩放 / 点面全部来自 GTM 原控件本身，跟内嵌版一致
        val preview = MultiblockPreviewWidget(definition, null, width, height)
        // `SchemaRenderer.rayTracing` 构造器里默认 false（javap：`SchemaRenderer.<init>` iconst_0 → putfield），
        // 不开时 `BaseSchemaRenderer.draw` 不进 `Area.isInside → rayTrace` 那段，`lastRayTrace()` 恒为 null，
        // 点面/悬停高亮/选块菜单全是死的（GTM 自己那份 `MultiblockPreviewWidget.java:169-192` 也没开）。
        // 只作用于这一份实例。
        preview.multiblockSchemaInfo?.renderer?.rayTracing(true)
        return preview
    } finally {
        PreviewControls.endFullscreen()
    }
}

private class FullscreenPanel : ModularPanel<FullscreenPanel>(PANEL_NAME)

private const val OWNER = "gtetscore"
private const val PANEL_NAME = "gtetscore_multiblock_preview_fullscreen"

/** 让出四周的空间，预览控件自己的滑条列 / 部件列还要占几十像素。 */
private const val PREVIEW_MARGIN_X = 70
private const val PREVIEW_MARGIN_Y = 70
private const val MIN_PREVIEW_SIZE = 120

/** 灰色半透明底（ARGB）：0.85 让底下的 JEI / EMI 只剩隐约轮廓，才像"铺满的一层"。 */
private val BACKDROP_COLOR: Int = Color.withAlpha(Color.rgb(128, 128, 128), 0.85f)
