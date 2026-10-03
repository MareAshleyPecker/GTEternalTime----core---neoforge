package rain.fox.gtetcore.common.item.terminal

import com.gregtechceu.gtceu.api.multiblock.MultiPredicate
import com.gregtechceu.gtceu.api.multiblock.PredicateContext
import com.gregtechceu.gtceu.api.multiblock.pattern.BlockPattern
import com.gregtechceu.gtceu.api.multiblock.pattern.PatternState
import com.gregtechceu.gtceu.api.multiblock.predicates.BasePredicate
import com.gregtechceu.gtceu.api.multiblock.util.BlockInfo
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * 结构规划器 —— 把 [BlockPattern] 摊成「每个格子要放什么」的清单。
 *
 * 只算坐标与候选、不改世界：同一组候选归成一个「分级组」，线圈 / 聚变玻璃 / 火箱 / 分级机壳
 * 都能被自动识别成组，玩家在终端里按组挑具体方块即可，不需要为每种方块硬编码等级枚举。
 *
 * ⚠️ GTM 8.0.0 把图案系统整个重写了：老版靠反射读的七个私有字段
 * `blockMatches / fingerLength / thumbLength / palmLength / centerOffset / aisleRepetitions / structureDir`
 * **一个都不存在**了，`MultiblockState / TraceabilityPredicate / SimplePredicate` 也一并删除。
 * 新版模型是 `PatternSlice[] + int[] dimensions + OriginOffset + RelativeDirection[] + Char2ObjectMap<MultiPredicate>`，
 * 且这些 getter 全是 public —— 所以本文件**不再需要任何反射**。
 *
 * ⚠️ 坐标一律走新版自己的 `OriginOffset#apply` 与 `RelativeDirection#getRelativeFacing`
 * （`BlockPattern#checkSlice` 用的就是这两句），而不是把老的 `setActualRelativeOffset` 手抄一遍：
 * 只有用同一套函数，算出来的世界坐标才和结构检测严格对齐。
 */
object StructureBuildPlanner {

    /** 一组共享同一候选列表的格子（通常就是一个「分级组」）。 */
    data class Group(
        val key: String,
        val candidates: List<ItemStack>,
    ) {

        /** 是否真的有多档可选（多档才是「分级方块」）。 */
        fun tiered(): Boolean {
            return candidates.size > 1
        }

        /** 默认取第一个候选。 */
        fun defaultChoice(): ItemStack {
            return if (candidates.isEmpty()) ItemStack.EMPTY else candidates[0]
        }
    }

    /** 规划结果。 */
    data class Plan(
        val slots: List<StructureSlot>,
        val groups: Map<String, Group>,
    ) {

        /** 需要放置的格子（世界为空的位置）。 */
        fun emptySlots(): List<StructureSlot> {
            return slots
        }

        fun tieredGroups(): List<Group> {
            return groups.values.stream().filter { it.tiered() }.toList()
        }
    }

    /**
     * 规划选项（[planCells] 用）。
     *
     * @param repeatCount     可重复层的重复次数；只对「最小 ≠ 最大」的层生效
     * @param flip            坐标映射的镜像位（与机器自身的 `isFlipped()` 取或）
     * @param includeOccupied 是否把「已经有方块」的格子也收进清单
     */
    data class Options(
        val repeatCount: Int,
        val flip: Boolean,
        val includeOccupied: Boolean,
    ) {

        companion object {

            /** 默认：按最小重复数展开、不额外镜像、只收空格子。 */
            @JvmField
            val DEFAULT = Options(0, flip = false, includeOccupied = false)
        }
    }

    /**
     * 一个格子。
     *
     * @param occupied 这一格世界上已经有方块（`candidates` 此时是「谓词全部叶子谓词的候选并集」）
     * @param required 这一格的候选是「限次谓词的最小数量」逼出来的（必须放），而不是兜底挑出来的
     */
    data class Cell(
        val pos: BlockPos,
        val candidates: List<ItemStack>,
        val groupKey: String?,
        val occupied: Boolean,
        val required: Boolean,
    )

    /** [planCells] 的结果。 */
    data class CellPlan(
        val cells: List<Cell>,
        val groups: Map<String, Group>,
    )

    /**
     * 从谓词里挑出的候选。
     *
     * @param required 这批候选是「限次谓词的最小数量」逼出来的（这一格**必须**放），不是兜底挑的
     */
    data class Picked(
        val candidates: List<ItemStack>,
        val required: Boolean,
    )

    /**
     * 扫描结构，产出规划。
     *
     * @param pattern 控制器上的结构
     * @param state   该控制器的结构状态（GTM 8.0.0 里 `MultiblockState` 已由 [PatternState] 取代）
     * @param level   世界（用于判断哪些格子已经是空的）
     */
    @JvmStatic
    fun plan(pattern: BlockPattern, state: PatternState, level: Level): Plan {
        val cells = planCells(pattern, state, level, Options.DEFAULT)
        val slots = ArrayList<StructureSlot>()
        for (cell in cells.cells) {
            if (cell.occupied) continue
            slots.add(StructureSlot(cell.pos, cell.candidates, cell.groupKey))
        }
        return Plan(java.util.List.copyOf(slots), cells.groups)
    }

    /**
     * 与 [plan] 同一套遍历顺序与坐标映射，额外支持三件事（都要靠选项开启）：
     *
     * - 把「已经有方块」的格子也收进清单 —— 线圈替换模式与拆除模式必须看到这些格子，
     *   否则换不了线圈、也拆不掉方块；
     * - 可重复层的实际重复数 = 把 [Options.repeatCount] 夹到 [最小, 最大]（最小 == 最大时用该固定值）；
     * - 坐标映射的镜像位 = 选项 **或** 机器自身的 `isFlipped()`。
     */
    @JvmStatic
    fun planCells(pattern: BlockPattern, state: PatternState, level: Level, options: Options): CellPlan {
        val cells = ArrayList<Cell>()
        val groups = LinkedHashMap<String, Group>()

        val controller = state.controller ?: return CellPlan(emptyList(), emptyMap())
        val centerPos = controller.blockPos
        val facing = controller.frontFacing
        val upwardsFacing = controller.upwardsFacing
        val flipped = controller.isFlipped || options.flip

        // 限次谓词的计数存在 PatternState 自己的 PredicateContext 里（新版引擎的结构检测也读它）。
        // 先把世界塞进去：谓词的 test 要读当前 level / pos。
        val context = state.context
        context.updateLevel(level)

        val slices = pattern.slices
        val dimensions = pattern.dimensions
        val directions = pattern.directions

        // ⚠️ 三条轴向必须用 checkSlice 同一个函数算；镜像只作用于 LEFT/RIGHT 轴，这是新版的语义
        val sliceDir = directions[0].getRelativeFacing(facing, upwardsFacing, flipped)
        val stringDir = directions[1].getRelativeFacing(facing, upwardsFacing, flipped)
        val charDir = directions[2].getRelativeFacing(facing, upwardsFacing, flipped)

        // 图案原点的世界坐标 = 控制器位置 + 图案自身偏移（等价于 checkSlice 里的 startPos）
        val origin = BlockPos.MutableBlockPos(centerPos.x, centerPos.y, centerPos.z)
        pattern.offset.apply(origin, facing, upwardsFacing, flipped)

        val thumb = dimensions[1]
        val palm = dimensions[2]

        // 这一层在世界里沿 sliceDir 的滑移量：与 BasicSliceStrategy 累加 offset 的方式一致，
        // 即「前面所有层实际展开出的重复数之和」
        var sliceOffset = 0
        // 复用的游标，避免每格新建一个 MutableBlockPos
        val cursor = BlockPos.MutableBlockPos()

        for (c in slices.indices) {
            val slice = slices[c]
            // 可重复层：最小 == 最大时恒用该固定值；否则把设置里的重复次数夹到 [最小, 最大]
            val repMin = slice.minRepeats
            val repMax = slice.maxRepeats
            val reps = if (repMin == repMax) repMin
            else Math.max(repMin, Math.min(options.repeatCount, Math.max(repMax, repMin)))
            for (rep in 0 until reps) {
                // 每展开一层，层计数清零（与 checkSlice 里 context.clearLayerCounts() 同一时机）
                context.clearLayerCounts()
                for (b in 0 until thumb) {
                    for (a in 0 until palm) {
                        val predicate = pattern.predicates.get(slice.charAt(b, a))
                        // 没登记的字符 / 空气 / 任意方块：这一格什么都不用放，跳过
                        if (predicate == null || predicate.isAir || predicate.isAny) continue

                        cursor.set(origin.x, origin.y, origin.z)
                        cursor.move(sliceDir, sliceOffset)
                        cursor.move(stringDir, b)
                        cursor.move(charDir, a)
                        val pos = cursor.immutable()

                        // 已经有方块的位置不用管（和自动搭建一致：只补空格）；
                        // 但线圈替换 / 拆除要看这些格子，所以选项开着时也收进清单
                        if (!level.isEmptyBlock(pos)) {
                            // ⚠️ 已存在的方块照样要过一遍限次谓词的计数 —— 否则半成型结构里
                            // 「已经放好的仓室」不算数，规划器会重复补仓室、或误判最小数量已满足
                            context.updatePos(pos)
                            for (leaf in predicate.expand()) {
                                if (isLimited(leaf)) testLimited(leaf, context)
                            }
                            if (options.includeOccupied) {
                                val raw = rawCandidates(predicate)
                                if (raw.isNotEmpty()) {
                                    val key = TerminalItems.groupKey(raw)
                                    groups.getOrPut(key) { Group(key, raw) }
                                    cells.add(Cell(pos, raw, key, occupied = true, required = false))
                                }
                            }
                            continue
                        }

                        context.updatePos(pos)
                        val picked = candidatesOf(predicate, context)
                        val candidates = picked.candidates
                        if (candidates.isEmpty()) continue

                        val key = TerminalItems.groupKey(candidates)
                        groups.getOrPut(key) { Group(key, candidates) }
                        cells.add(Cell(pos, candidates, key, false, picked.required))
                    }
                }
                sliceOffset++
            }
        }

        // copyOf 必须写全限定名：Kotlin 的 kotlin.collections.List / Map 上没有这两个静态方法
        return CellPlan(java.util.List.copyOf(cells), java.util.Map.copyOf(groups))
    }

    /**
     * 谓词的「全部叶子谓词候选的并集」—— 不按限次顺序挑，就是要这一格**接受的全集**。
     * 拆除模式的防误删判定与线圈替换都用它。
     */
    private fun rawCandidates(predicate: MultiPredicate): List<ItemStack> {
        val blocks = LinkedHashSet<Block>()
        for (leaf in predicate.expand()) {
            for (info in leaf.candidates) blocks.add(info.blockState.block)
        }
        val candidates = ArrayList<ItemStack>()
        for (block in blocks) {
            if (block === Blocks.AIR) continue
            // 1.21 删掉了 Item.defaultInstance，直接用 ItemStack 包
            val stack = ItemStack(block.asItem())
            if (!stack.isEmpty) candidates.add(stack)
        }
        return candidates
    }

    /**
     * 「限次谓词」= 带任何 min/max 限制的叶子谓词。
     *
     * 老版 `TraceabilityPredicate` 把这类谓词单独放在 `limited` 列表里、其余放 `common`；
     * 新版只有一个扁平的 `BasePredicate` 列表，限制字段默认 -1，所以这里按字段回推同一个划分。
     */
    private fun isLimited(leaf: BasePredicate): Boolean {
        return leaf.minCount != -1 || leaf.maxCount != -1 ||
            leaf.minSliceCount != -1 || leaf.maxSliceCount != -1
    }

    /**
     * 老版 `SimplePredicate#testLimited` 的等价物：这一格上的方块算不算进这个限次谓词的计数。
     *
     * ⚠️ 计数只在「谓词自己的方块判定通过」时才 +1（与老版 `mergeInt(this, base ? 1 : 0, sum)` 一致），
     * 不能无条件加 —— 否则一个格子上所有限次谓词都会被算一次，最小数量会提前满足。
     */
    private fun testLimited(leaf: BasePredicate, context: PredicateContext): Boolean {
        return testGlobal(leaf, context) && testSlice(leaf, context)
    }

    private fun testGlobal(leaf: BasePredicate, context: PredicateContext): Boolean {
        if (leaf.minCount == -1 && leaf.maxCount == -1) return true
        val base = leaf.test(context)
        val count = if (base) context.incrementGlobalCount(leaf) else context.getGlobalCount(leaf)
        return if (leaf.maxCount == -1 || count <= leaf.maxCount) base else false
    }

    private fun testSlice(leaf: BasePredicate, context: PredicateContext): Boolean {
        if (leaf.minSliceCount == -1 && leaf.maxSliceCount == -1) return true
        val base = leaf.test(context)
        val count = if (base) context.incrementSliceCount(leaf) else context.getSliceCount(leaf)
        return if (leaf.maxSliceCount == -1 || count <= leaf.maxSliceCount) base else false
    }

    /**
     * 从谓词里挑出这一格的候选物品。
     *
     * 三个分支的顺序与判定条件**逐条照搬老版**（也就是 GTCEu 老 `autoBuild` 的挑法），
     * 顺序一动，搭建出来的仓室分布就变了。
     */
    private fun candidatesOf(predicate: MultiPredicate, context: PredicateContext): Picked {
        val leaves = predicate.expand()
        // ⚠️ 限次谓词按 minCount 升序排：老版 `TraceabilityPredicate#sort()` 就是这么排 `limited` 的
        // （`limited.sort(Comparator.comparingInt(a -> a.minCount))`），顺序决定了分支 ①/② 先挑中哪一组，
        // 新版列表是按 priority 排的，不补这一下就换了遍历顺序
        val limited = leaves.filter { isLimited(it) }.sortedBy { it.minCount }
        var infos: MutableList<BlockInfo>? = null
        var find = false

        // ① 优先补「本层最小数量」还没凑够的限次谓词
        for (limit in limited) {
            if (limit.minSliceCount > 0) {
                val curr = context.getSliceCount(limit)
                if (curr < limit.minSliceCount && (limit.maxSliceCount == -1 || curr < limit.maxSliceCount)) {
                    context.incrementSliceCount(limit)
                } else {
                    continue
                }
            } else {
                continue
            }
            infos = ArrayList(limit.candidates)
            find = true
            break
        }
        // ② 再补「全局最小数量」还没凑够的限次谓词
        if (!find) {
            for (limit in limited) {
                if (limit.minCount > 0) {
                    val curr = context.getGlobalCount(limit)
                    if (curr < limit.minCount && (limit.maxCount == -1 || curr < limit.maxCount)) {
                        context.incrementGlobalCount(limit)
                    } else {
                        continue
                    }
                } else {
                    continue
                }
                infos = ArrayList(limit.candidates)
                find = true
                break
            }
        }
        // ③ 兜底：把「还没到上限」的限次谓词与全部普通谓词的候选并起来
        if (!find) {
            for (limit in limited) {
                if (limit.maxSliceCount != -1 && context.getSliceCount(limit) == limit.maxSliceCount) {
                    continue
                }
                if (limit.maxCount != -1 && context.getGlobalCount(limit) == limit.maxCount) {
                    continue
                }
                context.incrementSliceCount(limit)
                context.incrementGlobalCount(limit)
                infos = appendAll(infos, limit.candidates)
            }
            for (common in leaves) {
                if (isLimited(common)) continue
                infos = appendAll(infos, common.candidates)
            }
        }

        val candidates = ArrayList<ItemStack>()
        if (infos != null) {
            for (info in infos) {
                if (info.nonAir()) {
                    val stack = info.itemStackForm
                    if (!stack.isEmpty) candidates.add(stack)
                }
            }
        }
        // find = true 表示这次是被「层 / 全局最小数量」逼出来的一格：这一格必须放，不能算作「多出来的仓室」
        return Picked(candidates, find)
    }

    /** 拼接两份候选：任一侧为空就原样返回另一侧（语义与老版的 `ArrayUtils.addAll` 一致）。 */
    private fun appendAll(a: MutableList<BlockInfo>?, b: List<BlockInfo>?): MutableList<BlockInfo>? {
        if (a == null) return if (b == null) null else ArrayList(b)
        if (b == null) return ArrayList(a)
        val out = ArrayList<BlockInfo>(a.size + b.size)
        out.addAll(a)
        out.addAll(b)
        return out
    }
}
