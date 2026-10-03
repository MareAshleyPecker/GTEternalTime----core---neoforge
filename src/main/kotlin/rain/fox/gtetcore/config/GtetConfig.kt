package rain.fox.gtetcore.config

import net.neoforged.fml.ModList
import net.neoforged.fml.config.ModConfig
import net.neoforged.neoforge.common.ModConfigSpec
import rain.fox.gtetcore.Gtetcore

/**
 * GTET 的 COMMON 配置（NeoForge 的 [ModConfigSpec]，对应 1.20.1 时代的 ForgeConfigSpec）。
 *
 * 目前只移植了**机器真正要读的** `multiblock` 段；老项目里另有 `dev`（导出/诊断）、
 * `timeflow`（时序潮汐与系数）、`overlay`（结构工具颜色）三段，等对应功能移植过来再补，
 * 免得先摆一堆用不到的空条目。键名与默认值必须与老项目保持一致。
 */
object GtetConfig {

    // ── 默认值（与老项目一字不差，改默认值等于改玩家行为）──

    const val DEFAULT_CHECK_FAILED_WAITING_TIME: Int = 10
    const val DEFAULT_PLACEMENT_CHECK_DELAY: Int = 20
    const val DEFAULT_UNLOAD_WAITING_TIME: Int = 1
    const val DEFAULT_ASYNC_CHECK_INTERVAL: Int = 250
    const val DEFAULT_SEND_FORM_ERROR_MESSAGE: Boolean = true

    /** 部件是否可被多个已成型结构共享；默认 false（防串配方）。 */
    const val DEFAULT_PARTS_SHAREABLE: Boolean = false

    private val BUILDER: ModConfigSpec.Builder = ModConfigSpec.Builder()

    val CHECK_FAILED_WAITING_TIME: ModConfigSpec.IntValue
    val PLACEMENT_CHECK_DELAY: ModConfigSpec.IntValue
    val UNLOAD_WAITING_TIME: ModConfigSpec.IntValue
    val ASYNC_CHECK_INTERVAL: ModConfigSpec.IntValue
    val SEND_FORM_ERROR_MESSAGE: ModConfigSpec.BooleanValue
    val PARTS_SHAREABLE: ModConfigSpec.BooleanValue

    val SPEC: ModConfigSpec

    init {
        BUILDER.comment("多方块结构检测相关（时间单位：tick）", "Multiblock structure checks (in ticks)")
            .push("multiblock")

        CHECK_FAILED_WAITING_TIME = BUILDER.comment("结构成型失败后的重试等待时间")
            .defineInRange("checkFailedWaitingTime", DEFAULT_CHECK_FAILED_WAITING_TIME, 1, 1200)

        PLACEMENT_CHECK_DELAY = BUILDER.comment("放置检查延迟")
            .defineInRange("placementCheckDelay", DEFAULT_PLACEMENT_CHECK_DELAY, 0, 1200)

        UNLOAD_WAITING_TIME = BUILDER.comment("区块卸载后的等待时间")
            .defineInRange("unloadWaitingTime", DEFAULT_UNLOAD_WAITING_TIME, 1, 1200)

        ASYNC_CHECK_INTERVAL = BUILDER.comment("异步结构检测间隔")
            .defineInRange("asyncCheckInterval", DEFAULT_ASYNC_CHECK_INTERVAL, 1, 10000)

        SEND_FORM_ERROR_MESSAGE = BUILDER.comment("结构成型失败时是否给玩家发提示")
            .define("sendFormErrorMessage", DEFAULT_SEND_FORM_ERROR_MESSAGE)

        PARTS_SHAREABLE = BUILDER.comment(
            "多方块部件是否允许被多个已成型结构共享（默认 false，防串配方）",
            "⚠️ 该值只在结构检测那一刻被读，改完要等下一次检测才生效",
            "Allow multiblock parts to be shared between formed structures"
        ).define("partsShareable", DEFAULT_PARTS_SHAREABLE)

        BUILDER.pop()

        // ── 以下分类尚未移植，先留编号占位（xxx0 / xxx01 / xxx02 …），轮到对应功能时按序填入 ──
        // xxx0 = dev（开发者选项）：exportModeEnabled / exportDirectory / recipeExportDirectory / SendThreadDiagnosticlog
        val xxx0: Unit = Unit
        // xxx01 = timeflow（时序与潮汐系数）：masterTowerUnique / tideAmplitude / tidePeriod / overclockFactorK /
        //         timeBottleHandFactor / towerSegmentCapacity / towerMaxSegments / towerWhitelist
        val xxx01: Unit = Unit
        // xxx02 = overlay（结构工具覆盖层）：writeColor / detectColor / detectBoxLifetime
        val xxx02: Unit = Unit

        SPEC = BUILDER.build()
    }

    /** 在 mod 构造期注册配置；必须在构造期调，否则 NeoForge 不会加载它。 */
    fun register() {
        ModList.get().getModContainerById(Gtetcore.ID).ifPresent {
            it.registerConfig(ModConfig.Type.COMMON, SPEC)
        }
    }

    // ── 读取入口 ──
    // 配置没加载时（例如数据生成阶段）一律退回默认值，所以每个入口都过一道 isLoaded 判断。

    fun checkFailedWaitingTime(): Int = intValue(CHECK_FAILED_WAITING_TIME, DEFAULT_CHECK_FAILED_WAITING_TIME)

    fun placementCheckDelay(): Int = intValue(PLACEMENT_CHECK_DELAY, DEFAULT_PLACEMENT_CHECK_DELAY)

    fun unloadWaitingTime(): Int = intValue(UNLOAD_WAITING_TIME, DEFAULT_UNLOAD_WAITING_TIME)

    fun asyncCheckInterval(): Int = intValue(ASYNC_CHECK_INTERVAL, DEFAULT_ASYNC_CHECK_INTERVAL)

    fun sendFormErrorMessage(): Boolean = booleanValue(SEND_FORM_ERROR_MESSAGE, DEFAULT_SEND_FORM_ERROR_MESSAGE)

    /** 部件共享总开关：机器类的 `canShared()` 与共享提示行都读它。 */
    fun partsShareable(): Boolean = booleanValue(PARTS_SHAREABLE, DEFAULT_PARTS_SHAREABLE)

    private fun intValue(value: ModConfigSpec.IntValue, fallback: Int): Int =
        if (SPEC.isLoaded) value.get() else fallback

    private fun booleanValue(value: ModConfigSpec.BooleanValue, fallback: Boolean): Boolean =
        if (SPEC.isLoaded) value.get() else fallback
}
