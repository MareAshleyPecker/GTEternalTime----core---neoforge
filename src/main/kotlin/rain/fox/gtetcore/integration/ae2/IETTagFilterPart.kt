package rain.fox.gtetcore.integration.ae2

import appeng.api.stacks.AEKey
import appeng.api.stacks.GenericStack
import net.minecraft.nbt.CompoundTag
import java.util.function.Predicate

/**
 * 「带标签过滤的 ME 库存部件」共用的那一小块：白 / 黑两条标签表达式 + 一个可缓存的判定器。
 *
 * ⚠️ 实现类必须自己持有 [ETTagFilter] 实例（而不是每次判定 new 一个），否则「缓存解析结果」就没意义了。
 *
 * @author rain fox
 */
interface IETTagFilterPart {

    /** 本部件持有的判定器（实现类构造时 new 一次，之后一直复用）。 */
    fun getTagFilter(): ETTagFilter

    /** 白名单原始表达式（玩家写什么就是什么，不做归一化）。 */
    fun getTagWhite(): String

    /** 换白名单表达式。实现里必须顺手调 [applyTagFilter]。 */
    fun setTagWhite(expression: String)

    /** 黑名单原始表达式。 */
    fun getTagBlack(): String

    /** 换黑名单表达式。实现里必须顺手调 [applyTagFilter]。 */
    fun setTagBlack(expression: String)

    /** 把当前两条原始表达式喂给判定器（内部会跳过「没变」的情况）。 */
    fun applyTagFilter() {
        getTagFilter().set(getTagWhite(), getTagBlack())
    }

    /** 判定一个 AE key 是否放行。 */
    fun testTag(what: AEKey?): Boolean {
        return getTagFilter().test(what)
    }

    /**
     * 库存逻辑真正用的谓词：**标签放行 ∧ 不是别的仓已经配置过的东西**。
     *
     * ⚠️ 后半截不能省：GTM 的 `IMEStockingPart.addedToController`
     * （IMEStockingPart.java:10-20）默认就是把自动拉取谓词设成「不同仓去重」检查，
     * 我们覆写它时必须把这条语义组合回来，否则同一个多方块里两个库存总成会配置到同一种物品。
     *
     * @param distinctCheck GTM 那边「该 stack 是否已配置在别的库存部件上」的检查
     */
    fun tagAutoPullTest(distinctCheck: Predicate<GenericStack>): Predicate<GenericStack> {
        return Predicate { stack -> testTag(stack.what()) && !distinctCheck.test(stack) }
    }

    /** 把两条表达式写进给定 tag（数据棒 / 拆方块保存共用一份键名）。 */
    fun writeTagFilter(tag: CompoundTag) {
        tag.putString(NBT_TAG_WHITE, getTagWhite())
        tag.putString(NBT_TAG_BLACK, getTagBlack())
    }

    /** 从给定 tag 读回两条表达式；缺键就不动（老存档 / 没存过的数据棒）。 */
    fun readTagFilter(tag: CompoundTag) {
        if (tag.contains(NBT_TAG_WHITE)) setTagWhite(tag.getString(NBT_TAG_WHITE))
        if (tag.contains(NBT_TAG_BLACK)) setTagBlack(tag.getString(NBT_TAG_BLACK))
    }

    companion object {

        // 接口体里不允许直接写 const val，常量统一放这里
        const val NBT_TAG_WHITE: String = "ETTagWhite"
        const val NBT_TAG_BLACK: String = "ETTagBlack"
    }
}
