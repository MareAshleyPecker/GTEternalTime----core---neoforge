package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 高级终端设置面板用到的语言键。
 *
 * 只登记**本次 MUI 界面真正引用**的键：设置面板（标题 + 8 项设置 + tooltip）、
 * 两块分级面板（标题 / tooltip / 空状态）。老项目里那几个 AE 绑定提示键属于未移植的 AE 切片，这里不登记；
 * 物品名与 `build.too_many` 也已由别处登记，重复登记会让数据生成因「重复的翻译键」直接失败。
 *
 * 登记进 [LangUtil] 后：en 由 registrate 的语言钩子写进 en_us，cn 由 [ZhCnLangProvider] 写进 zh_cn。
 *
 * @author rain fox
 */
object AdvancedTerminalLang {

    /** 语言键前缀（与老项目一致，不要改）。 */
    const val PREFIX: String = "item.gtetcore.advanced_terminal"

    const val TITLE: String = "$PREFIX.setting.title"

    const val SETTING_1: String = "$PREFIX.setting.1"
    const val SETTING_1_TIP: String = "$PREFIX.setting.1.tooltip"
    const val SETTING_2: String = "$PREFIX.setting.2"
    const val SETTING_2_TIP: String = "$PREFIX.setting.2.tooltip"
    const val SETTING_3: String = "$PREFIX.setting.3"
    const val SETTING_3_TIP: String = "$PREFIX.setting.3.tooltip"
    const val SETTING_4: String = "$PREFIX.setting.4"
    const val SETTING_4_TIP: String = "$PREFIX.setting.4.tooltip"
    const val SETTING_5: String = "$PREFIX.setting.5"
    const val SETTING_5_TIP: String = "$PREFIX.setting.5.tooltip"
    const val SETTING_6: String = "$PREFIX.setting.6"
    const val SETTING_6_TIP: String = "$PREFIX.setting.6.tooltip"
    const val SETTING_7: String = "$PREFIX.setting.7"
    const val SETTING_7_TIP: String = "$PREFIX.setting.7.tooltip"
    const val SETTING_8: String = "$PREFIX.setting.8"
    const val SETTING_8_TIP: String = "$PREFIX.setting.8.tooltip"

    /** 「使用 AE 物品」的补充说明：AE 链接本阶段不移植，这一项只能看不能点。 */
    const val SETTING_5_TIP_DISABLED: String = "$PREFIX.setting.5.tooltip.disabled"

    /** 「模块搭建」的补充说明：模块化机器本阶段不移植，这一项现在调了也不生效。 */
    const val SETTING_7_TIP_DISABLED: String = "$PREFIX.setting.7.tooltip.disabled"

    const val PANEL_CYCLE: String = "$PREFIX.panel.cycle"
    const val PANEL_CYCLE_TIP: String = "$PREFIX.panel.cycle.tooltip"
    const val PANEL_CHOOSE: String = "$PREFIX.panel.choose"
    const val PANEL_PICK_TIP: String = "$PREFIX.panel.pick.tooltip"
    const val PANEL_EMPTY: String = "$PREFIX.panel.empty"

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.kotlinInit` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        // ⚠️ 物品名（[PREFIX] 本身）由 Registrate 的 `.lang(...)` 生成，这里**不要**再写一遍。
        LangUtil.add(TITLE, "Advanced Terminal Setting", "高级终端设置")

        LangUtil.add(SETTING_1, "Coil level", "线圈等级")
        LangUtil.add(
            SETTING_1_TIP, "Set the priority level for automatic coil placement.",
            "设置优先自动放置的线圈等级。"
        )
        LangUtil.add(SETTING_2, "Number of repetitions of the structure", "重复结构次数")
        LangUtil.add(
            SETTING_2_TIP,
            "Used to set the number of repetitions for the placement of repeating parts in structures like distillation towers, assembly lines, etc.",
            "用于设置可重复结构(蒸馏塔、装配线等)的重复部分放置次数"
        )
        LangUtil.add(SETTING_3, "No Hatch mode", "无仓室模式")
        LangUtil.add(
            SETTING_3_TIP,
            "Whether to enable the no-Hatch mode. After enabling the no-chamber mode, various Hatch will not be placed when they are not unique.",
            "是否启用无仓室模式。启用无仓室模式后不会在非唯一时放置各种仓室。"
        )
        LangUtil.add(SETTING_4, "Coil replace mode", "线圈替换模式")
        LangUtil.add(
            SETTING_4_TIP,
            "Whether to enable the coil-replace mode. After enabling the coil-replace mode, coil will be replaced by the Coil before.",
            "是否启用线圈替换模式。启用线圈替换模式会将所有线圈替换为线圈等级中指定的线圈。"
        )
        LangUtil.add(SETTING_5, "Is use AE items", "使用AE物品")
        LangUtil.add(
            SETTING_5_TIP,
            "Whether to enable to use items in AE. After enabling this, you can autobuild with items in AE storage by an ME terminal.",
            "是否使用AE物品。使用AE物品开启后，会通过背包中的AE终端连接到相应的AE网络并使用其中的物品来进行建造。"
        )
        LangUtil.add(
            SETTING_5_TIP_DISABLED, "AE network link is not ported yet - this option does nothing.",
            "AE 链接尚未移植，本项当前无效（点不动）。"
        )
        LangUtil.add(SETTING_6, "Mirror build", "镜像搭建")
        LangUtil.add(
            SETTING_6_TIP,
            "Whether to enable the mirror build. After enabling this, the structure is placed mirrored (flipped left/right or front/back).",
            "是否启用镜像搭建。启用后结构按镜像摆放（左右或前后翻转）。"
        )
        LangUtil.add(SETTING_7, "Module build", "模块搭建")
        LangUtil.add(
            SETTING_7_TIP,
            "Which structure to build: 0 = main structure, N = the N-th structure. If the controller only has the main structure, the main structure is built.",
            "选择要搭建的结构：0 = 主结构，N = 第 N 套结构。控制器只有主结构时按主结构搭建。"
        )
        LangUtil.add(
            SETTING_7_TIP_DISABLED, "Modular machines are not ported yet - this option does nothing for now.",
            "模块化机器尚未移植，本项当前无效（可以照常调）。"
        )
        LangUtil.add(SETTING_8, "Demolition mode", "拆除模式")
        LangUtil.add(
            SETTING_8_TIP,
            "Whether to enable the demolition mode. After enabling this, blocks are no longer placed: the structure blocks at each position are removed instead. Blocks that do not belong to this structure are left untouched.",
            "是否启用拆除模式。启用后不再放置方块，而是按结构把当前位置的结构方块拆掉。不属于本结构的方块不会被拆除。"
        )

        LangUtil.add(PANEL_CYCLE, "Tiered blocks (switch)", "分级方块（切换）")
        LangUtil.add(
            PANEL_CYCLE_TIP, "Switch to the next candidate block of this tier group.",
            "切换到本分级组的下一个候选方块。"
        )
        LangUtil.add(PANEL_CHOOSE, "Tiered blocks (choose)", "分级方块（勾选）")
        LangUtil.add(
            PANEL_PICK_TIP, "Click to show this tier group in the panel below.",
            "点击后下方面板改为显示这一组的分级方块。"
        )
        LangUtil.add(
            PANEL_EMPTY, "Shift+right-click a controller to scan the structure first",
            "先 Shift+右键控制器扫描结构"
        )
    }
}
