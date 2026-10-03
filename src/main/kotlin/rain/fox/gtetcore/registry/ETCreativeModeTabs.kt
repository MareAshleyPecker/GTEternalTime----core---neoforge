package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMachines
import com.gregtechceu.gtceu.common.data.machines.GTMultiMachines
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.util.lang.LangUtil
import java.util.function.Supplier

/**
 * GTET 的**分类创造页**（照老工程 `GTETCreativeModeTabs` 的形状搬过来）。
 *
 * 老工程是「一个内容域一个页」，这里保持一致：一个通用帮助函数 [registerTab] + 七个分类页。
 * 目前只有 `machine` / `item` 两页真的有内容（并行仓 / 超频仓、高级终端 / 时序钟），
 * 其余五页先建出来占位，后续切片往里填。
 *
 * 归页（把物品塞进哪一页）**不在这里做**：
 * - 注册时就确定 —— 用 `ETRegistrate` 里的 `GTRegistrate.inTab(tab) { ... }` 包住注册语句；
 * - 已经注册完的补页 —— 用 `GTRegistrate.assignTab(tab, entries)`。
 * 两者都别直接调 GTM 的原生 API：`creativeModeTab(tab)` 只对**之后**注册的东西生效
 * （GTM 自己也这么用，见 `GTMachines` 的静态块），而 `setCreativeTab(entry, tab)` 按**对象身份**
 * 查表，机器手里那份 `MachineEntry` 和创造页生成器查表用的 `ItemEntry` 不是同一个对象，会静默失效。
 *
 * 页内容由 GTM 自己的 [GTCreativeModeTabs.RegistrateDisplayItemsGenerator] 生成：它遍历本
 * registrate 注册的全部物品、按 `isInCreativeTab` 过滤出属于本页的，所以**同一件物品只会出现一次**。
 *
 * @author rain fox
 */
object ETCreativeModeTabs {

    /**
     * 通用建页帮助函数：登记一个分类页并返回条目。
     *
     * 双语分工与全局一致：英文走 registrate 的 `addLang("itemGroup", ...)`（写 en_us），
     * 中文走 [LangUtil.TAB_LANG] → `ZhCnLangProvider`（写 zh_cn），各写各的文件、互不重复。
     *
     * @param registrate   归属的 registrate（每个 addon 一份，见 `ETRegistrate`）
     * @param modId        命名空间，进语言键与注册名
     * @param name         页名（注册名），语言键为 `itemGroup.<modId>.<name>`
     * @param icon         图标栈（懒取值，注册表建好后才会被调到）
     * @param titleDefault 英文页名
     * @param titleCn      中文页名，缺省与英文同形
     */
    @JvmStatic
    @JvmOverloads
    fun registerTab(
        registrate: GTRegistrate,
        modId: String,
        name: String,
        icon: Supplier<ItemStack>,
        titleDefault: String,
        titleCn: String = titleDefault,
    ): RegistryEntry<CreativeModeTab, CreativeModeTab> {
        LangUtil.TAB_LANG[name] = titleCn
        return registrate
            .defaultCreativeTab(name) { builder ->
                builder
                    .displayItems(GTCreativeModeTabs.RegistrateDisplayItemsGenerator(name, registrate))
                    .icon(icon)
                    .title(registrate.addLang("itemGroup", GTETSCore.id(modId, name), titleDefault))
                    .build()
            }
            .register()
    }

    /** GTET 机器页：并行仓、超频仓（线程仓待变体表移植后自动进来）。 */
    @JvmField
    val MACHINE: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "machine",
        Supplier { GTMachines.ELECTROLYZER[GTValues.LV].asStack() },
        "GTET Machines",
        "GTET 机器",
    )

    /** GTET 物品页：高级终端、时序钟。 */
    @JvmField
    val ITEM: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "item",
        Supplier { GTItems.BATTERY_HULL_LV.asStack() },
        "GTET Items",
        "GTET 物品",
    )

    /** GTET 方块页：暂无内容，留给后续切片。 */
    @JvmField
    val BLOCK: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "block",
        Supplier { GTBlocks.COIL_NAQUADAH.asStack() },
        "GTET Blocks",
        "GTET 方块",
    )

    /** GTET 多方块页：暂无内容（时序主塔等进来后才会有）。 */
    @JvmField
    val MULTIBLOCK: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "multiblock",
        Supplier { GTMultiMachines.LARGE_BOILER_BRONZE.asStack() },
        "GTET Multiblocks",
        "GTET 多方块",
    )

    /** GTET 流体页：暂无内容。图标用原版水桶占位。 */
    @JvmField
    val FLUID: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "fluid",
        Supplier { ItemStack(Items.WATER_BUCKET) },
        "GTET Fluids",
        "GTET 流体",
    )

    /** GTET 矿石页：暂无内容。图标用原版钻石矿占位。 */
    @JvmField
    val ORE: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "ore",
        Supplier { ItemStack(Items.DIAMOND_ORE) },
        "GTET Ores",
        "GTET 矿石",
    )

    /**
     * GTO 方块页：暂无内容。
     *
     * 老工程这里用的是 GTOCore 的 `ENERGY_CONTROL_CASING_MK2`，本工程还没移植 GTOCore 的方块，
     * 图标先用 GTM 的聚变外壳 mk2 占位（等 GTO 方块切片落地后换掉）。
     */
    @JvmField
    val GTOBLOCK: RegistryEntry<CreativeModeTab, CreativeModeTab> = registerTab(
        ETRegistrate.REGISTRATE,
        GTETSCore.ID,
        "gtoblock",
        Supplier { GTBlocks.FUSION_CASING_MK2.asStack() },
        "GTO's Blocks",
        "GTO的方块",
    )

    /**
     * 老工程留的空 `init()`，这里保持同形。
     *
     * 实际**不需要**调用：七个页都是本 object 的 `val`，注册层只要引用到其中任意一个
     * （`ETMachines` 引 `MACHINE`、`ETItems` 引 `ITEM`），整个 object 就会被初始化、七个页一起登记。
     */
    fun init() {}
}
