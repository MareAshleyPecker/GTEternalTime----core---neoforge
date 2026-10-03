package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 时序钟的双语条目。
 *
 * 这里的是**带数字的运行时行**（汇率、相位、存量都会变，所以只能 `Component.translatable(键, 参数...)`）；
 * 两行**静态**说明（搬运 / 升级）由 `ETItems` 自己的 `LangUtil.add` 登记，键是
 * `item.gtetscore.clock_of_time_sequence.tooltip.<名字>`。
 *
 * 必须在数据生成之前登记 —— `CommonProxy.kotlinInit()` 会调 [register]，早于 `GatherDataEvent`。
 *
 * @author rain fox
 */
object TimeClockLang {

    /** 语言键前缀（与老项目一致，不要改）；物品注册名是 `clock_of_time_sequence`，键里仍沿用 `time_clock`。 */
    private const val PREFIX = "item.gtetscore.time_clock.tip."

    /** 档位与容量上限：`档位 %s / 容量上限 %s TF`。 */
    const val TIER: String = PREFIX + "tier"

    /** 钟内 TF 与其折算 EU：`钟内 %s TF（= %s EU）`。 */
    const val CONTENT: String = PREFIX + "content"

    /** 单位定义。 */
    const val UNIT: String = PREFIX + "unit"

    /** 当前汇率与相位。 */
    const val RATE: String = PREFIX + "rate"

    /** 不在世界内（物品栏预览 / JEI）时的汇率行。 */
    const val RATE_UNKNOWN: String = PREFIX + "rate_unknown"

    /** 潮汐已关闭。 */
    const val TIDE_OFF: String = PREFIX + "tide_off"

    /** 距峰值 / 距谷值的秒数。 */
    const val TIDE_NEXT: String = PREFIX + "tide_next"

    /** 当前往返损耗。 */
    const val LOSS: String = PREFIX + "loss"

    /** 绑定成功的聊天提示。 */
    const val BOUND: String = PREFIX + "bound"

    /** 解绑成功的聊天提示。 */
    const val UNBOUND: String = PREFIX + "unbound"

    /** 绑上了但没有取用权限时的聊天提示（比 [BOUND] 多一句说明）。 */
    const val BOUND_DENIED: String = PREFIX + "bound_denied"

    /** tooltip 里的「已绑定」行。 */
    const val BOUND_TIP: String = PREFIX + "bound_tip"

    /** tooltip 里的「未绑定」行。 */
    const val UNBOUND_TIP: String = PREFIX + "unbound_tip"

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.kotlinInit` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        // ⚠️ 物品名（`item.gtetscore.clock_of_time_sequence`）由 Registrate 的 `.lang(...)` 生成，
        //    这里**不要**再写一遍，否则数据生成会因「重复的翻译键」直接失败。
        LangUtil.add(TIER, "Tier %s / capacity %s TF", "档位 %s / 容量上限 %s TF")
        LangUtil.add(CONTENT, "Stored %s TF (= %s EU)", "钟内 %s TF（= %s EU）")
        LangUtil.add(
            UNIT,
            "1 TF = 8,192 EU (= 1A IV x 1 tick)",
            "1 TF = 8,192 EU（= 1A IV × 1 tick）"
        )
        LangUtil.add(RATE, "Tide rate %sx (phase %s)", "潮汐汇率 %s×（相位 %s）")
        LangUtil.add(
            RATE_UNKNOWN,
            "Tide rate: n/a outside a level",
            "潮汐汇率：不在世界内，无法计算"
        )
        LangUtil.add(
            TIDE_OFF,
            "Tide disabled (a = 0, rate is always 1.00x)",
            "潮汐已关闭（a = 0，汇率恒为 1.00×）"
        )
        LangUtil.add(
            TIDE_NEXT,
            "Next peak in %s s, next trough in %s s",
            "距峰值 %s 秒，距谷值 %s 秒"
        )
        LangUtil.add(
            LOSS,
            "Round-trip loss %s%% (charge 100%%, discharge 80%%)",
            "往返损耗 %s%%（充入 100%% / 取出 80%%）"
        )
        LangUtil.add(
            BOUND,
            "Time clock bound to master tower at (%s, %s, %s)",
            "时序钟已绑定主控塔：(%s, %s, %s)"
        )
        LangUtil.add(UNBOUND, "Time clock unbound", "时序钟已解除绑定")
        LangUtil.add(
            BOUND_DENIED,
            "Bound to the master tower at (%s, %s, %s), but you are not its owner / on its whitelist - payments will fall back to the TF inside the clock",
            "已绑定主控塔：(%s, %s, %s)，但你不在它的所有者 / 白名单里 —— 扣费会回落到钟内 TF"
        )
        LangUtil.add(BOUND_TIP, "Bound tower: %s", "已绑定主控塔：%s")
        LangUtil.add(UNBOUND_TIP, "Not bound to any master tower", "未绑定主控塔")
    }
}
