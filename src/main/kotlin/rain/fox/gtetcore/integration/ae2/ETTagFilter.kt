package rain.fox.gtetcore.integration.ae2

import appeng.api.stacks.AEFluidKey
import appeng.api.stacks.AEItemKey
import appeng.api.stacks.AEKey
import com.gregtechceu.gtceu.utils.TagExprFilter
import com.gregtechceu.gtceu.utils.TagExprFilter.TagExprParser.MatchExpr
import it.unimi.dsi.fastutil.objects.Object2BooleanMap
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap
import net.minecraft.tags.TagKey
import java.util.stream.Stream

/**
 * 「标签表达式」判定器：白名单 + 黑名单两条表达式，把 [AEKey]（可以是物品也可以是流体）判成「放行 / 不放行」。
 *
 * 表达式解析本身复用 GTM 的 `TagExprFilter`（就是 GTM 标签过滤器盖子用的那一套），
 * 所以运算符语义与 GTM 完全一致：`&`（与）`|`（或）`!`（非）`^`（异或）`( )`（分组），
 * 以及标签路径末尾的 `*` 通配。另外本类额外接受两种**书写便利**：
 *
 * - `#` 前缀一律忽略（`#forge:ingots` 与 `forge:ingots` 等价）。⚠️ GTM 原版表达式**不认** `#`，
 *   直接写进 `TagExprFilter` 会变成永真不命中的字面量，所以必须在本类里先剥掉；
 * - `,` 当作 `|` 的简写（多条标签并排写）。
 *
 * ## 判定语义
 *
 * - 白名单、黑名单**都留空** → 完全不过滤（等价于 GTM 原版库存总线，装上去就能用）；
 * - 只填黑名单 → 除了命中黑名单的都放行；
 * - 填了白名单 → 必须命中白名单，且不得命中黑名单。
 *
 * ## ⚠️ 表达式写错时的行为
 *
 * `TagExprFilter.parseExpression`（TagExprFilter.java）对非法输入**不抛异常**：它要么返回 `null`
 * （空串、只剩运算符），要么返回一棵「怎么判都是 false」的表达式树（如 `(`、`!!!`、
 * 括号不配对）。所以写坏的表达式 = 一个都拉不进来（fail-closed），而不是崩溃。
 * 一个例外要记住：合法但**不存在**的标签（拼错、或该整合包没装出这个标签）同样是不命中 —— 两侧都填了
 * 却一件也拉不进来时，先怀疑标签名拼错。
 *
 * ## 缓存
 *
 * 解析结果（`MatchExpr`）只在两条原始字符串真的变了时重算，**绝不在每次判定时重新解析字符串**；
 * 另外按 [AEKey] 缓存判定结果（标签集合要从 ItemStack / FluidStack 现取，取一次就够）。
 * 缓存上限见 [CACHE_LIMIT]，超出直接整体清空（AE2 的 key 总量是有限的，正常玩不会触顶）。
 *
 * @author rain fox
 */
class ETTagFilter {

    private val results: Object2BooleanMap<AEKey> = Object2BooleanOpenHashMap()

    private var whiteSource = ""
    private var blackSource = ""

    /** null = 该侧没有可用的表达式（留空，或写成了只剩运算符的非法表达式）。 */
    private var white: MatchExpr? = null
    private var black: MatchExpr? = null

    /**
     * 换一套表达式。**两条原始字符串都没变时什么都不做**（这是热路径：面板每次改字、每次读档都会调）。
     *
     * @param white 白名单原始表达式（可为 null，按空串处理）
     * @param black 黑名单原始表达式（可为 null，按空串处理）
     */
    fun set(white: String?, black: String?) {
        val w = white ?: ""
        val b = black ?: ""
        if (w == this.whiteSource && b == this.blackSource) return
        this.whiteSource = w
        this.blackSource = b
        this.white = TagExprFilter.parseExpression(normalize(w))
        this.black = TagExprFilter.parseExpression(normalize(b))
        results.clear()
    }

    /** 两侧都留空 = 本判定器完全不拦东西。 */
    fun isDisabled(): Boolean {
        return white == null && black == null
    }

    /**
     * 判定一个 AE key 是否放行。
     *
     * @param what AE2 的物品 / 流体 key
     * @return true = 允许被库存逻辑拉进来
     */
    fun test(what: AEKey?): Boolean {
        if (what == null) return false
        if (isDisabled()) return true
        if (results.containsKey(what)) return results.getBoolean(what)
        val allowed = matches(white, what, true) && !matches(black, what, false)
        if (results.size >= CACHE_LIMIT) results.clear()
        results.put(what, allowed)
        return allowed
    }

    companion object {

        /** 按 key 缓存判定结果的上限，超出整体清空。 */
        private const val CACHE_LIMIT = 4096

        /** 自动填表达式时最多取多少个标签（避免幻影槽塞出来的字符串超过输入框长度上限）。 */
        private const val AUTO_FILL_TAG_LIMIT = 16

        /**
         * 单侧判定。
         *
         * @param expr            该侧的表达式树，null 表示该侧留空
         * @param what            AE key
         * @param blankMeansAllow 该侧留空时的取值：白名单留空 = 放行（true），黑名单留空 = 不命中（false）
         */
        private fun matches(expr: MatchExpr?, what: AEKey, blankMeansAllow: Boolean): Boolean {
            if (expr == null) return blankMeansAllow
            // AEKey → ItemStack / FluidStack，再交给 GTM 自己的判定（它内部会把标签集合取出来喂给表达式树）
            if (what is AEItemKey) return TagExprFilter.tagsMatch(expr, what.toStack(1))
            if (what is AEFluidKey) return TagExprFilter.tagsMatch(expr, what.toStack(1))
            return false
        }

        /**
         * 把一个 AE key 判成「属于哪些标签」，给面板的幻影槽用（放一个样本进来，把它的标签摊开写成表达式）。
         *
         * ⚠️ 取标签的路子与 GTM 自己的 `TagExprFilter.tagsMatch` 保持一致（TagExprFilter.java：物品走
         * `ItemStack#getTags()`，流体走 `FluidStack#getFluid().defaultFluidState().getTags()`）。
         * 不认识的非物品 / 非流体 key 返回空表。
         *
         * @return 该 key 的标签 id 列表（已去重、已排序），可能为空
         */
        @JvmStatic
        fun tagIds(what: AEKey?): List<String> {
            return tagStream(what)
                .map { tag -> tag.location().toString() }
                .distinct()
                .sorted()
                .toList()
        }

        /**
         * 把一个 AE key 的标签摊成一条可直接用的白/黑名单表达式：多个标签用 `|` 连接（命中任意一个即算命中）。
         *
         * 拿去铺开的标签最多 [AUTO_FILL_TAG_LIMIT] 个（按字母序截断，截在标签之间，
         * 所以结果**一定是一条合法表达式**）；样本没有标签时返回空串。
         * 这是给玩家的**起点**而不是终点：面板里的输入框随便改，删掉多余的、换成 `&` / `!` 都行。
         */
        @JvmStatic
        fun expressionOf(what: AEKey?): String {
            val ids = tagIds(what)
            if (ids.isEmpty()) return ""
            return java.lang.String.join(
                " | ",
                if (ids.size > AUTO_FILL_TAG_LIMIT) ids.subList(0, AUTO_FILL_TAG_LIMIT) else ids
            )
        }

        /**
         * 把玩家书写的表达式整理成 `TagExprFilter` 能吃的形式：忽略 `#`、把 `,` 当 `|`。
         *
         * ⚠️ 只做这两件事，运算符与括号原样透传（GTM 那套 `& | ! ^ ( ) *` 的语义不许在这里被破坏）。
         */
        @JvmStatic
        fun normalize(raw: String?): String {
            if (raw == null) return ""
            // 只去掉首尾码点 <= U+0020 的字符
            return raw.replace(',', '|').replace("#", "").trim { it <= ' ' }
        }

        /** 取该 key 的标签流；类型不认识时给空流。 */
        private fun tagStream(what: AEKey?): Stream<out TagKey<*>> {
            if (what is AEItemKey) {
                return what.toStack(1).tags
            }
            if (what is AEFluidKey) {
                return what.toStack(1).fluid.defaultFluidState().tags
            }
            return Stream.empty<TagKey<*>>()
        }
    }
}
