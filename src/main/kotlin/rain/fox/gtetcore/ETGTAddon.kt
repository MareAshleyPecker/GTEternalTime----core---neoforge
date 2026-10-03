package rain.fox.gtetcore

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.addon.AddonFinder
import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.recipes.RecipeOutput
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.api.timeflow.ETTimeFlowCapability
import rain.fox.gtetcore.registry.ETMachines
import rain.fox.gtetcore.registry.ETRegistrate
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Consumer

/**
 * GTET 的 GTCEu 插件入口（GTM **8.0.0** / 1.21.1 NeoForge 版）。
 *
 * ## 8.0.0 的 addon 契约（与老工程 1.20.1 的差别，已 javap + 读源码核实）
 * 接口 `com.gregtechceu.gtceu.api.addon.IGTAddon` 只剩**一个**抽象方法 [getRegistrate]，
 * 其余全是 `default`：`addRecipes` / `removeRecipes` / `collectMaterialCasings` /
 * `registerRecipeKeys` / `requiresHighTier`。
 * 老工程依赖的 `initializeAddon` / `addonModId` / `registerElements` / `registerRecipeCapabilities`
 * **在 8.0.0 里已经全部不存在**，各自的新落点见对应成员的注释。
 *
 * 注解也从「无参」变成了「带 modid」：
 * ```
 * public @interface GTAddon { String value(); }      // = addon 所属的 modid
 * ```
 * ⚠️ **这个值必须与 modid 逐字符相等**。[AddonFinder] 是按
 * `注解的 value == modInfo.getModId()` 过滤的（`AddonFinder.java:51-54`），
 * 对不上就整条被静默丢掉、连一条日志都不留 —— 这正是本文件此前写成 `@GTAddon("")`
 * 时「看着注册了、其实没注册」的原因。所以下面走 [GTETSCore.ID] 而不是手写字面量。
 *
 * ## 生命周期（谁在什么时候调这些方法）
 * | 方法 | 调用者 | 时机 |
 * |---|---|---|
 * | `getRegistrate` | `GTRegistrate` / GTM 各处 | 注册与数据生成期 |
 * | [addRecipes] | `GTRecipes.recipeAddition`（`:110`） | 每次资源重载（`ReloadableServerResourcesMixin`）|
 * | [removeRecipes] | `GTRecipes.recipeRemoval`（`:129`） | 每次资源重载，`RecipeManager.apply` 的 HEAD |
 * | `collectMaterialCasings` | `GTBlocks`（`:272`） | ⚠️ 8.0.0 已 `@Deprecated(forRemoval)`，改订 `MaterialCasingCollectionEvent` |
 * | `registerRecipeKeys` | `GTRecipeComponents`（`:49`） | ⚠️ 8.0.0 已 `@Deprecated(forRemoval)`，改订 `KJSRecipeKeyEvent` |
 * | `requiresHighTier` | `GTCEuAPI.initializeHighTier`（`:46`） | Pre-Init，一次性；决定 GTM 全局高档位内容开关 |
 *
 * 上面两个 `@Deprecated(forRemoval = true, since = "8.0.0")` 的钩子**刻意不覆写**：
 * GTM 的意图是让 addon 直接订阅那两个事件（都是 `IModBusEvent`），
 * 覆写它们等于主动踩一个下一版就会删掉的 API。
 *
 * ## 自检搬到哪里去了
 * 老工程的 `initializeAddon()` 是 GTM 主动回调、用来「响亮断言各子系统真的注册上了」的钩子；
 * 8.0.0 没有这个回调，所以自检改由我们自己的 NeoForge 事件承担 —— 见 [verify]，
 * 它在 `CommonProxy.onCommonSetup`（`FMLCommonSetupEvent`）里被调用。
 *
 * @see rain.fox.gtetcore.registry.ETRegistrate 本 addon 的 GTRegistrate 实例
 * @see verify 自检入口
 * @author rain fox
 */
@GTAddon(GTETSCore.ID)
class ETGTAddon : IGTAddon {

    // ⚠️ 这里**不能**写 `init {}` 日志，也**不能**在这个类里碰 `GTETSCore` 的任何非 `const` 成员。
    // 原因（实测踩过，现象见下）：GTM 的 `AddonFinder.getInstances()` 是在 **gtceu 自己的 mod 构造期**
    // 被调用的（`GTDynamicDataPack` / `GTCEuAPI.initializeHighTier`），而 KFF 的 `MOD_BUS` 是
    // 「按**当前活跃** mod 容器」解析的。addon 的构造器一碰 `GTETSCore`，就会提前触发它的静态初始化
    // → `ProxyLoader.load()` → `CommonProxy()` → `MOD_BUS.register(this)`，那一刻活跃容器是 gtceu，
    // 于是抛：
    //   IllegalStateException: Tried to get KotlinModLoadingContext for active mod container,
    //   but gtceu mod container was instance of ...FMLModContainer
    // 结果是 GTETSCore 变成 "Could not initialize class"、整个模组加载失败。
    // 也就是说：**addon 类只允许引用编译期常量（`GTETSCore.ID` 会内联，安全）**。
    // `getRegistrate()` 里那个 `ETRegistrate` 是独立 object，不碰 GTETSCore，所以是安全的。

    /** 返回本模组共享的 [GTRegistrate] 注册实例。 */
    override fun getRegistrate(): GTRegistrate = ETRegistrate.REGISTRATE

    /**
     * GTET 的配方入口 —— **刻意留空**。
     *
     * ⚠️ 覆盖它只是为了让签名与 8.0.0 的接口严格一致（`RecipeOutput` 非空；
     * 老存根写的 `RecipeOutput?` 与接口的 `@NotNull` 参数对不上）。
     *
     * 本模组的配方现在**全部走数据生成**：`ETRecipeProvider` → `GatherDataEvent`
     * → `data/gtetscore/recipe/shaped/` 下的 json（接线在 `CommonProxy.onGatherData`，配方本体在
     * `data/recipe/TerminalRecipes.kt`）。而本回调走的是**运行期动态注入**另一条路
     * （`GTRecipes.recipeAddition` → `GTDynamicDataPack`，由 `ReloadableServerResourcesMixin`
     * 在每次资源重载时触发）。两条路会用**同一个 id** 各塞一份配方，所以这里不再重复注册。
     *
     * 将来真有「运行期才能算出来、写不进 json」的配方，再往这里加。
     */
    override fun addRecipes(provider: RecipeOutput) {
        GTETSCore.LOGGER.log(
            Level.DEBUG,
            "[GTET] IGTAddon#addRecipes 被 GTCEu 调用（本模组配方走 datagen，这里不重复注册）",
        )
    }

    /**
     * 剔除 GTM 自带的、已被 GTET 接管的配方。
     *
     * 本方法在**每次资源重载**时被调用，拿到的 [consumer] 是
     * `RecipeManager.apply` 的 `map::remove`（`RecipeManagerEarlyMixin:25`）——
     * 也就是说它直接把配方从「待加载的 json 表」里划掉，GTM 自己那份动态注入的配方也在这张表里。
     * GTM 随后会把剔除过的 id 记进 `GTRecipes.RECIPE_FILTERS`，下一轮动态注入时就会主动跳过。
     */
    override fun removeRecipes(consumer: Consumer<ResourceLocation>) {
        forRemoveRecipes(gtm_parallel_hatch_recipe_names, consumer, "shaped")
        forRemoveRecipes(gtm_ae_pattern_buffer_recipe_names, consumer, "assembly_line")
    }

    companion object {

        /**
         * GTET 的启动自检 —— 老工程 `initializeAddon()` 里那几句「响亮断言」的 8.0.0 落点。
         *
         * 调用方是 `CommonProxy.onCommonSetup`（`FMLCommonSetupEvent`，mod 总线）。
         * 选这个时机是因为它满足自检的两个前提：**所有注册都已经发生**（`RegisterEvent` 全部派发完毕、
         * 注册表已冻结），**且还没有任何存档 / 世界逻辑**，此时崩掉最省事。
         *
         * ⚠️ `runData`（数据生成）**不会**触发 `FMLCommonSetupEvent`（它只跑 `CommonModLoader.begin()`），
         * 所以这个自检在 `runData` 的日志里看不到，必须真起游戏才算验证过。
         *
         * 设计原则照老工程：**先打日志、再 `check` 抛异常**。日志给出可 grep 的
         * `registered` / `MISSING` 两种证据，`check` 保证真出问题时立刻崩，
         * 而不是拖进游戏里变成一句难懂的「找不到注册项」。顺序不能反 ——
         * 抛异常会中断流程，日志就没机会写出来了。
         */
        @JvmStatic
        fun verify() {
            checkAddonDiscovered()
            checkMachinesRegistered()
            checkTimeFlowCapabilityRegistered()
            // TODO(材料切片): checkElementsRegistered()
            //   等 ETElements 移植过来，查 GTRegistries.ELEMENTS。
            // TODO(并行仓切片): hideGtmParallelHatchesFromCreativeTabs()
        }

        /**
         * 自检 ①：GTCEu 真的发现了本 addon。
         *
         * 这条专治本文件此前那个 bug：`@GTAddon("")` 的 id 与 modid 对不上，
         * [AddonFinder] 静默过滤掉整条，addon 的每个回调都不会被调用、也不报错。
         */
        private fun checkAddonDiscovered() {
            val discovered = AddonFinder.getAddons()[GTETSCore.ID]
            GTETSCore.LOGGER.log(
                Level.INFO,
                "[GTET] addon 发现自检：@GTAddon(\"{}\") → {}",
                GTETSCore.ID,
                discovered?.javaClass?.name ?: "MISSING",
            )
            check(discovered != null) {
                "GTCEu 没有发现 GTET 的 addon：@GTAddon 的 value 必须与 modid 逐字符相等" +
                    "（现在是 '${GTETSCore.ID}'）。AddonFinder 按「注解 value == modInfo.getModId()」过滤" +
                    "（AddonFinder.java:51-54），对不上就整条静默丢弃，之后所有回调都不会被调用。"
            }
        }

        /**
         * 自检 ②：GTET 的机器真的注册进了 GTM 的机器表。
         *
         * 注册入口是 [ETMachines]（由 `CommonProxy.kotlinInit` 里那次取值触发类加载）。
         * 此刻机器表已经冻结，留下什么就是什么 —— 真没注册上就直接崩，别等进游戏才发现机器没了。
         */
        private fun checkMachinesRegistered() {
            // 只读 DeferredHolder 的 id（普通字段），**不读**它的值：注册表外的取值会抛异常
            val machineId = ETMachines.parallel_hatch_iv.id
            val registered = GTRegistries.MACHINES.containsKey(machineId)
            GTETSCore.LOGGER.log(
                Level.INFO,
                "[GTET] 机器注册自检：{} → {}",
                machineId,
                if (registered) "registered" else "MISSING",
            )
            check(registered) {
                "GTET 的机器没有注册进 GTRegistries.MACHINES：$machineId。" +
                    "注册入口是 ETMachines（CommonProxy.kotlinInit 里那次取值负责触发类加载）。"
            }
        }

        /**
         * 自检 ③：TF 的配方能力真的注册进了 GTM 的能力表。
         *
         * 时刻（`FMLCommonSetupEvent`）满足自检的前提：`RegisterEvent` 已全部派发完、
         * 注册表已冻结，那时 `GTRegistries.RECIPE_CAPABILITIES` 里有什么就是什么。
         *
         * ⚠️ 8.0.0 与老工程的两处差别（已读源码核实）：
         * 1. 查的是 [GTRegistries.RECIPE_CAPABILITIES] —— 8.0.0 里它是一个真正的注册表
         *    （`GTRegistries.makeRegistry(GTRegistries.Keys.RECIPE_CAPABILITY)`，`GTRegistries.java:125`），
         *    老工程那张「unfreeze 与 freeze 之间的可写表」连同
         *    `IGTAddon#registerRecipeCapabilities()` 回调一起没了；
         * 2. 能力现在是**注册表项**，所以查找键是带命名空间的全名 `gtetscore:time_flow`
         *    （老工程是裸名 `time_flow`）。注册入口见 [ETTimeFlowCapability.register]。
         *
         * 缺了这条注册的后果是「配方里的 `gtetscore:time_flow` 解析不出来、TF 也扣不动」，
         * 而且报错会拖到配方加载期才出现 —— 所以在这里响亮地崩掉。
         */
        private fun checkTimeFlowCapabilityRegistered() {
            val capabilityId = GTETSCore.id(ETTimeFlowCapability.NAME)
            val registered = GTRegistries.RECIPE_CAPABILITIES.get(capabilityId)
            GTETSCore.LOGGER.log(
                Level.INFO,
                "[GTET] 配方能力自检：{} → {}",
                capabilityId,
                if (registered != null) "registered" else "MISSING",
            )
            check(registered === ETTimeFlowCapability.CAP) {
                "TF 的配方能力没有注册进 GTRegistries.RECIPE_CAPABILITIES：$capabilityId。" +
                    "注册入口是 ETTimeFlowCapability.register（由 CommonProxy.kotlinInit 调用）。" +
                    "少了它，配方里的 $capabilityId 会解析不出来、TF 也扣不动。"
            }
        }

        /**
         * 把 GTM 自带的并行仓从创造页里藏掉。
         *
         * **当前是桩**：GTET 自己的并行仓只移植了 IV 一档（`ETMachines.parallel_hatch_iv`），
         * GTM 那四档（IV / LuV / ZPM / UV）现在还得留给玩家用，藏了就等于 LuV 及以上没并行仓可用。
         * 等 `ETParallelHatches` 全族（IV ~ MAX 十档）搬完，在这里调
         * [forHideCTabs] 把下面那四个名字喂进去即可。
         *
         * GTM 8.0.0 那边的注册名（`GTMachineUtils.registerTieredMachines` 的拼法是
         * `GTValues.VN[tier].toLowerCase() + "_" + name`，`GCYMMachines.java:58` 传的 name 是
         * `"parallel_hatch"`，所以与老工程逐字一致，不需要改）：
         * `iv_parallel_hatch` / `luv_parallel_hatch` / `zpm_parallel_hatch` / `uv_parallel_hatch`。
         */
        @JvmStatic
        fun hideGtmParallelHatchesFromCreativeTabs() {
            // TODO(并行仓切片): forHideCTabs(listOf(
            //     "iv_parallel_hatch", "luv_parallel_hatch", "zpm_parallel_hatch", "uv_parallel_hatch",
            // ))
        }

        /**
         * 登记一个「把指定注册名的物品从创造页里删掉」的监听器。
         *
         * 老工程 `forHideCTabs` 的 NeoForge 版。两处必须改写：
         * 1. **事件换人**：1.20.1 Forge 的 `BuildCreativeModeTabContentsEvent` 有一个可变的
         *    `entries` 集合；1.21 NeoForge 收紧了 API —— `getParentEntries()` 返回的是**不可变视图**，
         *    改内容必须走 `remove(ItemStack, TabVisibility)`（NeoForge 21.1.252 已 javap 核实）。
         * 2. **总线换法**：`BuildCreativeModeTabContentsEvent` 是 `IModBusEvent`，只能挂 mod 总线；
         *    老工程那套 `FMLModContainer.eventBus` 取法在 NeoForge 里不存在，
         *    这里直接用 KFF 的 [MOD_BUS]。也**不能**用 `@EventBusSubscriber`（原因见 `ProxyLoader` 的类注释）。
         *
         * @param itemNames 物品的注册名（不含 `gtceu:` 命名空间 —— 本方法固定按 gtceu 命名空间解析）
         */
        @JvmStatic
        fun forHideCTabs(itemNames: List<String>) {
            MOD_BUS.addListener(Consumer<BuildCreativeModeTabContentsEvent> { event ->
                for (name in itemNames) {
                    // 取不到注册项（GTM 没装 / 名字被改）就跳过，别把空栈塞进表里
                    val item = BuiltInRegistries.ITEM.getOptional(GTCEu.id(name)).orElse(null) ?: continue
                    event.remove(ItemStack(item), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS)
                }
            })
        }

        /**
         * 把一个「配方类型 + 注册名」的列表交给 [consumer] 剔除。
         *
         * ⚠️ 配方 id 的形状是 `<命名空间>:<配方类型>/<注册名>`，两截都不能省：
         * - 命名空间是 `gtceu`（GTM 自带的配方都挂在它名下）；
         * - `/` 前面那一截是 **GTM 落盘时加的前缀**，不是我们拼的 ——
         *   工作台配方走 `ShapedRecipeBuilder.save` 的 `withPrefix("shaped/")`
         *   （`ShapedRecipeBuilder.java:111`），GT 配方走 `GTRecipeBuilder.save` 的
         *   `withPrefix(recipeType.id.getPath() + "/")`（`GTRecipeBuilder.java:1510`）。
         *
         * @param recipeNames 注册名列表（不含命名空间与配方类型前缀）
         * @param recipeType  配方类型路径，如 `shaped` / `assembly_line`
         */
        @JvmStatic
        fun forRemoveRecipes(
            recipeNames: List<String>,
            consumer: Consumer<ResourceLocation>,
            recipeType: String,
        ) {
            recipeNames.forEach { consumer.accept(GTCEu.id("$recipeType/$it")) }
        }

        /**
         * GTM 自带并行仓配方的注册名（`GCYMRecipes.java:173-184`，8.0.0 未改名）。
         *
         * 只剔 `mk1`（IV 档）。GTET 自己的 `parallel_hatch_iv` 只覆盖 IV 这一档，
         * `mk2/mk3/mk4`（LuV/ZPM/UV）不剔 —— 剔了那三档就会「既没有 GTM 配方、也没有 GTET 替代配方」，
         * 玩家永远造不出来。等 GTET 补齐那三档再回来加。
         */
        private val gtm_parallel_hatch_recipe_names = listOf(
            "parallel_hatch_mk1",
        )

        /**
         * GTM 自带 ME 样板总成两条装配线配方的注册名
         * （`MetaTileEntityMachineRecipeLoader.java:699` / `:717`，8.0.0 未改名）。
         *
         * ⚠️ 这两条只在装了 AE2 时才注册（上游有 `isAE2Loaded` 守卫），没装 AE2 时剔除它们是无害的空操作。
         */
        private val gtm_ae_pattern_buffer_recipe_names = listOf(
            "me_pattern_buffer",
            "me_pattern_buffer_proxy",
        )
    }
}
