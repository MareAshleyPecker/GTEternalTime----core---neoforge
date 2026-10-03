package rain.fox.gtetcore.common.item.terminal

import net.minecraft.core.component.DataComponentPatch
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler

/**
 * 取料来源（本阶段只有玩家背包这一侧）—— 一次搭建里把「查」和「扣」都做成批量操作。
 *
 * [Inventory]：循环**前**把玩家背包扫一遍建索引（含背包里各物品自带的容器），
 * 之后每种物品 O(1) 查存量，扣的时候直接落到具体槽位 —— 不再每格重扫背包。
 *
 * 扣物品的时机仍然是「放置成功之后」：预留 → 放置 → 成功则 commit、失败则 release。
 */
@Suppress("unused")
object TerminalItemSource {

    /** 嵌套容器只往下找一层（背包里那件物品自带的容器）。 */
    private const val MAX_NESTING = 1

    /**
     * 物品键：与 [ItemStack.isSameItemSameComponents] 等价的哈希键（物品同一性 + 组件深比较）。
     *
     * ⚠️ 1.21 取消了物品 NBT，老版的 `stack.tag` 没了 —— 这里改用**组件补丁**
     * （[ItemStack.getComponentsPatch]）：它就是「这一个栈相对原型多出来的那部分数据」，
     * 语义上正是老版整棵 tag 树所扮演的角色。
     */
    data class Key(
        @get:JvmName("item") val item: Item,
        @get:JvmName("components") val components: DataComponentPatch,
    ) {

        companion object {

            /** 物品栈的键；空栈为 `null`。 */
            @JvmStatic
            fun of(stack: ItemStack): Key? {
                return if (stack.isEmpty) null else Key(stack.item, stack.componentsPatch)
            }
        }
    }

    // ======================== 背包索引 ========================

    /** 玩家背包（含一层嵌套容器）的索引。 */
    class Inventory private constructor() {

        private val byKey = LinkedHashMap<Key, MutableList<SlotRef>>()
        private val totals = HashMap<Key, Int>()
        private var readCount = 0

        private fun scan(handler: IItemHandler, depth: Int) {
            val slots = handler.slots
            for (i in 0 until slots) {
                val stack = handler.getStackInSlot(i)
                readCount++
                if (stack.isEmpty) continue

                // 嵌套容器：先看这一件物品自带的容器（只往下找一层）
                if (depth < MAX_NESTING) {
                    val nested = stack.getCapability(Capabilities.ItemHandler.ITEM)
                    if (nested != null && nested !== handler) scan(nested, depth + 1)
                }

                val key = Key.of(stack) ?: continue
                byKey.getOrPut(key) { ArrayList() }.add(SlotRef.create(key, handler, i, stack.count))
                totals[key] = (totals[key] ?: 0) + stack.count
            }
        }

        /** 这种物品一共还能拿出几个（已扣掉本轮预留/已扣的）。 */
        fun count(wanted: ItemStack): Int {
            val key = Key.of(wanted) ?: return 0
            return totals[key] ?: 0
        }

        /** 索引建立时的 `getStackInSlot` 调用次数（诊断用）。 */
        fun slotReads(): Int = readCount

        /**
         * 预留一个（**不**真扣）：放置成功后再 [commit]，失败就 [release]。
         *
         * @return 预留到的槽位；没有存量时为 `null`
         */
        fun reserve(wanted: ItemStack): SlotRef? {
            val key = Key.of(wanted) ?: return null
            val slots = byKey[key] ?: return null
            for (ref in slots) {
                if (ref.remainingCount > 0) {
                    ref.remainingCount--
                    totals[key] = (totals[key] ?: 0) - 1
                    return ref
                }
            }
            return null
        }

        /** 放置成功：真正从槽位里扣掉一个。 */
        fun commit(ref: SlotRef) {
            ref.handler.extractItem(ref.slotIndex, 1, false)
        }

        /** 放置失败：把预留还回去（这一格没消耗物品，也不需要再读一次槽位）。 */
        fun release(ref: SlotRef) {
            ref.remainingCount++
            totals[ref.key] = (totals[ref.key] ?: 0) + 1
        }

        /**
         * 一个可扣的槽位，以及它还剩几个可扣。
         *
         * ⚠️ 构造器仍是 `private`（Java 侧只能从 [Inventory.reserve] 拿到它）：
         * 建索引那一步走伴生对象里的 `internal` 工厂 —— Kotlin 的外层类看不到嵌套类的
         * `private` 成员，而 [Inventory] 的预留 / 提交 / 归还必须直接改这几个计数。
         */
        class SlotRef private constructor(
            internal val key: Key,
            internal val handler: IItemHandler,
            internal val slotIndex: Int,
            internal var remainingCount: Int,
        ) {

            fun index(): Int = slotIndex

            fun remaining(): Int = remainingCount

            companion object {

                /** 只给 [Inventory.scan] 建索引用。 */
                internal fun create(key: Key, handler: IItemHandler, slotIndex: Int, remaining: Int): SlotRef {
                    return SlotRef(key, handler, slotIndex, remaining)
                }
            }
        }

        companion object {

            /**
             * 建索引。每个槽位只调**一次** `getStackInSlot`。
             *
             * ⚠️ 1.21 的能力查询换了两处：老版 `player.getCapability(ForgeCapabilities.ITEM_HANDLER)`
             * 返回 `LazyOptional`，新版直接返回处理器本身（拿不到就是 `null`），不再需要 `orElse`。
             *
             * @param player 玩家；`null` 时返回空索引
             */
            @JvmStatic
            fun of(player: Player?): Inventory {
                if (player == null) return Inventory()
                return ofHandler(player.getCapability(Capabilities.ItemHandler.ENTITY))
            }

            /**
             * 从一个物品栏处理器建索引（[of] 最终也走这里；也方便脱离玩家单独测）。
             */
            @JvmStatic
            fun ofHandler(handler: IItemHandler?): Inventory {
                val inventory = Inventory()
                if (handler != null) inventory.scan(handler, 0)
                return inventory
            }
        }
    }

    // TODO(AE 切片)：老文件的 `AeDemand` / `AeOps`（按物品类型聚合需求、每种类型各一次
    //  SIMULATE / MODULATE）本阶段不移植 —— AE 链接先不做，取料只走上面的背包索引。
    //  等 AE 链接移植时，按老文件 `AeDemand.of(grid, player)` 的形状补回这两个类型。

    // ======================== 物品键收集 ========================

    /** 物品键 → 该键的代表栈（提示与来源统计都要用到栈本身）。 */
    class Demand {

        private val counts = LinkedHashMap<Key, Int>()
        private val stacks = LinkedHashMap<Key, ItemStack>()

        fun add(stack: ItemStack) {
            val key = Key.of(stack) ?: return
            counts[key] = (counts[key] ?: 0) + 1
            stacks.putIfAbsent(key, stack)
        }

        fun counts(): Map<Key, Int> = counts

        fun stacks(): Map<Key, ItemStack> = stacks

        fun total(): Int {
            var sum = 0
            for (value in counts.values) sum += value
            return sum
        }
    }
}
