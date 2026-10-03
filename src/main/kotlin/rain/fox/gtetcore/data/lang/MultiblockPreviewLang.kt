package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 多方块 3D 预览（JEI / EMI 信息页的内嵌预览 + 全屏预览）的语言键。
 *
 * 登记进 [LangUtil] 后：en 由 registrate 的语言钩子写进 en_us，cn 由
 * [rain.fox.gtetcore.data.lang.ZhCnLangProvider] 写进 zh_cn。
 *
 * @author rain fox
 */
object MultiblockPreviewLang {

    /** 语言键前缀。 */
    const val PREFIX: String = "gtetscore.multiblock_preview"

    /** 内嵌预览右上角：进入全屏。 */
    const val BUTTON_FULLSCREEN: String = "$PREFIX.button.fullscreen"

    /** 全屏预览右上角：退出全屏。 */
    const val BUTTON_EXIT: String = "$PREFIX.button.exit"

    /** 两个界面都有：重置预览视角。 */
    const val BUTTON_RESET_VIEW: String = "$PREFIX.button.reset_view"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(BUTTON_FULLSCREEN, "Fullscreen preview", "全屏预览")
        LangUtil.add(BUTTON_EXIT, "Exit fullscreen (ESC)", "退出全屏（ESC）")
        LangUtil.add(BUTTON_RESET_VIEW, "Reset the preview view", "重置预览视角")
    }
}
