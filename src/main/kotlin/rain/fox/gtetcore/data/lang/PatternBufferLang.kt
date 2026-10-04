package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * AE2 集成·样板总成族（四档总成 + 通用镜像）的 tooltip 语言键。
 *
 * 只放**共用**的两条键：档位 / 容量的名字走注册处的 `langValue(...)` 与
 * `LangUtil.BLOCK_LANG`（每档一行，见 `ETMEPatternBufferHatches`），不在这里堆 8 条。
 *
 * 登记时机：`ETMEPatternBufferHatches.register()` 里调一次，而那行又由 `ETMachines` 在
 * `CommonProxy.kotlinInit()` 早段触发，所以必然早于数据生成。
 *
 * @author rain fox
 */
object PatternBufferLang {

    private const val PREFIX = "gtetscore.machine.me_pattern_buffer."

    /** 容量说明（`%s` = 槽位数；四档总成共用一条键）。 */
    const val CAPACITY: String = PREFIX + "capacity"

    /** 镜像那条「能连所有档位」的说明（镜像只有一件，槽位数不固定）。 */
    const val PROXY_TIERS: String = PREFIX + "proxy_tiers"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(CAPACITY, "Pattern slots: %s", "样板槽位：%s")
        LangUtil.add(
            PROXY_TIERS,
            "Connects to any tier of ME Pattern Buffer: 27 / 63 / 126 / 216 patterns",
            "可连接任意档位的 ME 样板总成：27 / 63 / 126 / 216 样板"
        )
    }
}
