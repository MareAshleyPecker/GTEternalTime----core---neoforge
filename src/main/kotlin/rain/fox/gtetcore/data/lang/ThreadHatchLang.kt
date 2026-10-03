package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 线程仓一族（仓本身 + `ThreadedRecipeLogic` 的显示层 + 它的 Jade provider）的**全部**语言键。
 *
 * 集中在一处：机器面板（`ThreadedRecipeStatus.appendDisplayLines`）与 Jade
 * （`ThreadedRecipeLogicProvider`）共用同一批文案，两边必须逐字一致。
 * en 由 registrate 的语言钩子写进 en_us，cn 由 `ZhCnLangProvider` 写进 zh_cn。
 */
object ThreadHatchLang {

    private const val PREFIX = "gtetscore.threads."

    /** 线程仓方块提示（八档共用一条键）。 */
    const val TOOLTIP: String = "gtetscore.machine.thread_hatch.tooltip"

    /**
     * Jade provider 的配置键。
     *
     * ⚠️ Jade 的 `config.jade.plugin_<命名空间>.<uid 路径>` 是**必填**键：
     * dev 环境缺键会在 `JadeClient#onGui` 上抛 `AssertionError` 把客户端崩掉。
     */
    const val JADE_CONFIG: String = "config.jade.plugin_gtetscore.threaded_recipe_logic"

    /** `线程 %s（在用 %s）` —— 参数是线程数上限与正在跑的条数。 */
    const val LANG_STATUS: String = PREFIX + "status"

    /** 整机「同时处理多少次配方运行」（Σ 各线程 `GTRecipe#getTotalRuns()`）。 */
    const val LANG_TOTAL_RUNS: String = PREFIX + "total_runs"

    /** 整机耗电（Σ 各线程 EU/t）。 */
    const val LANG_TOTAL_EUT: String = PREFIX + "total_eut"

    /** 组行第一行（Jade 那条就是进度条本身）：槽位 / 进度 / 时长。 */
    const val LANG_PROGRESS: String = PREFIX + "progress"

    /** 组行第二行（机器面板用）：产出 / 该组线程条数 / 该组 EU/t。 */
    const val LANG_OUTPUTS: String = PREFIX + "outputs"

    /** 明细被截断时的尾行。 */
    const val LANG_MORE: String = PREFIX + "more"

    /** Jade 组行里产物后面的尾巴（不带「产出 %s」外壳，Jade 那条是图标 + 名字）。 */
    const val LANG_GROUP_TAIL: String = PREFIX + "group_tail"

    /** 一条组行里产物种类超上限时的补充。 */
    const val LANG_OUTPUT_MORE: String = PREFIX + "output_more"

    /** 同一行内多个产物之间的分隔符。 */
    const val LANG_OUTPUT_SEP: String = PREFIX + "output_sep"

    /** 该组没有物品产出时的占位。 */
    const val LANG_NO_OUTPUT: String = PREFIX + "no_output"

    /** 幂等登记；必须在 mod 构造期调用一次（由 `CommonProxy.initLang` 调），早于 `runData`。 */
    @JvmStatic
    fun register() {
        LangUtil.add(
            TOOLTIP,
            "Splits the controller into %s parallel thread slots; each slot runs its own recipe on its own timer.",
            "把控制器拆成 %s 条线程槽，每条线程各自跑一种配方、各自计时。"
        )
        LangUtil.add(JADE_CONFIG, "Threaded Recipe Logic", "多线程配方状态")
        LangUtil.add(LANG_STATUS, "Threads %s (%s in use)", "线程 %s（在用 %s）")
        LangUtil.add(
            LANG_TOTAL_RUNS,
            "Processing %s recipe runs at once (sum over all threads)",
            "同时处理 %s 次配方运行（各线程之和）"
        )
        LangUtil.add(
            LANG_TOTAL_EUT,
            "Consuming %s EU/t (sum over all threads)",
            "整机耗电 %s EU/t（各线程之和）"
        )
        LangUtil.add(LANG_PROGRESS, "#%s  %s/%s t", "#%s  %s/%s t")
        LangUtil.add(LANG_OUTPUTS, "Output %s · %s threads · %s EU/t", "产出 %s · %s 条线程 · %s EU/t")
        LangUtil.add(
            LANG_MORE,
            "  ...and %s more recipe groups (showing first %s)",
            "  ……还有 %s 个配方组（仅显示前 %s 个）"
        )
        LangUtil.add(LANG_GROUP_TAIL, " · %s threads · %s EU/t", " · %s 条线程 · %s EU/t")
        LangUtil.add(LANG_OUTPUT_MORE, ", +%s more kinds", "，等 %s 种")
        LangUtil.add(LANG_OUTPUT_SEP, ", ", "、")
        LangUtil.add(LANG_NO_OUTPUT, "no item output", "无物品产出")
    }
}
