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

    /** 正在构造的是全屏那份预览：全屏里那颗按钮是「退出」，内嵌那份是「全屏」。 */
    private var fullscreen: Boolean = false

    /** 构造全屏预览期间置位；见 [MultiblockPreviewFullscreenScreen.buildUI]。 */
    fun beginFullscreen() {
        fullscreen = true
    }

    fun endFullscreen() {
        fullscreen = false
    }

    /** 挂按钮条；失败不能连带 GTM 自己的预览控件打不开。 */
    @JvmStatic
    fun attach(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition) {
        try {
            preview.child(buildRow(preview, definition))
        } catch (t: Throwable) {
            GTETSCore.LOGGER.log(Level.WARN, "[GTET-TEST] 挂载多方块预览控制按钮失败", t)
        }
    }

    private fun buildRow(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition): Flow {
        val row = Flow.row().name(ROW_NAME).coverChildren().childPadding(2)
        // 位置用 right(int) / top(int)（像素），不要用 rightRel：DimensionSizer#calcPoint 对 end 单位做
        // `parentSize - v` 翻转，rightRel(1.0f) 会把控件甩到父级左侧外面去（GTM 自己那颗
        // 「display preview in world」按钮 MultiblockPreviewWidget.java:206 就是这么写的）。
        row.right(2).top(2)

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
