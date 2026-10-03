package rain.fox.gtetcore.integration.ae2

import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.AEKey
import brachy.modularui.api.IPanelHandler
import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.ItemDrawable
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.FluidSlotSyncHandler
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.value.sync.StringSyncValue
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.ToggleButton
import brachy.modularui.widgets.layout.Flow
import brachy.modularui.widgets.slot.FluidSlot
import brachy.modularui.widgets.slot.ModularSlot
import brachy.modularui.widgets.slot.PhantomItemSlot
import brachy.modularui.widgets.textfield.TextFieldWidget
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanelBuilder
import com.gregtechceu.gtceu.api.transfer.fluid.CustomFluidTank
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import com.gregtechceu.gtceu.common.mui.GTMuiWidgets
import com.gregtechceu.gtceu.common.mui.widgets.PopupPanel
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.data.lang.Ae2Lang
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.IntSupplier
import java.util.function.Supplier

/**
 * 「标签过滤 + 定量拉取 + 多方块共享」浮层：白名单输入框、黑名单输入框各配一个**幻影槽**，
 * 一个「每次拉 N 个」的数字框，再加一个「能不能被别的多方块共享」的开关。
 *
 * ## ⚠️ 与老工程（1.20.1 / GTM 7.5.3 / LDLib2）的结构性差异
 *
 * 老工程这个类实现的是 GTM 的 `IFancyConfigurator`（`getIcon` / `getTitle` / `createConfigurator`
 * 三件套，由 fancy 侧栏托管成可折叠浮层）。GTM 8.0.0 的 UI 换成 MUI，
 * **`IFancyConfigurator` 整个没有了**（jar 里 `com/gregtechceu/gtceu/api/gui/` 整包已无此类），
 * 所以这里改用 GTM 自己那套等价机制：
 *
 * 1. [attach] 往机器面板的**右侧配置列**加一个图标按钮
 *    （`MachineUIPanelBuilder#getRightConfiguratorPanel()`，同款用法见 `MachineIoConfigPage.kt:166`）；
 * 2. 按钮点开一个 `PopupPanel` 浮层 —— `IMEStockingPart#getPanelBuilder`
 *    的 `stocking_settings` 走的就是这条路径，所以两块的观感一致。
 *
 * 界面内容与老工程逐项对齐：两行表达式 + 定量行 +（可选）共享行 + 四行说明；
 * 定量行的步进手感沿用 GTM 自己那份 `GTMuiWidgets#createIntInputWithButtons`
 * （1 / Shift ×4 / Ctrl ×16 / Alt ×64，并自带上下限按钮禁用）。
 *
 * ## 四行控件
 *
 * 1. **白名单**：输入框（留空 = 不限制）+ 幻影槽；
 * 2. **黑名单**：输入框（留空 = 不限制）+ 幻影槽；
 * 3. **每次拉取量 N**：数字框 + 左右两个加减按钮，范围 0 ~ [BATCH_MAX]，**0 = 不限制**（默认）。
 *    N 必须 ≥ 本仓的「保底数量」（`IMEStockingPart#getMinStackSize`，同一个右侧配置列里的另一个浮层），
 *    否则备出来的量永远达不到保底、本仓会一直空着。
 * 4. **多方块共享开关**：`ToggleButton` + 右侧一行短状态文字。贴图用 `GTGuiTextures.PRIVATE_MODE_BUTTON`
 *    （`textures/gui/widget/button_public_private.png` 切片，第 0 片 = public、第 1 片 = private）。
 *    **默认关 = 隔离**（防串配方）；两个方向分别发生什么、以及「改动在结构重新检查后生效」
 *    全部写在 tooltip 的 4 条里（[Ae2Lang.SHARE_TIP_0] ~ [Ae2Lang.SHARE_TIP_3]）。
 *    ⚠️ 一台机器只有**一个**开关：二合一件的流体侧那块面板不画这一行（`showShareSwitch = false`）。
 *
 * ## 同步与「谁来写」
 *
 * - 两条表达式走 `StringSyncValue#allowC2S`（客户端输入回到服务端 setter），定量走 `IntSyncValue`，
 *   共享开关走 `BooleanSyncValue`；服务端值变化由 MUI 自己 `detectAndSendChanges` 推回客户端，
 *   所以幻影槽在服务端改了表达式之后输入框下一 tick 自己就会刷新；
 * - ⚠️ 幻影槽只在**服务端**写表达式（`machine.level.isClientSide` 那次回调是乐观显示值，
 *   写了会被下一次同步覆盖，看起来像「放了没反应」）；
 * - ⚠️ 同一台机器可能挂两块面板（物品侧 / 流体侧），所有同步值键带实例号（[key]），否则撞键。
 *
 * ## 幻影槽怎么工作
 *
 * 放一个物品（流体部件则是流体，可以从 JEI/EMI 拖）之后，取该样本的全部标签、按字母序用 `|`
 * 连接写进本行的表达式 —— 「命中其中任意一个标签」的都放行，这正是「先拉一类东西」的起点。
 *
 * - 物品幻影槽：`ModularSlot#changeListener`（`PhantomItemSlotSyncHandler` 在服务端收到点击后
 *   `ModularSlot#set` → `setChanged`，槽内容真的变了才回调）；
 * - 流体幻影槽：`CustomFluidTank#setOnContentsChanged`（GTM 的 `CustomFluidTank` 覆写了 `setFluid`
 *   并在末尾触发 `onContentsChanged`，已核实源码）；
 * - ⚠️ 样本**一个标签都没有**时表达式保持不变（不清空、不报错）—— 免得把玩家手写的一大串表达式顺手抹掉；
 * - ⚠️ 样本容器（[sampleHandler] / [sampleTank]）不参与任何真实库存，只给这一个槽用。
 *
 * @author rain fox
 */
class ETTagFilterConfigurator(
    private val machine: IMEStockingHost,
    /** true = 流体部件（幻影槽收流体、N 的单位是 mB），false = 物品部件。 */
    private val fluid: Boolean,
    /**
     * 是否画出「多方块共享」开关行。
     *
     * ⚠️ 只有二合一件的流体侧那块面板会传 false（一台机器有两块面板、但只有一个开关，
     * 画两次会让玩家以为要拨两次）。
     */
    private val showShareSwitch: Boolean = true,
) {

    /** 幻影槽的样本容器：本实例独占，只有那一个槽，不参与任何真实库存。 */
    private val sampleHandler: CustomItemStackHandler = CustomItemStackHandler(1)
    private val sampleTank: CustomFluidTank = CustomFluidTank(1000)

    /** 本实例的同步值 / 子面板键前缀（见类注释最后一条）。 */
    private val key: String = "gtetscore_tag_filter_" + nextIndex()

    private fun syncKey(name: String): String = key + "_" + name

    /**
     * 机器本体。
     *
     * ⚠️ [IMEStockingHost] 只保证 `IMuiMachine`（GTM 8.0.0 的面板装配点都在那边），
     * 而 `level` / `definition` 在 `MetaMachine` 上，所以这里显式转一次。
     * 所有实现类都是 `MetaMachine` 的子类（面板由 GTM 用 `MetaMachine` 驱动），转换不会失败。
     */
    private fun self(): MetaMachine = machine as MetaMachine

    /**
     * 装配：往右侧配置列串一个按钮，按钮点开本配置器的浮层。
     *
     * ⚠️ `rightConfigurators(Consumer)` 是**整体赋值**（MachineUIPanelBuilder.java 里只有一个
     * `putfield`），所以必须先 getter 取回 GTM 自己那份（电源按钮、活塞按钮…）再串上我们的，
     * 直接覆写会把 GTM 原生配置列清空。
     */
    fun attach(builder: MachineUIPanelBuilder, syncManager: PanelSyncManager) {
        val popup = syncManager.syncedPanel(key, true) { manager, _ -> createPopup(manager) }
        val existing = builder.rightConfigurators()
        builder.rightConfigurators { flow ->
            existing?.accept(flow)
            flow.child(createButton(popup))
        }
    }

    /** 右侧配置列的入口按钮：图标直接用本机器的物品图标（与 GTM 自己那些配置按钮同尺寸）。 */
    private fun createButton(popup: IPanelHandler): IWidget {
        val icon: IDrawable = ItemDrawable(self().definition.asStack()).asIcon().size(BUTTON_SIZE)
        return ButtonWidget()
            .size(BUTTON_SIZE)
            .overlay(icon)
            .onMousePressed { _, _ ->
                popup.openPanel()
                true
            }
            .tooltipAutoUpdate(true)
            .tooltipBuilder { it.addLine(Text.lang(Ae2Lang.TITLE)) }
    }

    /** 浮层本体：四 / 五行控件 + 四行说明。 */
    private fun createPopup(manager: PanelSyncManager): ModularPanel<*> {
        val column: ParentWidget<*> = Flow.col().coverChildren().childPadding(ROW_GAP).margin(PADDING)
        column.child(expressionRow(Ae2Lang.WHITE, machine::getTagWhite, machine::setTagWhite, SYNC_WHITE))
        column.child(expressionRow(Ae2Lang.BLACK, machine::getTagBlack, machine::setTagBlack, SYNC_BLACK))
        column.child(batchRow(manager))
        if (showShareSwitch) column.child(shareRow(manager))
        for (hint in HINTS) {
            column.child(TextWidget(Text.lang(hint)).width(PANEL_WIDTH - 2 * PADDING).height(HINT_HEIGHT))
        }
        return PopupPanel.createPopupPanel(key + "_panel", PANEL_WIDTH, contentHeight()).child(column)
    }

    /** 一行表达式：标题 + 输入框 + 幻影槽。 */
    private fun expressionRow(
        titleKey: String,
        getter: Supplier<String>,
        setter: Consumer<String>,
        syncName: String,
    ): IWidget {
        val value = StringSyncValue(getter, setter).allowC2S()
        val field: TextFieldWidget = TextFieldWidget()
            .width(FIELD_WIDTH)
            .height(ROW_HEIGHT)
            .setMaxLength(MAX_EXPRESSION_LENGTH)
            .value(value)
        return Flow.row()
            .coverChildren()
            .childPadding(CELL_GAP)
            .child(label(titleKey))
            .child(field)
            .child(phantom(syncName, setter))
    }

    /** 定量行：数字框 + 加减按钮，上下限与步进手感全交给 GTM 自己那份实现。 */
    private fun batchRow(manager: PanelSyncManager): IWidget {
        // ⚠️ 必须用显式 SAM：IntSyncValue 同时有 (IntSupplier, IntConsumer) 与 (IntSupplier, IntSupplier)
        // 两个重载，直接写 lambda 会「Overload resolution ambiguity」。
        val value = IntSyncValue(
            IntSupplier { machine.getBatchSize() },
            IntConsumer { machine.setBatchSize(it) },
        ).allowC2S()
        register(manager, SYNC_BATCH, value)
        return Flow.row()
            .coverChildren()
            .childPadding(CELL_GAP)
            .child(label(Ae2Lang.BATCH))
            .child(
                GTMuiWidgets.createIntInputWithButtons(
                    value,
                    IntSupplier { BATCH_MIN },
                    IntSupplier { BATCH_MAX },
                )
            )
    }

    /** 共享行：一个开关 + 右侧一行短状态文字（文字必须短，MUI 的文本控件不换行）。 */
    private fun shareRow(manager: PanelSyncManager): IWidget {
        val value = BooleanSyncValue(
            BooleanSupplier { machine.canBeShared() },
            BooleanConsumer { machine.setCanBeShared(it) },
        ).allowC2S()
        register(manager, SYNC_SHARED, value)
        val state: TextWidget<*> = TextWidget(
            Supplier<Component> {
                Text.lang(if (machine.canBeShared()) Ae2Lang.SHARE_ON else Ae2Lang.SHARE_OFF)
            }
        ).height(ROW_HEIGHT)
        return Flow.row()
            .coverChildren()
            .childPadding(CELL_GAP)
            .child(label(Ae2Lang.SHARE))
            .child(
                // ⚠️ 用 stateOverlay 的第 0 片（public = 允许共享）、第 1 片（private = 隔离），
                // 与 GTM 自己的 AbstractEnderLinkCover 用法一致；不要用 background()，那会套一层底板把图标压扁。
                ToggleButton()
                    .size(ROW_HEIGHT)
                    .value(value)
                    .stateOverlay(GTGuiTextures.PRIVATE_MODE_BUTTON[0])
                    .tooltipAutoUpdate(true)
                    .tooltipBuilder { tip -> TOOLTIPS.forEach { tip.addLine(Text.lang(it)) } }
            )
            .child(state)
    }

    /** 行标题（固定宽度，让四行的控件左对齐成一条竖线）。 */
    private fun label(key: String): TextWidget<*> =
        TextWidget(Text.lang(key)).width(LABEL_WIDTH).height(ROW_HEIGHT)

    /** 已经注册过的同步值不再重复注册（浮层可能被重复构建）。 */
    private fun register(manager: PanelSyncManager, name: String, value: brachy.modularui.value.sync.SyncHandler<*>) {
        if (!manager.hasSyncHandler(value)) manager.syncValue(syncKey(name), 0, value)
    }

    /** 物品版 / 流体版幻影槽。 */
    private fun phantom(syncName: String, setter: Consumer<String>): IWidget =
        if (fluid) fluidPhantom(setter) else itemPhantom(setter)

    private fun itemPhantom(setter: Consumer<String>): IWidget {
        val slot = ModularSlot(sampleHandler, 0).changeListener { _, _, _, _ ->
            acceptSample(AEItemKey.of(sampleHandler.getStackInSlot(0)), setter)
        }
        return PhantomItemSlot()
            .size(SLOT_SIZE)
            .syncHandler(brachy.modularui.value.sync.PhantomItemSlotSyncHandler(slot))
            .background(GTGuiTextures.SLOT)
    }

    private fun fluidPhantom(setter: Consumer<String>): IWidget {
        sampleTank.setOnContentsChanged { acceptSample(AEFluidKey.of(sampleTank.fluid), setter) }
        // ⚠️ phantom(true) 不能省：FluidSlotSyncHandler 的 `phantom` 默认 false（构造器里就
        // `putfield phantom = 0`），不置位的话点击走的是「灌桶 / 倒桶」那条真实容器路径（tryClickContainer），
        // 而不是「把手上那份放进幻影槽」（tryClickPhantom）。
        return FluidSlot()
            .size(SLOT_SIZE)
            .syncHandler(FluidSlotSyncHandler(sampleTank).phantom(true))
            .background(GTGuiTextures.SLOT)
    }

    /**
     * 样本 → 表达式。
     *
     * ⚠️ 只在服务端写（客户端那次回调是乐观值，写了会被下一次同步覆盖）；样本没有标签时保持原表达式不动。
     */
    private fun acceptSample(sample: AEKey?, setter: Consumer<String>) {
        if (self().level?.isClientSide == true) return
        if (sample == null) return
        val expression = ETTagFilter.expressionOf(sample)
        if (expression.isNotEmpty()) setter.accept(expression)
    }

    /** 浮层高：各行控件 + 行距 + 四行说明（PopuPanel 是固定尺寸，得自己算出来）。 */
    private fun contentHeight(): Int {
        val rows = if (showShareSwitch) 4 else 3
        return 2 * PADDING + rows * ROW_HEIGHT + (rows + HINTS.size) * ROW_GAP + HINTS.size * HINT_HEIGHT
    }

    companion object {

        /** 面板 / 同步值键前缀的自增号（见类注释最后一条）。 */
        private var index = 0
        private fun nextIndex(): Int = ++index

        /** 浮层宽度与内边距；MUI 的文本控件不换行，所以宽度是硬约束。 */
        private const val PANEL_WIDTH = 200
        private const val PADDING = 4

        /** 行高 / 幻影槽边长 / 按钮边长 / 间距。 */
        private const val ROW_HEIGHT = 18
        private const val SLOT_SIZE = 18
        private const val BUTTON_SIZE = 16
        private const val CELL_GAP = 2
        private const val ROW_GAP = 2

        /** 标题列宽与输入框宽度。 */
        private const val LABEL_WIDTH = 56
        private const val FIELD_WIDTH = 112

        /** 说明行高。 */
        private const val HINT_HEIGHT = 10

        /** 表达式最长字符数，够自动填 16 个标签（`ETTagFilter.AUTO_FILL_TAG_LIMIT` 是 16）。 */
        private const val MAX_EXPRESSION_LENGTH = 512

        /** 数字框上下限与部件侧的夹取保持一致（部件那边还会再夹一次，这里只影响 UI）。 */
        private const val BATCH_MIN = 0
        private const val BATCH_MAX = 1_000_000

        /** 同步值的键尾（前面还有实例号，见 [syncKey]）。 */
        private const val SYNC_WHITE = "white"
        private const val SYNC_BLACK = "black"
        private const val SYNC_BATCH = "batch"
        private const val SYNC_SHARED = "shared"

        /** 说明块四行：运算符 / 便利写法 / 幻影槽 / 留空语义与保底冲突。 */
        private val HINTS = listOf(Ae2Lang.HINT_0, Ae2Lang.HINT_1, Ae2Lang.HINT_2, Ae2Lang.HINT_3)

        /** 共享开关的四条 tooltip。 */
        private val TOOLTIPS =
            listOf(Ae2Lang.SHARE_TIP_0, Ae2Lang.SHARE_TIP_1, Ae2Lang.SHARE_TIP_2, Ae2Lang.SHARE_TIP_3)

        /**
         * 装配点：部件的 `getPanelBuilder` 覆写里调一次。
         *
         * ⚠️ `IMEStockingPart#getPanelBuilder`（IMEStockingPart.java:33-38）本身已经往右侧配置列加了
         * 自动拉取开关与 `stocking_settings` 按钮，所以实现类必须
         * `super.getPanelBuilder(...)` 之后再 [attach]，否则 GTM 那两件会被我们的赋值覆盖掉。
         *
         * @param fluid          true = 流体部件
         * @param showShareSwitch 二合一件的流体侧那块面板传 false
         */
        @JvmStatic
        fun attach(
            machine: IMEStockingHost,
            builder: MachineUIPanelBuilder,
            syncManager: PanelSyncManager,
            settings: UISettings,
            fluid: Boolean,
            showShareSwitch: Boolean = true,
        ) {
            ETTagFilterConfigurator(machine, fluid, showShareSwitch).attach(builder, syncManager)
        }
    }
}
