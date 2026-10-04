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
 * **内嵌页那一格怎么占**：不摘 `SchemaWidget`、也不算任何像素 —— 给它套一个只包住它自己的
 * [SchemaHolder]（`coverChildren()`，**不设尺寸**），再把壳放回它原来的索引。壳多大由 MUI 按子件算
 * （`StandardResizer.doCoverChildren` 用 `child.getArea().requestedWidth/Height`），所以
 * 「原来那一格多少像素」=「壳多少像素」是同一件事，不是我们抄一个数。摘掉 schema 换成一个固定尺寸的
 * 壳曾经让这一格（以及 EMI/JEI 按整控件尺寸算出的显示缩放）变了样；壳内嵌 schema 之后，
 * 控件树只多一层、尺寸链不变。
 *
 * 3D 不再绘制但控件留在树上：`InternalWidgetTree.drawTree` 第一句是
 * `if (parent.isEnabled() || ignoreEnabled)`，`setEnabled(false)` 只停绘制与交互，不参与尺寸
 * （`Flow.shouldIgnoreChildSize` 只在 `collapseDisabledChildren` 时才跳过停用子件；`PagedWidget` 也正是
 * 用 `setEnabled(false)` 藏非当前页）。
 *
 * 提示与按钮在壳里叠在 schema 上：提示 `sizeRel(1f)`、按钮行 `right(0).top(0)`。
 * 两者都是「尺寸/坐标依赖父级」，`doCoverChildren` 只统计不依赖父级的子件 ⇒ 壳的尺寸始终只由 schema 决定。
 * ⚠️ 反过来，如果壳里**没有** schema（只剩这两个依赖父级的子件），`doCoverChildren` 会走到
 * 「all children depend on their parent and min size is 0」那条 `GuiError`。
 *
 * 不猜绝对像素偏移的理由（8.0.0 实况）：3D 控件右边还有 `parts_view`(20) 与整控件的 `padding(7)`，
 * 宿主（JEI / EMI）给整框报的尺寸也不等于控件尺寸。
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
     */
    @JvmStatic
    fun attach(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition) {
        // 全屏那份的按钮由 buildFullscreenPanel 直接钉在面板右下角、3D 也照常显示，这里不碰
        if (fullscreen) return
        // 三步**各自兜底**：S1.14 实机里提示那步抛了异常，被同一个 catch 吞掉后连按钮条一起没了（整块空白）。
        // 按钮条最后挂：它是交互入口，比别的都重要。
        val holder = try {
            anchorFor(preview)
        } catch (t: Throwable) {
            warn("建立多方块预览按钮锚点失败", t)
            null
        }
        try {
            showFullscreenHint(holder)
        } catch (t: Throwable) {
            warn("内嵌页 3D 占位提示挂载失败", t)
        }
        try {
            attachRow(holder, preview, definition)
        } catch (t: Throwable) {
            warn("挂载多方块预览控制按钮失败", t)
        }
    }

    /**
     * 按钮与提示的落点：`SchemaWidget` 原来的槽位换成一个**只包住 schema 自己**的壳（见类注释）。
     *
     * 失败返回 `null`（调用方各有兜底），不让 GTM 的预览控件打不开。
     */
    private fun anchorFor(preview: MultiblockPreviewWidget): SchemaHolder? {
        val schema = preview.multiblockSchemaInfo?.multiSchema ?: return null
        // ⚠️ 不能用 `schema.parent`：`AbstractWidget.getParent` 带 isValid 守卫，构造期的子件
        // `valid == false`，直接抛 `IllegalStateException: SchemaWidget is not in a valid state!`
        // （就是 S1.14 实机整块空白的成因）。只能从拿到的预览控件往下找。
        val parent = findParent(preview, schema) ?: return null
        val index = parent.children.indexOfFirst { it === schema }
        if (index < 0) return null
        // ⚠️ 父级已 validate 时 `remove(int)` 会 dispose 被摘子件（`AbstractParentWidget.remove(int)`
        // 里 `if (this.isValid()) child.dispose()`），而壳要靠这个 schema 撑尺寸 ⇒ 那种情况下不动树，
        // 退回「按钮贴预览控件左上角、3D 照旧显示」的老兜底
        if (parent.isValid()) return null

        // ⚠️ 必须用**索引式** remove：`AbstractParentWidget.remove(IWidget)` 内部是
        // `ArrayList.remove(Object)` → `o.equals(e)`，而 MUI 的 `Widget.equals` 写的是
        // `o.getClass() != Widget.class → false`（javap：equals 偏移 0-14），
        // 也就是**任何 Widget 子类都不等于它自己** ⇒ 传子件进去永远摘不掉。
        if (!parent.remove(index)) return null

        // 壳**不设尺寸**：`coverChildren()` ⇒ 它的尺寸就是 schema 的尺寸（`doCoverChildren` 读
        // `requestedWidth/Height`），「原来那一格」不用我们算
        val holder = SchemaHolder().coverChildren()
        holder.addChild(schema, 0)
        if (!parent.addChild(holder, index)) {
            // 插不回去（正常不会发生）：把 schema 放回原位，宁可不做替换也不能让它掉出树
            parent.addChild(schema, index)
            return null
        }
        // 3D 不画了，但控件留在树上维持布局（见类注释：setEnabled 只停绘制/交互）
        schema.setEnabled(false)
        return holder
    }

    /**
     * 内嵌页不再显示 3D（配方查看器里的绝对视口错位不修了）：壳里放一行居中提示。
     *
     * 尺寸用 `sizeRel(1f)` 而不是固定像素：壳的尺寸就是原来那一格，提示跟着它走，不引入新数字。
     */
    private fun showFullscreenHint(holder: SchemaHolder?) {
        // 认不出 schema 的落点就什么都不放：宁可不显示，也不去乱盖 3D
        val target = holder ?: return
        // 用 TextWidget 而不是 `IDrawable.DrawableWidget(Text.lang(..).asIcon())`：`asIcon()` 返回的是
        // `drawable.Icon`（图标语义，按 box 缩放），多行文本画成什么样不受控；TextWidget 走同一套
        // TextRenderer（按宽折行 + 水平/垂直居中）。
        val hint = TextWidget(Component.translatable(MultiblockPreviewLang.HINT_FULLSCREEN))
            .sizeRel(1f, 1f)
            .textAlign(Alignment.Center)
            .tooltip { r -> r.addLine(Text.lang(MultiblockPreviewLang.HINT_FULLSCREEN)) }
        target.addChild(hint, -1)
    }

    /** 内嵌那排只有「全屏」一颗（见 [createRow] 的 `fullscreen = false` 分支）。 */
    private fun attachRow(holder: SchemaHolder?, preview: MultiblockPreviewWidget,
                          definition: MultiblockMachineDefinition) {
        val row = createRow(preview, definition, false)
        if (holder != null) {
            // 壳的尺寸由 schema 决定（不是 coverChildren 父级上的自造坐标），所以 `right/top` 不构成循环依赖，
            // 按钮就是贴在 3D 那一格的右上角
            holder.addChild(row.right(0).top(0), -1)
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

    /** 包住 `SchemaWidget` 的壳：尺寸由 schema 自己撑（`coverChildren()`），提示与按钮挂在里面。 */
    private class SchemaHolder : ParentWidget<SchemaHolder>()

    /** MUI 的控件是 `Foo<W extends Foo<W>>`，Kotlin 里没法用菱形推断（先例 MachineIoConfigPage.kt:315）。 */
    private class PreviewButton : ButtonWidget<PreviewButton>()
}
