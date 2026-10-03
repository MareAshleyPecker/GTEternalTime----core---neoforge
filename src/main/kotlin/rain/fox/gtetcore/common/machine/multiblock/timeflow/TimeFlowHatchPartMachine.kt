package rain.fox.gtetcore.common.machine.multiblock.timeflow

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient
import com.gregtechceu.gtceu.utils.ExtendedUseOnContext
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import rain.fox.gtetcore.api.timeflow.ETTimeFlowHandler
import rain.fox.gtetcore.api.timeflow.ITimeFlowStorage
import rain.fox.gtetcore.api.timeflow.ITimeFlowTower
import rain.fox.gtetcore.api.timeflow.TimeFlowTowers
import rain.fox.gtetcore.common.item.timeflow.TimeClockData
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.TimeFlowHatchLang

import brachy.modularui.api.drawable.Text
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.layout.Flow

/**
 * 「时序仓」多方块部件（UHV ~ MAX 六档，档位与容量由 `ETTimeFlowHatches` 的变体表在构造时注入）。
 *
 * ## 它干三件事
 * 1. **持有 TF 缓冲**：内部一个 [ETTimeFlowHandler]（long，上限 = 本档容量），
 *    本类只通过 [ITimeFlowStorage] 与它打交道 —— **没有第二份 TF 缓冲**；
 * 2. **把 TF 暴露给配方**：[tfHandler] 是以 `attachTrait` 挂上去的，于是
 *    `MultiblockPartMachine#getHandlerList()` 会自己把它收进 `RecipeHandlerList`
 *    （它是拿 `getTraitsByInterface(IRecipeHandlerTrait.class)` 收的，见 `MultiblockPartMachine.java:131-134`），
 *    控制器成型时再把 `part.getRecipeHandlers()` 收进去；配方的 `tickInputs` 里只要带
 *    `gtetscore:time_flow`，每 tick 就会真的从本仓扣（链路见 [ETTimeFlowHandler] 的类注释）。
 *    ⚠️ **本类刻意不覆写 `getRecipeHandlers()`**：8.0.0 的 trait 路线已经能走通，
 *    再手工拼一个 `RecipeHandlerList` 反而会与 trait 表里的那一份重复（同一份缓冲被扣两次）。
 * 3. **从主控塔拉 TF**：绑定一座塔，每 tick `tower.extractTimeFlow(剩余空间)` 灌进自己的缓冲。
 *
 * ## 存档（⚠️ 与老工程写法完全不同）
 * 老工程走 `@Persisted` 字段 + `saveCustomPersistedData` / `loadCustomPersistedData`；
 * 8.0.0 删了后两个（`saveAdditional` 已是 `final`，见 `ManagedSyncBlockEntity.java:53`），
 * 改由 `api.sync_system` 的 `@SaveField` 负责：
 * - **绑定**（维度串 + 三个坐标）是本类自己的 `@SaveField` 字段；
 * - **TF 存量**在 [tfHandler] 里，而本类的 `tfHandler` 字段标了 `@SaveField` ⇒ sync 系统会
 *   **递归**进那个 `ISyncManaged` 对象、把 trait 自己的 `@SaveField` 一起写进同一个 tag
 *   （`SyncDataHolder$SyncManagedTransformer`，`SyncDataHolder.java:249-260`；GTM 自己的
 *   `EnergyHatchPartMachine` 也是这么存 `NotifiableEnergyContainer` 的）。
 *   所以**不需要**手写 `loadAdditional`：老工程那段「把越界值夹回本档容量」的逻辑搬到了
 *   [ETTimeFlowHandler] 的读侧（sync 系统直接写字段，绕不过 setter，只能在读的时候兜）。
 *
 * ## 本期只做同维度，也没有「自动连唯一塔」
 * 绑定塔与自己在**同一维度**、且塔所在区块已加载时才拉；跨维度一律不拉（设定 §5：
 * 跨维度只能靠时序钟搬运）。拉不到就是「本次不拉」，**静默等待**，不刷日志、不报错。
 * ⚠️ 老工程还有一条「未绑定时自动连全服唯一那座塔」（`MasterTowerRegistry.autoTower()`）——
 * 那个记账类属于**主塔切片**，本工程还没有，所以这里只有显式绑定一条路
 * （见 [pullTick] 里的 `TODO(主塔切片)`）。
 *
 * ## 档位
 * 交给 GTM 的档位就是变体表给的那个**真实档位**（`0..MAX`）。虚档位 `ETV` 那一档要等
 * `ETValues` 移植过来才会出现，届时本类的构造参数会多一个「逻辑档位」，理由与后果见
 * `ETTimeFlowHatches` 的类注释与它 [rain.fox.gtetcore.common.data.machine.hatch.ETTimeFlowHatches.VARIANTS]
 * 上的 TODO。
 *
 * @param tier     电压档位（真实档位，决定外壳贴图；见变体表）
 * @param capacity 本档容量上限（TF），由 `ETTimeFlowHatches` 的变体表显式给出
 *
 * @author rain fox
 */
class TimeFlowHatchPartMachine(
    info: BlockEntityCreationInfo,
    tier: Int,
    // 容量构造时定死、之后不再变化，所以**不需要**存档字段（面板两端都按同一个构造参数渲染）。
    private val capacity: Long,
) : TieredPartMachine(info, tier), ITimeFlowStorage, IMuiMachine {

    /**
     * TF 缓冲，同时也是本仓唯一的真值源：配方扣的就是它，拉塔灌的也是它。
     *
     * ⚠️ 用 `attachTrait` 挂成真正的 `MachineTrait`（`NotifiableRecipeHandlerTrait`），
     * 而不是像老工程那样持有一个「不是 trait 的处理器」—— 两个理由见类注释（处理器表 + 存档）。
     * `@SaveField` 标在**这个字段**上，sync 系统就会递归进去存它内部的 `time_flow`。
     */
    @field:SaveField
    val tfHandler: ETTimeFlowHandler = attachTrait(ETTimeFlowHandler(0L, capacity))

    // ================================================================
    //  绑定（维度 + 坐标；分四个字段存，String/Int 都是 sync 系统原生支持的类型）
    // ================================================================

    /** 绑定塔所在维度（`minecraft:overworld` 这种）；空串 = 未绑定。 */
    @field:SaveField
    @field:SyncToClient
    var boundDim: String = ""
        private set

    /** 绑定塔的 X 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundX: Int = 0
        private set

    /** 绑定塔的 Y 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundY: Int = 0
        private set

    /** 绑定塔的 Z 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundZ: Int = 0
        private set

    /** 是否已绑定一座塔。 */
    val isBound: Boolean get() = boundDim.isNotEmpty()

    /**
     * 每 tick 拉一次塔的订阅句柄。
     *
     * ⚠️ **不用** `ConditionalSubscriptionHandler`（老工程原先用它，条件是 `isBound`）：那个 handler
     * 的条件只在 `updateSubscription()` 被显式调到时才重算，而本仓「有没有塔可拉」会在**我们收不到
     * 通知**的时刻变化 —— 唯一塔被建起来 / 被别人拆掉、塔所在区块卸载又加载。条件一旦卡在 `false`，
     * 就再也不会自己翻回来，仓会一直不工作。
     * 所以改成无条件每 tick 调一次 [pullTick]：它开头就是几次整数比较，没塔就直接返回
     * （与无线能源仓同一条取舍）。
     */
    private var tickSubscription: TickableSubscription? = null

    /** 读回绑定好的塔；未绑定 / 维度串坏了返回 `null`。 */
    fun getBoundTower(): GlobalPos? {
        if (!isBound) return null
        val dim = ResourceLocation.tryParse(boundDim) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dim),
            BlockPos(boundX, boundY, boundZ)
        )
    }

    /** 绑定到一座塔；返回是否真的改了（重复绑同一座塔返回 `false`）。 */
    fun bindTower(tower: GlobalPos): Boolean {
        val dim = tower.dimension().location().toString()
        val pos = tower.pos()
        if (dim == boundDim && pos.x == boundX && pos.y == boundY && pos.z == boundZ) return false
        boundDim = dim
        boundX = pos.x
        boundY = pos.y
        boundZ = pos.z
        markBindingDirty()
        return true
    }

    /** 解绑；返回是否真的改了。 */
    fun unbindTower(): Boolean {
        if (!isBound) return false
        boundDim = ""
        boundX = 0
        boundY = 0
        boundZ = 0
        markBindingDirty()
        return true
    }

    /**
     * 把四个绑定字段标脏、同步给客户端（部件面板要显示绑的是哪座塔）。
     *
     * ⚠️ `@field:SyncToClient` 只是「这个字段允许同步」，**不会**自己发现变化：值改完必须显式
     * `markClientSyncFieldDirty(字段名)`，否则客户端一直看旧值（与 `TestSyncPartMachine` 同款）。
     * 绑定只在玩家做手势时变一次，所以这里没有每 tick 发包的开销。
     */
    private fun markBindingDirty() {
        syncDataHolder.markClientSyncFieldDirty("boundDim")
        syncDataHolder.markClientSyncFieldDirty("boundX")
        syncDataHolder.markClientSyncFieldDirty("boundY")
        syncDataHolder.markClientSyncFieldDirty("boundZ")
    }

    // ================================================================
    //  ITimeFlowStorage（本仓的 TF 缓冲就是 tfHandler）
    // ================================================================

    /** 当前存量（TF）。 */
    override fun getTimeFlow(): Long = tfHandler.amount

    /**
     * 直接写入存量，返回夹取后的实际值（契约见 [ITimeFlowStorage]）。
     *
     * [ETTimeFlowHandler] 只有 `insert` / `extract` 两个口子，所以这里是「先清空、再灌入」；
     * 夹取由 `insert` 自己做（它按容量截断）。
     */
    override fun setTimeFlow(amount: Long): Long {
        val clamped = amount.coerceIn(0L, getTimeFlowCapacity())
        tfHandler.extract(Long.MAX_VALUE)
        tfHandler.insert(clamped)
        return clamped
    }

    /** 容量上限（TF）= 本档容量，构造时定死。 */
    override fun getTimeFlowCapacity(): Long = tfHandler.capacity

    // ================================================================
    //  从塔拉 TF（每 tick）
    // ================================================================

    /**
     * 一 tick 的拉取：`tower.extractTimeFlow(剩余空间)` → 灌进自己的缓冲。
     *
     * 顺序与判据：
     * 1. 客户端 / 缓冲已满 ⇒ 直接返回；
     * 2. **没绑定** ⇒ 返回（老工程这里还会回落到「自动连唯一塔」，那个记账类属主塔切片，见下面的 TODO）；
     * 3. **维度不同 ⇒ 不拉**（设定 §5：跨维度只能靠时序钟搬运）；
     * 4. [TimeFlowTowers.find] 找不到（塔不在 / 区块未加载 / 那一格不是塔）⇒ **静默返回**，
     *    不刷日志也不报错 —— 塔没强加载，区块滚出视野是常态，不该变成刷屏；
     * 5. 塔**没成型** ⇒ 不拉（没成型就是一堆方块，不是「闸口」）；
     * 6. 放置者没有取用权限 ⇒ 不拉（与无线仓同一条判据，见 [ITimeFlowTower.canUseTimeFlowByUuid]）；
     * 7. 拉到多少算多少，`extractTimeFlow` 不够就有多少给多少（接口契约）。
     *
     * ⚠️ 第 6 步只在**自动连**那条路上才是新防线（显式绑定那条路在时序钟绑塔时就已经验过权限了）；
     * 自动连要等主塔切片，但这一步留着是对的 —— 它挡的是「没权限的玩家在塔旁边摆一台仓白拿 TF」。
     *
     * ⚠️ 这里**只搬运、不换汇**（设定 §3.1）：充入 / 取出效率不在仓里做，
     * 折损只发生在主控塔那个唯一闸口上。
     */
    private fun pullTick() {
        if (isRemote) return
        val lvl = level ?: return
        if (lvl.isClientSide) return

        val room = getTimeFlowRoom()
        if (room <= 0L) return

        // TODO(主塔切片): 未绑定时回落到 `MasterTowerRegistry.autoTower()`（唯一塔模式下的零操作连接）。
        //   老工程那一句是 `getBoundTower() ?: MasterTowerRegistry.autoTower() ?: return`；
        //   本工程还没有主塔记账（MasterTowerRegistry 属主塔切片），所以现在只有显式绑定一条路。
        val target = getBoundTower() ?: return
        if (target.dimension() != lvl.dimension()) return

        val tower = TimeFlowTowers.find(lvl, target.pos()) ?: return
        // ⚠️ 「成型」在 8.0.0 是 MultiblockControllerMachine 上的 `isFormed`（IMultiController 已删）
        if ((tower as? MultiblockControllerMachine)?.isFormed != true) return
        val owner = ownerUUID
        if (owner != null && !tower.canUseTimeFlowByUuid(owner)) return

        val got = tower.extractTimeFlow(room)
        if (got > 0L) tfHandler.insert(got)
    }

    // ================================================================
    //  绑定手势（手持已绑塔的时序钟右键本仓）
    // ================================================================

    /**
     * GTM 的机器右键钩子。
     *
     * ⚠️ 8.0.0 删掉了 `IInteractedMachine`（老工程用它拿 `onUse(state, level, pos, player, hand, hit)`），
     * 现在的对应物是 `MetaMachine` 上的两个方法（`MetaMachine.java:532,557`）：
     * - `onUseWithItem(ExtendedUseOnContext)` —— 手里**拿着东西**时先调它（`MetaMachineBlock.java:297`）；
     * - `onUse(ExtendedUseOnContext)` —— 手里空着，或物品交互没消费掉时（`MetaMachineBlock.java:315`）。
     * 两者都排在 `tryOpenUI(...)` **之前**，返回非 `PASS` 就直接 `areturn`
     * （`MetaMachineBlock.java:300,318`），所以这个手势不会被部件的 MUI 面板吃掉。
     * 时序钟是**物品**，走的是第一条 ⇒ 覆写 [onUseWithItem]。
     *
     * 手势（与时序钟的「右键塔 = 绑定 / 潜行右键 = 解绑」同构）：
     * - 手里的**时序钟已经绑了塔** → 非潜行 = 本仓绑到那座塔；潜行 = 解绑；
     * - 时序钟没绑塔 / 塔在别的维度 ⇒ 只发一条提示，右键**交还给方块本身**（返回 `PASS`）；
     * - 手里不是时序钟 ⇒ 原样交给 `super`（保住 GTM 自己的工具手势，如潜行右键拆覆盖物）。
     */
    override fun onUseWithItem(context: ExtendedUseOnContext): InteractionResult {
        val stack = context.itemInHand
        if (!TimeClockData.isTimeClock(stack)) return super.onUseWithItem(context)

        // ⚠️ `ExtendedUseOnContext#getPlayer()` 在 Java 侧标的是 `@UnknownNullability`
        //    （`ExtendedUseOnContext.java:31-33`），Kotlin 这边得按可空处理；
        //    真到不了 null（方块交互一定有玩家），兜底就是交还给 super。
        val player = context.player ?: return super.onUseWithItem(context)
        val lvl = context.level
        if (!lvl.isClientSide) {
            val tower = TimeClockData.getBoundTower(stack)
            if (tower == null) {
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.CLOCK_UNBOUND).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }
            if (!player.isShiftKeyDown && tower.dimension() != lvl.dimension()) {
                // 本仓本期只做同维度直扣塔，跨维度的绑定先拦下来（免得绑了却永远拉不到）
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.WRONG_DIMENSION).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }

            if (player.isShiftKeyDown) {
                unbindTower()
                player.displayClientMessage(Component.translatable(TimeFlowHatchLang.UNBOUND), true)
            } else {
                bindTower(tower)
                val p = tower.pos()
                player.displayClientMessage(
                    Component.translatable(TimeFlowHatchLang.BOUND, p.x, p.y, p.z), true
                )
            }
        }
        // 两端都返回「已消耗」，免得客户端再跑一遍同样的手势
        return InteractionResult.sidedSuccess(lvl.isClientSide)
    }

    // ================================================================
    //  面板（MUI）与共享闸门
    // ================================================================

    /**
     * 部件面板：名字 + 本档容量 + 绑定状态。
     *
     * ⚠️ **刻意不显示「当前存量」**：TF 存量没有标 `@SyncToClient`（每 tick 扣费的量，同步它等于
     * 每 tick 发包；GTM 自己的 `NotifiableEnergyContainer` 也只 `@SaveField`、不 `@SyncToClient`）。
     * 要知道此刻存量，用 MUI 的服务端取值控件另开一路，或进游戏用调试手段看。
     * 面板里那三行要么是构造参数（两端一致）、要么是**手势时**才变的同步字段，都不会每 tick 发包。
     */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        mainWidget.child(
            Flow.column()
                .coverChildren()
                .child(Text.lang("block.gtetscore.${definition.name}").asWidget().margin(6))
                .child(Text.lang(TimeFlowHatchLang.PANEL_CAPACITY, capacity).asWidget().margin(6))
                .child(Text.of(boundLine()).asWidget().margin(6))
        )
    }

    /** 面板上的绑定行：绑了显示坐标，没绑显示「未绑定」。 */
    private fun boundLine(): Component =
        if (isBound) Component.translatable(TimeFlowHatchLang.PANEL_BOUND, boundX, boundY, boundZ)
        else Component.translatable(TimeFlowHatchLang.PANEL_UNBOUND)

    /**
     * 部件共享闸门：返回全局开关 [GtetConfig.partsShareable]（默认 `false` = 禁止共享）。
     *
     * 打开配置就恢复串配方风险：本件的 TF 缓冲与「绑定的主控塔」是每件独立的，
     * 被两个已成型结构共享时两个控制器会从同一份缓冲里取走同额的时间流。
     * ⚠️ 该值只在结构检测那一刻被读（`canShared(controller, substructureName)`，
     * 8.0.0 的签名比老工程多了两个参数），改配置后要等下一次结构检测才生效。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()

    // ================================================================
    //  生命周期
    // ================================================================

    /**
     * 建立 tick 订阅。
     *
     * 无条件订阅：见 [tickSubscription] 上那段「为什么不用 `ConditionalSubscriptionHandler`」。
     * 客户端不订阅（拉塔是纯服务端行为）。
     *
     * ⚠️ `super.onLoad()` 必须写成 `super<TieredPartMachine>.onLoad()`：本类多实现了一个
     * [IMuiMachine]，而它的上游接口链（`IMuiMachine` → `IMachineFeature` → `IGregtechBlockEntity`
     * → NeoForge 的 `IBlockEntityExtension`）里有一个 `onLoad()` 的接口 default，
     * 于是 Kotlin 认为「类成员」与「接口 default」两条路都通，不指名就报
     * `Multiple supertypes available`。指名到 `TieredPartMachine` 才会走到
     * `MetaMachine#onLoad`（trait 的 `onMachineLoad`、渲染状态刷新都在那里，**不能省**）。
     */
    override fun onLoad() {
        super<TieredPartMachine>.onLoad()
        if (isRemote) return
        tickSubscription = subscribeServerTick { pullTick() }
    }

    /** 卸载时退订，免得区块滚出视野后还在吃 tick。 */
    override fun onUnload() {
        unsubscribe(tickSubscription)
        tickSubscription = null
        super.onUnload()
    }

    // ⚠️ 8.0.0 **没有** `getFieldHolder()` / `MANAGED_FIELD_HOLDER` 了（LDLib syncdata 已弃用），
    //    也没有老工程那个 `@Persisted` 字段一览表 —— 存档范围完全由 `@SaveField` 决定，
    //    所以本类不再需要任何「把本类挂到父类字段持有者后面」的样板。
}
