package rain.fox.gtetcore.common.machine.multiblock.part.ae

import appeng.api.config.Actionable
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanelBuilder
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableFluidTank
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.feature.multiblock.IMEStockingPart
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.integration.ae2.ETTagFilter
import rain.fox.gtetcore.integration.ae2.ETTagFilterConfigurator
import rain.fox.gtetcore.integration.ae2.IMEStockingHost
import java.util.function.Supplier

/**
 * 「ME 标签库存输入仓」：GTM 的 `me_stocking_input_hatch`（ME 库存输入仓）+ 标签过滤 + 定量拉取。
 *
 * 与物品版 [ETTagFilterStockBusPartMachine] 是同构的两件东西（GTM 自己那两件也是同构的），
 * 所以设计取舍不在这里重复 —— 三道把关点（autoPull 选配置 / [syncME] 备货 / [ETTagFilterStockFluidSlot.drain] 真取数）
 * 见物品版类注释。流体侧只有两处差异：
 *
 * - 取数点是 `ExportOnlyAEFluidSlot#drain(int, FluidAction)`（GTM 的库存流体槽覆写的正是它），
 *   而不是 `extractItem`；`drain(FluidStack, FluidAction)` 会转发到它，所以两条路都覆盖到。
 * - 「每次拉 N 个」的 N 单位是 **mB**（1000 mB = 1 桶），上限 `BATCH_MAX` 因此取 1_000_000 mB = 1000 桶。
 *
 * ## ⚠️ 与老工程（1.20.1 / GTM 7.5.3）的结构性差异
 *
 * - **`createTank` 的覆写点还在，而且更好用**：8.0.0 的签名是 `createTank(int capacity, int slots)`
 *   （老工程是 `createTank(initialCapacity, slots, vararg args)`），并且 `FluidHatchPartMachine` 的构造器
 *   就是 `this.tank = attachTrait(createTank(capacity, slots))`（javap 可证），所以覆写它就能整份换掉库存列表 ——
 *   与老工程完全一致，**不需要**物品侧那种换数组元素的手法。
 *   `slots` 传进来的就是 GTM 的 `MEHatchPartMachine.CONFIG_SIZE`（16，`MEHatchPartMachine` 构造器里是 `bipush 16`），
 *   与 GTM 自己那份 `MEStockingHatchPartMachine#createTank` 里写死的 16 同值。
 * - **`getActionSource()` 仍然要自己转发**：8.0.0 的 `MEHatchPartMachine` 有 public 的 `getMainNode()` /
 *   `isOnline()`，但**没有** `getActionSource()`，只有 protected 的 `actionSource` 字段（javap 可证）——
 *   这一点与老工程一致。
 * - `@Persisted` → `@field:SaveField`、`@DescSynced` → `@field:SyncToClient`（改值要
 *   `syncDataHolder.markClientSyncFieldDirty("shareEnabled")`）；`IDropSaveMachine` 整类已删，
 *   `@SaveField` 字段本来就随拆随放，所以不再需要 `saveToItem` / `loadFromItem`。
 *
 * @author rain fox
 */
open class ETTagFilterStockHatchPartMachine(
    info: BlockEntityCreationInfo,
) : MEStockingHatchPartMachine(info), IMEStockingHost, IMEStockingPart {

    /** 标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private val tagFilter = ETTagFilter()

    /** 两条原始表达式（setter 形参非空的理由见物品版同名段）。 */
    @field:SaveField
    private var tagWhite = ""

    @field:SaveField
    private var tagBlack = ""

    /** 每次从网络备货的上限（mB）；0 = 不限制（默认）。 */
    @field:SaveField
    private var batchSize = 0

    /**
     * 「允许多方块共享」开关，**默认 false = 隔离**。
     *
     * ⚠️ 默认值不能改成 true：流体侧的标签 / 定量 / 库存列表同样是每件独立的配置，
     * 两个控制器读同一份 `stock` 就是串配方。带 `@field:SyncToClient` 的理由见物品版同名字段。
     */
    @field:SyncToClient
    @field:SaveField
    private var shareEnabled = false

    init {
        applyTagFilter()
    }

    /**
     * ⚠️ 这一条是 GTM 的不对称处：物品侧 `MEBusPartMachine` 有 public 的 `getActionSource()`，
     * 流体侧 `MEHatchPartMachine` 只有 `protected final IActionSource actionSource` 字段、**没有 getter**
     * （javap 两份类的方法表可直接对比）。库存槽需要一个统一视图，所以这里把那个 protected 字段暴露出来。
     */
    override fun getActionSource(): IActionSource = actionSource

    // ////////////////////////////////
    // ***** 仓室隔离 ****//
    // ////////////////////////////////

    /** 语义、时序与「为什么默认必须隔离」全部见物品版 [ETTagFilterStockBusPartMachine.canShared]（流体侧同款）。 */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean {
        return shareEnabled || GtetConfig.partsShareable()
    }

    override fun canBeShared(): Boolean {
        return shareEnabled || GtetConfig.partsShareable()
    }

    /** 同物品版：服务端改值 + 让本件所属的每个多方块立刻复检结构（⚠️ 先复制再遍历）。 */
    override fun setCanBeShared(shared: Boolean) {
        if (isRemote) return
        shareEnabled = shared
        syncDataHolder.markClientSyncFieldDirty("shareEnabled")
        for (controller in java.util.List.copyOf(controllers)) {
            controller.checkAndFormStructure()
        }
    }

    // ///////////////////////////////
    // ****** 标签过滤 *****//
    // ///////////////////////////////

    /** 理由见物品版同名方法：`@SaveField` 是反射直写字段，读档不经过 setter。 */
    override fun getTagFilter(): ETTagFilter {
        tagFilter.set(tagWhite, tagBlack)
        return tagFilter
    }

    override fun getTagWhite(): String = tagWhite

    override fun setTagWhite(expression: String) {
        tagWhite = expression
        applyTagFilter()
    }

    override fun getTagBlack(): String = tagBlack

    override fun setTagBlack(expression: String) {
        tagBlack = expression
        applyTagFilter()
    }

    // ///////////////////////////////
    // ******* 定量模式 *****//
    // ///////////////////////////////

    override fun getBatchSize(): Int = batchSize

    override fun setBatchSize(size: Int) {
        batchSize = Mth.clamp(size, BATCH_MIN, BATCH_MAX)
    }

    // ///////////////////////////////
    // ***** 机器生命周期 *****//
    // ///////////////////////////////

    /**
     * 换掉 GTM 的流体库存列表，好让每个槽都由 [ETTagFilterStockFluidSlot] 承担取数。
     *
     * ⚠️ 这个方法是**在 `super(...)` 构造期间**被调用的（`FluidHatchPartMachine` 构造器里
     * `attachTrait(createTank(...))`），那时本类的字段还没初始化 —— 所以列表与槽只持有 `this` 这个视图，
     * 绝不在构造期读 [batchSize] / [tagFilter]（它们只在运行期的 `syncME` / 取数里被读）。
     */
    override fun createTank(initialCapacity: Int, slots: Int): NotifiableFluidTank {
        aeFluidHandler = ETTagFilterStockFluidList(this, slots)
        return aeFluidHandler
    }

    /**
     * 必须在 GTM 那份 `addedToController` 之后：GTM 的 `IMEStockingPart#addedToController` 会覆盖 `autoPullTest`。
     * 限定 `super<MEStockingBusPartMachine>` 的理由见物品版同名方法。
     */
    override fun addedToController(controller: MultiblockControllerMachine, substructureName: String?) {
        super<MEStockingHatchPartMachine>.addedToController(controller, substructureName)
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart))
    }

    // ///////////////////////////////
    // ********** 备货 *********//
    // ///////////////////////////////

    /** 与 GTM 的 `MEStockingHatchPartMachine#syncME()` 逐行等价，只多了判空、标签闸门与定量上限。 */
    override fun syncME() {
        val grid = getMainNode().getGrid() ?: return
        val networkInv: MEStorage = grid.getStorageService().getInventory()
        val batch = batchLimit()

        for (slot in aeFluidHandler.getInventory()) {
            val config = slot.getConfig()
            if (config != null && testTag(config.what())) {
                val key = config.what()
                val available = networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, getActionSource())
                val want = available.coerceAtMost(batch)
                if (want >= minStackSize) {
                    slot.setStock(GenericStack(key, want))
                    continue
                }
            }
            slot.setStock(null)
        }
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    /**
     * 同物品版：先跑 GTM 的 `IMEStockingPart#getPanelBuilder` 默认实现、再追加「标签过滤」，
     * 否则会清掉 GTM 原生的配置列按钮。
     *
     * ⚠️ 必须写 `super<IMEStockingPart>`（理由见物品版同名方法：两个不同的默认实现，Kotlin 要求指明）。
     */
    override fun getPanelBuilder(
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ): MachineUIPanelBuilder {
        val builder = super<IMEStockingPart>.getPanelBuilder(guiData, syncManager, settings)
        ETTagFilterConfigurator.attach(this, builder, syncManager, settings, fluid = true, showShareSwitch = true)
        return builder
    }

    // ////////////////////////////////
    // ****** 配置（数据棒）******//
    // ////////////////////////////////

    override fun writeConfigToTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = super.writeConfigToTag(registries)
        writeTagFilter(tag)
        tag.putInt(NBT_BATCH_SIZE, batchSize)
        tag.putBoolean(NBT_SHARE, shareEnabled)
        return tag
    }

    override fun readConfigFromTag(registries: HolderLookup.Provider, tag: CompoundTag) {
        super.readConfigFromTag(registries, tag)
        readTagFilter(tag)
        if (tag.contains(NBT_BATCH_SIZE)) setBatchSize(tag.getInt(NBT_BATCH_SIZE))
        if (tag.contains(NBT_SHARE)) setCanBeShared(tag.getBoolean(NBT_SHARE))
    }

    // ///////////////////////////////
    // ******* 库存实现 *****//
    // ///////////////////////////////

    /**
     * GTET 自己的 AE 流体库存列表：只为让每个槽都是 [ETTagFilterStockFluidSlot]。
     *
     * ⚠️ 其余什么都不用做：`ExportOnlyAEFluidList` 的内部委托把 `drain` 转发给**我们的槽实例**，
     * 所以配方 / 能力两条取数路都落到 [ETTagFilterStockFluidSlot.drain]。
     */
    class ETTagFilterStockFluidList(private val host: IMEStockingHost, slots: Int) :
        ExportOnlyAEFluidList(host as MetaMachine, slots, Supplier { ETTagFilterStockFluidSlot(host) }) {

        override fun isStocking(): Boolean = true

        override fun isAutoPull(): Boolean = host.isAutoPull()

        /**
         * GTM 自己那份库存列表在这里做的是
         * `super.hasStackInConfig(stack, false) || (checkExternal && testConfiguredInOtherPart(stack))`。
         * 我们把库存列表换成了自己的，就必须把同一条语义搬过来，否则面板里
         * 「这个流体已经配置在本多方块的另一个库存件上了」的判断会静默失效。
         *
         * ⚠️ 不能写 `IConfigurableSlotList.super.hasStackInConfig(...)`：那个接口在**父类**上，
         * 不在本类的直接 superinterface 列表里，Java / Kotlin 都不允许这么限定。所以循环照抄一遍。
         */
        override fun hasStackInConfig(stack: GenericStack?, checkExternal: Boolean): Boolean {
            if (stack != null && stack.amount() > 0) {
                for (i in 0 until getConfigurableSlots()) {
                    val config = getConfigurableSlot(i).getConfig()
                    if (config != null && config.what() == stack.what()) return true
                }
            }
            return checkExternal && host.testConfiguredInOtherPart(stack)
        }
    }

    companion object {

        /** 「每次拉 N 个」的可配范围（N 的单位是 mB，上限 1_000_000 mB = 1000 桶）。0 = 不限制。 */
        const val BATCH_MIN: Int = 0
        const val BATCH_MAX: Int = 1_000_000

        /** 定量上限在数据棒里的键名。 */
        private const val NBT_BATCH_SIZE: String = "ETBatchSize"

        /** 共享开关在数据棒里的键名（理由见物品版 `NBT_SHARE`）。 */
        private const val NBT_SHARE: String = "ETShareEnabled"
    }
}

/**
 * GTET 自己的 AE 库存流体槽：把 GTM `MEStockingHatchPartMachine.ExportOnlyAEStockingFluidSlot#drain`
 * 那段逻辑重写一遍，并加上标签闸门与取数上限。
 */
class ETTagFilterStockFluidSlot : ExportOnlyAEFluidSlot {

    private val host: IMEStockingHost

    constructor(host: IMEStockingHost) {
        this.host = host
    }

    constructor(host: IMEStockingHost, config: GenericStack?, stock: GenericStack?) : super(config, stock) {
        this.host = host
    }

    /** 真正的取数点（`drain(FluidStack, FluidAction)` 会转发到这里）。 */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        val stock = getStock() ?: return FluidStack.EMPTY
        val config = getConfig() ?: return FluidStack.EMPTY
        val stockKey = stock.what()
        if (stockKey !is AEFluidKey) return FluidStack.EMPTY

        // 闸门一：标签
        if (!host.getTagFilter().test(config.what())) return FluidStack.EMPTY
        // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)
        val limit = minOf(maxDrain.toLong(), stock.amount(), host.batchLimit())
        if (limit <= 0 || !host.isOnline()) return FluidStack.EMPTY

        val grid = host.getMainNode().getGrid() ?: return FluidStack.EMPTY
        val aeNetwork: MEStorage = grid.getStorageService().getInventory()

        val actionable = if (action.simulate()) Actionable.SIMULATE else Actionable.MODULATE
        val extracted = aeNetwork.extract(stockKey, limit, actionable, host.getActionSource())
        if (extracted <= 0) return FluidStack.EMPTY

        val resultStack = AEUtil.toFluidStack(stockKey, extracted)
        if (action.execute()) {
            // 顺手把显示用的持有量减掉（GTM 原版同款）
            val left = stock.amount() - extracted
            setStock(if (left <= 0) null else ExportOnlyAESlot.copy(stock, left))
            getOnContentsChanged()?.run()
        }
        return resultStack
    }

    override fun copy(): ETTagFilterStockFluidSlot = ETTagFilterStockFluidSlot(
        host,
        getConfig()?.let { ExportOnlyAESlot.copy(it) },
        getStock()?.let { ExportOnlyAESlot.copy(it) },
    )
}
