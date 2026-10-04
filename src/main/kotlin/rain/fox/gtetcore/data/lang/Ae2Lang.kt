package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.util.lang.LangUtil

/**
 * AE2 集成那一族（标签过滤面板 + 库存件 tooltip）的**全部**语言键。
 *
 * 文案逐字沿用老工程 `ETTagFilterHatches.registerLang()` 里的那一批（键名换成 `gtetscore.` 前缀），
 * 所以中英两边与玩家已经见过的一致。
 *
 * ⚠️ 说明行刻意写得短：MUI 的文本控件**不换行**，写长了会画出浮层外。
 * 共享开关的 4 条 tooltip 不受这个限制（tooltip 自己会折行），所以那里把语义写全。
 *
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成。
 *
 * @author rain fox
 */
object Ae2Lang {

    private const val PREFIX = "gtetscore.machine.et_tag_filter."

    /** 浮层标题，也是右侧配置列那个按钮的 tooltip。 */
    const val TITLE: String = PREFIX + "title"

    /** 二合一件的流体侧那块面板的标题（一台机器两块面板，标题要能分开）。 */
    const val TITLE_FLUIDS: String = PREFIX + "title.fluids"

    /** 白名单行标题（留空 = 不限制）。 */
    const val WHITE: String = PREFIX + "white"

    /** 黑名单行标题（留空 = 不限制）。 */
    const val BLACK: String = PREFIX + "black"

    /** 定量行标题（0 = 不限制）。 */
    const val BATCH: String = PREFIX + "batch"

    /** 多方块共享开关行标题。 */
    const val SHARE: String = PREFIX + "share"

    /** 共享开关的「开」状态文字（紧跟开关右侧）。 */
    const val SHARE_ON: String = PREFIX + "share.on"

    /** 共享开关的「关」状态文字。 */
    const val SHARE_OFF: String = PREFIX + "share.off"

    /** 开关悬停说明 0：这个开关管什么。 */
    const val SHARE_TIP_0: String = PREFIX + "share.tip.0"

    /** 开关悬停说明 1：关（隔离）方向。 */
    const val SHARE_TIP_1: String = PREFIX + "share.tip.1"

    /** 开关悬停说明 2：开（允许共享）方向。 */
    const val SHARE_TIP_2: String = PREFIX + "share.tip.2"

    /** 开关悬停说明 3：时序（什么时候生效）。 */
    const val SHARE_TIP_3: String = PREFIX + "share.tip.3"

    /** 说明行 0：运算符。 */
    const val HINT_0: String = PREFIX + "hint.0"

    /** 说明行 1：`,` 与 `#` 的便利写法。 */
    const val HINT_1: String = PREFIX + "hint.1"

    /** 说明行 2：幻影槽用法。 */
    const val HINT_2: String = PREFIX + "hint.2"

    /** 说明行 3：留空语义与定量 / 保底的冲突。 */
    const val HINT_3: String = PREFIX + "hint.3"

    /**
     * 两件标签库存件共用的**功能说明** tooltip（跟在 GTM 那两行名称说明之后）。
     *
     * 方块 tooltip 键，不是面板键 —— 键名沿用老工程，中英一字不差。
     */
    const val TOOLTIP: String = PREFIX + "tooltip"

    /**
     * 「多方块共享」那条 tooltip 的键（两件共用）。
     *
     * ⚠️ 两件的共享开关**默认关（隔离）**、可在「标签过滤」面板里切换，所以光留 GTM 的
     * `gtceu.part_sharing.disabled`（= "Multiblock Sharing §4Disabled"）会让玩家以为改不了 ——
     * 那条保留（它描述的正是默认状态），后面再补这一条说明「可以切」。
     */
    const val SHARE_TOOLTIP: String = PREFIX + "share.tooltip"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(TITLE, "Tag Filter", "标签过滤")
        LangUtil.add(TITLE_FLUIDS, "Tag Filter (Fluids)", "标签过滤（流体）")
        LangUtil.add(WHITE, "Whitelist (blank = no limit)", "白名单（留空 = 不限制）")
        LangUtil.add(BLACK, "Blacklist (blank = no limit)", "黑名单（留空 = 不限制）")
        LangUtil.add(BATCH, "Pull per batch (0 = no limit)", "每次拉取量（0 = 不限制）")
        LangUtil.add(HINT_0, "Ops: & | ! ^ ( ) *", "运算符 & | ! ^ ( ) *")
        LangUtil.add(HINT_1, "Also accepts , and #", "也认 , 与 # 前缀")
        LangUtil.add(HINT_2, "Phantom slot fills tags", "幻影槽放样本自动填标签")
        LangUtil.add(HINT_3, "N must be >= min count", "N 需不小于保底数量")

        // 两条方块 tooltip（键在 ETTagFilterHatches 里被两件库存件引用）
        LangUtil.add(TOOLTIP, "AE tag filtering + batch pull", "AE 标签过滤 + 定量拉取")
        LangUtil.add(
            SHARE_TOOLTIP,
            "Multiblock sharing: isolated by default, toggle in the Tag Filter panel",
            "多方块共享：默认隔离，可在「标签过滤」面板里切换"
        )

        LangUtil.add(SHARE, "Multiblock sharing", "多方块共享")
        LangUtil.add(SHARE_ON, "Allowed", "允许共享")
        LangUtil.add(SHARE_OFF, "Isolated", "隔离")
        LangUtil.add(
            SHARE_TIP_0,
            "Whether other multiblocks may occupy this part",
            "本件能不能被别的多方块占用"
        )
        LangUtil.add(
            SHARE_TIP_1,
            "Off (isolated): if this part already belongs to a formed multiblock, another structure's check fails here. Prevents recipe mixups.",
            "关（隔离）：本件已属于某个已成型多方块时，别的结构检查到这一格就判失败 —— 防止两个结构串配方"
        )
        LangUtil.add(
            SHARE_TIP_2,
            "On (allowed): another structure must be re-formed (checked again) to take this part; already formed structures do not change by themselves.",
            "开（允许共享）：别的结构要重新成型（重新检查一次结构）才会占用本件；已成型结构不会自己变化"
        )
        LangUtil.add(
            SHARE_TIP_3,
            "Toggling re-checks this part's own multiblocks at once; the change takes effect on structure re-check.",
            "拨动开关会立刻让本件所属的多方块复检一次；改动在结构重新检查后生效"
        )
    }
}
