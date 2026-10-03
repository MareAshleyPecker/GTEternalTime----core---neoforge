package rain.fox.gtetcore.common.item.timeflow

import com.gregtechceu.gtceu.api.item.ComponentItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.api.timeflow.TimeFlowTowers
import rain.fox.gtetcore.data.lang.TimeClockLang
import rain.fox.gtetcore.registry.ETDataComponents

/**
 * 时序钟的数据层 —— **全部状态都写在物品的数据组件里**（见 [TimeClockState]）。
 *
 * 1.21 没有物品 NBT，所以本对象是老工程 `TimeClockData` 里那套静态读写入口的**数据组件版**：
 * 读写、夹取、绑定 / 解绑、扣费来源判定都在这里，外部（tooltip、机器、后续的主塔手势）只碰这里。
 *
 * ## 为什么不用 GTM 的 ManagedFieldHolder
 * 它要求 `Class<? extends IManaged>`，`Item` 不是；`IElectricItem` 又是 EU 语义硬编码。
 * 自定义单位「时间流」只能自己写数据类 + 数据组件。
 */
object TimeClockData {

    // ======================== 身份判定 ========================

    /**
     * 是不是时序钟。
     *
     * 按**行为组件**判定而不是按注册表条目判定 —— 免得数据层与 [rain.fox.gtetcore.registry.ETItems] 互相引用。
     */
    @JvmStatic
    fun isTimeClock(stack: ItemStack): Boolean {
        val item = stack.item
        return item is ComponentItem && item.components.contains(TimeClockBehavior)
    }

    // ======================== 组件读写 ========================

    /** 读整份状态；没写过组件就返回 [TimeClockState.EMPTY]。 */
    @JvmStatic
    fun read(stack: ItemStack): TimeClockState = stack.get(ETDataComponents.TIME_CLOCK_DATA) ?: TimeClockState.EMPTY

    /** 写整份状态。 */
    @JvmStatic
    fun write(stack: ItemStack, state: TimeClockState) {
        stack.set(ETDataComponents.TIME_CLOCK_DATA, state)
    }

    /** 改一部分状态（数据类不可变，所以是「读出来改完写回去」）。 */
    private fun modify(stack: ItemStack, change: (TimeClockState) -> TimeClockState) {
        write(stack, change(read(stack)))
    }

    // ======================== 档位与容量 ========================

    /** 容量档位；没写过时按最低档 L1。越界值夹到 `[1, 3]`。 */
    @JvmStatic
    fun getTier(stack: ItemStack): Int =
        read(stack).capacityTier.coerceIn(ETTimeFlow.CLOCK_TIER_MIN, ETTimeFlow.CLOCK_TIER_MAX)

    /** 当前容量上限（TF）。 */
    @JvmStatic
    fun getCapacity(stack: ItemStack): Long = ETTimeFlow.clockCapacity(getTier(stack))

    /**
     * 设置档位。**只抬容量上限**，不动钟内 TF（数据类天然如此）。
     *
     * @return 档位真的变了才返回 `true`
     */
    @JvmStatic
    fun setTier(stack: ItemStack, tier: Int): Boolean {
        val clamped = tier.coerceIn(ETTimeFlow.CLOCK_TIER_MIN, ETTimeFlow.CLOCK_TIER_MAX)
        if (clamped == getTier(stack)) return false
        modify(stack) { it.copy(capacityTier = clamped) }
        return true
    }

    /**
     * **升级入口** —— 升一档，容量上限变大，**钟内 TF 不丢、物品不换**（设定 §5「一件物品、按升级提升」）。
     *
     * 升级件的具体形态（物品 / 配方）本期不做，将来由升级件调用本方法即可。
     *
     * @return 真的升上去了才返回 `true`（已经 L3 时返回 `false`）
     */
    @JvmStatic
    fun upgrade(stack: ItemStack): Boolean = setTier(stack, getTier(stack) + 1)

    // ======================== 钟内 TF ========================

    /** 钟内 TF。 */
    @JvmStatic
    fun getTimeFlow(stack: ItemStack): Long = read(stack).timeFlow

    /** 写入钟内 TF；自动夹到 `0..容量上限`，返回夹取后的实际值。 */
    @JvmStatic
    fun setTimeFlow(stack: ItemStack, amount: Long): Long {
        val clamped = amount.coerceIn(0L, getCapacity(stack))
        modify(stack) { it.copy(timeFlow = clamped) }
        return clamped
    }

    // ======================== 绑定主控塔 ========================

    /** 绑定到某个坐标上的主控塔（维度 + 坐标 + 交互面，一起写进组件）。 */
    @JvmStatic
    fun bindTower(stack: ItemStack, level: Level, pos: BlockPos, face: Direction?) {
        write(
            stack,
            read(stack).copy(
                tower = GlobalPos.of(level.dimension(), pos.immutable()),
                towerFace = face ?: Direction.NORTH,
            )
        )
    }

    /** 解绑；顺手清掉全部绑定字段。 */
    @JvmStatic
    fun unbindTower(stack: ItemStack) {
        modify(stack) { it.copy(tower = null, towerFace = null) }
    }

    /** 读绑定的塔；没绑过返回 `null`。 */
    @JvmStatic
    fun getBoundTower(stack: ItemStack): GlobalPos? = read(stack).tower

    /** 读绑定时记下的交互面；没绑过返回 `null`。 */
    @JvmStatic
    fun getBoundFace(stack: ItemStack): Direction? = read(stack).towerFace

    // ======================== 闪存范式手势 ========================

    /**
     * 闪存范式手势（**非潜行**右键）= 绑定。
     *
     * 语义与 GTM 的 `WirelessTransmitterCover#onDataStickUse` 同构：**被右键的塔把自己的坐标写进手里那个物品**。
     *
     * ⚠️ GTM 的闪存分发只认「覆盖物」与「方块实体是机器」两种情况（`DataItemBehavior`），
     * 而时序钟是普通物品 —— 真正生效的是 [TimeClockBehavior.onItemUseFirst] 那条路，
     * 这里这两个入口是留给主塔自己实现 GTM 的闪存接口时转发的，塔做了才会被调到。
     */
    @JvmStatic
    fun onDataStickUse(
        player: Player,
        stack: ItemStack,
        towerLevel: Level,
        towerPos: BlockPos,
        face: Direction?,
    ): InteractionResult {
        if (!isTimeClock(stack)) return InteractionResult.PASS
        if (!towerLevel.isClientSide) {
            bindTower(stack, towerLevel, towerPos, face)
            player.displayClientMessage(
                Component.translatable(TimeClockLang.BOUND, towerPos.x, towerPos.y, towerPos.z), true
            )
        }
        return InteractionResult.sidedSuccess(towerLevel.isClientSide)
    }

    /**
     * 闪存范式手势（**潜行**右键）= 解绑。转发写法同 [onDataStickUse]，对应 `onDataStickShiftUse`。
     */
    @JvmStatic
    fun onDataStickShiftUse(
        player: Player,
        stack: ItemStack,
        towerLevel: Level,
        towerPos: BlockPos,
        face: Direction?,
    ): InteractionResult {
        if (!isTimeClock(stack)) return InteractionResult.PASS
        if (!towerLevel.isClientSide) {
            unbindTower(stack)
            player.displayClientMessage(Component.translatable(TimeClockLang.UNBOUND), true)
        }
        return InteractionResult.sidedSuccess(towerLevel.isClientSide)
    }

    // ======================== 扣费来源（同维度扣塔 / 跨维度只用钟内） ========================

    /** TF 的扣费来源。 */
    enum class PaymentSource {
        /** 同维度 + 塔所在区块已加载 ⇒ 直接从塔扣，钟内 TF 不动。 */
        BOUND_TOWER,

        /** 未绑定 / 跨维度 / 塔的区块没加载 ⇒ 只能用钟内 TF。 */
        CLOCK_ONLY,
    }

    /**
     * 判定这次该从哪里扣 TF（设定 §5）。
     *
     * 跨维度**一律**回到钟内 TF —— 想跨维度就得先在塔旁充装再拎过去，搬运有成本。
     */
    @JvmStatic
    fun resolvePaymentSource(clock: ItemStack, player: Player): PaymentSource {
        val bound = getBoundTower(clock) ?: return PaymentSource.CLOCK_ONLY
        val level = player.level()
        if (bound.dimension() != level.dimension()) return PaymentSource.CLOCK_ONLY
        // 塔所在区块没加载就不认这座塔（不破例做强加载）
        if (!level.isLoaded(bound.pos())) return PaymentSource.CLOCK_ONLY
        return PaymentSource.BOUND_TOWER
    }

    /**
     * 支付 [amount] TF：同维度优先从绑定的塔扣，否则从钟内扣。
     *
     * ⚠️ 塔本期不做，[TimeFlowTowers.find] 恒返回 `null`，所以 `BOUND_TOWER` 这一档眼下必然回落到钟内。
     * 接口与字段先留好，塔落地后本方法不用改。
     *
     * @return 付得起并已扣掉才返回 `true`（付不起时**一分不扣**）
     */
    @JvmStatic
    fun tryPay(clock: ItemStack, player: Player, amount: Long): Boolean {
        if (amount <= 0L) return true

        if (resolvePaymentSource(clock, player) == PaymentSource.BOUND_TOWER) {
            val bound = getBoundTower(clock)
            if (bound != null) {
                val tower = TimeFlowTowers.find(player.level(), bound.pos())
                // 先问余额再扣，避免「扣了一半发现不够」
                if (tower != null && tower.canUseTimeFlow(player) && tower.hasTimeFlow(amount)) {
                    return tower.extractTimeFlow(amount) >= amount
                }
            }
            // 塔不在 / 无权限 / 余额不足 ⇒ 回落到钟内 TF
        }
        return TimeClockStorage.of(clock).extractTimeFlow(amount) >= amount
    }

    // ======================== 只读视图 ========================

    /** 取一份 [rain.fox.gtetcore.api.timeflow.ITimeFlowStorage] 视图；写进去的东西立刻落在数据组件上。 */
    @JvmStatic
    fun storageOf(stack: ItemStack): TimeClockStorage = TimeClockStorage.of(stack)
}
