package rain.fox.gtetcore.common.machine.multiblock.part.ae

import appeng.api.config.Actionable
import appeng.api.networking.IManagedGridNode
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.GenericStack
import appeng.api.storage.MEStorage
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.blockentity.IGregtechBlockEntity
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanelBuilder
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import rain.fox.gtetcore.data.lang.Ae2Lang
import rain.fox.gtetcore.integration.ae2.ETTagFilter
import rain.fox.gtetcore.integration.ae2.ETTagFilterConfigurator
import rain.fox.gtetcore.integration.ae2.IMEStockingHost
import java.util.PriorityQueue
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * 「ME 二合一库存输入总成」：**一个方块**同时挂 `IMPORT_ITEMS` 与 `IMPORT_FLUIDS`，
 * 物品与流体**都走库存拉取**（不是把两个现成部件拼在一起），并且两侧**各带一套**标签白 / 黑名单 +
 * 定量拉取。本类里**没有一行 AE 抽取逻辑**：物品侧整支继承、流体侧的槽也直接复用现成的那一个。
 *
 * ## 两侧怎么装进一个方块
 *
 * - **物品侧整支继承** [ETTagFilterStockBusPartMachine]（ME 标签库存输入总线）：库存列表、库存槽、
 *   标签过滤、定量拉取、`syncME`、自动拉取、数据棒、物品侧面板全部现成；
 * - **流体侧另挂一份库存列表**（[DualFluidList]，本类构造期 `attachTrait`），槽直接复用
 *   [ETTagFilterStockFluidSlot]（它只依赖 [IMEStockingHost]，从不把宿主强转成机器）；
 * - **两侧各一个宿主视图**：物品侧就是机器自己（父类实现），流体侧是 [FluidSideView]
 *   —— 把 AE 句柄转发给机器、把标签 / 定量指向本类的流体字段。
 *
 * ## ⚠️ 8.0.0 砍掉的钩子与替代做法（逐条实测，非推测）
 *
 * 1. **没有 `createInventory` 覆写点**（7.5.3 那版在构造链里顺手造出流体侧列表）：
 *    流体列表改在**本类 `init`** 里 `attachTrait(...)`。时机是安全的 ——
 *    `MachineTraitHolder` 的 `allowTraitAttachment` 在构造期为 `true`，
 *    `MetaMachine#onLoad()` 里调 `machineLoaded()` 才置 `false` 并抛
 *    `IllegalStateException("Cannot add traits to machine after machine has been loaded.")`（javap 可证），
 *    而 `init` 仍在构造期。GTM 自己挂物品侧列表也是在某个子类构造帧里做的，时机相同。
 *    ⚠️ `ExportOnlyAEFluidList(MetaMachine, int, Supplier)` 的机器参数**它自己不用**
 *    （构造器字节码里 `aload_1` 从不读），挂 trait 必须由调用方做，且只能做一次
 *    （`MachineTrait#setMachine` 二次挂会抛 `IllegalStateException`）。
 * 2. **`IDropSaveMachine` 整类已删**：掉落物走 `MetaMachine#saveToItem` → `collectImplicitComponents`，
 *    `@field:SaveField` 字段本来就随拆随放，所以本类**不覆写** `saveToItem` / `loadFromItem`
 *    （老工程那两个覆写是为了把流体配置塞进掉落物）。
 * 3. `@Persisted` → `@field:SaveField`；`ManagedFieldHolder` / `getFieldHolder()` 整套没了
 *    （8.0.0 的同步字段按类扫描注解，trait 走自己的 `SyncDataHolder`）。
 * 4. `IMultiController` / `IMultiPart` 已删：`addedToController(controller, substructureName)` 与
 *    `removedFromController(controller)` 都在 `MEStockingBusPartMachine` 上，且**流体侧那个少一个参数**。
 * 5. 老工程那套 LDLib 主界面（`createUIWidget` + `AEItemConfigWidget` / `AEFluidConfigWidget`）
 *    已不存在：主界面两块配置槽由 GTM 自己画，标签面板改走 MUI 浮层。
 *    ⚠️ 流体侧那块浮层**不能**照抄老工程的「继承配置器 + 覆写 `getTitle()`」：8.0.0 的
 *    [ETTagFilterConfigurator] 是 final 类、MUI 侧没有 `getTitle()`，改由 `attach(...)` 的
 *    `titleKey` / `displayMachine` 两个具名参数承载（`displayMachine` 是因为本类流体侧宿主是视图对象，
 *    配置器内部要拿真机器取图标与 `level`）。
 *
 * ## 两侧各一套配置
 *
 * 物品侧：父类的 `tagWhite` / `tagBlack` / `batchSize`。流体侧：[fluidTagWhite] / [fluidTagBlack] /
 * [fluidBatchSize]（都是 `@field:SaveField`）。
 * ⚠️ 两侧字段都是**反射直写**（读档、拆方块放回去都不走 setter），所以判定器一律在 getter 里按当前
 * 字符串校准（见 [getFluidTagFilter] 与父类同名方法）。
 *
 * ## 没做的事
 *
 * - 「保底数量」(`minStackSize`) 与「周期」(`ticksPerCycle`) **两侧共用一份**：它们由 GTM 自带的
 *   `stocking_settings` 浮层编辑，那份面板只认一个 `IMEStockingPart`，要分两侧就得整块重写它
 *   （本轮不做，也不假装支持）。
 * - 「多方块共享」开关**全机只有一个**：`canShared()` 是机器级方法，没有任何「按侧判定」的时机，
 *   所以物品侧那块面板带开关、流体侧待 `showShareSwitch = false`（免得玩家以为要拨两次）。
 *   本类比单侧件更需要隔离：一块方块挂在两条能力链上，被两个多方块共享时两个控制器会读**同一份
 *   两侧配置**，串配方后果比单侧件更重（开关语义与默认值见父类 `canShared`）。
 *
 * @author rain fox
 */
open class ETMEDualStockingPartMachine(info: BlockEntityCreationInfo) :
    ETTagFilterStockBusPartMachine(info) {

    /**
     * 流体侧库存宿主视图；与 [aeFluidHandler] 一样在 [init] 里赋值。
     *
     * ⚠️ 两个字段都用 `lateinit` + `init` 赋值，而不是写成带初值的字段初始化器：
     * 顺序（先视图、后列表）在 `init` 里是一目了然的两行；`lateinit` **不生成任何构造期赋值**，
     * 所以也不存在「字段初始化器先跑、把构造期挂好的东西再覆盖一遍」这类时序问题。
     * ⚠️ 赋值点必须是**构造期**（`init`），理由见类注释第 1 条（`attachTrait` 的守卫）。
     */
    private lateinit var fluidSide: FluidSideView

    /** 流体侧库存列表（16 个配置槽，与物品侧、与 GTM 自己的库存件一致）。 */
    private lateinit var aeFluidHandler: ExportOnlyAEFluidList

    /** 流体侧标签判定器：构造一次、按表达式变化与 key 缓存。 */
    private val fluidTagFilter = ETTagFilter()

    /** 流体侧白名单原始表达式。 */
    @field:SaveField
    private var fluidTagWhite = ""

    /** 流体侧黑名单原始表达式。 */
    @field:SaveField
    private var fluidTagBlack = ""

    /** 流体侧「每次拉 N 个」的 N，单位 mB；0 = 不限制（默认，与物品侧一致）。 */
    @field:SaveField
    private var fluidBatchSize = 0

    init {
        fluidSide = FluidSideView(this)
        // ⚠️ 槽数取父类链上 MEInputBusPartMachine.CONFIG_SIZE（protected，可继承；javap 可证为 16）。
        //    不能用 MEStockingHatchPartMachine.CONFIG_SIZE：那是别的包里的 private 常量，跨包取不到（同为 16）。
        aeFluidHandler = attachTrait(DualFluidList(this, MEInputBusPartMachine.CONFIG_SIZE, fluidSide))
    }

    // ///////////////////////////////
    // ***** 机器生命周期 *****//
    // ///////////////////////////////

    /**
     * 重装自动拉取谓词：**按 key 类型分派到两侧标签**（物品 key 走物品侧、流体 key 走流体侧）。
     *
     * ⚠️ 必须在父类那份 `addedToController` **之后**：它装的是「物品侧标签 ∧ 去重」，而
     * `autoPullTest` 全 GTM 只有一个字段（没有按侧区分的入口），不重装的话流体 key 会被拿物品侧的
     * 表达式去判 —— autoPull 模式下流体侧会一个都拉不进来。
     */
    override fun addedToController(controller: MultiblockControllerMachine, substructureName: String?) {
        super.addedToController(controller, substructureName)
        setAutoPullTest(Predicate { autoPullAllows(it) })
    }

    override fun removedFromController(controller: MultiblockControllerMachine) {
        super.removedFromController(controller)
        // ⚠️ GTM 的 IMEStockingPart#removedFromController 只清 getSlotList()（物品侧那一份），流体侧要自己补
        if (isAutoPull) aeFluidHandler.clearInventory(0)
    }

    /**
     * 自动拉取（autoPull 打开时）选配置槽的放行判定。
     *
     * 「不是别的库存件已经配过的东西」这一条对两侧都保留（GTM 自己的语义），只把标签那一半按
     * key 的类型分了侧。
     */
    private fun autoPullAllows(stack: GenericStack): Boolean {
        val what = stack.what()
        val tagged = if (what is AEFluidKey) fluidSide.testTag(what) else testTag(what)
        return tagged && !testConfiguredInOtherPart(stack)
    }

    /**
     * 配置去重校验。
     *
     * ⚠️ 必须整个覆写：`IMEStockingPart` 只声明了**一个** `getSlotList()`（= 物品侧），
     * 接口的默认实现管不到流体侧 —— 同一个多方块里另一件库存仓配过的流体会被留在配置里。
     *
     * ⚠️ 不能写 `IMEStockingPart.super.validateConfig()`：那种限定调用只允许在「该接口是本类的直接
     * 超接口」时用，而这里它是由**超类**实现的。所以把接口默认实现那段循环搬过来，两侧各跑一遍。
     */
    override fun validateConfig() {
        clearDuplicateConfigs(getSlotList())
        clearDuplicateConfigs(aeFluidHandler)
    }

    /** 把「已经配置在同一多方块的另一个库存件上」的槽清掉（GTM 的 `IMEStockingPart#validateConfig` 循环体）。 */
    private fun clearDuplicateConfigs(list: IConfigurableSlotList) {
        for (i in 0 until list.configurableSlots) {
            val slot = list.getConfigurableSlot(i)
            val config = slot.config
            if (config != null && testConfiguredInOtherPart(config)) {
                slot.config = null
                slot.stock = null
            }
        }
    }

    /**
     * 「该配置是否已经在同一个多方块的另一个库存件上」。
     *
     * ⚠️ 物品配置交给 GTM 的物品版实现（它会跳过 distinct 模式、只看别件物品总成）；
     * 流体配置它**看不见**（那套只扫 `MEStockingBusPartMachine` 的物品列表），所以流体自己扫一遍。
     * 扫描语义与 GTM 的 `MEStockingHatchPartMachine#testConfiguredInOtherPart` 一致
     * —— 注意流体那版与物品版是**不对称**的：流体版**不**判 distinct，这里照做，不擅自「修好」它。
     */
    override fun testConfiguredInOtherPart(config: GenericStack?): Boolean {
        if (config == null) return false
        if (config.what() is AEFluidKey) return testFluidConfiguredInOtherPart(config)
        return super.testConfiguredInOtherPart(config)
    }

    /** 流体侧的 [testConfiguredInOtherPart]：扫别的库存输入仓，以及别的二合一件的流体列表。 */
    private fun testFluidConfiguredInOtherPart(config: GenericStack): Boolean {
        if (!isFormed) return false
        for (controller in getControllers()) {
            for (part in controller.parts) {
                if (part === this) continue
                if (part is MEStockingHatchPartMachine) {
                    // getSlotList() 是 public 的（IMEStockingPart 声明），不必碰它的 private 字段
                    if (part.slotList.hasStackInConfig(config, false)) return true
                } else if (part is ETMEDualStockingPartMachine) {
                    if (part.aeFluidHandler.hasStackInConfig(config, false)) return true
                }
            }
        }
        return false
    }

    // ///////////////////////////////
    // ****** 标签过滤（流体侧）*****//
    // ///////////////////////////////

    /**
     * 理由与父类同名方法一样：`@field:SaveField` 字段是同步系统用反射**直接写进字段**的
     * （读档、拆方块放回去、以后有人直接赋值），一个 setter 都不会经过 —— 只在 setter 里解析的话，
     * 存档读回之后判定器里还是空表达式，过滤会**静默失效**。
     * [ETTagFilter.set] 在两条字符串都没变时第一步就 return，所以这么做没有重复解析的代价。
     */
    fun getFluidTagFilter(): ETTagFilter {
        fluidTagFilter.set(fluidTagWhite, fluidTagBlack)
        return fluidTagFilter
    }

    fun getFluidTagWhite(): String = fluidTagWhite

    fun setFluidTagWhite(expression: String) {
        fluidTagWhite = expression
        applyFluidTagFilter()
    }

    fun getFluidTagBlack(): String = fluidTagBlack

    fun setFluidTagBlack(expression: String) {
        fluidTagBlack = expression
        applyFluidTagFilter()
    }

    /** 把当前两条原始表达式喂给流体侧判定器（内部会跳过「没变」的情况）。 */
    private fun applyFluidTagFilter() {
        fluidTagFilter.set(fluidTagWhite, fluidTagBlack)
    }

    fun getFluidBatchSize(): Int = fluidBatchSize

    fun setFluidBatchSize(size: Int) {
        // 上下限沿用物品侧那两个常量（0 = 不限制），两侧一致才好解释
        fluidBatchSize = Mth.clamp(size, ETTagFilterStockBusPartMachine.BATCH_MIN, ETTagFilterStockBusPartMachine.BATCH_MAX)
    }

    // ///////////////////////////////
    // ********** 备货 *********//
    // ///////////////////////////////

    /**
     * 库存刷新：`super` 管物品侧，本类先补流体侧的**配置**、再补流体侧的**备货**。
     *
     * ⚠️ 流体侧的配置刷新（`refreshFluidList`）放在这里而不是覆写 `autoIO()`：GTM 的 `refreshList()`
     * 是 private 且只填物品侧，而 8.0.0 的 `MEStockingBusPartMachine#autoIO` 结构是
     * 「`ticksPerCycle == 0` 时补默认值 → `getOffsetTimer() % ticksPerCycle == 0` 时
     * `if (autoPull) refreshList()` → `syncME()`」（javap 可证）。
     * 也就是说 `refreshList()` 与 `syncME()` 本来就在**同一个 tick、同一个取模分支**里跑，
     * 所以把流体侧配置刷新放在 `syncME()` 开头与之**完全等价**，还不用把 `ticksPerCycle`
     * 那个「0 时取配置默认值」的补齐逻辑抄一遍（抢在 `super.autoIO()` 之前跑就会踩到那个 0）。
     */
    override fun syncME() {
        if (isAutoPull) refreshFluidList()
        super.syncME()
        syncFluidME()
    }

    /**
     * 流体侧备货：与父类物品侧那份**逐行同构**（GTM 的 `MEStockingBusPartMachine#syncME` 与
     * `MEStockingHatchPartMachine#syncME` 本来也是同构的），只多了判空、标签闸门与定量上限。
     *
     * 标签闸门不是装饰：`stock` 就是多方块配方匹配看到的「本仓有多少」，把一个不放行的 key 留在
     * `stock` 里，配方会以为有料、开起来才发现取不到，于是卡住。
     */
    private fun syncFluidME() {
        val grid = getMainNode().grid ?: return
        val networkInv: MEStorage = grid.storageService.inventory
        val batch = fluidSide.batchLimit()

        for (slot in aeFluidHandler.inventory) {
            val config = slot.getConfig()
            if (config != null && fluidSide.testTag(config.what())) {
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

    /**
     * 流体侧的自动配置列表刷新：与 GTM 的 `MEStockingHatchPartMachine#refreshList` 同一套算法
     * （取网络上存量最大的 `CONFIG_SIZE` 个流体，按存量从大到小倒着填进配置槽），只把「该不该收这个 key」
     * 换成两侧共用的 [autoPullAllows]。
     *
     * ⚠️ 堆里存**新建的 [GenericStack]**而不是 `KeyCounter` 迭代出来的 entry 对象：AE2 19 的
     * `MEStorage#getAvailableStacks()` 返回的是 `KeyCounter`（AE2 15 是 `Object2LongMap`），
     * 迭代出来的 entry 是它内部子表的条目，长期持有不能保证不被复用。
     */
    private fun refreshFluidList() {
        val grid = getMainNode().grid
        if (grid == null) {
            aeFluidHandler.clearInventory(0)
            return
        }

        val networkStorage: MEStorage = grid.storageService.inventory
        val counter = networkStorage.availableStacks
        val slots = aeFluidHandler.configurableSlots

        // 小顶堆：留下存量最大的 slots 个
        val topFluids = PriorityQueue<GenericStack>(compareBy { it.amount() })

        for (entry in counter) {
            val amount = entry.longValue
            val what = entry.key

            if (amount <= 0) continue
            if (what !is AEFluidKey) continue

            val request = networkStorage.extract(what, amount, Actionable.SIMULATE, getActionSource())
            if (request == 0L) continue

            if (!autoPullAllows(GenericStack(what, amount))) continue
            if (amount >= minStackSize) {
                if (topFluids.size < slots) {
                    topFluids.offer(GenericStack(what, amount))
                } else if (amount > topFluids.peek().amount()) {
                    topFluids.poll()
                    topFluids.offer(GenericStack(what, amount))
                }
            }
        }

        // poll() 先出最小的，所以从后往前填，面板上就是「多的在上面」
        val fluidAmount = topFluids.size
        var index = 0
        while (index < slots) {
            if (topFluids.isEmpty()) break
            val stack = topFluids.poll()
            val what = stack.what()

            val request = networkStorage.extract(what, stack.amount(), Actionable.SIMULATE, getActionSource())

            val slot = aeFluidHandler.inventory[fluidAmount - index - 1]
            slot.setConfig(GenericStack(what, 1))
            slot.setStock(GenericStack(what, request))
            index++
        }

        aeFluidHandler.clearInventory(index)
    }

    // ///////////////////////////////
    // ********** GUI ***********//
    // ///////////////////////////////

    /**
     * 面板：`super` 已经把 GTM 原生那两件（自动拉取开关、`stocking_settings` 保底弹窗）与**物品侧**
     * 那块「标签过滤」都挂上了，这里只补**流体侧**那块。
     *
     * ⚠️ 两块面板靠两个参数分开（都是 8.0.0 才有的入口，见类注释第 5 条）：
     * `titleKey = Ae2Lang.TITLE_FLUIDS` 让流体侧那块标题带侧别（物品侧沿用共用的「标签过滤」），
     * `displayMachine = this` 是因为流体侧宿主是 [FluidSideView]（不是 `MetaMachine`），
     * 配置器内部要靠真机器取物品图标与 `level` —— 不传就会在打开界面时抛 `ClassCastException`。
     */
    override fun getPanelBuilder(
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ): MachineUIPanelBuilder {
        val builder = super.getPanelBuilder(guiData, syncManager, settings)
        ETTagFilterConfigurator.attach(
            fluidSide, builder, syncManager, settings,
            fluid = true,
            // 共享开关是全机一个（见类注释），流体侧那块不画
            showShareSwitch = false,
            titleKey = Ae2Lang.TITLE_FLUIDS,
            displayMachine = this,
        )
        return builder
    }

    // ////////////////////////////////
    // ****** 配置（数据棒）******//
    // ////////////////////////////////

    /**
     * 数据棒：GTM 的物品版写「AutoPull / GhostCircuit / 各配置槽」，父类又补了物品侧标签与定量，
     * 本类再补流体侧。
     *
     * ⚠️ 数据棒的两个入口在 GTM 里改不了，但都走本方法 / [readConfigFromTag]，所以流体配置照样能随数据棒走。
     */
    override fun writeConfigToTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = super.writeConfigToTag(registries)
        // autoPull 模式下流体配置是自动填的，跟 GTM 对物品侧的处理一样不存（存了也会被下次刷新覆盖）
        if (!isAutoPull) writeFluidSide(tag, registries)
        return tag
    }

    override fun readConfigFromTag(registries: HolderLookup.Provider, tag: CompoundTag) {
        super.readConfigFromTag(registries, tag)
        readFluidSide(tag, registries)
    }

    /** 把流体侧的两条表达式、定量上限与全部配置槽写进给定 tag。 */
    private fun writeFluidSide(tag: CompoundTag, registries: HolderLookup.Provider) {
        tag.putString(NBT_FLUID_TAG_WHITE, fluidTagWhite)
        tag.putString(NBT_FLUID_TAG_BLACK, fluidTagBlack)
        tag.putInt(NBT_FLUID_BATCH_SIZE, fluidBatchSize)
        val configs = CompoundTag()
        tag.put(NBT_FLUID_CONFIGS, configs)
        for (i in 0 until aeFluidHandler.configurableSlots) {
            val config = aeFluidHandler.getConfigurableSlot(i).config
            // ⚠️ AE2 19 的 GenericStack.writeTag / readTag 多了 HolderLookup.Provider 首参（AE2 15 没有）
            if (config != null) configs.put(i.toString(), GenericStack.writeTag(registries, config))
        }
    }

    /** 读回流体侧配置；缺键就不动（老存档 / 没存过的数据棒）。 */
    private fun readFluidSide(tag: CompoundTag, registries: HolderLookup.Provider) {
        if (tag.contains(NBT_FLUID_TAG_WHITE)) setFluidTagWhite(tag.getString(NBT_FLUID_TAG_WHITE))
        if (tag.contains(NBT_FLUID_TAG_BLACK)) setFluidTagBlack(tag.getString(NBT_FLUID_TAG_BLACK))
        if (tag.contains(NBT_FLUID_BATCH_SIZE)) setFluidBatchSize(tag.getInt(NBT_FLUID_BATCH_SIZE))
        if (tag.contains(NBT_FLUID_CONFIGS)) {
            val configs = tag.getCompound(NBT_FLUID_CONFIGS)
            for (i in 0 until aeFluidHandler.configurableSlots) {
                val key = i.toString()
                aeFluidHandler.getConfigurableSlot(i).config =
                    if (configs.contains(key)) GenericStack.readTag(registries, configs.getCompound(key)) else null
            }
        }
    }

    // ///////////////////////////////
    // ***** 流体侧宿主视图 *******//
    // ///////////////////////////////

    /**
     * 流体侧的库存宿主视图：AE 句柄转发给机器本身，标签与定量指向机器的**流体字段**。
     *
     * ⚠️ 必须是嵌套的静态式类（Kotlin 嵌套类）并自己持有机器引用：它在**构造期**就被创建
     * （见 [init]），此时用 `inner` 捕获 `this` 会在字段尚未初始化时就能读到默认值；
     * 拆成显式引用后，这里只保存引用、不读字段。
     *
     * ⚠️ **为什么类头上有 `IGregtechBlockEntity by machine`**：本工程的 [IMEStockingHost] 继承
     * `IMuiMachine`（为的是实现类直接进 GTM 8.0.0 的面板装配点），而 `IMuiMachine` → `IMachineFeature`
     * → `IGregtechBlockEntity`，于是「块实体」那一面（`getOffsetTimer()`、同步容器
     * `getSyncDataHolder()` / `getParentSyncObject()`、tick 订阅、以及 NeoForge 的
     * `IBlockEntityExtension`）也成了抽象成员 —— 机器实现它天然满足，一个纯配置视图却必须自己交代。
     * 这里按**委托**整面转发给真机器（视图本来就是「同一台机器、另一侧配置」，语义上没有撒谎），
     * 于是本类只需写两侧真正有差异的那些成员。
     * ⚠️ 不写这一句的话编译器会直接报
     * `Class 'ETMEDualStockingPartMachine.FluidSideView' is not abstract and does not implement abstract member 'getOffsetTimer'`。
     * ⚠️ 委托只覆盖 `IGregtechBlockEntity` 自己声明的（含它间接继承的）抽象成员；`IMachineFeature#self()` 是
     * **default** 方法（`return (MetaMachine) this`，javap 可证），**不**在委托范围内 —— 也就是说不存在
     * 「对着视图调 `self()`」这条路：本类不调它，库存槽也不调它，面板那边则由
     * `attach(..., displayMachine = this)` 显式喂真机器（见 [getPanelBuilder]）。
     *
     * ⚠️ 共享开关两侧共用机器那一个（`canShared()` 是机器级方法），所以这里只是转发，
     * 不是「流体侧自己有一个开关」—— 流体侧那块面板也不画这个开关，见 [getPanelBuilder]。
     */
    private class FluidSideView(private val machine: ETMEDualStockingPartMachine) :
        IMEStockingHost,
        IGregtechBlockEntity by machine {

        override fun getTagFilter(): ETTagFilter = machine.getFluidTagFilter()

        override fun getTagWhite(): String = machine.getFluidTagWhite()

        override fun setTagWhite(expression: String) = machine.setFluidTagWhite(expression)

        override fun getTagBlack(): String = machine.getFluidTagBlack()

        override fun setTagBlack(expression: String) = machine.setFluidTagBlack(expression)

        override fun isOnline(): Boolean = machine.isOnline()

        override fun getMainNode(): IManagedGridNode = machine.getMainNode()

        /**
         * 物品侧父类链上 `MEBusPartMachine` 有 public 的 `getActionSource()`（javap 可证），
         * 所以这里直接转发 —— 流体侧那支（`MEHatchPartMachine`）才是只有 protected 字段的那一个。
         */
        override fun getActionSource(): IActionSource = machine.getActionSource()

        override fun isAutoPull(): Boolean = machine.isAutoPull()

        override fun getBatchSize(): Int = machine.getFluidBatchSize()

        override fun setBatchSize(size: Int) = machine.setFluidBatchSize(size)

        override fun testConfiguredInOtherPart(config: GenericStack?): Boolean = machine.testConfiguredInOtherPart(config)

        override fun canBeShared(): Boolean = machine.canBeShared()

        override fun setCanBeShared(shared: Boolean) = machine.setCanBeShared(shared)
    }

    // ///////////////////////////////
    // ******* 库存实现 *************//
    // ///////////////////////////////

    /**
     * 流体侧库存列表：只为把槽换成本 mod 自己的 [ETTagFilterStockFluidSlot]（配方 / 能力两条取数路
     * 都落到它）。
     *
     * ⚠️ 为什么不直接用第一批的 `ETTagFilterStockFluidList`：它的构造器要把宿主同时当 `MetaMachine`
     * （拿去喂 `ExportOnlyAEFluidList`）用，而流体侧的宿主是 [FluidSideView]（不是机器）。
     * 这里把两件事拆开 —— 机器当 `MetaMachine`、视图当 [IMEStockingHost]，
     * 于是槽本身**一行都不用重写**。
     */
    private class DualFluidList(machine: MetaMachine, slots: Int, private val side: IMEStockingHost) :
        ExportOnlyAEFluidList(machine, slots, Supplier { ETTagFilterStockFluidSlot(side) }) {

        /** 让 GTM 的配置面板把本列表当作「库存列表」画（否则槽会被画成可编辑的普通槽）。 */
        override fun isStocking(): Boolean = true

        override fun isAutoPull(): Boolean = side.isAutoPull()

        /**
         * 把 `IConfigurableSlotList#hasStackInConfig` 的默认实现（只看自己那几格）搬过来，
         * 再补上 GTM 库存列表那条 `checkExternal` 语义。
         *
         * ⚠️ 不能写 `super.hasStackInConfig(...)` / `IConfigurableSlotList.super.hasStackInConfig(...)`：
         * 默认实现里 `checkExternal` 是**被忽略**的（javap 可证），而且那个接口在**父类**上、
         * 不在本类的直接 superinterface 列表里，限定调用不被允许。所以循环照抄一遍。
         */
        override fun hasStackInConfig(stack: GenericStack?, checkExternal: Boolean): Boolean {
            if (stack != null && stack.amount() > 0) {
                for (i in 0 until configurableSlots) {
                    val config = getConfigurableSlot(i).config
                    if (config != null && config.what() == stack.what()) return true
                }
            }
            return checkExternal && side.testConfiguredInOtherPart(stack)
        }
    }

    companion object {

        /** 流体侧配置（标签两条 + 定量上限 + 全部配置槽）在数据棒 NBT 里的键名。 */
        private const val NBT_FLUID_TAG_WHITE: String = "ETFluidTagWhite"
        private const val NBT_FLUID_TAG_BLACK: String = "ETFluidTagBlack"
        private const val NBT_FLUID_BATCH_SIZE: String = "ETFluidBatchSize"
        private const val NBT_FLUID_CONFIGS: String = "ETFluidConfigStacks"
    }
}
