@file:Suppress("UNUSED")
package rain.fox.gtetcore.common.item.terminal

import com.gregtechceu.gtceu.common.block.CoilBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.registry.ETDataComponents

/**
 * 高级终端的设置读写与"选哪一档"的判定（老项目存在物品 NBT 里，1.21 改成数据组件）。
 *
 * 内容：分级组偏好、界面当前显示哪一组、分级组候选缓存、上次规划目标、AE 链接。
 * 8 项开关在 [AdvancedTerminalSettings]，另一棵树。
 */
object TerminalSettings {

    // ======================== 读写 ========================

    @JvmStatic
    fun read(stack: ItemStack): TerminalData = stack.get(ETDataComponents.TERMINAL_DATA) ?: TerminalData.EMPTY

    /** 写回；内容没变就一个字节都不写（写组件会触发一次物品同步）。 */
    @JvmStatic
    fun write(stack: ItemStack, data: TerminalData) {
        if (read(stack) == data) return
        stack.set(ETDataComponents.TERMINAL_DATA, data)
    }

    // ======================== AE 链接 ========================

    @JvmStatic
    fun linkAe(stack: ItemStack, pos: GlobalPos) = write(stack, read(stack).copy(aeLink = pos))

    @JvmStatic
    fun unlinkAe(stack: ItemStack) = write(stack, read(stack).copy(aeLink = null))

    @JvmStatic
    fun getAeLink(stack: ItemStack): GlobalPos? = read(stack).aeLink

    @JvmStatic
    fun hasAeLink(stack: ItemStack): Boolean = getAeLink(stack) != null

    // ======================== 分级组偏好 ========================

    @JvmStatic
    fun getPreferences(stack: ItemStack): Map<String, String> = read(stack).prefs

    /** 为某个分级组选定方块（传 null 表示恢复默认）。 */
    @JvmStatic
    fun setPreference(stack: ItemStack, groupKey: String, itemId: String?) {
        val data = read(stack)
        val prefs = LinkedHashMap(data.prefs)
        if (itemId == null) prefs.remove(groupKey) else prefs[groupKey] = itemId
        write(stack, data.copy(prefs = prefs))
    }

    @JvmStatic
    fun clearPreferences(stack: ItemStack) = write(stack, read(stack).copy(prefs = emptyMap()))

    // ======================== 界面当前显示哪一组 ========================

    @JvmStatic
    fun getUiGroup(stack: ItemStack): String? = read(stack).uiGroup

    /** 写「右下显示哪一组」；传 null 等于清掉（界面退回第 1 组）。 */
    @JvmStatic
    fun setUiGroup(stack: ItemStack, groupKey: String?) = write(stack, read(stack).copy(uiGroup = groupKey))

    // ======================== 选档 ========================

    /** 按偏好在这格的候选里挑一个方块；没设偏好就用第一个候选。 */
    @JvmStatic
    fun resolve(terminal: ItemStack, slot: StructureSlot): ItemStack =
        resolve(slot, getPreferences(terminal), plannedGroups(terminal))

    /** 同 [resolve]，但偏好表与组表由调用方预先读好（一次搭建几百格，只读一次组件）。 */
    @JvmStatic
    fun resolve(slot: StructureSlot, prefs: Map<String, String>, planned: Map<String, List<String>>): ItemStack {
        if (slot.candidates.isEmpty()) return ItemStack.EMPTY
        val wanted = lookupPreference(slot.groupKey, slot.candidates, prefs, planned)
        val hit = findById(slot.candidates, wanted)
        if (hit != null) return hit
        // 偏好不在这一格的候选里：只有「整格候选都是线圈」才把它补回来 ——
        // 线圈谓词接受任何一级线圈（搭建侧按等级砍过候选），补回来是安全的；
        // 其它情况一律回退第一档，把谓词不接受的方块放进去只会让结构永远不成型。
        if (wanted != null && allCoils(slot.candidates)) {
            val extra = TerminalItems.itemStackOf(wanted)
            if (extra != null && !extra.isEmpty) return extra
        }
        return slot.candidates[0]
    }

    @JvmStatic
    fun lookupPreference(terminal: ItemStack, groupKey: String?, candidates: List<ItemStack>): String? =
        lookupPreference(groupKey, candidates, getPreferences(terminal), plannedGroups(terminal))

    /**
     * 找出「这一格该用哪一档」的偏好物品 id；取不到返回 null（不猜）。
     *
     * 第一步：组键精确命中（面板 / 扫描 / 搭建三处用同一套 [TerminalItems.groupKey]）。
     *
     * 第二步：**按候选集包含关系回退匹配** —— 这一步是必须的，否则「面板里选了却不生效」：
     * 静态表与结构谓词给的候选集不保证一致（线圈最典型：谓词给全部档，搭建侧按线圈等级砍过）。
     * 回退要求偏好所属组 `G` 与本格候选 `S` 有交集**且** `G ⊆ S` 或 `S ⊆ G`，
     * 满足多个时取交集最大的（并列取 NBT 顺序，保证确定性）。
     * 那道包含关系闸是为了防止「能源仓那一组选的档」误配到别的也收仓室的笼统格子。
     */
    @JvmStatic
    fun lookupPreference(groupKey: String?, candidates: List<ItemStack>,
                         prefs: Map<String, String>, planned: Map<String, List<String>>): String? {
        if (candidates.isEmpty()) return null
        if (prefs.isEmpty()) return null

        val slotIds = idsOf(candidates)
        if (groupKey != null) {
            val wanted = prefs[groupKey]
            if (wanted != null && slotIds.contains(wanted)) return wanted
        }

        if (planned.isEmpty()) return null
        var best: String? = null
        var bestOverlap = -1
        for ((key, value) in prefs) {
            val group = planned[key]
            if (group == null || group.size < 2) continue
            val groupIds: Set<String> = LinkedHashSet(group)
            val overlap = groupIds.count { slotIds.contains(it) }
            if (overlap == 0) continue
            if (!slotIds.containsAll(groupIds) && !groupIds.containsAll(slotIds)) continue
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = value
            }
        }
        return best
    }

    // ======================== 计划缓存 ========================

    /** 界面上展示的一个分级组。 */
    data class GroupView(val key: String, val candidates: List<String>, val chosen: String)

    /** 终端里当前记录的分级组（组键 → 候选 id）—— 静态组 + 上次扫描的组。 */
    @JvmStatic
    fun plannedGroups(stack: ItemStack): Map<String, List<String>> = read(stack).groups

    /**
     * 把 [TerminalStaticGroups] 的静态组预置进终端，让界面**不扫描**也能列出可选部件。
     *
     * ⚠️ **只在服务端调**：客户端写自己背包物品的组件不会同步回服务端。
     * 已有组原样保留（静态组与它们共存），组偏好一个都不动。
     *
     * @return 是否真的改了（没改就不写，免得平白触发一次物品同步）
     */
    @JvmStatic
    fun installStaticGroups(stack: ItemStack): Boolean {
        val statics = TerminalStaticGroups.groups()
        if (statics.isEmpty()) return false
        val data = read(stack)
        val merged = mergedGroups(data.groups, statics)
        if (data.groups == merged) return false
        write(stack, data.copy(groups = merged))
        return true
    }

    /** 静态组与已有组合并：已有的优先（同键就是同一组候选）。 */
    private fun mergedGroups(existing: Map<String, List<String>>,
                             statics: Map<String, List<String>>): Map<String, List<String>> {
        val merged = LinkedHashMap<String, List<String>>(existing)
        statics.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        return merged
    }

    /**
     * 记录一次规划：控制器位置 + 各分级组候选。
     *
     * 这是「扫描结果」覆盖分组缓存的唯一入口：分组 = 这次扫描结果 ∪ 静态组
     * （静态组不会被清掉，上次扫描留下、这次没出现的组会被丢掉）。
     */
    @JvmStatic
    fun cachePlan(stack: ItemStack, controller: BlockPos, dimension: ResourceLocation,
                  groups: Map<String, List<String>>) {
        val data = read(stack)
        val target = GlobalPos.of(
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension),
            controller
        )
        val merged = mergedGroups(groups, TerminalStaticGroups.groups())
        if (data.groups == merged && data.planTarget == target) return
        write(stack, data.copy(groups = merged, planTarget = target))
    }

    /** 上次规划涉及的控制器位置。 */
    @JvmStatic
    fun getPlanTarget(stack: ItemStack): GlobalPos? = read(stack).planTarget

    @JvmStatic
    fun hasPlan(stack: ItemStack): Boolean = getPlanTarget(stack) != null

    /** 上次规划里的分级组（只有多候选的组会进这个列表）。 */
    @JvmStatic
    fun cachedGroups(stack: ItemStack): List<GroupView> {
        val data = read(stack)
        val groups = ArrayList<GroupView>()
        for ((key, candidates) in data.groups) {
            if (candidates.size < 2) continue
            groups.add(GroupView(key, candidates, data.prefs[candidates[0]] ?: candidates[0]))
        }
        return groups
    }

    /** 在某个分级组里循环切换到下一个候选。 */
    @JvmStatic
    fun cycleChoice(stack: ItemStack, group: GroupView) {
        val candidates = group.candidates
        if (candidates.isEmpty()) return
        val index = candidates.indexOf(group.chosen)
        setPreference(stack, group.key, candidates[(index + 1) % candidates.size])
    }

    // ======================== 小工具 ========================

    private fun idsOf(candidates: List<ItemStack>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (candidate in candidates) {
            TerminalItems.itemId(candidate)?.let { ids.add(it) }
        }
        return ids
    }

    private fun findById(candidates: List<ItemStack>, itemId: String?): ItemStack? {
        if (itemId == null) return null
        for (candidate in candidates) {
            if (itemId == TerminalItems.itemId(candidate)) return candidate
        }
        return null
    }

    /** 整格候选是不是全是线圈（见 [resolve] 里"把最后一档补回来"的准入条件）。 */
    private fun allCoils(candidates: List<ItemStack>): Boolean {
        if (candidates.isEmpty()) return false
        for (candidate in candidates) {
            val blockItem = candidate.item as? BlockItem ?: return false
            if (blockItem.block !is CoilBlock) return false
        }
        return true
    }
}
