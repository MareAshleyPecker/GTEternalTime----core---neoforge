package rain.fox.gtetcore.common.item.terminal

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import rain.fox.gtetcore.Gtetcore

/**
 * 把 [TerminalStaticGroups] 的 6 类静态组预置进玩家手上的高级终端数据组件。
 *
 * 目的：终端面板读的是终端里的分级组（`gtetcore:terminal_data` 的 groups），
 * 只有「潜行右键控制器扫描过」才有内容。这里在服务端每 tick 看一眼主手物品，
 * 是终端就把静态组补进去，于是**不扫描也能直接列出**线圈 / 能源仓 / 超频仓 / 线程仓 / 并行仓 / 维护仓这 6 类。
 *
 * ⚠️ **必须服务端写**：客户端改自己背包物品的组件不会同步回服务端（写了等于没写）。
 * 之所以用「每 tick 检查主手」而不是「打开面板时补一次」：面板打开那一刻才写的话，
 * 客户端那一次读数拿到的还是旧值（第一次打开必然对不齐）；tick 里提前写好，客户端随物品同步自然拿到同一份数据。
 * 写组件本身很轻：[TerminalSettings.installStaticGroups] 发现「该有的组都在」就直接返回 false、一个字节都不改。
 *
 * ⚠️ 老版 `TickEvent.PlayerTickEvent` + `Phase.END` 现在是 [PlayerTickEvent.Post]。
 *
 * ⚠️ **这里刻意不用 `@EventBusSubscriber`**（老项目用的是它）：NeoForge 21.1.252 的注册器已不读
 * `bus` 成员（那个枚举 `@Deprecated(forRemoval = true)`），而 KFF 5.7.0 的
 * `AutoKotlinEventBusSubscriber` 又会去调 FML 4.0.44 里**已被删除**的 `net.neoforged.fml.Bindings`
 * —— 只要存在带该注解的 Kotlin `object`，mod 构造期就 `NoClassDefFoundError` 直接崩（runData/游戏都起不来）。
 * 所以改由 `Gtetcore.init` 里手工挂到 GAME 总线（KFF 源码里也建议直接手工注册）。
 */
object TerminalGroupSeeder {

    /** 自建高级终端的注册名。 */
    private const val TERMINAL_ID = "gtetcore:advanced_terminal"

    /** 懒解析 + 缓存的目标物品。 */
    private val TERMINALS: MutableList<Item> = ArrayList(1)

    private var resolved = false

    /** GAME 总线监听；注册见 [Gtetcore.init]（`NeoForge.EVENT_BUS.addListener`）。 */
    @JvmStatic
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity
        if (player.level().isClientSide) return

        // 只看主手：面板读的也是主手物品，两边必须同一只手
        val held = player.mainHandItem
        if (held.isEmpty) return
        for (terminal in terminals()) {
            if (held.item === terminal) {
                TerminalSettings.installStaticGroups(held)
                break
            }
        }
    }

    /** 按注册名查（不引用终端类，避免注册期的类初始化环路），查不到就跳过；只在第一次调用时解析。 */
    private fun terminals(): List<Item> {
        if (!resolved) {
            resolved = true
            val location = ResourceLocation.tryParse(TERMINAL_ID)
            if (location != null) {
                // ⚠️ 1.21 的物品注册表是原版的 BuiltInRegistries.ITEM ，它是 DefaultedRegistry：
                //    用 get() 查不到会静默回落到 air，所以这里必须用 getOptional
                BuiltInRegistries.ITEM.getOptional(location).ifPresent { item -> TERMINALS.add(item) }
            }
        }
        return TERMINALS
    }
}
