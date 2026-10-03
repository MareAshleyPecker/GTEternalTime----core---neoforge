package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.layout.Flow
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MultiblockPreviewLang

/**
 * 多方块预览右上角那排控制按钮，内嵌（JEI / EMI 信息页、GTM 终端）与全屏共用同一份。
 *
 * 由 [rain.fox.gtetcore.mixin.gtm.MultiblockPreviewWidgetMixin] 在
 * `MultiblockPreviewWidget.<init>` 的 TAIL 调 [attach] 挂进去。
 *
 * @author rain fox
 */
object PreviewControls {

    private const val ROW_NAME = "gtetscore_preview_controls"
    private const val BUTTON_SIZE = 16

    /** 两颗 16px 按钮 + `childPadding(2)`。 */
    private const val ROW_WIDTH = BUTTON_SIZE * 2 + 2

    /** 3D 视图在预览控件内容区里的左边界：`selected_block` 恒为 20 宽（MultiblockPreviewWidget.java:248-251）。 */
    private const val SCHEMA_X = 20

    /** 正在构造的是全屏那份预览：全屏里那颗按钮是「退出」，内嵌那份是「全屏」。 */
    private var fullscreen: Boolean = false

    /** 构造全屏预览期间置位；见 [buildFullscreenPanel]。 */
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
     * （`MultiblockPreviewWidget.java:192` 的 `.size(width, height)`）。
     */
    @JvmStatic
    fun attach(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition,
               schemaWidth: Int, schemaHeight: Int) {
        // 全屏那份的按钮由 buildFullscreenPanel 直接钉在面板右下角、3D 也照常显示，这里不碰
        if (fullscreen) return
        try {
            showFullscreenHint(preview, schemaWidth, schemaHeight)
            val row = createRow(preview, definition, false)
            // 只给像素制的 left/top：right(int) / rightRel(float) 都要先知道父级宽度，而父级是
            // `coverChildren()`（宽度反过来由子件撑出），是循环依赖 —— MUI 会先按未解析值摆一次，父级可能被撑大；
            // 而 JEI 那边整框尺寸取自控件的固定尺寸，取不到就退回 getMaxWidth/getMaxHeight = 200x180
            // （MultiblockInfoJeiCategory.java:56-63 → ModularUIJeiCategory.getWidth(recipe)），父级一变大框就跟着变。
            // 用构造参数里的 3D 控件宽度直接算绝对坐标，父级尺寸与这排按钮无关。
            row.left(SCHEMA_X + schemaWidth - ROW_WIDTH - 2).top(2)
            preview.child(row)
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 挂载多方块预览控制按钮失败", t)
        }
    }

    /**
     * 内嵌页不再显示 3D（配方查看器里的绝对视口错位不修了），把 SchemaWidget 从树里摘掉、原位放一行居中提示。
     *
     * <p>SchemaWidget 是普通控件、不带 handler，摘掉安全（与全屏那边 `parts_view` 的情况不同）。
     */
    private fun showFullscreenHint(preview: MultiblockPreviewWidget, schemaWidth: Int, schemaHeight: Int) {
        val schema = preview.multiblockSchemaInfo?.multiSchema ?: return
        val parent = schema.parent as? ParentWidget<*> ?: return
        val index = parent.children.indexOf(schema)
        val parentValid = parent.isValid()
        // 摘掉的是**尚未 validate** 的子树（本函数跑在 MultiblockPreviewWidget 构造器里），
        // 而 `AbstractParentWidget.remove` 只在父 `isValid()` 时才 dispose 被摘的子件 —— 这里补一次：
        // `SchemaWidget.dispose` 会调 `SchemaRenderer.dispose`（cancelCompilation + clearBuffer + discardAll），
        // GTM 自己从不 dispose 这个 renderer（全 jar 里没有同时引用 dispose 与 SchemaRenderer 的类），
        // 不补就会每次打开信息页漏一份 renderer 资源。
        if (!parent.remove(schema)) return
        if (!parentValid) schema.dispose()
        val hint = IDrawable.DrawableWidget(Text.lang(MultiblockPreviewLang.HINT_FULLSCREEN).asIcon().center())
            .size(schemaWidth, schemaHeight)
            .tooltip { r -> r.addLine(Text.lang(MultiblockPreviewLang.HINT_FULLSCREEN)) }
        if (index >= 0) parent.addChild(hint, index) else parent.child(hint)
    }

    /** 按钮条本体；位置由调用方定（内嵌=控件右上角，全屏=面板右下角）。 */
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

    /** MUI 的控件是 `Foo<W extends Foo<W>>`，Kotlin 里没法用菱形推断（先例 MachineIoConfigPage.kt:315）。 */
    private class PreviewButton : ButtonWidget<PreviewButton>()
}
