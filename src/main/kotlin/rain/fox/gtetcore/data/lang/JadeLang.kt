package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * Jade HUD 里那两条进度条的语言键（TF 那条是本工程自己的，GTM 不认识 TF）。
 *
 * 登记进 [LangUtil] 后：en 由 registrate 的语言钩子写进 en_us，cn 由 [ZhCnLangProvider] 写进 zh_cn。
 *
 * @author rain fox
 */
object JadeLang {

    private const val PREFIX = "gtetscore.jade."

    /** 能量条：格式与 GTM 自己的 `gtceu.jade.energy_stored` 一致（`%s / %s EU`）。 */
    const val ENERGY_STORED: String = PREFIX + "energy_stored"

    /** TF（时间流）条。 */
    const val TIME_FLOW_STORED: String = PREFIX + "time_flow_stored"

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.initLang` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        LangUtil.add(ENERGY_STORED, "%s / %s EU", "%s / %s EU")
        LangUtil.add(TIME_FLOW_STORED, "Time Flow: %s / %s TF", "时间流：%s / %s TF")
    }
}
