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

    /** 全屏（内嵌页里 3D 已隐藏，这颗按钮只在全屏里出现）：重置预览视角。 */
    const val BUTTON_RESET_VIEW: String = "$PREFIX.button.reset_view"

    /** 全屏：上一个 / 下一个多方块。 */
    const val BUTTON_PREV: String = "$PREFIX.button.prev"
    const val BUTTON_NEXT: String = "$PREFIX.button.next"

    /** 全屏顶部的「名字（序号/总数）」格式。 */
    const val LABEL_INDEX: String = "$PREFIX.label.index"

    /** 内嵌页里替换 3D 的那行提示。 */
    const val HINT_FULLSCREEN: String = "$PREFIX.hint.fullscreen"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(BUTTON_FULLSCREEN, "Fullscreen preview", "全屏预览")
        LangUtil.add(BUTTON_EXIT, "Exit fullscreen (ESC)", "退出全屏（ESC）")
        LangUtil.add(BUTTON_RESET_VIEW, "Reset the preview view", "重置预览视角")
        LangUtil.add(BUTTON_PREV, "Previous multiblock", "上一个多方块")
        LangUtil.add(BUTTON_NEXT, "Next multiblock", "下一个多方块")
        LangUtil.add(LABEL_INDEX, "%s (%s/%s)", "%s（%s/%s）")
        // 内嵌里那块地方只有 ~140x150 像素（JEI 框 200x180 减 selected_block 20 与部件列），
        // 单行写不下 ⇒ 用 \n 分短行（MUI 的 RichTextCompiler 认显式换行，也会按宽度自动折行）
        LangUtil.add(
            HINT_FULLSCREEN,
            "Click \"Fullscreen\"\nat the top right\nfor the 3D preview",
            "点右上角「全屏」\n查看 3D 结构预览"
        )
    }
}
