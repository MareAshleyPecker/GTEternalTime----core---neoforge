package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「多方块结构工具」三个道具 + 导出面板的**全部**语言键。
 *
 * 老工程（1.20.1）这套文案一半是硬编码英文（`StructureWriteBehavior` 里的 "Export" / "Size: X:%d …"），
 * 一半挂在 `LangUtil.add`（`gtetcore.structure_detect.no_pattern` / `gtetcore.terminal.recheck`）；
 * 新工程统一收进本文件（命名空间换成 `gtetscore`）。
 *
 * 物品名走登记处的 `.lang(...)` + `LangUtil.ITEM_LANG`，这里不重复登记。
 *
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成。
 *
 * @author rain fox
 */
object StructureToolLang {

    private const val PREFIX = "gtetscore.structure_tool."

    // ── 结构工具（structure_tools）──

    const val TOOLTIP_SELECT: String = PREFIX + "select"
    const val TOOLTIP_CLEAR: String = PREFIX + "clear"
    const val TOOLTIP_OPEN_PANEL: String = PREFIX + "open_panel"

    // ── 结构刷新工具（structure_checker）──

    const val TOOLTIP_RECHECK: String = PREFIX + "recheck"

    // ── 结构检测工具（structure_detect）──

    const val TOOLTIP_DETECT_1: String = PREFIX + "detect.1"
    const val TOOLTIP_DETECT_2: String = PREFIX + "detect.2"

    // ── 导出面板 ──

    /** `Size: X:%s Y:%s Z:%s`。 */
    const val PANEL_SIZE: String = PREFIX + "panel.size"

    /** `Dir: +%s | %s | %s`（三个相对方向）。 */
    const val PANEL_DIR: String = PREFIX + "panel.dir"

    const val PANEL_START: String = PREFIX + "panel.start"
    const val PANEL_END: String = PREFIX + "panel.end"

    /** 选区的空值占位（`-`）。 */
    const val PANEL_EMPTY: String = PREFIX + "panel.empty"

    const val BTN_EXPORT: String = PREFIX + "button.export"
    const val BTN_ROT_X: String = PREFIX + "button.rot_x"
    const val BTN_ROT_Y: String = PREFIX + "button.rot_y"
    const val BTN_ROT_Z: String = PREFIX + "button.rot_z"

    // ── 聊天栏反馈 ──

    /** 第一下右键只钉下起点：`Start: <坐标>`。 */
    const val MSG_START: String = PREFIX + "msg.start"

    /** 起止都对上了：`<起点> -> <终点> | Size: <x>x<y>x<z>`。 */
    const val MSG_RANGE: String = PREFIX + "msg.range"

    const val MSG_CLEARED: String = PREFIX + "msg.cleared"

    /** `Exported to <路径>`。 */
    const val MSG_EXPORTED: String = PREFIX + "msg.exported"

    /** 配置里关掉了导出模式（`dev.exportModeEnabled = false`）。 */
    const val MSG_EXPORT_DISABLED: String = PREFIX + "msg.export_disabled"

    const val MSG_NO_SELECTION: String = PREFIX + "msg.no_selection"

    /** 写盘失败（`%s` = 异常信息）。 */
    const val MSG_EXPORT_FAILED: String = PREFIX + "msg.export_failed"

    /** 检测工具右键到的不是多方块控制器。 */
    const val MSG_NOT_CONTROLLER: String = PREFIX + "msg.not_controller"

    /** 控制器没有结构定义（老工程的 `gtetcore.structure_detect.no_pattern`）。 */
    const val MSG_NO_PATTERN: String = PREFIX + "msg.no_pattern"

    /** 检测失败：`<错误处数>` + `<坐标列表>`。 */
    const val MSG_DETECT_FAILED: String = PREFIX + "msg.detect_failed"

    /** 刷新工具：`Structure recheck triggered: <机器名>`。 */
    const val MSG_RECHECK: String = PREFIX + "msg.recheck"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(
            TOOLTIP_SELECT,
            "Right-click a block: the 1st click pins the start corner, later clicks drag the opposite corner",
            "右键方块：第一下钉住起点，之后每次右键拖动对角终点（可扩可缩）"
        )
        LangUtil.add(TOOLTIP_CLEAR, "Sneak + right-click to clear the selection", "潜行右键清除选区")
        LangUtil.add(TOOLTIP_OPEN_PANEL, "Right-click air to open the export panel", "右键空气打开导出面板")
        LangUtil.add(
            TOOLTIP_RECHECK,
            "Sneak + right-click a multiblock controller to force a structure recheck",
            "潜行右键多方块控制器强制重检结构"
        )
        LangUtil.add(
            TOOLTIP_DETECT_1,
            "Right-click a multiblock controller to run a structure check",
            "右键多方块控制器当场跑一遍结构检测"
        )
        LangUtil.add(
            TOOLTIP_DETECT_2,
            "Bad blocks are boxed in; the boxes fade after the configured time",
            "错误位置会画出线框，按配置的时间自动消失"
        )

        LangUtil.add(PANEL_SIZE, "Size: X:%s Y:%s Z:%s", "尺寸: X:%s Y:%s Z:%s")
        LangUtil.add(PANEL_DIR, "Dir: +%s | %s | %s", "方向: +%s | %s | %s")
        LangUtil.add(PANEL_START, "Start: %s", "起点: %s")
        LangUtil.add(PANEL_END, "End: %s", "终点: %s")
        LangUtil.add(PANEL_EMPTY, "-", "-")
        LangUtil.add(BTN_EXPORT, "Export", "导出")
        LangUtil.add(BTN_ROT_X, "Rot X", "绕 X 转")
        LangUtil.add(BTN_ROT_Y, "Rot Y", "绕 Y 转")
        LangUtil.add(BTN_ROT_Z, "Rot Z", "绕 Z 转")

        LangUtil.add(MSG_START, "Start: %s", "起点: %s")
        LangUtil.add(MSG_RANGE, "%s -> %s | Size: %sx%sx%s", "%s -> %s | 尺寸: %sx%sx%s")
        LangUtil.add(MSG_CLEARED, "Selection cleared", "选区已清除")
        LangUtil.add(MSG_EXPORTED, "Exported to %s", "已导出到 %s")
        LangUtil.add(
            MSG_EXPORT_DISABLED,
            "Structure export is disabled (dev.exportModeEnabled = false)",
            "结构导出已在配置里关闭（dev.exportModeEnabled = false）"
        )
        LangUtil.add(MSG_NO_SELECTION, "No selection yet", "还没有选区")
        LangUtil.add(MSG_EXPORT_FAILED, "Export failed: %s", "导出失败: %s")
        LangUtil.add(MSG_NOT_CONTROLLER, "Not a multiblock controller", "这不是多方块控制器")
        LangUtil.add(MSG_NO_PATTERN, "No structure defined", "无结构定义")
        LangUtil.add(
            MSG_DETECT_FAILED,
            "Structure check failed: %s bad position(s) at %s",
            "结构检测失败: %s 处错误，位置 %s"
        )
        LangUtil.add(MSG_RECHECK, "Structure recheck triggered: %s", "已触发多方块结构重检: %s")
    }
}
