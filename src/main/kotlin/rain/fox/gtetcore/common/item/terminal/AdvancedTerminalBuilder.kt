package rain.fox.gtetcore.common.item.terminal

import com.gregtechceu.gtceu.api.block.MetaMachineBlock
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
import com.gregtechceu.gtceu.api.multiblock.pattern.BlockPattern
import com.gregtechceu.gtceu.api.multiblock.pattern.PatternState
import com.gregtechceu.gtceu.common.block.CoilBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import org.apache.commons.lang3.ArrayUtils
import rain.fox.gtetcore.data.lang.AdvancedTerminalLang
import java.lang.reflect.Modifier

/**
 * 高级终端的搭建驱动 —— 潜行右键控制器后走完整条「选图案 → 规划 → 放置/拆除」链路。
 *
 * 设置只在开头读一次（[AdvancedTerminalSettings.read]），偏好表与已规划组表也各读一次，
 * 之后整轮都不再碰物品组件。与规划器的关系：[StructureBuildPlanner.planCells] 只在
 * 「线圈替换模式 / 拆除模式」下才把**已经有方块**的格子也吐出来，其余情况一律只处理空格子。
 *
 * ⚠️ AE 链接本阶段不移植（见各处 `TODO(AE 切片)`）：AE 一律视为「永远拿不到物品」，
 * 也就是老代码 `grid == null` 的那条分支，取料只走玩家背包。
 */
@Suppress("ConstPropertyName")
object AdvancedTerminalBuilder {

    /**
     * 单次搭建处理的格子数上限。
     *
     * ⚠️ **取舍说明**：这里**不**做分 tick 的渐进式搭建 —— 那需要跨 tick 保存
     * 「还没放完的清单」，玩家中途退出 / 区块卸载 / 控制器被拆都会让这份状态悬空，
     * 反而更容易出问题。改成「一次放完，超过上限就整体拒绝并提示」，
     * 上限内是一 tick 完成，超过就让玩家自己分几次搭（或改大这个常量）。
     */
    const val max_blocks_per_build: Int = 512

    private val FACINGS = arrayOf(
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    )
    private val FACINGS_H = arrayOf(
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    )

    /**
     * 一次搭建的结果。
     *
     * @param placed   放下的方块数
     * @param removed  拆掉的方块数
     * @param missing  没找到来源（背包里没有）的格子数
     * @param rejected 因为超过 [max_blocks_per_build] 而整体没执行
     */
    data class Result(
        @get:JvmName("placed") val placed: Int,
        @get:JvmName("removed") val removed: Int,
        @get:JvmName("missing") val missing: Int,
        @get:JvmName("rejected") val rejected: Boolean,
    ) {

        companion object {

            /** 什么都没做。 */
            @JvmField
            val NONE = Result(0, 0, 0, false)
        }
    }

    /**
     * 入口：对控制器执行一次搭建（或拆除）。
     *
     * @param player        操作者
     * @param terminal      手上的高级终端（设置 / 偏好 / 规划缓存都在它的数据组件里）
     * @param controllerPos 被潜行右键的方块位置
     */
    @JvmStatic
    fun run(player: Player, terminal: ItemStack, controllerPos: BlockPos): Result {
        val level = player.level()
        // ⚠️ GTM 8.0.0 删掉了 `IMultiController` 接口与 `MetaMachine#getMachine(Level, BlockPos)` 的旧形态：
        // 控制器自己就是方块实体，直接按具体类判型。
        val controller = MetaMachine.getMachine(level, controllerPos) as? MultiblockControllerMachine
            ?: return Result.NONE

        val settings = AdvancedTerminalSettings.read(terminal)
        // 图案取不到（没有图案 / 不是规划器认识的具体图案）：整次调用什么都不做
        val pattern: BlockPattern = selectPattern(controller, settings.module) ?: return Result.NONE
        // 老版 `IMultiController#getMultiblockState()` → 新版 `PatternState`（默认结构的那一份）
        val state: PatternState = controller.defaultPatternState ?: return Result.NONE
        // ⚠️ 必须补这一句绑定：老版 `MultiblockState#getController()` 是「按 controllerPos 从世界里
        //    现场查、查到再缓存进 lastController」的惰性解析，所以老代码不依赖「跑过一次结构检查」；
        //    新版 `PatternState.controller` 只由结构检查写入，而检查是 onLoad 之后第 2 tick 才排上的
        //    —— 不绑的话，刚放下 / 刚读档的控制器会规划出空清单，终端看起来「点了没反应」。
        //    写入的值与结构检查写的是同一对（this, getBlockPos），已检查过的机器上等于没写。
        state.setController(controller, controller.blockPos)

        val formed = controller.isFormed

        // 拆除模式：成型与否都执行，结束后清一次计数缓存，不请求重检
        if (settings.demolition) {
            val result = execute(player, terminal, settings, controller, pattern, state,
                demolition = true,
                replaceCoil = false
            )
            clearStateCache(state)
            return result
        }
        // 未成型：直接搭
        if (!formed) {
            return execute(player, terminal, settings, controller, pattern, state,
                demolition = false,
                replaceCoil = false
            )
        }
        // 已成型 + 线圈替换：搭完额外让部件重新挂载
        if (controller is WorkableMultiblockMachine && settings.replaceCoil) {
            val result = execute(player, terminal, settings, controller, pattern, state,
                demolition = false,
                replaceCoil = true
            )
            controller.onPartUnload()
            return result
        }
        // 已成型又没开线圈替换：整次调用空转
        return Result.NONE
    }

    /**
     * 清一次结构检查的计数与每格缓存 —— 老版 `MultiblockState#clearCache()`。
     *
     * ⚠️ 新版这两样东西分了家：限次谓词的计数在 [PatternState.getContext] 上，
     * 每格的 `BlockInfo` 缓存在 [PatternState.getCache] 上（`clearCache()` 这个名字已经不存在）。
     * 只清这两处，**仍然不请求重检**（老代码就是这样）。
     */
    private fun clearStateCache(state: PatternState) {
        state.context.clearGlobalCounts()
        state.context.clearLayerCounts()
        state.cache.clear()
    }

    /**
     * 选这次的图案。
     *
     * `module == 0` 用控制器当前的主结构；`module == N > 0` 老版本取模块化多方块的第 N 档结构。
     *
     * TODO(模块化切片)：模块机（`ETModularMachine`）还没移植 —— 新版 GTM 的图案挂在
     *  `MultiblockMachineDefinition` 上、按 substructure 的名字索引，老版那种覆写 `getPattern()` 的写法没有了。
     *  所以模块档位暂时一律退回主结构，也就是老代码「取不到（不是模块机 / 档位非法 / 实现内部抛异常）
     *  就静默退回主结构」的那条分支。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun selectPattern(controller: MultiblockControllerMachine, module: Int): BlockPattern? {
        return try {
            // TODO(模块化切片)：module > 0 时这里要改成取模块机的第 module 档结构
            // 规划器只吃具体的 BlockPattern（可展开图案 ExpandablePattern 到不了这里）；判型失败即本次空转
            controller.defaultStructurePattern as? BlockPattern
        } catch (ignored: Throwable) {
            null
        }
    }

    // ======================== 规划 + 执行 ========================

    private fun execute(player: Player, terminal: ItemStack, settings: AdvancedTerminalSettings,
                        controller: MultiblockControllerMachine, pattern: BlockPattern, state: PatternState,
                        demolition: Boolean, replaceCoil: Boolean): Result {
        val level = player.level()

        val includeOccupied = demolition || replaceCoil
        val options = StructureBuildPlanner.Options(settings.repeatCount, settings.flip, includeOccupied)
        val plan = StructureBuildPlanner.planCells(pattern, state, level, options)

        // 设置与偏好都只解析一次（见类注释）
        val prefs = TerminalSettings.getPreferences(terminal)
        val planned = TerminalSettings.plannedGroups(terminal)

        // 扫描写入：这次的规划结果 ∪ 静态组（静态组由 TerminalSettings 自己保证不被清掉）。
        // ⚠️ 老代码传的是 `MultiblockState#getPos()`（ogmr 分支给「结构检查游标」开的访问器，
        //    指向检查扫到的最后一格）；新版 `PatternState` 只暴露 controllerPos，
        //    而「控制器位置」才是这里真正要的东西（拆除时也要靠它排除控制器自己那一格）。
        TerminalSettings.cachePlan(terminal, controller.blockPos, level.dimension().location(), idGroups(plan))

        return if (demolition) demolish(player, level, plan, controller.blockPos)
        else place(player, terminal, settings, controller, state, level, plan, prefs, planned, replaceCoil)
    }

    private fun idGroups(plan: StructureBuildPlanner.CellPlan): Map<String, List<String>> {
        val groups = LinkedHashMap<String, List<String>>()
        for ((key, group) in plan.groups) {
            val ids = ArrayList<String>()
            for (candidate in group.candidates) {
                val id = TerminalItems.itemId(candidate)
                if (id != null) ids.add(id)
            }
            if (ids.isNotEmpty()) groups[key] = ids
        }
        return groups
    }

    // ======================== 放置 ========================

    @Suppress("UNUSED_PARAMETER")
    private fun place(player: Player, terminal: ItemStack, settings: AdvancedTerminalSettings,
                      controller: MultiblockControllerMachine, state: PatternState, level: Level,
                      plan: StructureBuildPlanner.CellPlan, prefs: Map<String, String>,
                      planned: Map<String, List<String>>, replaceCoil: Boolean): Result {
        val creative = player.isCreative

        // ① 逐格决议：这一格要放什么（候选加工 → 偏好 → 拆除旧线圈）
        val targets = ArrayList<BlockPos>()
        val wanted = ArrayList<ItemStack>()
        for (cell in plan.cells) {
            val current = level.getBlockState(cell.pos)
            if (cell.occupied) {
                // 只有「线圈替换模式」才碰已有方块，而且只换线圈
                if (!replaceCoil || current.block !is CoilBlock) continue
            }
            val candidates = effectiveCandidates(cell.candidates, settings)
            if (candidates.isEmpty()) continue
            // 组键仍用「谓词原始候选」算（面板键与搭建键才能对上），见 TerminalSettings.lookupPreference
            val slot = StructureSlot(cell.pos, candidates, cell.groupKey)
            val want = TerminalSettings.resolve(slot, prefs, planned)
            if (want.isEmpty) continue
            targets.add(cell.pos)
            wanted.add(want)
        }

        if (targets.size > max_blocks_per_build) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                targets.size, max_blocks_per_build), true)
            return Result(0, 0, 0, true)
        }
        if (targets.isEmpty()) return Result.NONE

        // ② 来源：按物品类型聚合需求，再一次性建背包索引
        val demand = TerminalItemSource.Demand()
        if (!creative) {
            for (stack in wanted) demand.add(stack)
        }
        val bag = if (creative) TerminalItemSource.Inventory.of(null)
        else TerminalItemSource.Inventory.of(player)
        // TODO(AE 切片)：老代码在这里按需求算「背包能提供多少」（fromBag），再
        //  AeGridLink.resolveGrid + AeDemand.simulate 把缺口交给 AE。AE 未移植 → 没有 network，
        //  缺口直接算缺料（等价老代码 grid == null 的分支）。

        // ③ 逐格放置（顺序与规划一致）
        var placed = 0
        var missing = 0
        val placedPositions = LinkedHashSet<BlockPos>()
        for (i in targets.indices) {
            val pos = targets[i]
            val want = wanted[i]
            val blockItem = want.item as? BlockItem ?: continue

            var reserved: TerminalItemSource.Inventory.SlotRef? = null
            if (!creative) {
                reserved = bag.reserve(want)
                if (reserved == null) {
                    // TODO(AE 切片)：老代码这里再问一次 AE（`ae.reserve`），问到了就记 `fromAe = true`、
                    //  放置成功后 `ae.commit`。AE 视为永远拿不到物品 → 直接算缺料。
                    missing++
                    continue
                }
                // 线圈替换：先把旧线圈收进背包（收不进就跳过这一格），再放新的 ——
                // 旧线圈占着那一格，不先挪走的话放置会被「位置不可替换」挡下来
                if (replaceCoil) {
                    val current = level.getBlockState(pos)
                    if (current.block is CoilBlock) {
                        val old = ItemStack(current.block.asItem())
                        if (!canPickUp(player, old)) {
                            if (reserved != null) bag.release(reserved)
                            missing++
                            continue
                        }
                        level.removeBlock(pos, false)
                        give(player, level, pos, old)
                    }
                }
            }

            val context = BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                want.copy(), BlockHitResult.miss(player.getEyePosition(0f), Direction.UP, pos))
            val result: InteractionResult = try {
                blockItem.place(context)
            } catch (ignored: Throwable) {
                InteractionResult.FAIL
            }

            // Java 原版是引用比较（`result == InteractionResult.FAIL`），这里保持引用比较
            if (result === InteractionResult.FAIL) {
                // 放失败：把预留还回去，物品一个都不扣
                if (reserved != null) bag.release(reserved)
                missing++
                continue
            }

            if (!creative) {
                if (reserved != null) bag.commit(reserved)
                // TODO(AE 切片)：老代码这里还有 `if (fromAe) ae.commit(want)`（AE 的记账，整轮结束再一次性真扣）
            }
            placed++
            placedPositions.add(pos)
        }

        // TODO(AE 切片)：老代码整轮结束后要 `ae.flush()`，把实际用掉的量「每种类型一次 MODULATE」真扣掉

        // 控制器自身就是方块实体，老版 `IMultiController#self()` 已删（原来还要兜 self() == null 的情况）
        val frontFacing = controller.frontFacing
        fixFacings(level, placedPositions, frontFacing)

        return Result(placed, 0, missing, false)
    }

    /**
     * 候选加工：线圈等级 + 无仓室模式。
     *
     * ⚠️ 线圈等级为 0 时**不**砍掉最高档（给出全部档位）—— 这样「面板里选的档」与
     * 「搭建时算出来的组键」天然一致，不需要靠回退匹配兜底。
     *
     * ⚠️ 无仓室模式：机器上**完全不放置仓室**，仓室格一律改放对应的机械方块
     * （`required` 格也不例外 —— 该模式要的就是一台没有仓室的壳）。
     */
    private fun effectiveCandidates(candidates: List<ItemStack>,
                                    settings: AdvancedTerminalSettings): List<ItemStack> {
        if (candidates.isEmpty()) return candidates
        var result = candidates

        if (settings.coilTier > 0 && anyCoil(candidates)) {
            val index = settings.coilTier.coerceAtMost(candidates.size) - 1
            result = listOf(candidates[index.coerceAtLeast(0)])
        }
        // 无仓室模式：仓室格改放对应的机械方块；该格只接受仓室时留空
        if (settings.noHatch) {
            val casing = result.firstOrNull { !isHatch(it) }
            return if (casing != null) listOf(casing) else emptyList()
        }
        return result
    }

    private fun anyCoil(candidates: List<ItemStack>): Boolean {
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem
            if (blockItem != null && blockItem.block is CoilBlock) {
                return true
            }
        }
        return false
    }

    /** 候选第 1 项是不是「多方块部件」（各种仓 / 总线 / 维护仓）。 */
    private fun isHatch(first: ItemStack): Boolean {
        val blockItem = first.item as? BlockItem ?: return false
        val block = blockItem.block
        // ⚠️ 老版 `IMachineBlock` 接口已删：8.0.0 里所有机器方块都是 MetaMachineBlock
        // （`MachineDefinition` 的 blockHolder 就是按 `? extends MetaMachineBlock` 收的）
        return block is MetaMachineBlock && partBlocks().contains(block)
    }

    /** 部件方块表缓存（懒加载，只算一次）。 */
    private var cachedPartBlocks: MutableSet<Block>? = null

    /**
     * 全部「多方块部件」方块。
     *
     * 来源是 [PartAbility] 各能力上登记过的方块（GTM 注册部件时都会登记能力值），
     * 只算一次并缓存。
     *
     * ⚠️ 这是个近似：只在 `PartAbility` 自己的静态字段里找能力实例，
     * 别的模组自建的能力实例收不到。8.0.0 里另有 `PartAbility.VALUES`（构造器登记的全量表）
     * 可以免掉反射，但那会把「别的模组的能力」也算进来 —— 那是行为变化，这里先保持与老代码一致。
     */
    private fun partBlocks(): Set<Block> {
        val cached = cachedPartBlocks
        if (cached != null) return cached

        val blocks = HashSet<Block>()
        for (field in PartAbility::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) || field.type != PartAbility::class.java) continue
            try {
                val ability = field.get(null) as PartAbility?
                if (ability != null) blocks.addAll(ability.allBlocks)
            } catch (ignored: ReflectiveOperationException) {
                // 个别能力取不到不影响其余
            }
        }
        cachedPartBlocks = blocks
        return blocks
    }

    // ======================== 拆除 ========================

    private fun demolish(player: Player, level: Level, plan: StructureBuildPlanner.CellPlan,
                         controllerPos: BlockPos): Result {
        val targets = ArrayList<BlockPos>()
        for (cell in plan.cells) {
            // 谓词为空 / 谓词是空气：候选被剔干净了，直接跳过
            if (cell.candidates.isEmpty()) continue
            // 谓词是控制器：控制器自己那一格永远不拆
            if (cell.pos == controllerPos) continue

            val current = level.getBlockState(cell.pos)
            if (current.isAir) continue
            // 不属于这一格谓词候选的方块一律不动 —— 防误删玩家方块的那道闸
            if (!matches(cell.candidates, current)) continue
            targets.add(cell.pos)
        }

        if (targets.size > max_blocks_per_build) {
            player.displayClientMessage(Component.translatable(AdvancedTerminalLang.BUILD_TOO_MANY,
                targets.size, max_blocks_per_build), true)
            return Result(0, 0, 0, true)
        }

        var removed = 0
        for (pos in targets) {
            val current = level.getBlockState(pos)
            if (current.isAir) continue
            val drop = ItemStack(current.block.asItem())
            if (level.removeBlock(pos, false)) {
                removed++
                give(player, level, pos, drop)
            }
        }
        return Result(0, removed, 0, false)
    }

    private fun matches(candidates: List<ItemStack>, current: BlockState): Boolean {
        val block = current.block
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem
            if (blockItem != null && blockItem.block === block) {
                return true
            }
        }
        return false
    }

    // ======================== 掉落 / 背包 ========================

    /** 拆下来的方块给玩家：先塞背包，塞不下就掉在原地（不会凭空消失）。 */
    private fun give(player: Player, level: Level, pos: BlockPos, stack: ItemStack) {
        if (stack.isEmpty) return
        val leftover = stack.copy()
        if (!player.addItem(leftover) && !leftover.isEmpty) {
            Block.popResource(level, pos, leftover)
        }
    }

    /** 先模拟塞一遍再决定要不要真塞（塞不下就什么都不做）。 */
    private fun canPickUp(player: Player, stack: ItemStack): Boolean {
        // 1.21 的能力查询直接返回处理器本身（拿不到就是 null），不再有 LazyOptional#orElse
        val handler: IItemHandler = player.getCapability(Capabilities.ItemHandler.ENTITY)
            ?: return false
        var probe = stack.copy()
        var i = 0
        while (i < handler.slots && !probe.isEmpty) {
            probe = handler.insertItem(i, probe, true)
            i++
        }
        return probe.isEmpty
    }

    // ======================== 朝向修正 ========================

    /**
     * 让线缆 / 仓室这些带朝向的方块朝向空位（与 GTCEu 自己的自动搭建一致）。
     *
     * 两处省着来（不改变放置结果）：
     * 1. 方块没有 `FACING` / `HORIZONTAL_FACING` 属性时直接跳过 ——
     *    一次 `getBlockEntity` 都不查；
     * 2. 有属性时整格只查一次 `getBlockEntity`（原来每次试探都要查，最多 6 次）。
     */
    private fun fixFacings(level: Level, placed: Collection<BlockPos>, frontFacing: Direction) {
        for (pos in placed) {
            val state = level.getBlockState(pos)
            val property: Property<Direction>
            val order: Array<Direction>
            if (state.hasProperty(BlockStateProperties.FACING)) {
                property = BlockStateProperties.FACING
                order = ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS)
            } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                property = BlockStateProperties.HORIZONTAL_FACING
                order = if (frontFacing.axis == Direction.Axis.Y) FACINGS_H
                else ArrayUtils.addAll(arrayOf(frontFacing), *FACINGS_H)
            } else {
                continue
            }

            // 老版是 `blockEntity is IMachineBlockEntity → metaMachine`；8.0.0 里那条接口没了，
            // MetaMachine.getMachine 干的就是「方块实体是 MetaMachine 就返回它」这一件事
            val machine: MetaMachine? = MetaMachine.getMachine(level, pos)

            var found: Direction? = null
            for (direction in order) {
                if (isFacingUsable(level, pos, direction, machine, placed)) {
                    found = direction
                    break
                }
            }
            if (found == null) found = Direction.NORTH
            // Java 原版是引用比较（`state.getValue(property) != found`），这里保持引用比较
            if (state.getValue(property) !== found) {
                level.setBlock(pos, state.setValue(property, found), 3)
            }
        }
    }

    private fun isFacingUsable(level: Level, pos: BlockPos, direction: Direction,
                               machine: MetaMachine?, placed: Collection<BlockPos>): Boolean {
        val neighbor = pos.relative(direction)
        // 机器类方块：朝向要合法，且前方为空
        if (machine != null) {
            return level.isEmptyBlock(neighbor) && machine.isFacingValid(direction)
        }
        // 普通方块：前方要么是空气，要么是我们这次刚放下的（那就算被挡）
        return !placed.contains(neighbor) && level.isEmptyBlock(neighbor)
    }
}
