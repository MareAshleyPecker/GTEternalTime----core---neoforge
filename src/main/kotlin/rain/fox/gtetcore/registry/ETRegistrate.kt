package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MachineInstanceFactory
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.builder.MachineBuilder
import com.tterrag.registrate.builders.ItemBuilder
import com.tterrag.registrate.providers.ProviderType
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 自己的 GTRegistrate 实例：GTM 要求每个 addon 用自己的一份，官方的只服务 gtceu 本体。
 *
 * `create(modId)` 会自动把注册/数据生成监听器挂到 gtetcore 自己的 mod 事件总线上。
 */
object ETRegistrate {

    @JvmField
    val REGISTRATE: GTRegistrate = GTRegistrate.create(GTETSCore.ID)

    init {
        // 双语条目分工：英文进 registrate 的 en_us，中文由 ZhCnLangProvider 写进 zh_cn。
        REGISTRATE.addDataGenerator(ProviderType.LANG) { provider ->
            LangUtil.CUSTOM_LANG.forEach { (key, pair) -> provider.add(key, pair.first) }
        }
    }
}

/**
 * 注册机器的 Kotlin 入口。
 *
 * GTM 的 `GTRegistrate.machine(...)` 带自引用泛型 `S extends MachineBuilder<..., S>`，Kotlin 推不出来
 * （直接写会报 `Not enough information to infer type argument for 'S'`）；这里显式落到星投影的
 * `MachineBuilder` 上，调用方就能一条链写完。运行时它本来就是普通 `MachineBuilder`（GTM 内部 unchecked cast）。
 */
fun <M : MetaMachine> GTRegistrate.machineBuilder(
    name: String,
    factory: MachineInstanceFactory<M>,
): MachineBuilder<MachineDefinition, M, *> = machine(name, factory)

/**
 * 归页入口：在 [block] 里注册的物品 / 机器 / 方块**自动落进** [tab]。
 *
 * GTM 的 `creativeModeTab(tab)` 设的是「当前页」，只对**之后**注册的东西生效
 * （GTM 自己就这么用，见 `GTMachines` 的静态块），所以必须把注册语句包在里面：
 * ```
 * REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
 *     REGISTRATE.item("clock_of_time_sequence", ::ComponentItem)...register()
 * }
 * ```
 * 出了块就恢复调用前的「当前页」，免得后面别的注册被顺手带走。
 */
fun <T> GTRegistrate.inTab(
    tab: RegistryEntry<CreativeModeTab, out CreativeModeTab>,
    block: () -> T,
): T {
    val previous = creativeModeTab()
    creativeModeTab(tab)
    try {
        return block()
    } finally {
        if (previous == null) resetCreativeModeTab() else creativeModeTab(previous)
    }
}

/**
 * 补页入口：把**已经注册完**的条目显式归到 [tab]。
 *
 * 必须先按注册名取回 `Registries.ITEM` 那一份条目再 `setCreativeTab`：`TAB_LOOKUP` 是
 * `IdentityHashMap`、创造页生成器按**对象身份**比对（`GTCreativeModeTabs.java:95-97`），
 * 而机器手里那份是 `MachineEntry`、方块是 `BlockEntry`，直接传进去静默失效。
 *
 * ⚠️ **机器不能走这条路**：机器的物品条目要到 `RegisterEvent` 派发时才由 `createEntry()` 建出来
 * （`MachineBuilder.java:637-672`），构造期这里必然取不到 —— 见 [rain.fox.gtetcore.registry.ETMachines]。
 * 取不到时打 WARN（原先静默 `continue`，「机器没进页」查了半天）。
 */
fun GTRegistrate.assignTab(
    tab: RegistryEntry<CreativeModeTab, out CreativeModeTab>,
    entries: Iterable<RegistryEntry<*, *>>,
) {
    for (entry in entries) {
        val itemEntry: RegistryEntry<Item, Item>? =
            getOptional<Item, Item>(entry.id.path, Registries.ITEM).orElse(null)
        if (itemEntry == null) {
            GTETSCore.LOGGER.log(
                Level.WARN,
                "[GTET] assignTab 归页失败：{} 在 ITEM 注册表里还没有同名的条目" +
                    "（机器物品要到 RegisterEvent 才建出来，构造期归页对机器无效）",
                entry.id,
            )
            continue
        }
        setCreativeTab(itemEntry, tab)
    }
}

/**
 * 关掉 Registrate 的「影子归页」——**每件物品注册时都必须调一次**（见 [inTab] 里的用法）。
 *
 * `AbstractRegistrate.item(...)` 在建 builder 时会顺手 `.tab(defaultCreativeModeTab)`，
 * 而这个字段的初值是**原版搜索页** `CreativeModeTabs.SEARCH`（写在 `AbstractRegistrate.<init>` 里），
 * 于是每件物品都额外挂着一条「把自己塞进搜索页」的 modifier。
 *
 * 搜索页的内容是从**其它页聚合**出来的（`CreativeModeTabs.SEARCH` 的展示生成器遍历各页取
 * `getDisplayItems()`）：第一次构建时别的页还没建、聚合为空，看不出问题；但开一次背包就会走
 * `CreativeModeTab.tryRebuildTabContents` 重建，此时聚合结果里**已经有**这件物品，
 * 那条 modifier 再塞一次就撞上 NeoForge 的断言（21.1.252 `BuildCreativeModeTabContentsEvent.accept`
 * → `assertNewEntryDoesNotAlreadyExists`）：
 * `IllegalArgumentException: Itemstack 1 gtetscore:advanced_terminal already exists in the tab's list`，
 * 崩在 `ModLoadingException` 上，看起来像内存不足，其实是这个。
 *
 * GTET 的归页统一走 GTRegistrate 的 `TAB_LOOKUP`（见 [inTab] / [assignTab]），不需要影子归页。
 * 必须在 **`.register()` 之前**清 —— modifier 是在 register 时按这份页映射注册到 registrate 上的，
 * 注册完再清就晚了。清掉后物品照样搜得到（搜索页靠聚合，不靠这条 modifier）。
 */
fun <T : Item, P> ItemBuilder<T, P>.noDefaultTab(): ItemBuilder<T, P> {
    removeTab(CreativeModeTabs.SEARCH)
    ETCreativeModeTabs.all().mapNotNull { it.key }.forEach { removeTab(it) }
    return this
}

/**
 * 关掉 Registrate 的「影子归页」总闸 —— **每建完一个创造页都必须调一次**（见 [ETCreativeModeTabs.registerTab]）。
 *
 * [noDefaultTab] 只能救单件物品，救不了机器：机器的物品是 `MachineBuilder` 内部经
 * `AbstractRegistrate.item(parent, name, factory)` 建的（`MachineBuilder.java:228`），
 * 我们拿不到那个 `ItemBuilder`。所以要从根上把 `defaultCreativeModeTab` 置空 ——
 * `AbstractRegistrate.item(...)` 建 builder 时看到它是 null 就**不会**再挂归页 modifier。
 *
 * ⚠️ 必须在**建完页之后**清：`defaultCreativeTab(name) { ... }` 内部会先把字段设成新页的 key、
 * 再拿它生成 `itemGroup.<id>.<name>` 的语言键，清早了会 NPE。
 */
fun GTRegistrate.clearDefaultTab() {
    // 参数在 Java 侧是平台类型，允许传 null；传 null 就是「没有默认页」
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    defaultCreativeTab(null as ResourceKey<CreativeModeTab>?)
}
