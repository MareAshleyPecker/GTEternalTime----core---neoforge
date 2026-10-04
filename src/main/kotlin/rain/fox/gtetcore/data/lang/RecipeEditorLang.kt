package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「配方编辑器」物品 + 三页面板的**全部**语言键。
 *
 * 老工程这套文案整段硬编码在 `RecipeEditorBehavior` / `RecipeCodeWriter` 里
 * （`"§7配方 id"` / `"§c[配方编辑器] 导出失败：…"` 这种），只有两处对话框标题挂在
 * `LangUtil.add` 上（`gtetcore.recipe_editor.count_title` / `fluid_amount_title`）。
 * 新工程统一收进本文件 —— 一是 1.21 的界面文案走 `Component.translatable` 才能跟随语言设置，
 * 二是 MUI 的 `Text.lang(key)` 本来就要键。
 *
 * 命名空间从老的 `gtetcore` 换成新的 `gtetscore`。
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成（en 由 registrate 的语言钩子写进 en_us，
 * cn 由 `ZhCnLangProvider` 写进 zh_cn）。
 *
 * @author rain fox
 */
object RecipeEditorLang {

    private const val PREFIX = "gtetscore.recipe_editor."

    // ── 物品 tooltip（物品名走登记处的 `LangUtil.ITEM_LANG`，这里不重复）──

    const val TOOLTIP_OPEN: String = PREFIX + "tooltip.open"
    const val TOOLTIP_KINDS: String = PREFIX + "tooltip.kinds"
    const val TOOLTIP_EXPORT: String = PREFIX + "tooltip.export"

    // ── 面板骨架 ──

    /** 面板标题。 */
    const val TITLE: String = PREFIX + "title"

    /** 顶部三个导航按钮。 */
    const val NAV_RECIPE: String = PREFIX + "nav.recipe"
    const val NAV_TYPES: String = PREFIX + "nav.types"
    const val NAV_CODE: String = PREFIX + "nav.code"

    // ── 页 1：配方字段 ──

    const val FIELD_RECIPE_ID: String = PREFIX + "field.recipe_id"
    const val FIELD_DURATION: String = PREFIX + "field.duration"
    const val FIELD_EUT: String = PREFIX + "field.eut"
    const val FIELD_FLUID_AMOUNT: String = PREFIX + "field.fluid_amount"
    const val FIELD_CIRCUIT: String = PREFIX + "field.circuit"

    /** 电压下拉按钮上的文字：`%s ▾`。 */
    const val TIER_BUTTON: String = PREFIX + "tier.button"

    /** 幽灵电路关闭时的占位。 */
    const val CIRCUIT_OFF: String = PREFIX + "circuit.off"

    // ── 页 1：四段槽位标题（参数依次是「实际用几个槽」与操作提示）──

    const val SECTION_ITEM_INPUT: String = PREFIX + "section.item_input"
    const val SECTION_FLUID_INPUT: String = PREFIX + "section.fluid_input"
    const val SECTION_ITEM_OUTPUT: String = PREFIX + "section.item_output"
    const val SECTION_FLUID_OUTPUT: String = PREFIX + "section.fluid_output"

    /** 物品槽的操作提示（接在数量后面）。 */
    const val HINT_ITEM: String = PREFIX + "section.hint_item"

    /** 流体槽的操作提示。 */
    const val HINT_FLUID: String = PREFIX + "section.hint_fluid"

    // ── 页 1：底部按钮 ──

    const val BTN_REFRESH: String = PREFIX + "button.refresh"
    const val BTN_EXPORT: String = PREFIX + "button.export"
    const val BTN_COPY: String = PREFIX + "button.copy"
    const val BTN_SHOW_CODE: String = PREFIX + "button.show_code"

    // ── 数量编辑行（老工程是「中键弹对话框」，MUI 版改成面板内联一行）──

    /** `编辑槽 #%s 的数量`。 */
    const val COUNT_TITLE_ITEM: String = PREFIX + "count.title_item"

    /** `编辑槽 #%s 的流体量`。 */
    const val COUNT_TITLE_FLUID: String = PREFIX + "count.title_fluid"

    /** 没有任何槽被选中时的占位。 */
    const val COUNT_NONE: String = PREFIX + "count.none"

    const val COUNT_APPLY: String = PREFIX + "count.apply"
    const val COUNT_CANCEL: String = PREFIX + "count.cancel"
    const val COUNT_TIP: String = PREFIX + "count.tip"

    // ── 页 2：配方种类 ──

    const val TYPES_TITLE: String = PREFIX + "types.title"

    /** GT 类型按钮上的文字：`GT: %s`。 */
    const val TYPES_GT_ENTRY: String = PREFIX + "types.gt_entry"

    // ── 页 3：代码 ──

    /** `代码预览（导出为 %s/<配方id>.kt）`。 */
    const val CODE_TITLE: String = PREFIX + "code.title"

    // ── 提示消息 ──

    /** `[配方编辑器] 已导出：%s`。 */
    const val MSG_EXPORTED: String = PREFIX + "msg.exported"

    /** `[配方编辑器] 导出失败：%s`。 */
    const val MSG_EXPORT_FAILED: String = PREFIX + "msg.export_failed"

    /** `[配方编辑器] 代码已复制到剪贴板`。 */
    const val MSG_COPIED: String = PREFIX + "msg.copied"

    // ── 配方种类显示名（`RecipeDraft.Kind#langKey` 引用的就是这几条）──

    const val KIND_CRAFTING_SHAPED: String = PREFIX + "kind.crafting_shaped"
    const val KIND_CRAFTING_SHAPELESS: String = PREFIX + "kind.crafting_shapeless"
    const val KIND_SMELTING: String = PREFIX + "kind.smelting"
    const val KIND_BLASTING: String = PREFIX + "kind.blasting"
    const val KIND_SMOKING: String = PREFIX + "kind.smoking"
    const val KIND_STONECUTTING: String = PREFIX + "kind.stonecutting"
    const val KIND_SMITHING: String = PREFIX + "kind.smithing"
    const val KIND_GT: String = PREFIX + "kind.gt"

    /** 幂等登记；必须在 mod 构造期调用一次（由 `CommonProxy.initLang` 调），早于 `runData`。 */
    @JvmStatic
    fun register() {
        LangUtil.add(
            TOOLTIP_OPEN,
            "Right-click air to open the visual recipe editor",
            "右键空气打开可视化配方编辑器"
        )
        LangUtil.add(
            TOOLTIP_KINDS,
            "Vanilla crafting stations + every GT recipe type",
            "支持原版工作台/熔炉系/锻造台/切石机 + 全部 GT 配方类型"
        )
        LangUtil.add(
            TOOLTIP_EXPORT,
            "Exports GT datagen code to %s",
            "导出 GT datagen 代码到 %s"
        )

        LangUtil.add(TITLE, "Recipe Editor", "配方编辑器")
        LangUtil.add(NAV_RECIPE, "Recipe", "① 配方")
        LangUtil.add(NAV_TYPES, "Type", "② 类型")
        LangUtil.add(NAV_CODE, "Code", "③ 代码")

        LangUtil.add(FIELD_RECIPE_ID, "Recipe id", "配方 id")
        LangUtil.add(FIELD_DURATION, "Duration (tick)", "时间 (tick)")
        LangUtil.add(FIELD_EUT, "Base EU/t", "基础耗电 (EU/t)")
        LangUtil.add(FIELD_FLUID_AMOUNT, "Fluid amount (mB)", "流体量 (mB)")
        LangUtil.add(FIELD_CIRCUIT, "Ghost circuit", "幽灵电路")
        LangUtil.add(TIER_BUTTON, "%s \u25be", "%s \u25be")
        LangUtil.add(CIRCUIT_OFF, "off", "关闭")

        LangUtil.add(SECTION_ITEM_INPUT, "Item input %s %s", "物品输入 %s %s")
        LangUtil.add(SECTION_FLUID_INPUT, "Fluid input %s %s", "流体输入 %s %s")
        LangUtil.add(SECTION_ITEM_OUTPUT, "Item output %s %s", "物品输出 %s %s")
        LangUtil.add(SECTION_FLUID_OUTPUT, "Fluid output %s %s", "流体输出 %s %s")
        LangUtil.add(
            HINT_ITEM,
            "(click a slot to place / right-click to clear / middle-click to set count)",
            "(点槽放物品 / 右键清空 / 中键改数量)"
        )
        LangUtil.add(
            HINT_FLUID,
            "(drag a fluid in / right-click to clear / middle-click to set mB)",
            "(拖入流体 / 右键清空 / 中键改量)"
        )

        LangUtil.add(BTN_REFRESH, "Refresh", "刷新")
        LangUtil.add(BTN_EXPORT, "Export to dir", "导出到目录")
        LangUtil.add(BTN_COPY, "Copy code", "复制代码")
        LangUtil.add(BTN_SHOW_CODE, "Show code", "看完整代码")

        LangUtil.add(COUNT_TITLE_ITEM, "Set count of slot #%s", "编辑槽 #%s 的数量")
        LangUtil.add(COUNT_TITLE_FLUID, "Set mB of slot #%s", "编辑槽 #%s 的流体量")
        LangUtil.add(COUNT_NONE, "Middle-click a slot to edit its count", "中键点一个槽来改它的数量")
        LangUtil.add(COUNT_APPLY, "Apply", "确定")
        LangUtil.add(COUNT_CANCEL, "Cancel", "取消")
        LangUtil.add(
            COUNT_TIP,
            "1 ~ 2147483647; the value is written to the slot on the server",
            "取值 1 ~ 2147483647；确认后写到服务端的槽里"
        )

        LangUtil.add(
            TYPES_TITLE,
            "Recipe kind (7 vanilla stations + every registered GT recipe type)",
            "配方种类（原版七种 + GT 全部已注册类型，含附属）"
        )
        LangUtil.add(TYPES_GT_ENTRY, "GT: %s", "GT: %s")

        LangUtil.add(CODE_TITLE, "Code preview (exported to %s/<id>.kt)", "代码预览（导出为 %s/<id>.kt）")

        LangUtil.add(MSG_EXPORTED, "[Recipe editor] Exported: %s", "[配方编辑器] 已导出：%s")
        LangUtil.add(MSG_EXPORT_FAILED, "[Recipe editor] Export failed: %s", "[配方编辑器] 导出失败：%s")
        LangUtil.add(MSG_COPIED, "[Recipe editor] Code copied to clipboard", "[配方编辑器] 代码已复制到剪贴板")

        LangUtil.add(KIND_CRAFTING_SHAPED, "Crafting (shaped)", "工作台（有序）")
        LangUtil.add(KIND_CRAFTING_SHAPELESS, "Crafting (shapeless)", "工作台（无序）")
        LangUtil.add(KIND_SMELTING, "Furnace", "熔炉")
        LangUtil.add(KIND_BLASTING, "Blast furnace", "高炉")
        LangUtil.add(KIND_SMOKING, "Smoker", "烟熏炉")
        LangUtil.add(KIND_STONECUTTING, "Stonecutter", "切石机")
        LangUtil.add(KIND_SMITHING, "Smithing table", "锻造台")
        LangUtil.add(KIND_GT, "GT recipe", "GT 配方")
    }
}
