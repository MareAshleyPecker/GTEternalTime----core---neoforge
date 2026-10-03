@file:Suppress("UNUSED")
package rain.fox.gtetcore.config

import net.neoforged.fml.ModList
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
import rain.fox.gtetcore.GTETCore

/**
 * GTET 的 COMMON 配置（NeoForge 的 [ModConfigSpec]，对应 1.20.1 时代的 ForgeConfigSpec）。
 *
 * 目前只移植了**机器真正要读的** `multiblock` 段；老项目里另有 `dev`（导出/诊断）、
 * `timeflow`（时序潮汐与系数）、`overlay`（结构工具颜色）三段，等对应功能移植过来再补，
 * 免得先摆一堆用不到的空条目。键名与默认值必须与老项目保持一致。
 */
@Suppress("ConstPropertyName")
object GtetConfig {

    // ── 默认值（与老项目一字不差，改默认值等于改玩家行为）──

    const val default_check_failed_waiting_time: Int = 10
    const val default_placement_check_delay: Int = 20
    const val default_unload_waiting_time: Int = 1
    const val default_async_check_interval: Int = 250
    const val default_send_form_error_message: Boolean = true

    /** 部件是否可被多个已成型结构共享；默认 false（防串配方）。 */
    const val default_parts_shareable: Boolean = false

    private val builder: ModConfigSpec.Builder = ModConfigSpec.Builder()

    val check_failed_waiting_time: ModConfigSpec.IntValue
    val placement_check_delay: ModConfigSpec.IntValue
    val unload_waiting_time: ModConfigSpec.IntValue
    val async_check_interval: ModConfigSpec.IntValue
    val send_form_error_message: ModConfigSpec.BooleanValue
    val parts_shareable: ModConfigSpec.BooleanValue

    val spec: ModConfigSpec

    init {
        builder.comment("多方块结构检测相关（时间单位：tick）", "Multiblock structure checks (in ticks)")
            .push("multiblock")

        check_failed_waiting_time = builder.comment("结构成型失败后的重试等待时间")
            .defineInRange("checkFailedWaitingTime", default_check_failed_waiting_time, 1, 1200)

        placement_check_delay = builder.comment("放置检查延迟")
            .defineInRange("placementCheckDelay", default_placement_check_delay, 0, 1200)

        unload_waiting_time = builder.comment("区块卸载后的等待时间")
            .defineInRange("unloadWaitingTime", default_unload_waiting_time, 1, 1200)

        async_check_interval = builder.comment("异步结构检测间隔")
            .defineInRange("asyncCheckInterval", default_async_check_interval, 1, 10000)

        send_form_error_message = builder.comment("结构成型失败时是否给玩家发提示")
            .define("sendFormErrorMessage", default_send_form_error_message)

        parts_shareable = builder.comment(
            "多方块部件是否允许被多个已成型结构共享（默认 false）",
            "⚠️ 该值只在结构检测那一刻被读，改完要等下一次检测才生效",
            "Allow multiblock parts to be shared between formed structures"
        ).define("partsShareable", default_parts_shareable)

        builder.pop()

        // ── 以下分类尚未移植，先留编号占位（xxx0 / xxx01 / xxx02 …），轮到对应功能时按序填入 ──
        // xxx0 = dev（开发者选项）：exportModeEnabled / exportDirectory / recipeExportDirectory / SendThreadDiagnosticlog
        val xxx0 = Unit
        // xxx01 = timeflow（时序与潮汐系数）：masterTowerUnique / tideAmplitude / tidePeriod / overclockFactorK /
        //         timeBottleHandFactor / towerSegmentCapacity / towerMaxSegments / towerWhitelist
        val xxx01 = Unit
        // xxx02 = overlay（结构工具覆盖层）：writeColor / detectColor / detectBoxLifetime
        val xxx02 = Unit

        spec = builder.build()
    }

    /** 在 mod 构造期注册配置；必须在构造期调，否则 NeoForge 不会加载它。 */
    fun register() {
        ModList.get().getModContainerById(GTETCore.ID).ifPresent {
            it.registerConfig(ModConfig.Type.COMMON, spec)
        }
    }

    // ── 读取入口 ──
    // 配置没加载时（例如数据生成阶段）一律退回默认值，所以每个入口都过一道 isLoaded 判断。

    fun checkFailedWaitingTime(): Int = intValue(check_failed_waiting_time, default_check_failed_waiting_time)

    fun placementCheckDelay(): Int = intValue(placement_check_delay, default_placement_check_delay)

    fun unloadWaitingTime(): Int = intValue(unload_waiting_time, default_unload_waiting_time)

    fun asyncCheckInterval(): Int = intValue(async_check_interval, default_async_check_interval)

    fun sendFormErrorMessage(): Boolean = booleanValue(send_form_error_message, default_send_form_error_message)

    /** 部件共享总开关：机器类的 `canShared()` 与共享提示行都读它。 */
    fun partsShareable(): Boolean = booleanValue(parts_shareable, default_parts_shareable)

    private fun intValue(value: ModConfigSpec.IntValue, fallback: Int): Int =
        if (spec.isLoaded) value.get() else fallback

    private fun booleanValue(value: ModConfigSpec.BooleanValue, fallback: Boolean): Boolean =
        if (spec.isLoaded) value.get() else fallback
}
