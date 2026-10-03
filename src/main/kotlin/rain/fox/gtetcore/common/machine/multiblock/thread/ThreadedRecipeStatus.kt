package rain.fox.gtetcore.common.machine.multiblock.thread

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.recipe.content.Content
import com.gregtechceu.gtceu.api.recipe.ingredient.IntProviderIngredient
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.data.lang.ThreadHatchLang
import kotlin.math.roundToLong

/**
 * [ThreadedRecipeLogic] 的**线程状态快照与显示文本**：把在跑的线程按「同一种配方」合并成组，
 * 再渲染成机器面板的文本行 / Jade 的 NBT。
 *
 * 显示口径：**一个配方组占两行** —— 第一行只放进度（Jade 那条就是进度条本身），第二行放这一组
 * 所有线程**加起来**的产出 + 线程条数 + 该组 EU/t；明细之前另有整机口径的合计两行。
 *
 * 两条显示路径共用本文件：机器面板在服务端求值后把组件同步给客户端，Jade 那边客户端手里没有线程表
 * （不是同步字段），所以 provider 只按 NBT 拼文本 —— [outputsText] / [countText] 两边共用。
 */
object ThreadedRecipeStatus {

    /**
     * 机器面板里最多列几条配方组。
     *
     * 上限的理由是**包大小**：这段文本由面板每 tick 在服务端求值后整表同步，而进度每 tick 都在动。
     */
    const val DISPLAY_LINES: Int = 10

    /**
     * 每条组行最多列几种产物，超出的用 [ThreadHatchLang.LANG_OUTPUT_MORE] 一句带过。
     *
     * 取 4 是照着研磨配方的上限来的（GTM `GTRecipeTypes#MACERATOR_RECIPES` 是 `setMaxIOSize(1, 4, 0, 0)`），
     * 更大的类型会落到那条「等 N 种」。
     */
    const val OUTPUTS_PER_LINE: Int = 4

    /**
     * 一个配方组的只读显示快照（服务端算：面板直接渲染，Jade 写进 NBT）。
     *
     * @param slot        组内**最小**槽位
     * @param threadCount 这一种配方占了几条线程
     * @param progress    组内最小槽位那条线程的进度（tick）
     * @param duration    同上那条线程的总时长（tick）
     * @param eutPerTick  这一组**实际吃的电**（EU/t）= 组内各线程之和
     * @param outputs     合并后的物品产出（已按 [OUTPUTS_PER_LINE] 截断）
     * @param hiddenKinds 被截断掉的产物种数（0 = 没截断）
     */
    data class GroupSnapshot(
        val slot: Int,
        val threadCount: Int,
        val progress: Int,
        val duration: Int,
        val eutPerTick: Long,
        val outputs: List<OutputSnapshot>,
        val hiddenKinds: Int,
    )

    /**
     * 一种产物的合并结果。
     *
     * @param stack   产物物品（**数量固定 1**，只用来取图标与名字）
     * @param min/max 一次机器周期该产出的数量区间（一般 min == max）
     * @param chanced 是否为**期望值**（内容带概率），显示时标 ≈
     */
    data class OutputSnapshot(val stack: ItemStack, val min: Long, val max: Long, val chanced: Boolean) {

        /** 产物显示名：传物品栈而不是翻译键，客户端自己就能把材料名解析对。 */
        val name: Component get() = stack.hoverName
    }

    /** [groupSnapshots] 的结果：截断后的组行 + 组总数（用于「还有 N 组」那一行）。 */
    data class GroupSnapshotPage(val groups: List<GroupSnapshot>, val totalGroups: Int)

    /**
     * 把在跑的线程按**同一种配方**合并成组（组内按槽位升序），组也按「组内最小槽位」升序。
     *
     * 只对前 [maxGroups] 组算产物明细，剩下的组只计数。
     */
    @JvmStatic
    fun groupSnapshots(logic: ThreadedRecipeLogic, maxGroups: Int): GroupSnapshotPage {
        val groups = ArrayList<MutableList<Pair<Int, ThreadedRecipeLogic.ThreadRec>>>()
        val byId = HashMap<ResourceLocation, MutableList<Pair<Int, ThreadedRecipeLogic.ThreadRec>>>()
        for ((slot, rec) in logic.runningThreadSlots) {
            val id = rec.recipe.getId()
            if (id == null) {
                // 与内核同一条判等：id 为 null 时退化成引用相等，而每条线程都是新建副本 → 各自成组
                val group = groups.firstOrNull {
                    ThreadedRecipeLogic.isSameRecipe(it[0].second.recipe, rec.recipe)
                }
                if (group != null) group.add(slot to rec) else groups.add(arrayListOf(slot to rec))
                continue
            }
            val existing = byId[id]
            if (existing != null) existing.add(slot to rec) else arrayListOf(slot to rec).also {
                byId[id] = it
                groups.add(it)
            }
        }
        return GroupSnapshotPage(groups.take(maxGroups).map { snapshotOf(it) }, groups.size)
    }

    private fun snapshotOf(group: List<Pair<Int, ThreadedRecipeLogic.ThreadRec>>): GroupSnapshot {
        val (slot, head) = group[0]
        val (outputs, hiddenKinds) = mergeOutputs(group)
        return GroupSnapshot(
            slot = slot,
            threadCount = group.size,
            // 进度取组内**最小槽位**那条线程：它正是镜像进基类单进度字段的那一条，两边口径对得上
            progress = head.progress,
            duration = head.duration,
            // 组内每条线程各扣各的电，所以该组实际耗电是相加
            eutPerTick = group.sumOf { eutPerTickOf(it.second.recipe) },
            outputs = outputs,
            hiddenKinds = hiddenKinds,
        )
    }

    /**
     * 一条线程**每 tick 实际吃的电**（EU/t）：GTM 现成的 `RecipeHelper#getRealEUt`
     * （有电输入取输入，否则取输出）。
     *
     * ⚠️ 不要再乘 `getTotalRuns()`：那是「这条线程一次跑几下配方运行」的计数，不是功率倍率。
     */
    private fun eutPerTickOf(recipe: GTRecipe): Long = RecipeHelper.getRealEUt(recipe).totalEU

    /** 整机耗电（EU/t）= **全部**在跑线程之和；不按显示行数截断（总量报「只显示的那些」就是假数）。 */
    @JvmStatic
    fun totalEutPerTick(logic: ThreadedRecipeLogic): Long =
        logic.runningThreadSlots.sumOf { eutPerTickOf(it.second.recipe) }

    /** 组内各线程的产物按物品合并求和（这些线程跑同一种配方，只是各自并行倍数不同）。 */
    private fun mergeOutputs(
        group: List<Pair<Int, ThreadedRecipeLogic.ThreadRec>>,
    ): Pair<List<OutputSnapshot>, Int> {
        val merged = ArrayList<Accumulated>()
        for ((_, rec) in group) {
            for (content in rec.recipe.getOutputContents(ItemRecipeCapability.CAP)) {
                val one = outputSnapshotOf(content, rec.recipe) ?: continue
                val existing = merged.firstOrNull { ItemStack.isSameItemSameComponents(it.key, one.key) }
                if (existing == null) merged.add(one) else {
                    existing.min += one.min
                    existing.max += one.max
                }
            }
        }
        val hidden = (merged.size - OUTPUTS_PER_LINE).coerceAtLeast(0)
        return merged.take(OUTPUTS_PER_LINE).map { OutputSnapshot(it.key, it.min, it.max, it.chanced) } to hidden
    }

    /**
     * 一条产物内容 × 这份配方 → 一次机器周期的数量区间。
     *
     * ⚠️ 概率产出（`chance < maxChance`）按**期望值**折算（口径同 GTM 的 `RecipeOutputProvider.java:95`）：
     * `数量 × getTotalRuns() × 概率`。8.0.0 已没有 `GTRecipeType#chanceFunction` / `getBoostedChance`，
     * 所以这里不再叠加概率加成。折算出来的数字带 `≈` 标记，不静默当成必定产出。
     *
     * 流体产出与 `chance == 0`（永不产出）的内容都不列。
     */
    private fun outputSnapshotOf(content: Content, recipe: GTRecipe): Accumulated? {
        if (content.chance <= 0) return null

        val sized = ItemRecipeCapability.CAP.of(content.content)
        val custom = sized.ingredient().customIngredient
        var min: Long
        var max: Long
        var icon: ItemStack

        if (custom is IntProviderIngredient) {
            // 区间产物（`IntProviderIngredient`）：数量取 provider 的上下界
            min = custom.countProvider.minValue.toLong()
            max = custom.countProvider.maxValue.toLong()
            icon = custom.maxSizeStack
        } else {
            icon = sized.ingredient().items.firstOrNull() ?: return null
            min = sized.count().toLong()
            max = min
        }
        if (icon.isEmpty) return null
        icon = icon.copyWithCount(1)

        val chanced = content.chance < content.maxChance
        if (chanced) {
            val factor = recipe.totalRuns.toDouble() * content.chance / content.maxChance
            min = (min * factor).roundToLong()
            max = (max * factor).roundToLong()
        }
        return Accumulated(icon, min, max, chanced)
    }

    /** 合并过程中的可变累加项（`key` 的 count 固定为 1，只用来判「同一种产物」）。 */
    private class Accumulated(val key: ItemStack, var min: Long, var max: Long, val chanced: Boolean)

    /** 产物列表 → 显示文本；面板与 Jade 共用，保证两处产物文本一模一样。 */
    @JvmStatic
    fun outputsText(outputs: List<OutputSnapshot>, hiddenKinds: Int): Component {
        if (outputs.isEmpty()) return Component.translatable(ThreadHatchLang.LANG_NO_OUTPUT)
        val text = Component.empty()
        outputs.forEachIndexed { index, output ->
            if (index > 0) text.append(Component.translatable(ThreadHatchLang.LANG_OUTPUT_SEP))
            text.append(output.name).append(" ").append(countText(output))
        }
        if (hiddenKinds > 0) text.append(Component.translatable(ThreadHatchLang.LANG_OUTPUT_MORE, hiddenKinds))
        return text
    }

    /** 数量文本：`×3`（定量）/ `×1~2`（区间）/ `≈4`（概率期望值；区间 + 概率时两者都在）。 */
    @JvmStatic
    fun countText(output: OutputSnapshot): Component {
        val count = if (output.min == output.max) {
            FormattingUtil.formatNumbers(output.min)
        } else {
            "${FormattingUtil.formatNumbers(output.min)}~${FormattingUtil.formatNumbers(output.max)}"
        }
        return Component.literal("${if (output.chanced) "≈" else "×"}$count")
    }

    /**
     * 把线程状态追加成机器 UI 的文本行（服务端调用）。
     *
     * 两道闸：客户端直接收手（线程表只在服务端有内容，读不到会显示成「在用 0」，比不显示更误导）；
     * 线程上限 ≤ 1 的机器不显示（那种机器退化成 GTM 原版的单配方机器）。
     */
    @JvmStatic
    fun appendDisplayLines(textList: MutableList<Component>, logic: ThreadedRecipeLogic) {
        if (logic.machine.level?.isClientSide == true) return

        val limit = logic.threadLimit
        if (limit <= 1) return

        val running = logic.runningThreadCount
        textList.add(Component.translatable(ThreadHatchLang.LANG_STATUS, limit, running))
        if (running <= 0) return

        textList.add(
            Component.translatable(
                ThreadHatchLang.LANG_TOTAL_RUNS,
                FormattingUtil.formatNumbers(logic.runningTotalRuns),
            )
        )
        textList.add(
            Component.translatable(
                ThreadHatchLang.LANG_TOTAL_EUT,
                FormattingUtil.formatNumbers(totalEutPerTick(logic)),
            )
        )

        val page = groupSnapshots(logic, DISPLAY_LINES)
        for (group in page.groups) {
            textList.add(Component.translatable(ThreadHatchLang.LANG_PROGRESS, group.slot, group.progress, group.duration))
            textList.add(
                Component.translatable(
                    ThreadHatchLang.LANG_OUTPUTS,
                    outputsText(group.outputs, group.hiddenKinds),
                    group.threadCount,
                    FormattingUtil.formatNumbers(group.eutPerTick),
                )
            )
        }
        if (page.totalGroups > page.groups.size) {
            textList.add(
                Component.translatable(
                    ThreadHatchLang.LANG_MORE,
                    page.totalGroups - page.groups.size,
                    page.groups.size,
                )
            )
        }
    }
}
