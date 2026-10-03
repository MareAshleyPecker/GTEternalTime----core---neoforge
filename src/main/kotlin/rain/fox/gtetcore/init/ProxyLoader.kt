package rain.fox.gtetcore.init

import net.neoforged.fml.loading.FMLEnvironment
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
import thedarkcolour.kotlinforforge.neoforge.forge.runForDist

/**
 * 代理装配 —— 按**物理端**决定装哪几个代理。由 `GTETSCore.init` 调用。
 *
 * 规则（老工程 `rain.gtetcore.gtet.init` 的拆法的 NeoForge 版）：
 * - [CommonProxy]：配置 / 数据组件 / registrate 登记触发 / 语言键 / 通用事件监听 —— **两端都装**；
 * - [ClientProxy]：渲染器 / 按键 / 客户端命令这些客户端专属的东西 —— **只在客户端装**。
 *
 * ⚠️ 老工程是 `DistExecutor.unsafeRunForDist({ ClientProxy }, { CommonProxy })` +
 * `ClientProxy : CommonProxy`，**只构造一个**代理。这边**不能继承**：NeoForge 的事件总线
 * （`net.neoforged.bus.EventBus.register`）会先 `checkSupertypes` **递归拒绝所有父类 / 接口里
 * 声明的 `@SubscribeEvent`**，`ClientProxy` 一注册就 `IllegalArgumentException` 崩在 mod 构造期
 * —— 继承来的监听器既不会被登记、也不允许存在。
 * 所以改成平级两层：**两端都构造 [CommonProxy]，客户端再额外构造 [ClientProxy]**。
 *
 * ⚠️ [ClientProxy] 的引用**必须留在下面这个 `runForDist` 的客户端分支里**：专用服务端不执行那个
 * 分支，JVM 就不会去解析 / 加载那个类（它带着 `@OnlyIn(Dist.CLIENT)`）。
 * 挪到 `runForDist` 外面，专用服务端就会连带加载客户端的类。
 */
object ProxyLoader {

    /** 装配代理。整个 mod 只会从这里走一次（`GTETSCore.init`）。 */
    @JvmStatic
    fun load() {
        // 通用代理：两端都要
        CommonProxy()

        // 客户端代理：只在客户端构造（它自己负责挂客户端专属的监听器与渲染器）
        runForDist(
            clientTarget = { ClientProxy() },
            serverTarget = { }
        )

        GTETSCore.LOGGER.log(Level.INFO, "[GTET] 代理已装配（dist = {}）", FMLEnvironment.dist)
    }
}
