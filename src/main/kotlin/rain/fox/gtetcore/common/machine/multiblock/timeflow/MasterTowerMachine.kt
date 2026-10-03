package rain.fox.gtetcore.common.machine.multiblock.timeflow

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.LongSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.multiblock.error.PatternStringError
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponentMap
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.api.timeflow.ITimeFlowTower
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.MasterTowerLang
import rain.fox.gtetcore.registry.ETDataComponents
import java.util.Locale
import java.util.UUID
import java.util.function.IntSupplier
import java.util.function.LongSupplier
import java.util.function.Supplier

/**
 * **主控塔** —— 收储 EU → TF（[exchangeTick]）、按段数算容量、持有所有者 / 白名单（实现 [ITimeFlowTower]）、
 * 按 `masterTowerUnique` 保证全服唯一（[formStructure] + [MasterTowerRegistry]）。
 *
 * 底座用 [WorkableElectricMultiblockMachine]：它已经把部件里的能源仓聚合成 `energyContainer`
 * （`WorkableElectricMultiblockMachine.java:74`），换汇那一 tick 直接读它。本机挂 `DUMMY_RECIPES`，不跑配方。
 *
 * ⚠️ 8.0.0 与老工程的三处写法差别：`checkPattern()` 已删（成型回调改为
 * `MultiblockControllerMachine#formStructure`，`PatternState.java:121`）；`@Persisted` → `@field:SaveField`，
 * 且 `saveAdditional` 已是 `final`；`IMachineLife#onMachineRemoved` → `MetaMachine#onMachineDestroyed`
 * （`MetaMachine.java:209`）。
 *
 * @author rain fox
 */
class MasterTowerMachine(info: BlockEntityCreationInfo) :
    WorkableElectricMultiblockMachine(info),
    ITimeFlowTower {

    /** 储备（TF）。`@SaveField` 决定存档范围，不需要手写 `saveAdditional` / `loadAdditional`。 */
    @field:SaveField
    private var storedTf: Long = 0L

    /** 上次成型时数出来的塔身段数。 */
    @field:SaveField
    private var segments: Int = 0

    /** 当前容量（TF）= 段数 × 每段容量；未成型时为 0。刻意不存档：它是结构的函数，成型时重算。 */
    private var capacityTf: Long = 0L

    /** 每 tick 的换汇订阅；无条件订阅，未成型时 [exchangeTick] 自己早退（理由同 [TimeFlowHatchPartMachine]）。 */
    private var tickSubscription: TickableSubscription? = null

    // ================================================================
    //  ITimeFlowStorage —— 塔的 TF 存储（ITimeFlowTower 继承了它）
    // ================================================================

    /** 当前储备（TF）。 */
    override fun getTimeFlow(): Long = storedTf

    /**
     * 直接写入储备，返回夹取后的实际值。
     *
     * ⚠️ 上限取 `max(容量, 当前存量)`：塔重建后段数变少时，按裸容量夹会把超出的部分**悄悄销毁**；
     * 取 max 之后超出部分只进不出（`getTimeFlowRoom()` 归零），玩家可以慢慢取走。
     */
    override fun setTimeFlow(amount: Long): Long {
        val upper = maxOf(capacityTf, storedTf)
        val clamped = amount.coerceIn(0L, upper)
        if (clamped != storedTf) {
            storedTf = clamped
            // @SaveField 只管「写什么」，把区块排进下一次自动存档还得靠这一句
            setChanged()
        }
        return clamped
    }

    /** 容量上限（TF）＝ 塔身段数 × 每段容量；未成型时为 0。 */
    override fun getTimeFlowCapacity(): Long = capacityTf

    // ================================================================
    //  ITimeFlowTower —— 所有者 / 白名单
    // ================================================================

    /** 塔的所有者 = GTM 记在机器上的所有者（放下控制器的玩家，或第一次右键控制器的玩家）。 */
    override fun getOwnerUuid(): UUID? = ownerUUID

    /** 白名单命中，或过一遍接口默认实现（未认领放行 / 本人；同队那一层见 [ITimeFlowTower]）。 */
    override fun canUseTimeFlowByUuid(uuid: UUID): Boolean =
        MasterTowerWhitelist.contains(uuid) || super<ITimeFlowTower>.canUseTimeFlowByUuid(uuid)

    // ================================================================
    //  结构成型 / 失效（唯一性门就写在成型这一步）
    // ================================================================

    /**
     * 结构成型：先过唯一性门，再算段数与容量、记账。
     *
     * 判定不过时**不调 super**（`isFormed` 保持 false、部件不挂控制器），并往 pattern state 上挂一条
     * [PatternStringError] —— GTM 自己的「结构不合法」面板会列出来（`GTMultiblockTextUtil.addUnformedWarning`）。
     *
     * ⚠️ 判定必须放行「这座塔自己曾经成型过」：否则配置一改，重检失败会把建好的塔拆掉
     * （见 [MasterTowerRegistry.Snapshot.allows] 的不追溯名单）。
     */
    override fun formStructure(substructureName: String) {
        if (!allowsFormingHere()) {
            getPatternState(substructureName).setError(
                PatternStringError(Component.translatable(MasterTowerLang.DUPLICATE))
            )
            return
        }
        super.formStructure(substructureName)

        segments = countSegments().coerceIn(1, GtetConfig.towerMaxSegments())
        capacityTf = segments.toLong() * GtetConfig.towerSegmentCapacity()

        val serverLevel = level as? ServerLevel ?: return
        // 记账只在服务端线程（成型就发生在服务端线程）
        MasterTowerRegistry.onTowerFormed(serverLevel.server, GlobalPos.of(serverLevel.dimension(), blockPos))
    }

    /** 唯一性门：已成型 / 多塔模式 / 曾经成型过 / 没占位者 / 占位者就是自己 ⇒ 放行。 */
    private fun allowsFormingHere(): Boolean {
        if (isFormed) return true
        if (!GtetConfig.masterTowerUnique()) return true
        val lvl = level ?: return true
        return MasterTowerRegistry.snapshot().allows(GlobalPos.of(lvl.dimension(), blockPos))
    }

    /**
     * 数塔身段数：数结构缓存里有几个不同的 Y 层，再减掉基座与顶盖两层（[BASE_AND_CAP_SLICES]）。
     *
     * ⚠️ 8.0.0 删了 `MultiblockState#getMatchContext()`，老工程「段锚点谓词 +1」的写法作废。
     * 不读 `BlockPattern#getRepetitionCount`：那读的是 `PatternSlice.actualRepeats`（`BlockPattern.java:249-251`），
     * 定义级共享可变字段，多座塔并存时会被彼此覆盖。
     */
    private fun countSegments(): Int {
        val cache = getPatternState(MultiblockControllerMachine.DEFAULT_STRUCTURE).cache
        val levels = HashSet<Int>()
        for (entry in cache.long2ObjectEntrySet()) {
            levels.add(BlockPos.getY(entry.longKey))
        }
        return levels.size - BASE_AND_CAP_SLICES
    }

    /**
     * 控制器方块**被拆掉**时释放唯一名额（`MetaMachineBlock.java:267`）。
     *
     * ⚠️ 只有这里才释放；结构失效（敲一格机壳）**不**释放，免得拆一格再补上的空窗里别处抢建第二座。
     */
    override fun onMachineDestroyed() {
        super.onMachineDestroyed()
        val serverLevel = level as? ServerLevel ?: return
        MasterTowerRegistry.onTowerControllerRemoved(
            serverLevel.server,
            GlobalPos.of(serverLevel.dimension(), blockPos),
        )
    }

    // ================================================================
    //  EU → TF 换汇（每 tick）
    // ================================================================

    /**
     * 一 tick 的换汇：从 `energyContainer`（塔自己聚合出来的能源仓）取电，按 `1 TF = 8192 EU` 换成 TF。
     *
     * 顺序：容量满 ⇒ 一点电都不取；`euToTf` 向下取整后用剩余容量夹一次；**反算 EU** 再 `changeEnergy` ——
     * 先算 TF 再算 EU，才不会出现「扣了不足 1 TF 的零头」那种损耗（零头留在能源仓里下一 tick 继续攒）。
     * 充入效率 η₁ = 100%（设定 §3.3 基线），潮汐汇率不参与充入。
     */
    private fun exchangeTick() {
        if (isRemote) return
        if (!isFormed) return
        if (!recipeLogic.isWorkingEnabled) return

        val room = getTimeFlowRoom()
        if (room <= 0L) return

        val container = energyContainer ?: return
        val eu = container.energyStored
        if (eu < ETTimeFlow.EU_PER_TF) return

        val tf = minOf(ETTimeFlow.euToTf(eu), room)
        if (tf <= 0L) return

        val consumed = -container.changeEnergy(-ETTimeFlow.tfToEu(tf))
        if (consumed <= 0L) return
        // 实际扣到的电按同样口径折回 TF（正常情况下 consumed == tfToEu(tf)）
        setTimeFlow(storedTf + ETTimeFlow.euToTf(consumed))
    }

    // ================================================================
    //  拆塔封存（存档之外的第二条通道）
    // ================================================================

    /** 敲掉控制器时把储备写进掉落物的数据组件（入口是 `MetaMachineBlock.java:252` 的 `saveToItem`）。 */
    override fun collectImplicitComponents(components: DataComponentMap.Builder) {
        super.collectImplicitComponents(components)
        if (storedTf > 0L) {
            components.set(ETDataComponents.MASTER_TOWER_RESERVE.get(), storedTf)
        }
    }

    /**
     * 放下控制器时把封存的储备还回来（`BlockItem` → `BlockEntity#applyComponentsFromItemStack`）。
     *
     * ⚠️ 直接给字段赋值、**不走 [setTimeFlow]**：放下那一刻结构还没成型（容量 = 0），走 setter 会被夹成 0。
     */
    override fun applyImplicitComponents(componentInput: BlockEntity.DataComponentInput) {
        super.applyImplicitComponents(componentInput)
        storedTf = componentInput.getOrDefault(ETDataComponents.MASTER_TOWER_RESERVE.get(), 0L).coerceAtLeast(0L)
    }

    // ================================================================
    //  面板（MUI）：只往标准多方块界面里追加几行
    // ================================================================

    /**
     * 追加：储备 / 容量 / 潮汐汇率 / 所有者。
     *
     * 三个服务端才有真值的量各挂一个同步取值控件（写法同 `GTMultiblockTextUtil`）；所有者本来就
     * `@SyncToClient`、汇率是 `f(gameTime)` 的纯函数，两端都算得出来，不额外发包。
     * 唯一性没成型的提示**不在这里**（挂在 pattern state 上，由 GTM 的结构错误面板显示）。
     */
    override fun getWidgetsForDisplay(syncManager: PanelSyncManager): List<IWidget> {
        val widgets = super.getWidgetsForDisplay(syncManager)

        val stored = syncManager.getOrCreateSyncHandler(
            "towerStoredTf", LongSyncValue::class.java,
            Supplier { LongSyncValue(LongSupplier { storedTf }) },
        )
        val capacity = syncManager.getOrCreateSyncHandler(
            "towerCapacityTf", LongSyncValue::class.java,
            Supplier { LongSyncValue(LongSupplier { capacityTf }) },
        )
        val segmentCount = syncManager.getOrCreateSyncHandler(
            "towerSegments", IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { segments }) },
        )

        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(
                    MasterTowerLang.STORED,
                    FormattingUtil.formatNumbers(stored.longValue),
                    FormattingUtil.formatNumbers(ETTimeFlow.tfToEu(stored.longValue)),
                ).withStyle(ChatFormatting.AQUA)
            }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(
                    MasterTowerLang.CAPACITY,
                    FormattingUtil.formatNumbers(capacity.longValue),
                    segmentCount.intValue.toString(),
                    FormattingUtil.formatNumbers(GtetConfig.towerSegmentCapacity()),
                ).withStyle(ChatFormatting.GRAY)
            }).asWidget()
        )
        widgets.add(Text.dynamic(Supplier { tideLine() }).asWidget())
        widgets.add(Text.dynamic(Supplier { ownerLine() }).asWidget())
        return widgets
    }

    /** 潮汐汇率与相位（`f(gameTime)` 的纯函数，两端都能算）。 */
    private fun tideLine(): Component {
        val lvl = level ?: return Component.empty()
        return Component.translatable(
            MasterTowerLang.RATE,
            String.format(Locale.ROOT, "%.4f", ETTimeFlow.tideRate(lvl.gameTime)),
            String.format(Locale.ROOT, "%.1f", ETTimeFlow.tidePhase(lvl.gameTime) * 100.0),
        ).withStyle(ChatFormatting.LIGHT_PURPLE)
    }

    /** 所有者那一行；未认领时显示「尚未认领」。 */
    private fun ownerLine(): Component {
        val owner = ownerUUID
        return if (owner == null) {
            Component.translatable(MasterTowerLang.OWNER_NONE).withStyle(ChatFormatting.DARK_GRAY)
        } else {
            Component.translatable(MasterTowerLang.OWNER, owner.toString()).withStyle(ChatFormatting.DARK_GRAY)
        }
    }

    // ================================================================
    //  生命周期
    // ================================================================

    /** 建立 tick 订阅（客户端不订阅：换汇是纯服务端行为）。 */
    override fun onLoad() {
        super.onLoad()
        if (isRemote) return
        tickSubscription = subscribeServerTick { exchangeTick() }
    }

    /** 卸载时退订，免得区块滚出视野后还在吃 tick。 */
    override fun onUnload() {
        unsubscribe(tickSubscription)
        tickSubscription = null
        super.onUnload()
    }

    companion object {

        /** 基座与顶盖这两层不是塔身段，数段数时要减掉（与 `ETMasterTower` 的 3 个 slice 一一对应）。 */
        const val BASE_AND_CAP_SLICES: Int = 2
    }
}
