package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 机器输入输出配置页的语言键。
 *
 * 面名一律用**相对机器朝向**的说法（正面 / 后面 / 左面 / 右面 / 顶面 / 底面），不再出现绝对方位。
 *
 * 登记进 [LangUtil] 后：en 由 registrate 的语言钩子写进 en_us，cn 由 [ZhCnLangProvider] 写进 zh_cn。
 *
 * @author rain fox
 */
object MachineIoConfigLang {

    /** 语言键前缀。 */
    const val PREFIX: String = "gtetscore.machine.io_config"

    const val TITLE: String = "$PREFIX.title"
    const val BUTTON: String = "$PREFIX.button"
    const val FACE_ITEM: String = "$PREFIX.face.item"
    const val FACE_FLUID: String = "$PREFIX.face.fluid"
    const val FACE_NONE: String = "$PREFIX.face.none"

    /** 六面图小格的 tooltip：怎么点 / 该面是正面设不上。 */
    const val FACE_CELL_TIP: String = "$PREFIX.face.tip"
    const val FACE_CELL_FRONT: String = "$PREFIX.face.front"

    /** 相对机器朝向的面名。 */
    const val REL_FRONT: String = "$PREFIX.rel.front"
    const val REL_BACK: String = "$PREFIX.rel.back"
    const val REL_TOP: String = "$PREFIX.rel.top"
    const val REL_BOTTOM: String = "$PREFIX.rel.bottom"
    const val REL_LEFT: String = "$PREFIX.rel.left"
    const val REL_RIGHT: String = "$PREFIX.rel.right"

    /** 26px 小格里放不下面名，各配一个单字 / 单字母缩写。 */
    const val REL_FRONT_SHORT: String = "$PREFIX.rel.front.short"
    const val REL_BACK_SHORT: String = "$PREFIX.rel.back.short"
    const val REL_TOP_SHORT: String = "$PREFIX.rel.top.short"
    const val REL_BOTTOM_SHORT: String = "$PREFIX.rel.bottom.short"
    const val REL_LEFT_SHORT: String = "$PREFIX.rel.left.short"
    const val REL_RIGHT_SHORT: String = "$PREFIX.rel.right.short"

    /** 四个开关的 tooltip（`%s` 填 [STATE_ON] / [STATE_OFF]）。 */
    const val TOGGLE_AUTO_ITEM: String = "$PREFIX.toggle.auto_item"
    const val TOGGLE_AUTO_FLUID: String = "$PREFIX.toggle.auto_fluid"
    const val TOGGLE_ALLOW_IN_ITEM: String = "$PREFIX.toggle.allow_in_item"
    const val TOGGLE_ALLOW_IN_FLUID: String = "$PREFIX.toggle.allow_in_fluid"

    const val STATE_ON: String = "$PREFIX.state.on"
    const val STATE_OFF: String = "$PREFIX.state.off"

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.kotlinInit` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        LangUtil.add(TITLE, "I/O Configuration", "输入输出配置")
        LangUtil.add(BUTTON, "Open the I/O configuration page", "打开输入输出配置页")
        LangUtil.add(FACE_ITEM, "Item output: %s", "物品输出面：%s")
        LangUtil.add(FACE_FLUID, "Fluid output: %s", "流体输出面：%s")
        LangUtil.add(FACE_NONE, "none", "未设置")

        LangUtil.add(
            FACE_CELL_TIP, "Left-click: set as the item output side; right-click: set as the fluid output side",
            "左键：设为物品输出面；右键：设为流体输出面"
        )
        LangUtil.add(
            FACE_CELL_FRONT, "This is the machine's front face - it cannot be set as an output side",
            "该面是机器正面，不能设为输出面"
        )

        LangUtil.add(REL_FRONT, "Front", "正面")
        LangUtil.add(REL_BACK, "Back", "后面")
        LangUtil.add(REL_TOP, "Top", "顶面")
        LangUtil.add(REL_BOTTOM, "Bottom", "底面")
        LangUtil.add(REL_LEFT, "Left", "左面")
        LangUtil.add(REL_RIGHT, "Right", "右面")

        LangUtil.add(REL_FRONT_SHORT, "F", "正")
        LangUtil.add(REL_BACK_SHORT, "B", "后")
        LangUtil.add(REL_TOP_SHORT, "T", "顶")
        LangUtil.add(REL_BOTTOM_SHORT, "D", "底")
        LangUtil.add(REL_LEFT_SHORT, "L", "左")
        LangUtil.add(REL_RIGHT_SHORT, "R", "右")

        LangUtil.add(TOGGLE_AUTO_ITEM, "Auto-output items: %s", "自动输出物品：%s")
        LangUtil.add(TOGGLE_AUTO_FLUID, "Auto-output fluids: %s", "自动输出流体：%s")
        LangUtil.add(
            TOGGLE_ALLOW_IN_ITEM, "Allow input from the item output side: %s",
            "允许从物品输出面输入：%s"
        )
        LangUtil.add(
            TOGGLE_ALLOW_IN_FLUID, "Allow input from the fluid output side: %s",
            "允许从流体输出面输入：%s"
        )
        LangUtil.add(STATE_ON, "ON", "开")
        LangUtil.add(STATE_OFF, "OFF", "关")
    }
}
