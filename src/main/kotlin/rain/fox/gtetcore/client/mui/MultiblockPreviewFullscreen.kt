package rain.fox.gtetcore.client.mui

import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.Rectangle
import brachy.modularui.overlay.OverlayStack
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.ModularScreen
import brachy.modularui.screen.viewport.ModularGuiContext
import brachy.modularui.utils.Alignment
import brachy.modularui.utils.Color
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widget.Widget
import brachy.modularui.widgets.ItemDisplayWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.layout.Flow
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.apache.logging.log4j.Level
import org.lwjgl.glfw.GLFW
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MultiblockPreviewLang
import java.util.Comparator

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

    /**
     * 面板尺寸**显式按窗口像素钉死**：MUI 给 main panel 的 `sizeRel(1f)` 参照的尺寸比窗口小一截
     * （探针实测 576x303 vs 屏幕区 640x337），只写 `sizeRel(1f)` 遮罩盖不满窗口。
     * 每次 resize（窗口改变时 MUI 也会调这里）重新同步一次。
     */
    override fun onResize(width: Int, height: Int) {
        super.onResize(width, height)
        mainPanel?.size(width, height)
    }

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
    val width = window.guiScaledWidth
    val height = window.guiScaledHeight
    // 尺寸写死在窗口像素上（不靠 sizeRel(1f)，见 onResize 注释），遮罩才真正铺满
    val panel = FullscreenPanel().size(width, height).background(Rectangle().color(BACKDROP_COLOR))
    FullscreenSwitcher(panel, width, height, definition).build()
    return panel
}

/**
 * 全屏内容：3D 铺满 + 左下部件数 + 右下按钮（含「上一个 / 下一个」），并负责切换多方块。
 *
 * <p>候选来自 GTM 注册表**只读枚举**（`GTRegistries.MACHINES`，与 `MultiblockInfoJeiCategory.registerRecipes`
 * 同一套筛选），按 id 排序 ⇒ 顺序稳定、不硬编码清单；`isRenderXEIPreview` 为假的（GTM 自己不给预览的）以及
 * 拿不到 `main` 图案的定义会被过滤掉。切换时重建预览控件（取景由 `PreviewCameraFit` 按新结构重算）与左下部件数列表。
 */
private class FullscreenSwitcher(
    private val panel: ModularPanel<*>,
    private val width: Int,
    private val height: Int,
    private var definition: MultiblockMachineDefinition
) {

    private val candidates: List<MultiblockMachineDefinition> = multiblockCandidates()
    private var index: Int = candidates.indexOf(definition).coerceAtLeast(0)
    private var preview: MultiblockPreviewWidget? = null
    private var parts: IWidget? = null
    private var label: IWidget? = null
    private var row: IWidget? = null

    fun build() {
        apply(definition)
    }

    private fun cycle(delta: Int) {
        if (candidates.size < 2) return
        index = (index + delta + candidates.size) % candidates.size
        apply(candidates[index])
    }

    /** 换一份预览：先把旧的从面板摘掉再挂新的（MUI 支持运行时增删子件，`DynamicWidget.updateChild` 就是这套）。 */
    private fun apply(target: MultiblockMachineDefinition) {
        try {
            preview?.let { panel.remove(it) }
            parts?.let { panel.remove(it) }
            label?.let { panel.remove(it) }
            row?.let { panel.remove(it) }

            definition = target
            index = candidates.indexOf(target).coerceAtLeast(0)
            val newPreview = createPreview(
                target,
                (width - PREVIEW_MARGIN_X).coerceAtLeast(MIN_PREVIEW_SIZE),
                (height - PREVIEW_MARGIN_Y).coerceAtLeast(MIN_PREVIEW_SIZE)
            )
            val newRow = PreviewControls.createRow(newPreview, target, true)
            if (candidates.size >= 2) {
                // 箭头按钮必须**每次重建**：`AbstractParentWidget.remove` 会 dispose 被摘掉的子树
                // （`remove(I)` 在父有效时调 `IWidget.dispose()`，`Widget.dispose` 递归 dispose 子件），
                // 复用同一对按钮 ⇒ 第二次切换时挂进去的是已 dispose、valid=false 的控件，既不再绘制也点不动。
                // IParentWidget.child 的参数顺序是 (index, widget)
                newRow.child(0, PreviewControls.arrowButton(GuiTextures.MOVE_LEFT,
                    MultiblockPreviewLang.BUTTON_PREV) { cycle(-1) })
                newRow.child(1, PreviewControls.arrowButton(GuiTextures.MOVE_RIGHT,
                    MultiblockPreviewLang.BUTTON_NEXT) { cycle(+1) })
            }
            val newParts = buildPartsColumn(newPreview).left(4).bottom(4)
            val newLabel = TextWidget(nameOf(target))
                .size(LABEL_WIDTH, LABEL_HEIGHT)
                .textAlign(Alignment.CenterLeft)
                .left(4).top(4)

            preview = newPreview
            parts = newParts
            row = newRow
            label = newLabel
            panel.child(newPreview.center())
            panel.child(newParts)
            panel.child(newLabel)
            panel.child(newRow.right(4).bottom(4))
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 切换全屏多方块预览失败", t)
        }
    }

    private fun nameOf(target: MultiblockMachineDefinition): Component {
        // langValue 在 GT/GTM 侧是 @Nullable，缺失时退回注册名，避免出现 "null" 文本
        val name = Component.translatable(target.langValue ?: target.id.toString())
        return if (candidates.size < 2) {
            name
        } else {
            Component.translatable(MultiblockPreviewLang.LABEL_INDEX,
                name, (index + 1).toString(), candidates.size.toString())
        }
    }
}

/** 只读枚举：GTM 注册表里的多方块，按 id 排序（顺序稳定）。 */
private fun multiblockCandidates(): List<MultiblockMachineDefinition> =
    try {
        GTRegistries.MACHINES.stream()
            .filter { it is MultiblockMachineDefinition }
            .map { it as MultiblockMachineDefinition }
            .filter { it.isRenderXEIPreview }
            .filter { it.structurePatterns.containsKey(MultiblockControllerMachine.DEFAULT_STRUCTURE) }
            .sorted(Comparator.comparing { d -> d.id.toString() })
            .toList()
    } catch (t: Throwable) {
        emptyList()
    }

private fun createPreview(definition: MultiblockMachineDefinition, width: Int, height: Int): MultiblockPreviewWidget {
    PreviewControls.beginFullscreen()
    try {
        // 3D 取景 / 旋转 / 缩放 / 点面全部来自 GTM 原控件本身，跟内嵌版一致
        val preview = MultiblockPreviewWidget(definition, null, width, height)
        // 全屏只要「整屏 3D + 左下部件数 + 右下按钮」：
        // 滑条列（slice 重复 / 谓词菜单）与自带部件列从这里摘掉/压掉，3D 才能铺满整屏。
        // 部件列 `parts_view` 是带 DynamicHandler 的控件，**不 remove**（它的 handler 已挂监听，
        // 摘出树后 GTM 刷新时会在游离控件上 updateChild），改成 1px（MUI 的 `Widget.invisible()`
        // 只关掉主题/悬停背景，缩到 1px 才是让它画不出内容的那一步）。
        removeChild(preview, "structure_patterns")
        shrinkChild(preview, "parts_view")
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

/** 左下角部件数列表：GTM 原部件列用的是配方查看器槽位，overlay 里拿不到 JEI 槽（画不出东西），这里自己用 ItemDisplayWidget 画。 */
private fun buildPartsColumn(preview: MultiblockPreviewWidget): Flow {
    val column = Flow.col().name(PARTS_NAME).coverChildren().childPadding(1)
    val counts = preview.multiblockSchemaInfo?.blockCounts ?: return column
    for (entry in counts.reference2IntEntrySet().sortedByDescending { it.intValue }) {
        val stack = ItemStack(entry.key, entry.intValue)
        column.child(
            ItemDisplayWidget()
                .item(stack)
                .displayAmount(true)
                .size(18)
                .tooltip { r -> r.addFromItem(stack) }
        )
    }
    return column
}

/** 深度优先按名字摘掉子件（只动控件树，不改 GTM 源码）。 */
private fun removeChild(root: IWidget, name: String): Boolean {
    val parent = root as? ParentWidget<*> ?: return false
    val child = parent.children.firstOrNull { it.name == name }
    if (child != null) return parent.remove(child)
    return parent.children.any { removeChild(it, name) }
}

/** 不摘出树（handler 还挂在屏上），只压成 1px；`invisible()` 关的是主题/悬停背景。 */
private fun shrinkChild(root: IWidget, name: String): Boolean {
    val parent = root as? ParentWidget<*> ?: return false
    val child = parent.children.firstOrNull { it.name == name }
    if (child != null) {
        // invisible()/size() 在 Widget<W> 上，不在 IWidget 上
        val widget = child as? Widget<*> ?: return false
        widget.invisible()
        widget.size(1)
        return true
    }
    return parent.children.any { shrinkChild(it, name) }
}

private class FullscreenPanel : ModularPanel<FullscreenPanel>(PANEL_NAME)

private const val OWNER = "gtetscore"
private const val PANEL_NAME = "gtetscore_multiblock_preview_fullscreen"
private const val PARTS_NAME = "gtetscore_fullscreen_parts"

/** 只留控件自身的 padding(14) + selected_block(20) 那点空间，3D 尽量铺满整屏。 */
private const val PREVIEW_MARGIN_X = 50
private const val PREVIEW_MARGIN_Y = 30
private const val MIN_PREVIEW_SIZE = 120

/** 左上角那行「名字（序号/总数）」。 */
private const val LABEL_WIDTH = 420
private const val LABEL_HEIGHT = 12

/** 灰色半透明底（ARGB）：0.85 让底下的 JEI / EMI 只剩隐约轮廓，才像"铺满的一层"。 */
private val BACKDROP_COLOR: Int = Color.withAlpha(Color.rgb(128, 128, 128), 0.85f)
