@file:Suppress("UNUSED")
package rain.fox.gtetcore.config

import net.neoforged.fml.ModList
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.api.timeflow.ETTimeFlow

/**
 * GTET 的 COMMON 配置（NeoForge 的 [ModConfigSpec]，对应 1.20.1 时代的 ForgeConfigSpec）。
 *
 * 目前移植了**功能真正要读的**两段：`multiblock`（结构检测）与 `timeflow`（时间流 / 时序潮汐系数）。
 * 老项目里另有 `dev`（导出/诊断）、`overlay`（结构工具颜色）两段，以及 `timeflow` 段里**主塔专用**的
 * 几项，等对应功能移植过来再补。键名与默认值必须与老项目保持一致 —— 它们是玩家配置文件里的键。
 */
@Suppress("ConstPropertyName")
object GtetConfig {

    // ── 默认值（与老项目一字不差，改默认值等于改玩家行为）──

    const val default_send_form_error_message: Boolean = true

    /** 部件是否可被多个已成型结构共享；默认 false（防串配方）。 */
    const val default_parts_shareable: Boolean = false

    /** 时序潮汐半幅 `a` 的默认值：`0` = 关闭潮汐（老项目默认值）。 */
    const val default_tide_amplitude: Double = 0.0

    /** 时序潮汐周期 `T`（tick）的默认值：1 游戏日 = 24000 tick。 */
    const val default_tide_period: Int = 24000

    /** 超频仓系数 `k` 的默认值：付一半能量价值（五折），鼓励建塔。 */
    const val default_overclock_factor_k: Double = 0.5

    /** 时序钟系数 `K_hand` 的默认值。 */
    const val default_time_clock_hand_factor: Double = 4.0

    /** 主控塔是否全服唯一；默认 true（第二座不成型）。 */
    const val default_master_tower_unique: Boolean = true

    /** 主控塔每段塔身的容量（TF）；⚠️ 1,000,000 是**占位值**，等平衡定稿（老工程同一个数）。 */
    const val default_tower_segment_capacity: Long = 1_000_000L

    /** 主控塔最多接入的塔身段数。 */
    const val default_tower_max_segments: Int = 10

    /** 主控塔白名单（逗号分隔的 UUID）；空串 = 只有所有者能取用。 */
    const val default_tower_whitelist: String = ""

    private val builder: ModConfigSpec.Builder = ModConfigSpec.Builder()

    val send_form_error_message: ModConfigSpec.BooleanValue
    val parts_shareable: ModConfigSpec.BooleanValue

    val tide_amplitude: ModConfigSpec.DoubleValue
    val tide_period: ModConfigSpec.IntValue
    val overclock_factor_k: ModConfigSpec.DoubleValue
    val time_clock_hand_factor: ModConfigSpec.DoubleValue

    val master_tower_unique: ModConfigSpec.BooleanValue
    val tower_segment_capacity: ModConfigSpec.LongValue
    val tower_max_segments: ModConfigSpec.IntValue
    val tower_whitelist: ModConfigSpec.ConfigValue<String>

    val spec: ModConfigSpec

    init {
        builder.comment("多方块结构检测相关（时间单位：tick）", "Multiblock structure checks (in ticks)")
            .push("multiblock")

        send_form_error_message = builder.comment("结构成型失败时是否给玩家发提示")
            .define("sendFormErrorMessage", default_send_form_error_message)

        parts_shareable = builder.comment(
            "多方块部件是否允许被多个已成型结构共享（默认 false）",
            "⚠️ 该值只在结构检测那一刻被读，改完要等下一次检测才生效",
            "Allow multiblock parts to be shared between formed structures"
        ).define("partsShareable", default_parts_shareable)

        builder.pop()

        // ── 时间流（TF）：单位、时序潮汐与系数 ──
        // 老项目 TOML 里的键名原样保留：tideAmplitude / tidePeriod / overclockFactorK / timeClockHandFactor。
        builder.comment(
            "时间流（TF）：单位、时序潮汐与系数",
            "Time flow (TF): unit pricing, tide and coefficients",
            "1 TF = 1A IV x 1 tick = 8192 EU; 1 TF = 1 second (flow rate) => 1 hour = 3600 TF"
        ).push("timeflow")

        tide_amplitude = builder.comment(
            "时序潮汐半幅 a：汇率 = 1 + a × sin(2π·t / T)，均值恒为 1；填 0 = 关闭潮汐。",
            "上限 0.1111（11.11%）：往返效率 η = 0.8 时反套利闭式解 a ≤ (1-η)/(1+η) 的取值，",
            "超过就会出现「便宜时买、贵时卖」的套利通道，所以配置层直接夹住。",
            "Tide amplitude a: rate = 1 + a * sin(2*pi*t / T), mean is always 1; 0 disables the tide.",
            "Capped at 0.1111 (11.11%) = (1-eta)/(1+eta) for a round-trip efficiency of 0.8."
        ).defineInRange("tideAmplitude", default_tide_amplitude, 0.0, ETTimeFlow.MAX_TIDE_AMPLITUDE)

        tide_period = builder.comment(
            "时序潮汐周期 T（tick），默认 24000 = 1 游戏日。",
            "Tide period T in ticks; the default 24000 is one in-game day."
        ).defineInRange("tidePeriod", default_tide_period, 1, 2400000)

        overclock_factor_k = builder.comment(
            "超频仓系数 k：每配方的时间流消耗 = (因超频多出的 EU ÷ 8192) × k，下限 1 TF。",
            "默认 0.5 = 付一半能量价值（五折）。",
            "Overclock hatch factor k: TF cost = (extra EU from overclocking / 8192) * k, floor 1 TF.",
            "The default 0.5 charges half of the energy value."
        ).defineInRange("overclockFactorK", default_overclock_factor_k, 0.0, 1000.0)

        time_clock_hand_factor = builder.comment(
            "时序钟系数 K_hand：手持加速的 TF 消耗 = (这次推进本该消耗的 EU ÷ 8192) × K_hand。",
            "口径必须是 EU 而不是「秒」——按秒计时系数要抬到 80 才等价。默认 4 = 比走超频仓贵 8 倍。",
            "Time clock factor K_hand: TF cost = (EU the skipped progress would have consumed / 8192) * K_hand.",
            "The unit must be EU, not seconds. The default 4 is 8x more expensive than an overclock hatch."
        ).defineInRange("timeClockHandFactor", default_time_clock_hand_factor, 0.0, 10000.0)

        // ── 主控塔（键名与老工程一字不差：masterTowerUnique / towerSegmentCapacity / …）──
        master_tower_unique = builder.comment(
            "主控塔是否全服唯一。true = 第二座不成型；false = 允许多座，每座各自独立记账。",
            "从 false 改成 true 时已存在的多座塔不追溯，只是新塔不能再成型。",
            "Whether the master tower is unique per server. true = a second tower will not form;",
            "false = many towers are allowed, each keeping its own separate balance.",
            "Switching false -> true does not retroactively remove existing towers."
        ).define("masterTowerUnique", default_master_tower_unique)

        tower_segment_capacity = builder.comment(
            "主控塔【每段塔身】的容量（TF）。塔的总容量 = 塔身段数 × 本值，段数由结构决定。",
            "⚠️ 默认值 1,000,000 是**占位值**，等最终平衡数值定稿后再改这里；不是定稿数值。",
            "Capacity in TF of ONE tower segment. Total capacity = segment count * this value.",
            "The default 1,000,000 is a PLACEHOLDER pending the final balance pass, not a final value."
        ).defineInRange("towerSegmentCapacity", default_tower_segment_capacity, 1L, Long.MAX_VALUE)

        tower_max_segments = builder.comment(
            "主控塔最多能接多少段塔身（只把计入容量的段数往下夹，结构上限是编译期常量）。",
            "Maximum number of tower segments the structure counts towards capacity."
        ).defineInRange("towerMaxSegments", default_tower_max_segments, 1, 64)

        tower_whitelist = builder.comment(
            "主控塔白名单：逗号分隔的玩家 UUID（例如 aaaa...,bbbb...）；留空表示只有所有者能取用。",
            "所有者永远在白名单里，不用在这里重复写。",
            "Tower whitelist: comma-separated player UUIDs. Empty means the owner only.",
            "The owner is always allowed and does not need to be listed."
        ).define("towerWhitelist", default_tower_whitelist)

        builder.pop()

        // ── 以下分类尚未移植，先留编号占位（xxx0 / xxx02 …），轮到对应功能时按序填入 ──
        // xxx0 = dev（开发者选项）：exportModeEnabled / exportDirectory / recipeExportDirectory / SendThreadDiagnosticlog
        val xxx0 = Unit
        // xxx02 = overlay（结构工具覆盖层）：writeColor / detectColor / detectBoxLifetime
        val xxx02 = Unit

        spec = builder.build()
    }

    /** 在 mod 构造期注册配置；必须在构造期调，否则 NeoForge 不会加载它。 */
    fun register() {
        ModList.get().getModContainerById(GTETSCore.ID).ifPresent {
            it.registerConfig(ModConfig.Type.COMMON, spec)
        }
    }

    // ── 读取入口 ──
    // 配置没加载时（例如数据生成阶段）一律退回默认值，所以每个入口都过一道 isLoaded 判断。

    fun sendFormErrorMessage(): Boolean = booleanValue(send_form_error_message, default_send_form_error_message)

    /** 部件共享总开关：机器类的 `canShared()` 与共享提示行都读它。 */
    fun partsShareable(): Boolean = booleanValue(parts_shareable, default_parts_shareable)

    /** 时序潮汐半幅 `a`；`0` = 关闭潮汐（默认）。 */
    fun tideAmplitude(): Double = doubleValue(tide_amplitude, default_tide_amplitude)

    /** 时序潮汐周期 `T`（tick），默认 24000。 */
    fun tidePeriod(): Int = intValue(tide_period, default_tide_period)

    /** 超频仓系数 `k`，默认 0.5。 */
    fun overclockFactorK(): Double = doubleValue(overclock_factor_k, default_overclock_factor_k)

    /** 时序钟系数 `K_hand`，默认 4。 */
    fun timeClockHandFactor(): Double = doubleValue(time_clock_hand_factor, default_time_clock_hand_factor)

    /** 主控塔是否全服唯一，默认 true。 */
    fun masterTowerUnique(): Boolean = booleanValue(master_tower_unique, default_master_tower_unique)

    /** 主控塔每段塔身容量（TF），默认 1,000,000（占位值）。 */
    fun towerSegmentCapacity(): Long = longValue(tower_segment_capacity, default_tower_segment_capacity)

    /** 主控塔最多计入容量的段数，默认 10。 */
    fun towerMaxSegments(): Int = intValue(tower_max_segments, default_tower_max_segments)

    /** 主控塔白名单原文（逗号分隔的 UUID），默认空串。 */
    fun towerWhitelist(): String = stringValue(tower_whitelist, default_tower_whitelist)

    private fun intValue(value: ModConfigSpec.IntValue, fallback: Int): Int =
        if (spec.isLoaded) value.get() else fallback

    private fun longValue(value: ModConfigSpec.LongValue, fallback: Long): Long =
        if (spec.isLoaded) value.get() else fallback

    private fun booleanValue(value: ModConfigSpec.BooleanValue, fallback: Boolean): Boolean =
        if (spec.isLoaded) value.get() else fallback

    private fun doubleValue(value: ModConfigSpec.DoubleValue, fallback: Double): Double =
        if (spec.isLoaded) value.get() else fallback

    private fun stringValue(value: ModConfigSpec.ConfigValue<String>, fallback: String): String =
        if (spec.isLoaded) value.get() else fallback
}
