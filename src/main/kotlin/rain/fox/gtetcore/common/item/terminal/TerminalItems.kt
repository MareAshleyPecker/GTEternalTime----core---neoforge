package rain.fox.gtetcore.common.item.terminal

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/** 一个待放置（或已符合）的格子；[groupKey] 见 [TerminalItems.groupKey]。 */
data class StructureSlot(
    val pos: BlockPos,
    val candidates: List<ItemStack>,
    val groupKey: String?,
) {

    fun isEmpty(): Boolean = candidates.isEmpty()
}

/**
 * 候选与物品 id 的小工具。
 *
 * 老项目里这三个函数住在 `StructureBuildPlanner` 里；新工程里设置层与规划器都要用，
 * 所以单独成文件，避免两者互相依赖。
 *
 * ⚠️ 1.21 换注册表 API：`ForgeRegistries.ITEMS` → `BuiltInRegistries.ITEM`，
 * 且 `Item.defaultInstance` 已删除，要用 `ItemStack(item)`。
 */
object TerminalItems {

    /** 一组候选的稳定标识：把所有候选的物品 id 排序后拼起来（面板与搭建必须用同一套算法）。 */
    @JvmStatic
    fun groupKey(candidates: List<ItemStack>): String {
        val ids = LinkedHashSet<String>()
        for (stack in candidates) {
            ids.add(itemId(stack) ?: "minecraft:air")
        }
        return ids.sorted().joinToString("|")
    }

    /** 物品注册名；拿不到（空气、未注册物品）时返回 null。 */
    @JvmStatic
    fun itemId(stack: ItemStack): String? =
        try {
            BuiltInRegistries.ITEM.getKey(stack.item).toString()
        } catch (ignored: Throwable) {
            null
        }

    /** 注册名 → 物品栈（偏好里存的是注册名字符串，搭建时要换回物品）。取不到返回 null。 */
    @JvmStatic
    fun itemStackOf(itemId: String): ItemStack? {
        val location = ResourceLocation.tryParse(itemId) ?: return null
        if (!BuiltInRegistries.ITEM.containsKey(location)) return null
        return ItemStack(BuiltInRegistries.ITEM.get(location))
    }
}
