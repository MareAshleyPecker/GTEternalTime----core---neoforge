package rain.fox.gtetcore

import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.ZhCnLangProvider
import rain.fox.gtetcore.registry.ETDataComponents
import rain.fox.gtetcore.registry.ETRegistrate
import rain.fox.gtetcore.registry.ETMachines
import rain.fox.gtetcore.test.TestMachines
import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.GTValues
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.data.event.GatherDataEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.runForDist

/**
 * GTET 在 1.21.1 / NeoForge 上的移植工程主类。
 *
 * 方块和机器都走 GTCEu 的 Registrate（见 `registry/ETRegistrate.kt`），不要再用 KFF 模板的 DeferredRegister。
 */
@Mod(Gtetcore.ID)
object Gtetcore {
    const val ID = "gtetcore"

    // the logger for our mod
    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.log(Level.INFO, "Hello world!")

        // 配置必须在 mod 构造期注册，NeoForge 才会加载它
        GtetConfig.register()

        // 数据组件（1.21 取代物品 NBT）也必须挂到 mod 事件总线上
        ETDataComponents.REGISTRY.register(MOD_BUS)

        // 阶段 3：在 mod 构造期触发机器登记（Registrate 必须在这时挂上自己的事件监听器）。
        // 注意别在这里读 MachineEntry 的值（如 .tier）：mod 构造期注册表还没建好，
        // 读它会抛 `IllegalStateException: Registry not present for DeferredHolder{... gtceu:machine}`。
        @Suppress("UNUSED_EXPRESSION")
        TestMachines.TEST_SYNC_PART
        @Suppress("UNUSED_EXPRESSION")
        ETMachines.PARALLEL_HATCH_IV
        LOGGER.log(
            Level.INFO,
            "[GTET-TEST] 阶段 3 测试机器已登记：{}:test_sync_part，registrate 命名空间 = {}",
            Gtetcore.ID, ETRegistrate.REGISTRATE.modid
        )

        val obj = runForDist(clientTarget = {
            MOD_BUS.addListener(::onClientSetup)
            Minecraft.getInstance()
        }, serverTarget = {
            MOD_BUS.addListener(::onServerSetup)
            "test"
        })

        // 数据生成（runData）时补中文语言文件
        MOD_BUS.addListener(::onGatherData)

        println(obj)
    }

    /**
     * 数据生成入口：en_us / en_ud 由 registrate 自己写，这里只补 zh_cn（各写各的文件，不抢路径）。
     */
    @JvmStatic
    @SubscribeEvent
    fun onGatherData(event: GatherDataEvent) {
        if (event.includeClient()) {
            event.addProvider(ZhCnLangProvider(event.generator.packOutput))
        }
    }

    /**
     * This is used for initializing client specific
     * things such as renderers and keymaps
     * Fired on the mod specific event bus.
     */
    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client...")
    }

    /**
     * Fired on the global Forge bus.
     */
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Server starting...")
    }

    /**
     * ⚠️ `@JvmStatic` 不能少：NeoForge 的 `@EventBusSubscriber` 只订阅**静态**方法，
     * 而 Kotlin `object` 里的函数默认编译成实例方法（`public final void`），漏了就永远不会被调用。
     */
    @JvmStatic
    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.log(Level.INFO, "Hello! This is working!")

        // 最小测试（阶段 1）：证明 GTCEu 8.0.0（1.21.1）在类路径上、API 真的能调用。
        LOGGER.log(
            Level.INFO,
            "[GTET-TEST] GTCEu 可调用：HV = {} EU/t，电压档位数 = {}，GTCEuAPI 类 = {}",
            GTValues.V[GTValues.HV], GTValues.V.size, GTCEuAPI::class.java.name
        )
    }
}
