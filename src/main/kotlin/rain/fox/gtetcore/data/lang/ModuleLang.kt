package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.common.data.machine.multiblock.modular.ETModularTestMultiblocks
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「模块化多方块」一族的**全部**语言键：基类 / 单元 / 主机 / 试验台。
 *
 * 集中在一处：机器面板（MUI）与结构失败原因（`failureReasons` → Jade / 面板）共用同一批文案，
 * 两边必须逐字一致。en 由 registrate 的语言钩子写进 en_us，cn 由 `ZhCnLangProvider` 写进 zh_cn。
 *
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成（同 `ThreadHatchLang` / `TestMultiblockLang`）。
 *
 * @author rain fox
 */
object ModuleLang {

    /** 全部键都在 `gtetscore.machine.` 下（`block.gtetscore.<id>` 那套是 registrate 自己写的，不走这里）。 */
    private const val PREFIX: String = "gtetscore.machine."

    /** 试验台的键前缀（id 与 `ETModularTestMultiblocks` 同一处，避免两处漂移）。 */
    private const val TEST_PREFIX: String = PREFIX + ETModularTestMultiblocks.MODULAR_TEST_ID + "."

    // ======================== 基类：模块等级 → 配方电压 ========================

    /** 「配方的电压等级超出当前模块」的原因键（参数 = 允许的最高档位名）。 */
    const val TIER_TOO_LOW: String = PREFIX + "modular.tier_too_low"

    // ======================== 单元（子机） ========================

    /** 「附近没有主机」的原因键（参数 = 距离）。 */
    const val NO_HOST: String = PREFIX + "module.no_host"

    /** 「配方等级高于主机」的原因键（参数 = 主机等级名）。 */
    const val MODULE_TIER_TOO_LOW: String = PREFIX + "module.tier_too_low"

    /** 已对接主机（参数 = 距离，格）。 */
    const val HOST_LINKED: String = PREFIX + "module.host_linked"

    /** 等级分工（参数 = 处理等级名、配方等级名）。 */
    const val TIER_SPLIT: String = PREFIX + "module.tier_split"

    /** 面板上「重新对接」按钮的文字。 */
    const val RECHECK: String = PREFIX + "module.recheck"

    // ======================== 主机（核心） ========================

    /** 「单元 N 台（已成型 M）」——参数是两个数量。 */
    const val HOST_MODULES: String = PREFIX + "host.modules"

    /** 主机等级（参数 = 档位名）。 */
    const val HOST_TIER: String = PREFIX + "host.tier"

    /** 主机可用电量（参数 = 已格式化的 EU）。 */
    const val HOST_AVAILABLE_EU: String = PREFIX + "host.available_eu"

    // ======================== 试验台 ========================

    /** 方块名（中文进 `LangUtil.BLOCK_LANG`，英文由注册时的 `.langValue(...)` 写进 en_us）。 */
    const val TEST_BLOCK_NAME_CN: String = "模块化测试机"

    /** tooltip 第 0 行：模块槽怎么用。 */
    const val TEST_TOOLTIP_0: String = TEST_PREFIX + "tooltip.0"

    /** tooltip 第 1 行：模块决定结构 / 机壳数抬电压上限。 */
    const val TEST_TOOLTIP_1: String = TEST_PREFIX + "tooltip.1"

    /** 当前模块等级（面板）。 */
    const val TEST_TIER: String = TEST_PREFIX + "tier"

    /** 结构里的模块方块数（面板）。 */
    const val TEST_MODULES: String = TEST_PREFIX + "modules"

    /** 当前配方电压上限（面板）。 */
    const val TEST_CAP: String = TEST_PREFIX + "cap"

    /** 模块槽的悬停提示。 */
    const val TEST_SLOT_TOOLTIP: String = TEST_PREFIX + "slot_tooltip"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        // ---- 基类 ----
        LangUtil.add(
            TIER_TOO_LOW,
            "Recipe voltage tier is above the current module (max: %s)",
            "配方的电压等级超出当前模块（上限：%s）",
        )

        // ---- 单元 ----
        LangUtil.add(NO_HOST, "No module host within %s blocks", "附近 %s 格内没有模块主机")
        LangUtil.add(
            MODULE_TIER_TOO_LOW,
            "Recipe voltage tier is above the host (%s)",
            "配方的电压等级高于主机（%s）",
        )
        LangUtil.add(HOST_LINKED, "Module host linked (%s blocks)", "已对接模块主机（距离 %s 格）")
        LangUtil.add(
            TIER_SPLIT,
            "Processing tier %s (own) · Recipe tier %s (host)",
            "处理等级 %s（以自己算）· 配方等级 %s（以主机算）",
        )
        LangUtil.add(RECHECK, "Relink host", "重新对接主机")

        // ---- 主机 ----
        LangUtil.add(HOST_MODULES, "Modules %s (%s formed)", "单元 %s 台（已成型 %s）")
        LangUtil.add(
            HOST_TIER,
            "Host tier %s (unit recipe tier follows it)",
            "主机等级 %s（单元的配方等级以它为准）",
        )
        LangUtil.add(
            HOST_AVAILABLE_EU,
            "Host buffer %s EU (units may draw from it)",
            "主机缓存 %s EU（单元可以从这里取电）",
        )

        // ---- 试验台 ----
        LangUtil.BLOCK_LANG[ETModularTestMultiblocks.MODULAR_TEST_ID] = TEST_BLOCK_NAME_CN
        LangUtil.add(
            TEST_TOOLTIP_0,
            "Put a module item in the module slot: Gold = MK1, Titanium = MK2, Neutronium = MK3.",
            "往模块槽里放模块物品：金 = MK1、钛 = MK2、中子素 = MK3。",
        )
        LangUtil.add(
            TEST_TOOLTIP_1,
            "The module changes the structure (3³ / 5³ / 7³); every 8 module casings raise the recipe voltage cap by one tier.",
            "模块决定结构（3³ / 5³ / 7³）；每 8 个模块方块把配方电压等级上限抬一档。",
        )
        LangUtil.add(TEST_TIER, "Module tier: %s", "模块等级：%s")
        LangUtil.add(TEST_MODULES, "Module casings: %s", "模块方块：%s")
        LangUtil.add(TEST_CAP, "Recipe voltage cap: %s", "配方电压上限：%s")
        LangUtil.add(TEST_SLOT_TOOLTIP, "Module slot", "模块槽")
    }
}
