package rain.fox.gtetcore.init

import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.GTValues
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.data.event.GatherDataEvent
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.common.item.terminal.TerminalGroupSeeder
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.AdvancedTerminalLang
import rain.fox.gtetcore.data.lang.ZhCnLangProvider
import rain.fox.gtetcore.data.recipe.ETRecipeProvider
import rain.fox.gtetcore.registry.ETDataComponents
import rain.fox.gtetcore.registry.ETItems
import rain.fox.gtetcore.registry.ETMachines
import rain.fox.gtetcore.registry.ETRegistrate
import rain.fox.gtetcore.test.TestMachines
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * 通用代理 —— **两端都要跑**的初始化与事件登记，全部收在这里。
 *
 * 分工（照老工程 `rain.gtetcore.gtet.init` 的拆法）：
 * - [CommonProxy]：配置、数据组件、registrate 登记触发、语言键、数据生成、两端通用的事件监听；
 * - [ClientProxy]：只在客户端才允许加载的东西（渲染器、按键、客户端命令…）。
 *
 * ⚠️ **两者平级，刻意不用继承**。老工程是 `ClientProxy : CommonProxy` + `DistExecutor`
 * 只构造一个代理，那套在 NeoForge 的事件总线上直接崩在 mod 构造期：
 * ```
 * IllegalArgumentException: Attempting to register a listener object of type ...ClientProxy,
 * however its supertype ...CommonProxy has a @SubscribeEvent method: ...onCommonSetup(...).
 * This is not allowed! Only the listener object can have @SubscribeEvent methods.
 * ```
 * 原因在 `net.neoforged.bus.EventBus`：`register(Object)` 先 `checkSupertypes` **递归拒绝所有
 * 父类 / 接口里声明的 `@SubscribeEvent`**，再只用 `getDeclaredMethods()` 扫本类声明的监听器 ——
 * 继承来的监听器既不会被登记，也不允许存在。所以这边改成：**两端都构造一个 [CommonProxy]，
 * 客户端再额外构造一个 [ClientProxy]**（见 `GTETCore.init`）。
 *
 * ⚠️ 这里用的是 `bus.register(this)` + `@SubscribeEvent`（老工程的写法），**不是** `@EventBusSubscriber`：
 * 后者会触发 KFF 5.7.0 的 `AutoKotlinEventBusSubscriber`，它去调 FML 4.0.44 里已删除的
 * `net.neoforged.fml.Bindings`，mod 构造期直接 `NoClassDefFoundError` 崩（runData 与游戏都起不来）。
 *
 * ⚠️ 本类**只注册到 mod 总线**，别再补一句 `NeoForge.EVENT_BUS.register(this)`：NeoForge 会校验
 * 「这个事件属于哪条总线」，把带 mod 总线事件的实例挂到 GAME 总线上会直接崩：
 * ```
 * IllegalArgumentException: Method ...onCommonSetup(FMLCommonSetupEvent) has @SubscribeEvent annotation,
 * but takes an argument that is not valid for this bus ...
 * Caused by: IllegalArgumentException: IModBusEvent events are not allowed on the common NeoForge bus!
 * ```
 * 要挂 GAME 总线的事件（如 `PlayerTickEvent`），用 `NeoForge.EVENT_BUS.addListener(...)` 单挂，
 * 或者另开一个只挂 GAME 总线的监听器对象。
 *
 * @author rain fox
 */
class CommonProxy {

    init {
        MOD_BUS.register(this)
        kotlinInit()
    }

    /**
     * Kotlin 侧的登记：配置、物品 / 方块、语言键。
     *
     * ⚠️ 全部必须发生在**数据生成之前** —— zh_cn 生成器（[ZhCnLangProvider]）只负责把
     * `LangUtil` 里已经攒好的表写盘，晚一步登记就等于这条翻译键不存在。
     */
    private fun kotlinInit() {
        // 配置必须在 mod 构造期注册，NeoForge 才会加载它
        GtetConfig.register()
        // 数据组件（1.21 取代物品 NBT）也必须挂到 mod 事件总线上
        ETDataComponents.REGISTRY.register(MOD_BUS)
        // Registrate 只在 builder 被创建的那一瞬间登记，所以必须在这里取一次值。
        // ⚠️ 别在这里读 MachineEntry 的值（如 .tier）：mod 构造期注册表还没建好，
        //    读它会抛 `IllegalStateException: Registry not present for DeferredHolder{... gtceu:machine}`。
        @Suppress("UNUSED_EXPRESSION") TestMachines.test_sync_part
        @Suppress("UNUSED_EXPRESSION") ETMachines.parallel_hatch_iv
        @Suppress("UNUSED_EXPRESSION") ETItems.ADVANCED_TERMINAL
        initLang()

        GTETCore.LOGGER.log(
            Level.INFO,
            "[GTET-TEST] 阶段 3 测试机器已登记：{}:test_sync_part，registrate 命名空间 = {}",
            GTETCore.ID, ETRegistrate.REGISTRATE.modid
        )

        // 高级终端静态组预置：GAME 总线的手工注册。
        // ⚠️ 刻意不写成带 `@EventBusSubscriber` 的 Kotlin object —— 原因见类注释。
        NeoForge.EVENT_BUS.addListener(TerminalGroupSeeder::onPlayerTick)
    }

    private fun initLang(){
        // 高级终端设置面板的语言键（必须早于 runData 的数据生成）
        AdvancedTerminalLang.register()
    }

    /**
     * 通用设置阶段。
     *
     * ⚠️ 这一段原来挂在 `GTETCore` 的 `@JvmStatic fun onCommonSetup` 上，但那个类**既没有**
     * `@EventBusSubscriber`（用不了，见类注释）**也没有**人调 `MOD_BUS.addListener` ——
     * 也就是说它从来没被登记过，是死代码。搬进代理、靠 `register(this)` 挂上才真的会跑。
     *
     * ⚠️ 别拿 `runData` 的日志来验证这一类监听器：数据生成只跑 `CommonModLoader.begin()`，
     * `FMLCommonSetupEvent` 是在 `load()` 里派发的 —— runData 里看不到它，不代表它没注册。
     * 要验证这些监听器只能真起游戏。
     */
    @SubscribeEvent
    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        GTETCore.LOGGER.log(Level.INFO, "Hello! This is working!")

        // 最小测试（阶段 1）：证明 GTCEu 8.0.0（1.21.1）在类路径上、API 真的能调用。
        GTETCore.LOGGER.log(
            Level.INFO,
            "[GTET-TEST] GTCEu 可调用：HV = {} EU/t，电压档位数 = {}，GTCEuAPI 类 = {}",
            GTValues.V[GTValues.HV], GTValues.V.size, GTCEuAPI::class.java.name
        )
    }

    /**
     * 专用服务端设置阶段。
     *
     * 放在通用代理里而不是单开一个 ServerProxy：两端都登记这个监听是无害的
     * （`FMLDedicatedServerSetupEvent` 只在专用服务端触发），老工程也没有 ServerProxy。
     */
    @SubscribeEvent
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        GTETCore.LOGGER.log(Level.INFO, "Server starting...")
    }

    /**
     * 数据生成入口：en_us / en_ud 由 registrate 自己写，这里只补 zh_cn 与工作台配方（各写各的文件，不抢路径）。
     *
     * 配方是服务端数据（`data/gtetcore/recipe/`），所以挂 [GatherDataEvent.includeServer] 那一支；
     * 语言文件是客户端资源，挂 `includeClient()`。
     */
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        if (event.includeClient()) {
            event.addProvider(ZhCnLangProvider(event.generator.packOutput))
        }
        if (event.includeServer()) {
            event.addProvider(ETRecipeProvider(event.generator.packOutput, event.lookupProvider))
        }
    }
}
