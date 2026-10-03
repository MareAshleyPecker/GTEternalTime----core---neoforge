@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.screen.ModularPanel
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.value.sync.StringSyncValue
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.TextWidget
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.common.item.tool.DebugBlockPattern
import rain.fox.gtetcore.common.item.tool.StructureWriteBehavior
import rain.fox.gtetcore.common.item.tool.StructureWriterData
import rain.fox.gtetcore.data.lang.StructureToolLang
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier
import java.util.function.Supplier

/** 标签文字颜色：正文偏白、起点绿、终点红（老界面用 §a / §c 写死在字符串里，这里改控件上色）。 */
private const val TEXT_COLOR: Int = 0xFFFAF9F6.toInt()
private const val START_COLOR: Int = 0xFF55FF55.toInt()
private const val END_COLOR: Int = 0xFFFF5555.toInt()

/** 面板尺寸与各块落点（176 宽与老界面一致；老界面那 138 高里含玩家背包，MUI 这份不含）。 */
private const val WIDTH = 176
private const val HEIGHT = 114
private const val BOX_X = 7
private const val BOX_Y = 7
private const val BOX_W = WIDTH - 2 * BOX_X
private const val BOX_H = 56
private const val ROT_Y = 67
private const val EXPORT_Y = 89
private const val BUTTON_H = 18
private const val BUTTON_GAP = 3
private const val ROT_W = (BOX_W - 2 * BUTTON_GAP) / 3

/**
 * 「结构工具」的导出面板（MUI 版）。
 *
 * 老工程这块是 LDLib 1.x 的 `IItemUIFactory`（`StructureWriteBehavior#createUI`）：
 * 一块 `DISPLAY` 底板写四行字（尺寸 / 朝向 / 起点 / 终点）+ 三个 `Rot X/Y/Z` 按钮 + 一个 `Export` 按钮。
 * 这里按同一套布局用 MUI 重画。
 *
 * ⚠️ **面板树在服务端与客户端各建一次**（服务端那次是为了登记同步值，见 MUI 的 `GuiManager`），
 * 所以树结构只能由「两端一样」的数据决定；四行文案**一律走同步值**（服务端读物品 → 同步给客户端），
 * 客户端不自己去读物品的数据组件。
 *
 * ⚠️ 四个按钮是「点一下干一件事」的瞬时动作，走 `.allowC2S()` 的同步值而不是
 * `registerSyncedAction`：后者在本项目实机上点不动（项目笔记 S1.8），同步值这套在高级终端面板上已在用。
 *
 * @author rain fox
 */
object StructureExportPanel {

    /** 面板名：MUI 拿它当同步命名空间。 */
    private const val PANEL_NAME = "structure_export_tools"

    /** 建面板。两端各调一次，树结构必须完全一致。 */
    @JvmStatic
    fun build(data: PlayerInventoryGuiData<*>, syncManager: PanelSyncManager): ModularPanel<*> =
        ExportUi(data, syncManager).build()

    private class ExportUi(
        private val data: PlayerInventoryGuiData<*>,
        private val syncManager: PanelSyncManager,
    ) {

        /** 手上的结构工具：每次现取（`GuiData` 拿着玩家与槽位下标，不是一个快照）。 */
        private val tool: () -> ItemStack = { data.usedItemStack }

        // ---------- 只读显示值 ----------
        // 都没有 `.allowC2S()`：只由服务端推给客户端，客户端永远不写（因此也不给 setter）。

        /** 选区尺寸（含端点）；任一维为 0 = 没有选区。 */
        private val sizeX = readOnlyInt("size_x") { StructureWriterData.read(tool()).size()?.first ?: 0 }
        private val sizeY = readOnlyInt("size_y") { StructureWriterData.read(tool()).size()?.second ?: 0 }
        private val sizeZ = readOnlyInt("size_z") { StructureWriterData.read(tool()).size()?.third ?: 0 }

        /** 朝向的 `Direction#ordinal`（客户端据此查 [DebugBlockPattern.getDir] 的方向名）。 */
        private val dirOrdinal = readOnlyInt("dir") { StructureWriterData.read(tool()).dir.ordinal }

        private val startText = readOnlyString("start") { posText(StructureWriterData.read(tool()).start) }
        private val endText = readOnlyString("end") { posText(StructureWriterData.read(tool()).end) }

        // ---------- 四个瞬时动作 ----------
        // getter 恒 false：这些值不承载状态，只当「点了一下」的信号；
        // `boolValue = …` 每次都无条件发一次 C2S（`ValueSyncHandler#setBoolValue` 里 notify 为真就 sync()），
        // 所以连点同一个按钮每次都算一次。

        private val rotX = action("do_rot_x") { StructureWriteBehavior.rotateX(tool()) }
        private val rotY = action("do_rot_y") { StructureWriteBehavior.rotateY(tool()) }
        private val rotZ = action("do_rot_z") { StructureWriteBehavior.rotateZ(tool()) }
        private val export = action("do_export") { StructureWriteBehavior.export(data.player, tool()) }

        fun build(): ModularPanel<*> {
            val panel = ExportPanel().size(WIDTH, HEIGHT)
            panel.child(ButtonWidget.panelCloseButton())

            val box = ExportBox().pos(BOX_X, BOX_Y).size(BOX_W, BOX_H).background(GTGuiTextures.DISPLAY)
            // 文案是 Supplier：每帧重算，服务端的同步值一变就跟着变
            box.child(label(4) { sizeText() }.color(TEXT_COLOR))
            box.child(label(18) { dirText() }.color(TEXT_COLOR))
            box.child(label(32) { Component.translatable(StructureToolLang.PANEL_START, startText.stringValue) }
                .color(START_COLOR))
            box.child(label(46) { Component.translatable(StructureToolLang.PANEL_END, endText.stringValue) }
                .color(END_COLOR))
            panel.child(box)

            panel.child(actionButton(StructureToolLang.BTN_ROT_X, BOX_X, ROT_Y, ROT_W) { fire(rotX) })
            panel.child(
                actionButton(StructureToolLang.BTN_ROT_Y, BOX_X + ROT_W + BUTTON_GAP, ROT_Y, ROT_W) { fire(rotY) }
            )
            panel.child(
                actionButton(
                    StructureToolLang.BTN_ROT_Z, BOX_X + 2 * (ROT_W + BUTTON_GAP), ROT_Y, ROT_W
                ) { fire(rotZ) }
            )
            panel.child(actionButton(StructureToolLang.BTN_EXPORT, BOX_X, EXPORT_Y, BOX_W) { fire(export) })
            return panel
        }

        // ======================== 文案 ========================

        private fun sizeText(): Component {
            if (sizeX.intValue <= 0) return Text.lang(StructureToolLang.PANEL_EMPTY)
            return Component.translatable(
                StructureToolLang.PANEL_SIZE, sizeX.intValue, sizeY.intValue, sizeZ.intValue
            )
        }

        private fun dirText(): Component {
            val dirs = DebugBlockPattern.getDir(dir())
            return Component.translatable(StructureToolLang.PANEL_DIR, dirs[0].name, dirs[1].name, dirs[2].name)
        }

        /** ordinal → `Direction`；越界退回 `NORTH`（同步值还没下来时）。 */
        private fun dir(): Direction = Direction.entries.getOrElse(dirOrdinal.intValue) { Direction.NORTH }

        private fun posText(pos: BlockPos?): String = pos?.toShortString() ?: "-"

        // ======================== 小工具 ========================

        /** 一行文字；`text` 是 lambda，每帧重算。 */
        private fun label(y: Int, text: () -> Component): ExportLabel =
            ExportLabel(Supplier { text() }).pos(6, y).size(BOX_W - 12, 9)

        private fun actionButton(key: String, x: Int, y: Int, width: Int, action: () -> Unit): IWidget {
            val button = ExportButton().pos(x, y).size(width, BUTTON_H).overlay(Text.lang(key))
            button.onMousePressed { _, _ ->
                action()
                true
            }
            return button
        }

        /** 把信号值翻一下 = 发一次 C2S。值本身没有含义，翻转只是为了让每次点击都产生一次写入。 */
        private fun fire(value: BooleanSyncValue) {
            value.boolValue = !value.boolValue
        }

        // ======================== 同步值登记 ========================

        private fun readOnlyInt(key: String, read: () -> Int): IntSyncValue {
            // ⚠️ 显式写出 SAM 类型：Kotlin 直接写 lambda 会撞上 `(IntSupplier, IntConsumer)`
            //    与 `(IntSupplier, IntSupplier)` 两个重载的歧义
            val value = IntSyncValue(IntSupplier { read() })
            syncManager.syncValue(key, value)
            return value
        }

        private fun readOnlyString(key: String, read: () -> String): StringSyncValue {
            val value = StringSyncValue(Supplier { read() })
            syncManager.syncValue(key, value)
            return value
        }

        /**
         * 瞬时动作值：getter 恒 `false`，setter 只在服务端跑。
         *
         * ⚠️ setter 会被**两端**各调一次（客户端写本地值那次、服务端收到包那次、服务端把值推回来那次），
         * 所以这里自己判端；[StructureWriteBehavior.export] 里另有一道 `isClientSide` 早退。
         */
        private fun action(key: String, run: () -> Unit): BooleanSyncValue {
            val value = BooleanSyncValue(BooleanSupplier { false }, BooleanConsumer {
                if (data.player is ServerPlayer) run()
            }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }
    }

    // ======================== 控件类型 ========================
    // MUI 这几只控件都是「自引用泛型」（`Foo<W extends Foo<W>>`），Kotlin 里没法用菱形推断，
    // 各写一个写死了类型参数的私有子类 —— 只有这样链式调用才拿得回自己的类型。

    private class ExportPanel : ModularPanel<ExportPanel>(PANEL_NAME)

    private class ExportBox : ParentWidget<ExportBox>()

    private class ExportButton : ButtonWidget<ExportButton>()

    private class ExportLabel : TextWidget<ExportLabel> {
        constructor(text: Supplier<Component>) : super(text)
    }
}
