package rain.fox.gtetcore.common.item.terminal

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.common.data.GTMachines
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import rain.fox.gtetcore.registry.ETMachines

/**
 * 高级终端右侧两块列表面板**默认**列出的几类可选部件（不依赖扫描）。
 *
 * 没有这张表的话，面板数据只能来自「上次 Shift+右键控制器扫描出来的分级组」，没扫过就什么都列不出来。
 * 顺序就是面板行序：线圈 / 能源仓 / 超频仓 /（线程仓 / 并行仓等移植后自动加入）/ 维护仓；
 * 输入输出总线与仓**故意不在表里**。
 *
 * ⚠️ 组键必须和结构扫描用同一套算法（[TerminalItems.groupKey]）：面板按键写偏好、搭建按键取偏好，
 * 键不一致等于玩家白选。静态表与谓词给的候选集不保证一致，所以 [TerminalSettings.lookupPreference]
 * 还有一层「按候选集包含关系回退匹配」兜底。
 */
object TerminalStaticGroups {

    private var cache: LinkedHashMap<String, List<ItemStack>>? = null

    /** 「几类都齐了才算建好」的下限：只有拿齐才缓存，否则每次重算。 */
    private const val CATEGORY_COUNT = 6

    /** 组键 → 候选物品 id，写进终端的分组缓存。 */
    @JvmStatic
    @Synchronized
    fun groups(): Map<String, List<String>> {
        val groups = LinkedHashMap<String, List<String>>()
        for ((key, stacks) in stacks()) {
            val ids = idsOf(stacks)
            // 只有多档的组才进面板
            if (ids.size > 1) groups[key] = ids
        }
        return groups
    }

    /** 组键 → 候选物品栈。 */
    @JvmStatic
    @Synchronized
    fun stacks(): Map<String, List<ItemStack>> {
        // ⚠️ 只在「各类都齐」时才缓存：本类可能在机器注册还没跑完时被第一次调用
        // （那时线程仓/并行仓还是空表），缓存住空表就永远补不回来了。
        val cached = cache
        if (cached != null) return cached
        val built = build()
        if (built.size >= CATEGORY_COUNT) cache = built
        return built
    }

    private fun build(): LinkedHashMap<String, List<ItemStack>> {
        val all = LinkedHashMap<String, List<ItemStack>>()
        addIfTiered(all, coils())
        addIfTiered(all, energyHatches())
        addIfTiered(all, entries(ETMachines.OVERCLOCK_HATCHES))
        addIfTiered(all, entries(ETMachines.THREAD_HATCHES))
        addIfTiered(all, entries(ETMachines.PARALLEL_HATCHES))
        addIfTiered(all, maintenanceHatches())
        return all
    }

    private fun addIfTiered(all: LinkedHashMap<String, List<ItemStack>>, stacks: List<ItemStack>) {
        if (stacks.size > 1) all[TerminalItems.groupKey(stacks)] = stacks
    }

    /** 线圈：与 GTCEu `Predicates.heatingCoils()` 同样按等级升序。 */
    private fun coils(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        GTCEuAPI.HEATING_COILS.entries.sortedBy { it.key.tier }.forEach { entry ->
            entry.value?.get()?.let { coil -> addBlock(stacks, coil) }
        }
        return stacks
    }

    /** 能源仓：分级能源仓（2A）、4A、16A、变电站用的 64A。 */
    private fun energyHatches(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        addEntries(stacks, GTMachines.ENERGY_INPUT_HATCH)
        addEntries(stacks, GTMachines.ENERGY_INPUT_HATCH_4A)
        addEntries(stacks, GTMachines.ENERGY_INPUT_HATCH_16A)
        addEntries(stacks, GTMachines.SUBSTATION_ENERGY_INPUT_HATCH)
        return stacks
    }

    /** 维护仓：普通 / 可配置 / 自动清理 / 自动维护。 */
    private fun maintenanceHatches(): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        addEntry(stacks, GTMachines.MAINTENANCE_HATCH)
        addEntry(stacks, GTMachines.CONFIGURABLE_MAINTENANCE_HATCH)
        addEntry(stacks, GTMachines.CLEANING_MAINTENANCE_HATCH)
        addEntry(stacks, GTMachines.AUTO_MAINTENANCE_HATCH)
        return stacks
    }

    // ======================== 小工具 ========================

    /** GTET 自己的分级仓表（懒取，注册还没跑完时取不到就当这一档不存在）。 */
    private fun entries(entries: List<MachineEntry<MachineDefinition>>): MutableList<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        for (entry in entries) addEntry(stacks, entry)
        return stacks
    }

    /** ⚠️ 分级注册的数组里**有空位**（没登记的档位是 null），必须逐个判空。 */
    private fun addEntries(stacks: MutableList<ItemStack>, entries: Array<MachineEntry<MachineDefinition>>?) {
        if (entries == null) return
        for (entry in entries) addEntry(stacks, entry)
    }

    private fun addEntry(stacks: MutableList<ItemStack>, entry: MachineEntry<MachineDefinition>?) {
        if (entry == null) return
        val definition = try {
            entry.get()
        } catch (ignored: Throwable) {
            // 物品还没注册：拿不到就当这一档不存在，别让面板整块挂掉
            return
        }
        val stack = try {
            definition.asStack()
        } catch (ignored: Throwable) {
            return
        }
        if (!stack.isEmpty) addStack(stacks, stack)
    }

    private fun addBlock(stacks: MutableList<ItemStack>, block: Block) {
        val stack = ItemStack(block.asItem())
        if (!stack.isEmpty) addStack(stacks, stack)
    }

    /** 去重：同一档只出现一次。 */
    private fun addStack(stacks: MutableList<ItemStack>, stack: ItemStack) {
        val id = TerminalItems.itemId(stack) ?: return
        for (existing in stacks) {
            if (id == TerminalItems.itemId(existing)) return
        }
        stacks.add(stack.copy())
    }

    private fun idsOf(stacks: List<ItemStack>): MutableList<String> {
        val ids = ArrayList<String>()
        for (stack in stacks) {
            TerminalItems.itemId(stack)?.let { ids.add(it) }
        }
        return ids
    }
}
