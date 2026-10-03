package rain.fox.gtetcore.api.timeflow

import rain.fox.gtetcore.config.GtetConfig
import kotlin.math.PI
import kotlin.math.sin

/**
 * 时间流（TF）核心 —— 单位、换算与「时序潮汐」汇率。塔 / 仓 / 钟共用这一份。
 *
 * ## 定稿数值
 * - `1 TF = 1A IV × 1 tick = 8192 EU`（IV = 8192 EU/t）；整条安培阶梯因此都是整数。
 * - `1 TF = 1 秒` 是**流量**口径（以 1 TF/s 流 1 秒 = 1 TF）⇒ `1 小时 = 3600 TF`。
 *   ⚠️ 「秒」只用在流量与容量上；**存量**一律写 EU。
 *   别把 1 TF 当成「IV 跑 1 秒」（= 163840 EU），那样阶梯会冒出 0.2A / 3276.8 TF 这种小数。
 * - `1 TF/t = 8192 EU/t`；`1 TF/s = 409.6 EU/t`。两条差 20 倍，别混用。
 *
 * ## 单位与取整
 * TF 一律用 **long** 存，**没有亚 TF 精度**（`0.01 TF` 存不下，这是已定约束）。
 * 由 EU 折 TF 默认向下取整；需要「至少 1 TF」的地方显式用 [euToTfMinOne]。
 *
 * ## 时序潮汐
 * `rate(t) = 1 + a × sin(2π·t / T)`，`T` 默认 1 游戏日 = 24000 tick，`a` 默认 0（关闭）。
 *
 * 时间基准一律用 `Level#getGameTime()`，**不要**用 `getDayTime()`（会被 `/time set` 操纵）。
 * 各维度的 gameTime 由服务器每个 tick 统一推进，因此天然**同相位**——这正是反套利的前提。
 *
 * 它是 `f(gameTime)` 的**纯函数**，客户端可以直接算，时序钟的 tooltip 因此不需要同步包。
 */
object ETTimeFlow {

    // ======================== 单位与常量 ========================

    /** 1 TF 折合多少 EU：`1A IV × 1 tick`。 */
    const val EU_PER_TF: Long = 8192L

    /** 1 秒的 tick 数。 */
    const val TICKS_PER_SECOND: Long = 20L

    /** `1 TF = 1 秒`（流量口径）⇒ 1 小时 = 3600 TF。 */
    const val TF_PER_HOUR: Long = 3600L

    /** 1 游戏日 = 24000 tick（潮汐周期的默认值）。 */
    const val GAME_DAY_TICKS: Long = 24000L

    /**
     * 潮汐半幅 `a` 的硬上限。
     *
     * 反套利闭式解 `a ≤ (1 - η) / (1 + η)`；η = 0.8 时 `a ≤ 11.11%`。
     * 超过这个值就会出现「便宜的时候买、贵的时候卖」的套利（设定 §3.3）。
     */
    const val MAX_TIDE_AMPLITUDE: Double = 0.1111

    /** 充入效率 η₁（设定 §3.3 建议基线）。 */
    const val CHARGE_EFFICIENCY: Double = 1.0

    /** 取出效率 η₂（设定 §3.3 建议基线）。 */
    const val DISCHARGE_EFFICIENCY: Double = 0.8

    /** 往返效率 η = η₁ × η₂。 */
    const val ROUND_TRIP_EFFICIENCY: Double = CHARGE_EFFICIENCY * DISCHARGE_EFFICIENCY

    /** 往返损耗 = `1 − η`。 */
    const val ROUND_TRIP_LOSS: Double = 1.0 - ROUND_TRIP_EFFICIENCY

    // ======================== 存量换算 ========================

    /** EU → TF，向下取整。非正输入返回 0。 */
    @JvmStatic
    fun euToTf(eu: Long): Long = if (eu <= 0L) 0L else eu / EU_PER_TF

    /** TF → EU。非正输入返回 0。 */
    @JvmStatic
    fun tfToEu(tf: Long): Long = if (tf <= 0L) 0L else tf * EU_PER_TF

    /**
     * EU → TF，**下限 1 TF**。
     *
     * 用于「花掉一点点电也要至少收 1 TF」的地方（TF 存 long，`0.01 TF` 存不下）。
     * 输入 `<= 0` 时返回 0（不收费）。
     */
    @JvmStatic
    fun euToTfMinOne(eu: Long): Long = if (eu <= 0L) 0L else maxOf(1L, euToTf(eu))

    // ======================== 速率换算 ========================

    /** `TF/t` → `EU/t`（`× 8192`）。 */
    @JvmStatic
    fun tfPerTickToEuPerTick(tfPerTick: Double): Double = tfPerTick * EU_PER_TF

    /** `EU/t` → `TF/t`（`÷ 8192`）。 */
    @JvmStatic
    fun euPerTickToTfPerTick(euPerTick: Double): Double = euPerTick / EU_PER_TF

    /** `TF/s` → `TF/t`（`÷ 20`）。 */
    @JvmStatic
    fun tfPerSecondToTfPerTick(tfPerSecond: Double): Double = tfPerSecond / TICKS_PER_SECOND

    // ======================== 时序潮汐 ========================

    /** 当前潮汐半幅 `a`；0 = 关闭。 */
    @JvmStatic
    fun tideAmplitude(): Double = GtetConfig.tideAmplitude()

    /** 当前潮汐周期 `T`（tick），至少 1。 */
    @JvmStatic
    fun tidePeriod(): Long = GtetConfig.tidePeriod().toLong().coerceAtLeast(1L)

    /**
     * 潮汐相位，范围 `[0, 1)`。
     *
     * `0` = 均值上升沿起点、`0.25` = 汇率峰值、`0.5` = 回落过均值、`0.75` = 汇率谷值。
     */
    @JvmStatic
    fun tidePhase(gameTime: Long): Double {
        val period = tidePeriod()
        return Math.floorMod(gameTime, period).toDouble() / period.toDouble()
    }

    /**
     * 时序潮汐汇率 `rate(t) = 1 + a × sin(2π·t / T)`，均值恒为 1。
     *
     * `a = 0`（默认）时恒返回 1.0，即关闭潮汐。
     */
    @JvmStatic
    fun tideRate(gameTime: Long): Double {
        val a = tideAmplitude()
        if (a == 0.0) return 1.0
        return 1.0 + a * sin(2.0 * PI * tidePhase(gameTime))
    }

    /** 距离下一个汇率**峰值**（相位 0.25）还有多少 tick；已经在峰值上时返回 0。 */
    @JvmStatic
    fun ticksToNextPeak(gameTime: Long): Long {
        val period = tidePeriod()
        return Math.floorMod(period / 4L - Math.floorMod(gameTime, period), period)
    }

    /** 距离下一个汇率**谷值**（相位 0.75）还有多少 tick；已经在谷值上时返回 0。 */
    @JvmStatic
    fun ticksToNextTrough(gameTime: Long): Long {
        val period = tidePeriod()
        return Math.floorMod(period * 3L / 4L - Math.floorMod(gameTime, period), period)
    }

    // ======================== 已定稿的消耗公式 ========================

    /**
     * 超频仓每次配方的时间流消耗（设定 §4）：
     * `TF = (配方因超频多出的 EU ÷ 8192) × k`，**下限 1 TF**。
     */
    @JvmStatic
    fun overclockCostTf(extraEu: Long): Long = scaledCostTf(extraEu, GtetConfig.overclockFactorK())

    /**
     * 时序钟手持加速的 TF 消耗（设定 §5）：
     * `TF = (这次推进本该消耗的 EU ÷ 8192) × K_hand`，**下限 1 TF**。
     *
     * ⚠️ 口径必须是 **EU**（不是「秒」）。按秒计的话 K_hand 得抬到 80 才等价；见设定 §5。
     */
    @JvmStatic
    fun handAccelerateCostTf(euCost: Long): Long = scaledCostTf(euCost, GtetConfig.timeClockHandFactor())

    private fun scaledCostTf(eu: Long, factor: Double): Long {
        if (eu <= 0L || factor <= 0.0) return 0L
        return maxOf(1L, (eu.toDouble() / EU_PER_TF * factor).toLong())
    }

    // ======================== 时序钟容量档位 ========================

    /** 时序钟最低档位（L1 = 1A ZPM）。 */
    const val CLOCK_TIER_MIN: Int = 1

    /** 时序钟最高档位（L3 = 1A OpV）。 */
    const val CLOCK_TIER_MAX: Int = 3

    /**
     * 时序钟的容量上限（TF），按「整数安培 × 电压 × 1 tick」给：
     *
     * | 档 | 容量 | 折算 TF | 折算 EU |
     * |---|---|---|---|
     * | L1 | 1A ZPM | 16 | 131072 |
     * | L2 | 1A UEV | 1024 | 8388608 |
     * | L3 | 1A OpV | 65536 | 536870912 |
     *
     * 相邻档正好 ×64（ZPM → UEV → OpV 各差 3 个电压档，4³ = 64）。
     * 越界档位夹到 `[1, 3]`。
     */
    @JvmStatic
    fun clockCapacity(tier: Int): Long = when (tier.coerceIn(CLOCK_TIER_MIN, CLOCK_TIER_MAX)) {
        1 -> 16L
        2 -> 1024L
        else -> 65536L
    }
}
