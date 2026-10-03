package rain.fox.gtetcore

import net.minecraft.resources.ResourceLocation
import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import rain.fox.gtetcore.init.ProxyLoader

/**
 * 模组身份 —— 这里只放「到处都要用、一眼就知道是什么」的东西：
 * 注册名、显示名、日志器、[id] 系列工具。
 *
 * 初始化与事件登记**不在这里**，全在代理里（`init/CommonProxy.kt` 两端都跑、
 * `init/ClientProxy.kt` 只在客户端跑），由 [ProxyLoader] 按物理端装配；
 * [init] 里只剩下「把代理装起来」这一句。
 *
 * ⚠️ 别在这个类上加 `@EventBusSubscriber`：那会触发 KFF 5.7.0 的
 * `AutoKotlinEventBusSubscriber`，它去调 FML 4.0.44 里已删除的 `net.neoforged.fml.Bindings`，
 * mod 构造期直接 `NoClassDefFoundError` 崩（runData 与游戏都起不来）。
 * 事件监听一律走代理里的 `bus.register(this)` + `@SubscribeEvent`。
 *
 * @author rain fox
 */
@Suppress("unused")
@Mod(GTETCore.ID)
object GTETCore {

    /** 注册名（modid）；与 `gradle.properties` 的 `mod_id` 一致，别改。 */
    const val ID = "gtetcore"

    /** 显示名；与 `gradle.properties` 的 `mod_name`（即 `neoforge.mods.toml` 的 displayName）保持一致。 */
    const val NAME = "GTEternalTime -- core --neoforge"

    /** 本模组的日志器。 */
    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.log(Level.INFO, "Hello world!")

        // 全部登记都在代理里，这里只负责把它们装起来。
        ProxyLoader.load()
    }

    /**
     * 本模组命名空间下的 [ResourceLocation]。
     *
     * 资源路径一律走这里，别再手写 `ResourceLocation.fromNamespaceAndPath(GTETCore.ID, ...)`。
     * `@JvmStatic` 是给 Java 侧（mixin、注册文件）用的。
     */
    @JvmStatic
    fun id(name: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ID, name)

    /** 指定命名空间下的 [ResourceLocation]（引用别的模组的资源时用）。 */
    @JvmStatic
    fun id(namespace: String, name: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(namespace, name)
}
