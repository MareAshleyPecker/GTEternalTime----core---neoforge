package rain.fox.gtetcore.init

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.client.StructureOverlayRenderer
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/**
 * 客户端专用代理 —— **只允许在客户端加载**。
 *
 * ⚠️ **不继承 [CommonProxy]**，两者是平级的：`GTETSCore.init` 里两端都构造一个 [CommonProxy]，
 * 客户端再额外构造本类。老工程那套 `ClientProxy : CommonProxy` 在 NeoForge 上直接崩 ——
 * `net.neoforged.bus.EventBus.register(Object)` 会 `checkSupertypes` 递归拒绝**父类里声明的
 * `@SubscribeEvent`**：
 * ```
 * IllegalArgumentException: Attempting to register a listener object of type ...ClientProxy,
 * however its supertype ...CommonProxy has a @SubscribeEvent method: ...onCommonSetup(...).
 * This is not allowed! Only the listener object can have @SubscribeEvent methods.
 * ```
 * 总线只用 `getDeclaredMethods()` 扫**本类**声明的监听器，继承来的既不被登记也不允许存在，
 * 所以「客户端 = 通用 + 客户端」这件事只能靠**各构造一个**来表达，不能靠继承。
 *
 * ⚠️ 本类只允许在客户端被加载：入口是 `GTETSCore.init` 里
 * `runForDist(clientTarget = { ClientProxy() }, ...)` 的客户端分支，专用服务端不执行它，
 * JVM 也就不会去解析 / 加载这个类（它还带着 `@OnlyIn(Dist.CLIENT)`）。
 * **别**把这个引用挪到 `runForDist` 外面，否则专用服务端会连带加载客户端的类。
 */
@OnlyIn(Dist.CLIENT)
class ClientProxy {

    init {
        MOD_BUS.register(this)
        GTETSCore.LOGGER.log(Level.INFO, "Initializing client proxy...")
    }

    /**
     * 客户端设置阶段：渲染器、按键绑定、客户端命令这些以后都往这里加
     * （老工程的 `StructureOverlayRenderer.register()` / `GTETClientCommands` 就挂在对应位置）。
     */
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        GTETSCore.LOGGER.log(Level.INFO, "Initializing client...")
        // 结构工具的选区 / 错误位置覆盖层（GAME 总线的 RenderLevelStageEvent）
        StructureOverlayRenderer.register()
    }
}
