package rain.fox.gtetcore.common.machine.multiblock.modular

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialEntry
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleTiers.item
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleTiers.prefix
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleTiers.tag
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleTiers.tierOf
import java.util.function.Supplier

/**
 * 「模块物品 → 等级」规则表：三种登记方式 + 一个统一查询。
 *
 * | 方式 | 登记 API | 命中判据 |
 * |---|---|---|
 * | ① 物品 | [item] | `stack.item` 直接相等（最精确） |
 * | ② tagprefix | [prefix] | 反查出物品的 `TagPrefix` + `Material` 后相等 |
 * | ③ 标签 | [tag] | `stack.is(tag)`（MC 标签自带父子层级，父标签也会一并命中） |
 *
 * 查询顺序固定为 **物品 → tagprefix → 标签**（精确度从高到低），命中即返回；全不命中返回 `0`。
 * `0` 在 [ETModularMachine] 里就地表示「没有模块 / 模块不合法」（`moduleTier == 0` 时该机不成型）。
 *
 * ⚠️ 登记动作是在**机器注册期**执行的（机器类的 `companion object init` 会被编进该类的 `<clinit>`，
 * 而机器注册时就访问了那个类），那时 GT 的物品**还没进注册表**（GTM `CommonProxy.init()` 里
 * `GTMachines.init()` 在 `GTItems.init()` 之前），所以 ① 登记的是 [Supplier] 而不是 `Item`：
 * 真正取物品推迟到 [tierOf] 查询时（机器运行时）。
 *
 * ② ③ 可以直接登记，无需推迟：`GTMaterials.*` 的字段在 `initMaterials()` 里就赋好值了，
 * 早于 `GTMachines.init()`；`CustomTags.*` 只是 `TagKey` 常量。
 *
 * ⚠️ 本表是**全局单例**，所有模块化多方块共用同一张表。两台机器需要互相冲突的规则时不要往同一张表里塞，
 * 覆写 [ETModularMachine.tierOfModule] 自己算即可。
 *
 * @author rain fox
 */
object ETModuleTiers {

    /** ① 精确物品 → 等级。存 [Supplier]：登记时读不到注册表，见类注释。 */
    private val byItem: MutableList<Pair<Supplier<out Item>, Int>> = ArrayList()

    /** ② 形态 → （材料 → 等级）。拆两层是为了查询只做两次哈希。 */
    private val byPrefix: MutableMap<TagPrefix, MutableMap<Material, Int>> = HashMap()

    /** ③ 标签 → 等级。用 LinkedHashMap 让遍历顺序 = 登记顺序，命中结果可预期。 */
    private val byTag: MutableMap<TagKey<Item>, Int> = LinkedHashMap()

    /** 登记「这些物品 = 第 [tier] 级」。参数是 [Supplier]，取物品推迟到查询时（见类注释）。 */
    fun item(tier: Int, vararg items: Supplier<out Item>): ETModuleTiers {
        requireTier(tier)
        for (i in items) byItem += i to tier
        return this
    }

    /** 登记「这些标签下的物品 = 第 [tier] 级」。 */
    fun tag(tier: Int, vararg tags: TagKey<Item>): ETModuleTiers {
        requireTier(tier)
        for (t in tags) byTag[t] = tier
        return this
    }

    /** 登记「[prefix] 形态的这些材料 = 第 [tier] 级」。 */
    fun prefix(tier: Int, prefix: TagPrefix, vararg materials: Material): ETModuleTiers {
        requireTier(tier)
        val byMaterial = byPrefix.getOrPut(prefix) { HashMap() }
        for (m in materials) byMaterial[m] = tier
        return this
    }

    /** 查等级：**物品 → tagprefix → 标签**，命中即返回；都不是就返回 `0`。 */
    fun tierOf(stack: ItemStack): Int {
        if (stack.isEmpty) return 0

        // ① 物品：最精确，先查
        tierOfItem(stack)?.let { return it }

        // ② tagprefix：反查「形态 + 材料」
        tierOfPrefix(stack)?.let { return it }

        // ③ 标签：层级关系交给 MC 的 `is` 去处理（父标签也算命中）。
        //    ⚠️ 不用 `ItemStack#getTags()`：它返回的是 Java `Stream`，在 Kotlin 里没法 non-local return；
        //       规则表通常只有几条，按登记顺序 `is` 一遍最省事也最好读。
        for ((tag, tier) in byTag) {
            if (stack.`is`(tag)) return tier
        }
        return 0
    }

    /**
     * ① 的反查。
     *
     * ⚠️ `supplier.get()` 才是真正读物品注册表的地方：登记期（机器注册）不能读，查询期（机器运行时）才能读。
     */
    private fun tierOfItem(stack: ItemStack): Int? {
        for ((supplier, tier) in byItem) {
            if (supplier.get() === stack.item) return tier
        }
        return null
    }

    /**
     * tagprefix 方式的反查。
     *
     * ⚠️ 一次 [ChemicalHelper.getMaterialEntry] 就能同时拿到形态和材料
     * （`ChemicalHelper.getPrefix` 只给形态，材料还得再查一次）。
     *
     * ⚠️ 8.0.0 删掉了老工程的「空条目」哨兵：`MaterialEntry` 现在是 record、构造器
     * `Preconditions.checkNotNull` 两个分量，`TagPrefix.NULL_PREFIX` / `GTMaterials.NULL` 都已不存在，
     * 查不到时 `getMaterialEntry` 返回 `null`。声明成可空类型再判，避免平台类型带来的空判警告。
     *
     * 返回 `null` = 这一支没命中，交给下一优先级，并不代表整个查询失败。
     */
    private fun tierOfPrefix(stack: ItemStack): Int? {
        if (byPrefix.isEmpty()) return null
        val entry: MaterialEntry? = ChemicalHelper.getMaterialEntry(stack.item)
        return entry?.let { byPrefix[it.tagPrefix()]?.get(it.material()) }
    }

    /** 等级必须是正数：`0` 是「无模块 / 不合法」的保留值，登记成 `0` 只会永远不成型（静默失败）。 */
    private fun requireTier(tier: Int) {
        require(tier > 0) { "模块等级必须 > 0（0 是「无模块 / 不合法」的保留值），收到：$tier" }
    }
}
