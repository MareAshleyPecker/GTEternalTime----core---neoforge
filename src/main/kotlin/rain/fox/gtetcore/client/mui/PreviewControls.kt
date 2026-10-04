package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.utils.Alignment
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.layout.Flow
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget
import net.minecraft.network.chat.Component
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MultiblockPreviewLang

/**
 * 多方块预览右上角那排控制按钮，内嵌（JEI / EMI 信息页、GTM 终端）与全屏共用同一份。
 *
 * 由 [rain.fox.gtetcore.mixin.gtm.MultiblockPreviewWidgetMixin] 在
 * `MultiblockPreviewWidget.<init>` 的 TAIL 调 [attach] 挂进去。
 *
 * **内嵌那排按钮的锚点**：不猜像素偏移，改成「认领 SchemaWidget 原来的槽位」——
 * 把 GTM 建的 `SchemaWidget` 从它父级里按索引摘掉，在**同一个索引**上放一个尺寸等于该
 * `SchemaWidget` 固定像素尺寸的 [SchemaHolder]，按钮与提示都挂进这个 holder，按钮在 holder 里
 * `right(0).top(0)`。holder 尺寸是确定值，`right/top` 不构成循环依赖（`coverChildren()` 父级上才会）。
 * 这么摆的理由：8.0.0 里 3D 控件右边还有 `parts_view`(20) 与 `padding(7)`，宿主（JEI / EMI）给整框报的
 * 尺寸也不等于控件尺寸，任何「schema 在父级里偏移 20px、按钮贴控件右上角」的绝对像素算式都会飘出框外。
 *
 * @author rain fox
 */
object PreviewControls {

    private const val ROW_NAME = "gtetscore_preview_controls"
    private const val BUTTON_SIZE = 16

    /** 正在构造的是全屏那份预览：全屏里那颗按钮是「退出」，内嵌那份是「全屏」。 */
    private var fullscreen: Boolean = false

    /** 构造全屏预览期间置位；见 [createRow]。 */
    fun beginFullscreen() {
        fullscreen = true
    }

    fun endFullscreen() {
        fullscreen = false
    }

    /**
     * 挂按钮条（**只挂内嵌那份**）+ 把内嵌的 3D 换成提示文字；失败不能连带 GTM 自己的预览控件打不开。
     *
     * `schemaWidth` / `schemaHeight` = `MultiblockPreviewWidget` 构造器第 3、4 个参数，也就是 3D 控件自己的尺寸
     * （`MultiblockPreviewWidget.java:192` 的 `.size(width, height)`）——只在读不到控件真实尺寸时当兜底。
     */
    @JvmStatic
    fun attach(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition,
               schemaWidth: Int, schemaHeight: Int) {
        // 全屏那份的按钮由 buildFullscreenPanel 直接钉在面板右下角、3D 也照常显示，这里不碰
        if (fullscreen) return
        // 三步**各自兜底**：S1.14 实机里提示那步抛了异常，被同一个 catch 吞掉后连按钮条一起没了（整块空白）。
        // 按钮条最后挂：它是交互入口，比别的都重要。
        val anchor = try {
            anchorFor(preview, schemaWidth, schemaHeight)
        } catch (t: Throwable) {
            warn("建立多方块预览按钮锚点失败", t)
            null
        }
        try {
            showFullscreenHint(anchor)
        } catch (t: Throwable) {
            warn("内嵌页 3D 占位提示挂载失败", t)
        }
        try {
            attachRow(anchor, preview, definition)
        } catch (t: Throwable) {
            warn("挂载多方块预览控制按钮失败", t)
        }
    }

    /**
     * 按钮与提示的落点：`SchemaWidget` 原来的槽位换成一个尺寸确定的容器。
     *
     * 失败返回 `null`（调用方各有兜底），不让 GTM 的预览控件打不开。
     */
    private fun anchorFor(preview: MultiblockPreviewWidget, schemaWidth: Int, schemaHeight: Int): Anchor? {
        val schema = preview.multiblockSchemaInfo?.multiSchema ?: return null
        // ⚠️ 不能用 `schema.parent`：`AbstractWidget.getParent` 带 isValid 守卫，构造期的子件
        // `valid == false`，直接抛 `IllegalStateException: SchemaWidget is not in a valid state!`
        // （就是 S1.14 实机整块空白的成因）。只能从拿到的预览控件往下找。
        val parent = findParent(preview, schema) ?: return null
        val index = parent.children.indexOfFirst { it === schema }
        if (index < 0) return null

        // 尺寸取自 SchemaWidget 自己（GTM 构造器里 `.size(width, height)` 钉死的固定像素），拿不到才退回
        // 构造参数。这是本方法唯一的两个数字，且都是 schema 自己的尺寸、不是它在父级里的偏移。
        val width = schema.resizer().fixedPixelWidth.takeIf { it > 0 } ?: schemaWidth
        val height = schema.resizer().fixedPixelHeight.takeIf { it > 0 } ?: schemaHeight

        val holder = SchemaHolder().size(width, height)
        val parentValid = parent.isValid()
        // ⚠️ 必须用**索引式** remove：`AbstractParentWidget.remove(IWidget)` 内部是
        // `ArrayList.remove(Object)` → `o.equals(e)`，而 MUI 的 `Widget.equals` 写的是
        // `o.getClass() != Widget.class → false`（javap：equals 偏移 0-14），
        // 也就是**任何 Widget 子类都不等于它自己** ⇒ 传子件进去永远摘不掉。
        if (!parent.remove(index)) return null
        // 摘掉的子树**尚未 validate**，而 `remove` 只在父 `isValid()` 时才 dispose 被摘子件，这里补一次；
        // `SchemaWidget.dispose` 会调 `SchemaRenderer.dispose`（cancelCompilation + clearBuffer + discardAll）。
        if (!parentValid) schema.dispose()
        if (!parent.addChild(holder, index)) return null
        return Anchor(holder, width, height)
    }

    /**
     * 内嵌页不再显示 3D（配方查看器里的绝对视口错位不修了）：`SchemaWidget` 已经被 [anchorFor] 摘掉，
     * 原位放一行居中提示。
     */
    private fun showFullscreenHint(anchor: Anchor?) {
        // 认不出 schema 的落点就什么都不放：宁可不显示，也不去乱盖 3D
        val holder = anchor?.holder ?: return
        // 用 TextWidget 而不是 `IDrawable.DrawableWidget(Text.lang(..).asIcon())`：`asIcon()` 返回的是
        // `drawable.Icon`（图标语义，按 box 缩放），多行文本画成什么样不受控；TextWidget 走同一套
        // TextRenderer（按宽折行 + 水平/垂直居中）。
        val hint = TextWidget(Component.translatable(MultiblockPreviewLang.HINT_FULLSCREEN))
            .size(anchor.width, anchor.height)
            .textAlign(Alignment.Center)
            .tooltip { r -> r.addLine(Text.lang(MultiblockPreviewLang.HINT_FULLSCREEN)) }
        holder.addChild(hint, -1)
    }

    /** 内嵌那排只有「全屏」一颗（见 [createRow] 的 `fullscreen = false` 分支）。 */
    private fun attachRow(anchor: Anchor?, preview: MultiblockPreviewWidget,
                          definition: MultiblockMachineDefinition) {
        val row = createRow(preview, definition, false)
        if (anchor != null) {
            // holder 尺寸确定 ⇒ 钉右上角不会反过来影响父级尺寸（老写法在 coverChildren 父级上算绝对坐标，
            // 一旦父级真实宽度与假设不符按钮就落到框外）
            anchor.holder.addChild(row.right(0).top(0), -1)
        } else {
            // 认不出 schema 的父级（正常构造路径不会发生）：退回预览控件的左上角，保证「全屏」点得到，
            // 这里同样不猜 3D 的偏移
            preview.addChild(row.left(0).top(0), -1)
        }
    }

    /** 在自己这棵子树里按引用找 `target` 的父级（构造期用不了 `getParent()`）。 */
    private fun findParent(root: IWidget, target: IWidget): ParentWidget<*>? {
        val parent = root as? ParentWidget<*> ?: return null
        if (parent.children.any { it === target }) return parent
        for (child in parent.children) findParent(child, target)?.let { return it }
        return null
    }

    private fun warn(message: String, t: Throwable) {
        GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] $message", t)
    }

    /** 按钮条本体；位置由调用方定（内嵌=holder 右上角，全屏=面板右下角）。 */
    fun createRow(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition,
                  fullscreen: Boolean): Flow {
        val row = Flow.row().name(ROW_NAME).coverChildren().childPadding(2)
        if (fullscreen) {
            row.child(
                iconButton(GuiTextures.CLOSE, MultiblockPreviewLang.BUTTON_EXIT) { closeFullscreen(preview) }
            )
        } else {
            row.child(
                iconButton(GuiTextures.FULLSCREEN, MultiblockPreviewLang.BUTTON_FULLSCREEN) {
                    MultiblockPreviewFullscreen.open(definition)
                }
            )
        }
        // 内嵌页已经不放 3D 了，「重置视角」没有对象 ⇒ 只留在全屏那排
        if (fullscreen) {
            row.child(
                iconButton(GuiTextures.REFRESH, MultiblockPreviewLang.BUTTON_RESET_VIEW) {
                    val info = preview.multiblockSchemaInfo
                    PreviewCameraFit.reset(info?.multiSchema, info?.mapSchema)
                }
            )
        }
        return row
    }

    /** 给全屏的「上一个 / 下一个」用（同一个图标按钮壳）。 */
    fun arrowButton(icon: IDrawable, tooltipKey: String, action: () -> Unit): IWidget =
        iconButton(icon, tooltipKey, action)

    private fun iconButton(icon: IDrawable, tooltipKey: String, action: () -> Unit): PreviewButton =
        PreviewButton()
            .size(BUTTON_SIZE)
            .overlay(icon)
            .onMousePressed { _, _ ->
                action()
                true
            }
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r -> r.addLine(Text.lang(tooltipKey)) }

    private fun closeFullscreen(preview: MultiblockPreviewWidget) {
        val screen = preview.screen
        if (screen is MultiblockPreviewFullscreenScreen) screen.closeOverlay()
    }

    /** 按钮与提示的落点；[holder] 就是我们插进 `schema_widgets` 那一排、尺寸等于 3D 控件的容器。 */
    private class Anchor(val holder: ParentWidget<*>, val width: Int, val height: Int)

    /** MUI 的控件是 `Foo<W extends Foo<W>>`，Kotlin 里没法用菱形推断（先例 MachineIoConfigPage.kt:315）。 */
    private class SchemaHolder : ParentWidget<SchemaHolder>()

    /** 同上。 */
    private class PreviewButton : ButtonWidget<PreviewButton>()
}
