package rain.fox.gtetcore.common.machine.multiblock.part.ae

import appeng.api.config.Actionable
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanelBuilder
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.feature.multiblock.IMEStockingPart
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.integration.ae2.ETTagFilter
import rain.fox.gtetcore.integration.ae2.ETTagFilterConfigurator
import rain.fox.gtetcore.integration.ae2.IMEStockingHost

/**
 * 「ME 标签库存输入总线」：GTM 的 `me_stocking_input_bus` + 标签白 / 黑名单过滤 + 「每次拉 N 个」的定量拉取。
 *
 * ## 两个模式（互不干涉、可叠加）
 *
 * 1. **标签模式**：只有「命中白名单且不命中黑名单」的 AE key 才允许被拉进来；两条都留空 = 不过滤（默认）。
 *    判定语义与书写规则见 [ETTagFilter]。
 * 2. **定量模式**：[batchSize] = 每次从 AE 网络往本仓备多少（0 = 不限制，默认）。是「一批一批地备」，
 *    不是「保底补到 N」—— 保底由 GTM 自己的 `minStackSize` 负责，两者会互相影响：
 *    ⚠️ 若 N 小于保底数量，本仓永远备不满（备出来的量达不到保底，[syncME] 会把 stock 清空）。
 *
 * ## 三道把关点（覆盖「配置 → 备货 → 真取数」全链）
 *
 * | 环节 | 方法 | 标签 | 定量 |
 * | --- | --- | --- | --- |
 * | autoPull 选配置 | GTM 的 `refreshList()` | [addedToController] 里换的 `autoPullTest` | — |
 * | 每个周期备货 | [syncME]（本类覆写） | ✔ | ✔（stock ≤ N） |
 * | 真正取数 | [ETTagFilterStockItemSlot.extractItem] | ✔（兜底） | ✔（min(要的量, 持有量, N)） |
 *
 * ⚠️ `stock` 全 GTM 只有两处会写：GTM 的 `refreshList()` 与本类的 [syncME]，而 `autoIO()` 的顺序是
 * 「先 refreshList 再 syncME」，所以 autoPull 模式下定量上限最终仍然生效；取数点再夹一次是为了不依赖这个时序。
 *
 * ## ⚠️ 与老工程（1.20.1 / GTM 7.5.3）的结构性差异
 *
 * - **没有 `createInventory` 覆写点了**：7.5.3 里库存列表是子类自己造的，8.0.0 改成由
 *   `MEStockingBusPartMachine` 的构造器直接把 `ExportOnlyAEStockingItemList` 交给父构造器、
 *   之后只从 `getInventory()` 取回（无虚方法可插），且那份列表的两个内部类仍是**包私有**、跨包继承不到。
 *   所以这里改成：**保留 GTM 的列表**（连同它的 `isStocking` / `isAutoPull` / `hasStackInConfig` 语义，
 *   一行都不用重写），只在构造末尾把 `inventory` 数组的元素逐个换成 [ETTagFilterStockItemSlot]
 *   —— 数组的编译期元素类型就是 `ExportOnlyAEItemSlot`，写入子类实例合法，
 *   而列表的 `extractItemInternal` 与 `handleRecipeInner` 都是在调用那一刻按数组取槽的（虚分派）。
 *   ⚠️ 槽实例在存档往返后仍然生效：`inventory` 上的 `@SaveField` 走
 *   `ObjectArrayTransformer`（复用现有数组、按元素 `currentValue()` 就地反序列化）
 *   + `NBTSerializableTransformer`（要求已有对象、就地写回），两者都不会重建元素。
 * - **没有 `IDropSaveMachine` 了**：8.0.0 整类已删（jar 里 `DropSave` 一个类都不剩），掉落物走
 *   `MetaMachine#saveToItem(ItemStack, HolderLookup.Provider)` → `collectComponents()`，
 *   也就是方块实体 NBT 自动进物品，所以 `@SaveField` 字段本来就随拆随放，不需要 `saveToItem` / `loadFromItem`。
 * - **`@Persisted` → `@field:SaveField`，`@DescSynced` → `@field:SyncToClient`**；
 *   后者改值要顺手 `syncDataHolder.markClientSyncFieldDirty("字段名")`（GTM 自己的 `setAutoPull` 同款）。
 *
 * @author rain fox
 */
open class ETTagFilterStockBusPartMachine(
    info: BlockEntityCreationInfo,
) : MEStockingBusPartMachine(info), IMEStockingHost, IMEStockingPart {

    /** 标签判定器：构造一次、按表达式变化与 key 缓存，绝不在每次判定时重新解析字符串。 */
    private val tagFilter = ETTagFilter()

    /**
     * 两条原始表达式。
     *
     * ⚠️ setter 的形参是**非空** `String`：本工程的 `IETTagFilterPart` 就是这么声明的
     * （老工程那份是可空的 `String?`）。写成可空会窄化失败，编译器直接报
     * 「'setTagWhite' overrides nothing」+「类未实现抽象成员」。
     */
    @field:SaveField
    private var tagWhite = ""

    @field:SaveField
    private var tagBlack = ""

    /** 每次从网络备货的上限；0 = 不限制（默认）。 */
    @field:SaveField
    private var batchSize = 0

    /**
     * 「允许多方块共享」开关，**默认 false = 隔离**。
     *
     * ⚠️ 默认值不能改成 true：本件的标签 / 定量 / 库存列表都是每件独立的，一旦被两个多方块共享，
     * 两个控制器会读同一份 `stock` 与同一套标签闸门 —— 这就是「串配方」。
     *
     * ⚠️ 带 `@field:SyncToClient`：共享开关面板要在客户端读这个值画按下状态与状态文字。
     */
    @field:SyncToClient
    @field:SaveField
    private var shareEnabled = false

    init {
        applyTagFilter()
        installTagFilterSlots()
    }

    /**
     * 把 GTM 造好的库存槽逐个换成 [ETTagFilterStockItemSlot]（理由见类注释「结构性差异」第一条）。
     *
     * ⚠️ 只换数组元素、不换列表本身：槽的 `config` / `stock` 在构造期都是空，直接搬过去即可。
     */
    private fun installTagFilterSlots() {
        val slots = aeItemHandler.getInventory()
        for (i in slots.indices) {
            val old = slots[i]
            slots[i] = ETTagFilterStockItemSlot(this, old.getConfig(), old.getStock())
        }
    }

    // ////////////////////////////////
    // ***** 仓室隔离 ****//
    // ////////////////////////////////

    /**
     * **仓室隔离（玩家可切换）**：默认禁止、面板开关打开后放行，返回值是「[shareEnabled] OR 全局配置
     * `multiblock.partsShareable`」。
     *
     * ⚠️ 这个值只在**结构检查那一刻**被读（GTM 唯一的消费点是结构图案逐格匹配），所以拨动开关
     * 不会让已经成型的结构凭空变化：隔离 → 允许共享对已成型结构没有任何即时影响（想让另一个多方块占用本件，
     * 必须让**那个**结构重新成型）；允许共享 → 隔离会让本件所属的**每个**控制器立刻复检
     * （见 [setCanBeShared]），共享的那一方该格判失败、当次就散架。
     *
     * ⚠️ 全局配置一旦打开就**无条件放行**、面板开关失去作用，「两个控制器读同一份 stock」的串配方风险随之恢复。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean {
        return shareEnabled || GtetConfig.partsShareable()
    }

    /** 面板开关的显示状态：与 [canShared] 同源（同样带配置兜底）。 */
    override fun canBeShared(): Boolean {
        return shareEnabled || GtetConfig.partsShareable()
    }

    /**
     * 拨动共享开关，并让本件所属的每个多方块**立刻复检一次结构**。
     *
     * ⚠️ **先复制再遍历**：`requestCheck()` 不通过时会走 `removedFromController`，而它会直接改
     * `controllers` 那个集合；`getControllers()` 返回的是**不可修改视图**（不是快照），边遍历边删会抛
     * `ConcurrentModificationException`。
     *
     * ⚠️ 只由服务端改：这是 `@SaveField` 字段（服务端权威），客户端自己赋值会在下一次同步时被覆盖。
     *
     * ⚠️ 8.0.0 的复检入口是 `MultiblockControllerMachine#checkAndFormStructure()`——
     * 老工程的 `IMultiController#requestCheck()` 在 8.0.0 已经不存在了（javap 可证）。
     */
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

    /**
     * ⚠️ 每次取用都先按当前两条原始表达式校准一次，而不是只在 setter 里解析：`@SaveField` 字段是
     * 同步系统用反射**直接写进字段**的（读档、拆方块放回去），一个 setter 都不会经过 ——
     * 只在 setter 里解析的话，存档读回之后判定器里还是空表达式，过滤会**静默失效**。
     * [ETTagFilter.set] 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
     */
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
     * 装回自动拉取谓词。
     *
     * ⚠️ 必须在 GTM 那份 `addedToController` **之后**调：GTM 的 `IMEStockingPart#addedToController`
     * 会把 `autoPullTest` 覆盖成「不同仓去重」检查（8.0.0 仍然是这一句），构造函数里设的会被它抹掉。
     * 这里把「标签放行」与「去重」两条语义组合回去。
     *
     * ⚠️ `addedToController` 同时被 `IMEStockingPart`（接口默认）与 `MultiblockPartMachine`（类实现）声明，
     * 所以本类显式列出了 `IMEStockingPart` 之后，`super.` 会报「Multiple supertypes available」——
     * 要的是**类**那条链（它会再往下调接口默认实现）。
     */
    override fun addedToController(controller: MultiblockControllerMachine, substructureName: String?) {
        super<MEStockingBusPartMachine>.addedToController(controller, substructureName)
        setAutoPullTest(tagAutoPullTest(this::testConfiguredInOtherPart))
    }

    // ///////////////////////////////
    // ********** 备货 *********//
    // ///////////////////////////////

    /**
     * 库存刷新：把「网络上有多少」按标签闸门与定量上限折算成「本仓备多少」，写进 `stock`。
     *
     * ⚠️ 与 GTM 的 `MEStockingBusPartMachine#syncME()` 逐行等价，只多了三处：① 网络取不到时直接返回
     * （GTM 原版没判空，只在在线时被调）；② 标签闸门；③ `want = min(available, batchLimit())`。
     *
     * 标签闸门不是装饰：`stock` 就是多方块配方匹配看到的「本仓有多少」，若把一个不放行的 key 留在 stock 里，
     * 配方会以为有料、开起来才发现取不到，于是卡住。
     */
    override fun syncME() {
        val grid = getMainNode().getGrid() ?: return
        val networkInv: MEStorage = grid.getStorageService().getInventory()
        val batch = batchLimit()

        for (slot in aeItemHandler.getInventory()) {
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
     * 面板：`IMEStockingPart#getPanelBuilder` 已经往右侧配置列加了自动拉取开关与库存保底弹窗，
     * 所以必须 **先跑 GTM 那个默认实现、再追加**「标签过滤」那一块，否则 GTM 那两件会被整体赋值覆盖掉。
     *
     * ⚠️ 这里必须写 `super<IMEStockingPart>`（`IMEStockingPart` 也因此在超类型表里显式列了一遍）：
     * 本类同时从 `IMEStockingPart` 与 `IMuiMachine` 继承到 `getPanelBuilder` 的**两个不同默认实现**，
     * Kotlin 会报「Multiple supertypes available」，必须指明要哪一个 —— 而要的是 GTM 那份带库存配置的。
     */
    override fun getPanelBuilder(
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ): MachineUIPanelBuilder {
        val builder = super<IMEStockingPart>.getPanelBuilder(guiData, syncManager, settings)
        ETTagFilterConfigurator.attach(this, builder, syncManager, settings, fluid = false, showShareSwitch = true)
        return builder
    }

    // ////////////////////////////////
    // ****** 配置（数据棒）******//
    // ////////////////////////////////

    /** 数据棒：在 GTM 原有的配置之外，把标签、定量与共享开关一起带走。 */
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

    companion object {

        /** 「每次拉 N 个」的可配范围。上限取 1_000_000：远超任何实际缓冲需求，又让输入框能一眼看全。 */
        const val BATCH_MIN: Int = 0
        const val BATCH_MAX: Int = 1_000_000

        /** 定量上限在数据棒里的键名。 */
        private const val NBT_BATCH_SIZE: String = "ETBatchSize"

        /**
         * 共享开关在数据棒里的键名。
         *
         * ⚠️ 必须显式写这一对键：`@SaveField` 只管方块实体存档，数据棒走的是
         * `writeConfigToTag` / `readConfigFromTag` 这条另一条路。缺键时不动，默认 false = 隔离。
         */
        private const val NBT_SHARE: String = "ETShareEnabled"
    }
}

/**
 * GTET 自己的 AE 库存物品槽：把 GTM `MEStockingBusPartMachine.ExportOnlyAEStockingItemSlot#extractItem`
 * 那段 AE 抽取逻辑重写一遍（它在**包私有**内部类里，跨包拿不到），并在里面加上两道 GTET 自己的闸门。
 */
class ETTagFilterStockItemSlot : ExportOnlyAEItemSlot {

    private val host: IMEStockingHost

    constructor(host: IMEStockingHost) {
        this.host = host
    }

    constructor(host: IMEStockingHost, config: GenericStack?, stock: GenericStack?) : super(config, stock) {
        this.host = host
    }

    /**
     * 从 AE 网络真正取数（GTM 原逻辑）+ 两道闸门。
     *
     * ⚠️ 闸门对**模拟与真实两条路一视同仁**，这是刻意的：GT 的配方匹配用 `simulate = true` 走的就是这条路，
     * 只卡真实抽取的话配方会「看着有料 → 开起来 → 实际拿不到 → 卡住」。
     */
    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (slot != 0) return ItemStack.EMPTY
        val stock = getStock() ?: return ItemStack.EMPTY
        val config = getConfig() ?: return ItemStack.EMPTY

        val key = config.what()
        // 闸门一：标签。stock 万一被别处的写法填上，这里也不放行。
        if (!host.getTagFilter().test(key)) return ItemStack.EMPTY
        // 闸门二：取数上限 = min(调用方要的量, 本仓当前持有量, 每批 N)。
        // ⚠️ 「不超过持有量」这一条是必需的：GTM 原版这里不看 stock（它的 stock 是全网存量、永远够），
        //    而定量模式下 stock 会被压到 N，不夹的话 stock 会被减成负数。
        val limit = minOf(amount.toLong(), stock.amount(), host.batchLimit())
        if (limit <= 0 || !host.isOnline()) return ItemStack.EMPTY

        val grid = host.getMainNode().getGrid() ?: return ItemStack.EMPTY
        val aeNetwork: MEStorage = grid.getStorageService().getInventory()

        val action = if (simulate) Actionable.SIMULATE else Actionable.MODULATE
        val extracted = aeNetwork.extract(key, limit, action, host.getActionSource())
        if (extracted <= 0) return ItemStack.EMPTY

        val result = if (key is AEItemKey) key.toStack(extracted.toInt()) else ItemStack.EMPTY
        if (!simulate) {
            // 顺手把显示用的持有量减掉（GTM 原版同款）
            val left = stock.amount() - extracted
            setStock(if (left <= 0) null else ExportOnlyAESlot.copy(stock, left))
            getOnContentsChanged()?.run()
        }
        return result
    }

    override fun copy(): ETTagFilterStockItemSlot = ETTagFilterStockItemSlot(
        host,
        getConfig()?.let { ExportOnlyAESlot.copy(it) },
        getStock()?.let { ExportOnlyAESlot.copy(it) },
    )
}
