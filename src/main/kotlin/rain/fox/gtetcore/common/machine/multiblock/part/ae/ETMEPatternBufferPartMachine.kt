@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.common.machine.multiblock.part.ae

import appeng.api.implementations.blockentities.PatternContainerGroup
import appeng.api.inventories.InternalInventory
import appeng.api.stacks.AEItemKey
import appeng.crafting.pattern.EncodedPatternItem
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.value.sync.SyncHandlers
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.PagedWidget
import brachy.modularui.widgets.layout.Flow
import brachy.modularui.widgets.layout.Grid
import brachy.modularui.widgets.slot.ItemSlot
import brachy.modularui.widgets.slot.SlotGroup
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.MethodsReturnNonnullByDefault
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.BLOCK_COLUMNS
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.MAX_BLOCKS
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.MAX_ROWS_PER_BLOCK
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.PAGE_CAPACITY
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine.Companion.SLOT
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.integration.ae2.ETPatternBufferCapacities
import rain.fox.gtetcore.mixin.gtm.IMEPatternBufferAccess
import java.util.function.BooleanSupplier
import java.util.function.IntConsumer
import java.util.function.IntSupplier
import java.util.function.Supplier
import javax.annotation.ParametersAreNonnullByDefault

/**
 * 「多阶段 ME 样板总成」：容量大于 GTM 原生 27 的样板总成，四档各一件（LuV/UV/UEV/UXV）。
 *
 * 就是 GTM 的 [MEPatternBufferPartMachine]，只把样板槽位数从写死的 27 变成按档取值；
 * 四种能力、AE 终端交互、数据棒绑定镜像、取回、Jade 显示全部沿用父类。容量靠
 * `MixinMEPatternBufferCapacity` 在父类构造期把内联的 `27` 换成查表值
 * （父类字段初始化早于子类字段，继承 + 覆写拿不到），取舍见 [ETPatternBufferCapacities]。
 *
 * ## 8.0.0 里本类只需补父类剩下的三件事
 *
 * 1. [getTerminalPatternInventory]：父类那个匿名实现的 `size()` 也写着 27；
 * 2. [getTerminalGroup]：未成型时父类把图标与名字写死成 GTM 自己的 `me_pattern_buffer`；
 * 3. [buildMainUI]：父类的面板固定 9×3 = 27 格，本档要按容量排布 + 翻页。
 *
 * ## ⚠️ 面板与格子数一律以**实际存储**为准（`getPatternInventory().getSlots()`）
 * mixin 万一没生效也只是面板变小，不会出现「面板 126 格、实际只能放 27 盘」这种错位
 * （[ETPatternBufferCapacities.verify] 会在日志里吵一次）。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class ETMEPatternBufferPartMachine(info: BlockEntityCreationInfo) : MEPatternBufferPartMachine(info) {

    /**
     * 当前翻到第几页（0 起）。
     *
     * 纯界面状态：不持久化。两端靠 `buildMainUI` 里那个 `IntSyncValue#allowC2S` 对齐
     * （照 GTM 自己 `ItemMagnetBehavior#buildUI` 的写法，那里也是「同步值 + `onUpdateListener`
     * 把控件拨到同步值那一页」）。两端只有**可见页**不同，槽位编号不受影响：所有页都是
     * `PagedWidget` 的子控件（`PagedWidget#getChildren` 直接返回整张 `pages` 列表，javap 可复核），
     * 所以配方格子的注册与同步两端一致。
     */
    private var uiPage: Int = 0

    /**
     * AE 终端看到的样板库存视图（尺寸 = 真实格子数）。
     *
     * 父类那个匿名实现的 `size()` 是内联的 27 且字段私有，只能整个覆写。三段逻辑与父类逐字
     * 对应（写槽 → 通知内容变化 → `onPatternChange` 更新 AE 索引表），少最后一段，第 28 格往后
     * 放进去的样板就不会被 `pushPattern` 认出来。
     *
     * ⚠️ 8.0.0 的 `onPatternChange(int)` 已是 **public**，所以这里不用再走 invoker。
     */
    private val terminalPatternInventory: InternalInventory = object : InternalInventory {

        override fun size(): Int {
            return patternInventory.slots
        }

        override fun getStackInSlot(slotIndex: Int): ItemStack {
            return patternInventory.getStackInSlot(slotIndex)
        }

        override fun setItemDirect(slotIndex: Int, stack: ItemStack) {
            patternInventory.setStackInSlot(slotIndex, stack)
            patternInventory.onContentsChanged(slotIndex)
            onPatternChange(slotIndex)
        }
    }

    init {
        // 探针：mixin 没生效时（GTM 版本变化）在日志里吵一次，而不是静默做成 27 格
        ETPatternBufferCapacities.verify(this)
    }

    /** 本机的样板槽位数（**以实际存储为准**）。 */
    fun getPatternCapacity(): Int {
        return patternInventory.slots
    }

    /**
     * **仓室隔离**：一件总成不能被两个多方块同时占用（防串配方）。
     *
     * `MultiblockPartMachine#canShared` 的基类默认返回 `true`（8.0.0 多出
     * `controller` / `substructureName` 两个参数），GTM 只在 `BlockPattern#checkPatternAt` 里消费它：
     * 第二件控制器把这件总成收进部件表时该格判失败、结构成不了型。本件必须隔离有两条理由：
     * GTM 自己就假设「一件总成只属于一个控制器」（父类 `getTerminalGroup()` 直接取
     * `getControllers().first()`）；而 AE 推样板时原料落在**这一件自己的**库存里
     * （`pushPattern` → `pushInputsToExternalInventory`），共用时谁先跑谁吃掉。
     *
     * 「总成当宿主、镜像装在各机器里」这种主要用法不受影响 —— 那时总成不在任何成型结构里，
     * `isFormed()` 为 false，这道闸门不参与判断。
     *
     * **闸门由配置 `multiblock.partsShareable` 兜底**（默认 false = 仍然隔离）：配置打开后本件可被
     * 两个已成型结构同时占用，上面那条串配方风险重新出现。⚠️ 该值只在 `BlockPattern#checkPatternAt`
     * 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()

    override fun getTerminalPatternInventory(): InternalInventory {
        return terminalPatternInventory
    }

    override fun getTerminalGroup(): PatternContainerGroup {
        // 已成型：沿用父类（控制器名字 + 电路号，或自定义名）
        if (isFormed) return super.getTerminalGroup()
        // 未成型：父类这一支把图标与名字写死成 GTM 自己的 me_pattern_buffer。
        // 我们这四档常常就是「不组进多方块、只给一堆镜像当宿主」的用法，所以必须显示自己这一档。
        val definition = definition
        val stack = definition.asStack()
        val customName = access().`gtet$getCustomName`()
        if (customName.isNotEmpty()) {
            return PatternContainerGroup(AEItemKey.of(stack), Component.literal(customName), emptyList())
        }
        return PatternContainerGroup(AEItemKey.of(stack), stack.item.description, emptyList())
    }

    /**
     * 样板槽面板：9 列一块、最多并 2 块 = **一页 126 格**；容量超过一页就翻页，面板尺寸不变。
     *
     * ```
     * 一页的格数   = 9 × 7 × 2 = 126              （[PAGE_CAPACITY]）
     * 页数         = ⌈容量 / 126⌉                 （216 → 2 页、512 → 5 页）
     * 某一页的块数 = clamp(⌈这一页的格数 / 63⌉, 1, 2)；每块行数 = ⌈这一页的格数 / (9 × 块数)⌉
     * 网格盒(宽×高) = 列数 × 18, 行数 × 18 ⇒ 按"一页铺满"算，**翻页时一个像素都不变**
     *
     * 档位  容量  块数×行数   网格盒(宽×高)
     * LuV    27   1 × 3      162 ×  54   （与 GTM 原生 27 格面板逐像素相同）
     * UV     63   1 × 7      162 × 126
     * UEV   126   2 × 7      324 × 126
     * UXV   216   2 × 7      324 × 126（2 页）
     * ```
     *
     * ⚠️ **这个盒子必须显式钉在页容器上**（`PatternPages().size(...)`）：`PagedWidget` 是
     * `Widget<W>`、**不是**布局父级（javap：`PagedWidget extends Widget`），页子件是按它的 *area* 摆的
     * （`IWidget.getParentArea()` 默认实现就是 `getParent().getArea()`）。而没设尺寸的 `Widget` 会退到
     * `getDefaultWidth/Height()`；`MultiblockPreviewWidget.<init>` 之外这些控件在构造期 `isValid()` 为假，
     * `Widget.getDefaultWidth()` 里那条分支直接 `bipush 18` ⇒ 页容器只有 **18×18**，
     * 于是 324×126 的网格（`Grid` 里 `leftRel(0.5f)` 是按父宽居中：`Unit.getAnchor()` 对
     * `autoAnchor && relative && value<1` 返回 value 本身，0.5 就是居中）会往左右各探出 153px、
     * 并且盖住流式布局排在它后面的翻页条与上方的网络状态行。
     *
     * ⚠️ **面板本身**由 MUI 按内容自适应（`MachineUIPanel#mainContents` 是 `coverChildren(169, 77)`
     * + 外层 `coverChildren()`），所以 324×126 的网格会撑出比 GTM 那件更宽的面板 —— 宽度上限就是靠
     * [MAX_BLOCKS] 卡在 324px，高度那侧靠翻页。
     * 老工程 LDLib 时代那套「窗口高 236px / 1080p 缩放放不下」的账在 MUI 里不成立，见
     * `ETPatternBufferUIWidget` 被删掉的理由（报告里有）。
     *
     * 与父类逐项对应，差别只有「网格按容量排布 + 超过一页时翻页」与「LDLib → MUI」的写法：
     * `Grid#gridOfSizeWidth(格数, 列数, 映射)` 顶替 `AEPatternViewSlotWidget` 的手写循环，
     * `SlotGroup` / `SyncHandlers#itemSlot` / `ItemSlot` 顶替 LDLib 的 `WidgetGroup` / `SlotWidget`。
     *
     * ⚠️ **每一页都真的建出来**（控件树两端必须一致，不能按当前页建树），由 `PagedWidget` 负责
     * 只启用当前页；页号自身走 `IntSyncValue#allowC2S` 两端对齐。页容器**不**去动同步处理器的
     * 注册 —— `ItemSlot` 的同步处理器在初始化时就登记进 `PanelSyncManager`，与本页是否可见无关。
     *
     * 翻页控件 `[<] 当前页/总页数 [>]` 摆在网格下方，只有页数 > 1 时才加，所以 27 / 63 / 126
     * 三档的面板与「没做翻页」时逐像素相同。
     *
     * 页内块内**先列后行**（`x = i % 列数, y = i / 列数`）：槽号先沿一块往下、再换右面一块、再翻页，
     * 与"总成里的第 N 盘样板"一一对应、不跳号。
     */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        val inventory = patternInventory
        val capacity = inventory.slots
        val pageCount = 1.coerceAtLeast(ceilDiv(capacity, PAGE_CAPACITY))
        uiPage = Mth.clamp(uiPage, 0, pageCount - 1)   // 页码兜底（容量变了也不会指到不存在的一页）

        val isOnlineValue = BooleanSyncValue(BooleanSupplier { isOnline }, BooleanConsumer { setOnline(it) })
        syncManager.syncValue(SYNC_ONLINE, isOnlineValue)
        // ⚠️ 必须用显式 SAM：IntSyncValue 同时有 (IntSupplier, IntConsumer) 与 (IntSupplier, IntSupplier)
        // 两个重载，直接写 lambda 会「Overload resolution ambiguity」。
        val pageValue = IntSyncValue(
            IntSupplier { uiPage },
            IntConsumer { uiPage = it },
        ).allowC2S()
        syncManager.syncValue(SYNC_PAGE, pageValue)

        val flow = Flow.col().coverChildren()

        // 顶部：ME 网络状态（改名 / 共享库存 / 共享流体仓 / 取回四个按钮由父类的 getPanelBuilder 挂在
        // 左右两侧配置列上，本方法不用再画）
        flow.child(
            Text.dynamic(
                Supplier {
                    if (isOnlineValue.boolValue) Component.translatable("gtceu.gui.me_network.online")
                    else Component.translatable("gtceu.gui.me_network.offline")
                }
            ).asWidget().marginTop(2).marginBottom(4)
        )

        // 面板尺寸按"一页铺满"算（容量不足一页时就用容量本身）：翻页只换页里的内容，尺寸一个像素不动
        val filled = capacity.coerceAtMost(PAGE_CAPACITY)
        val panelBlocks = blocksOf(filled)
        val panelRows = rowsOf(filled, panelBlocks)
        val columns = BLOCK_COLUMNS * panelBlocks
        val gridWidth = SLOT * columns
        val gridHeight = SLOT * panelRows

        // ⚠️ 组名带本 mod 前缀：同一时刻可能还开着 GTM 自己的 me_pattern_buffer 面板，别撞组名
        val group = SlotGroup(SLOT_GROUP, BLOCK_COLUMNS, 0, true)
        // ⚠️ 页容器**必须显式给尺寸**：`PagedWidget` 不是布局父级，页子件按它的 area 定位，
        // 不给尺寸就退到 `Widget.getDefaultWidth()` 的 18×18（详见 buildMainUI 的注释）
        val pages = PatternPages().size(gridWidth, gridHeight)
        for (p in 0 until pageCount) {
            val count = PAGE_CAPACITY.coerceAtMost(capacity - p * PAGE_CAPACITY)
            val base = p * PAGE_CAPACITY
            pages.addPage(
                Grid()
                    .name("pattern_page_$p")
                    // 两轴都钉死 = 与页容器同尺寸：网格整齐、翻页时盒子不变，也不依赖 coverChildren 推算
                    .size(gridWidth, gridHeight)
                    .minElementMargin(0, 0)
                    .minColWidth(SLOT)
                    .minRowHeight(SLOT)
                    .gridOfSizeWidth(count, columns, Grid.GridPosMapper<ItemSlot> { _, _, index ->
                        patternSlot(inventory, base + index, group)
                    })
            )
        }
        pages.initialPage(uiPage)
        // 两端各自把控件拨到同步值那一页；⚠️ 不要挂 onPageChange 回写同步值，否则与这里互相打转
        pages.onUpdateListener { widget ->
            val page = pageValue.intValue
            if (widget.currentPageIndex != page) widget.setPage(page)
        }
        flow.child(pages)

        // 翻页控件：只有一页时一个都不加（27 / 63 / 126 三档的面板保持原样）
        if (pageCount > 1) {
            flow.child(
                Flow.row()
                    .coverChildren()
                    .childPadding(2)
                    // 页容器有确定宽度 ⇒ 这一排按父宽居中（`leftRel(0.5f)` 的 anchor 就是 0.5），
                    // 显式定位后不会走 Flow 的 crossAxisAlignment 分支
                    .leftRel(0.5f)
                    .marginTop(2)
                    .child(
                        pageButton("<") {
                            pageValue.intValue = (pageValue.intValue + pageCount - 1) % pageCount
                        }
                    )
                    .child(
                        Text.dynamic(
                            Supplier {
                                Component.literal((pageValue.intValue + 1).toString() + "/" + pageCount)
                            }
                        ).asWidget().height(BUTTON)
                    )
                    .child(
                        pageButton(">") {
                            pageValue.intValue = (pageValue.intValue + 1) % pageCount
                        }
                    )
            )
        }

        mainWidget.child(flow.center())
    }

    /**
     * 一个样板槽。
     *
     * ⚠️ 7.5.3 的面板会把「编码样板」画成它的产物（LDLib 的 `setItemHook`），8.0.0 的
     * `ItemSlot` 只有只读的 `renderMappingFunction()`、没有 setter，而 GTM 自己的
     * `me_pattern_buffer` 面板也不再这么画 —— 所以本档跟 GTM 一致，画样板物品本身。
     */
    private fun patternSlot(
        inventory: CustomItemStackHandler,
        slotIndex: Int,
        group: SlotGroup,
    ): ItemSlot = ItemSlot()
        .slot(
            SyncHandlers.itemSlot(inventory, slotIndex)
                .slotGroup(group)
                .accessibility(true, true)
                .filter { stack -> stack.item is EncodedPatternItem<*> }
                .changeListener { _, _, _, _ -> onPatternChange(slotIndex) }
        )
        .background(GTGuiTextures.SLOT, GTGuiTextures.PATTERN_OVERLAY)

    /** 一个翻页按钮（`<` / `>`）。返回 `IWidget`：`ButtonWidget<W extends ButtonWidget<W>>` 是自引用
     * 泛型，星投影不能出现在函数返回类型上，交给 `child(IWidget)` 收下即可。 */
    private fun pageButton(label: String, onClick: Runnable): IWidget =
        ButtonWidget()
            .size(BUTTON)
            .onMousePressed { _, button ->
                if (button == 0) {
                    onClick.run()
                    true
                } else {
                    false
                }
            }
            .overlay(Text.str(label).asIcon().size(BUTTON))

    /** ⌈a / b⌉（b > 0）。 */
    private fun ceilDiv(a: Int, b: Int): Int {
        return (a + b - 1) / b
    }

    /** 这么多格子要并几块（1 ~ [MAX_BLOCKS]）。 */
    private fun blocksOf(count: Int): Int {
        return 1.coerceAtLeast(MAX_BLOCKS.coerceAtMost(ceilDiv(count, BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK)))
    }

    /** 这么多格子每块摊几行。 */
    private fun rowsOf(count: Int, blocks: Int): Int {
        return ceilDiv(count, BLOCK_COLUMNS * blocks)
    }

    /**
     * 取父类私有成员的桥（`customName` 只有 setter）。cast 走 `Object` 是因为编译期看不见
     * mixin 加在父类上的接口。
     */
    private fun access(): IMEPatternBufferAccess {
        return this as IMEPatternBufferAccess
    }

    /** `PagedWidget<W extends PagedWidget<W>>` 是自引用泛型，Kotlin 直接写 `PagedWidget<*>` 会在
     * `onUpdateListener(Consumer<W>)` 上撞「捕获类型」；落一个具体的自类型子类即可。 */
    private class PatternPages : PagedWidget<PatternPages>()

    companion object {

        /** 一格样板槽的像素边长（MUI 的 `ItemSlot.SIZE`）。 */
        private const val SLOT = 18

        /** 一个网格块的列数（与 GTM 的面板一致：9 列）。 */
        private const val BLOCK_COLUMNS = 9

        /**
         * 一个网格块最多几行（7 行 = 126px）。
         *
         * 它同时是"要不要再并一块"的阈值：行数一多先横向铺第二块 9 列（最多 [MAX_BLOCKS] 块），
         * 再放不下就翻页（见 [PAGE_CAPACITY]）。
         */
        private const val MAX_ROWS_PER_BLOCK = 7

        /**
         * 面板最多并几块（2 块 = 18 列 = 340px 宽）。
         *
         * 再宽下去面板会超出屏幕（MUI 的窗口宽度是自适应的，屏幕窄时两侧会被裁），所以宽度卡住、
         * 高度那侧靠翻页解决。
         */
        private const val MAX_BLOCKS = 2

        /**
         * 一页几格 = 9 × 7 × 2 = **126**。
         *
         * 容量不满一页时只渲染实际行数（27 → 1 块 × 3 行、63 → 1 块 × 7 行、126 → 2 块 × 7 行），
         * 超过就翻页、面板尺寸一个像素都不变（216 → 2 页、512 → 5 页）。
         */
        private const val PAGE_CAPACITY = BLOCK_COLUMNS * MAX_ROWS_PER_BLOCK * MAX_BLOCKS

        /** 翻页按钮边长。 */
        private const val BUTTON = 12

        /** 样板槽的 `SlotGroup` 名（MUI 面板内唯一即可，加前缀避开 GTM 自己的 `pattern_slots`）。 */
        private const val SLOT_GROUP = "gtetscore_pattern_slots"

        /** 同步值键（MUI 面板内唯一即可）。 */
        private const val SYNC_ONLINE = "gtetscore_pattern_buffer_online"
        private const val SYNC_PAGE = "gtetscore_pattern_buffer_page"
    }
}
