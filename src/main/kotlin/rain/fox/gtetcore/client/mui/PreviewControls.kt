package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.drawable.GuiTextures
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
     * 挂按钮条；失败不能连带 GTM 自己的预览控件打不开。
     *
     * `schemaWidth` = `MultiblockPreviewWidget` 构造器第 3 个参数，也就是 3D 控件自己的宽度
     * （`MultiblockPreviewWidget.java:192` 的 `.size(width, height)`），拿它算绝对坐标。
     */
    @JvmStatic
    fun attach(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition, schemaWidth: Int) {
        try {
            preview.child(buildRow(preview, definition, schemaWidth))
            PreviewCameraFit.debugLog(if (fullscreen) "全屏" else "内嵌", preview.multiblockSchemaInfo, schemaWidth)
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 挂载多方块预览控制按钮失败", t)
        }
    }

    private fun buildRow(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition,
                         schemaWidth: Int): Flow {
        val row = Flow.row().name(ROW_NAME).coverChildren().childPadding(2)
        // 只给像素制的 left/top：right(int) / rightRel(float) 都要先知道父级宽度，而父级是
        // `coverChildren()`（宽度反过来由子件撑出），是循环依赖 —— MUI 会先按未解析值摆一次，父级可能被撑大；
        // 而 JEI 那边整框尺寸取自控件的固定尺寸，取不到就退回 getMaxWidth/getMaxHeight = 200x180
        // （MultiblockInfoJeiCategory.java:56-63 → ModularUIJeiCategory.getWidth(recipe)），父级一变大框就跟着变。
        // 用构造参数里的 3D 控件宽度直接算绝对坐标，父级尺寸与这排按钮无关。
        row.left(SCHEMA_X + schemaWidth - ROW_WIDTH - 2).top(2)

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
        row.child(
            iconButton(GuiTextures.REFRESH, MultiblockPreviewLang.BUTTON_RESET_VIEW) {
                val info = preview.multiblockSchemaInfo
                PreviewCameraFit.reset(info?.multiSchema, info?.mapSchema)
            }
        )
        return row
    }

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
