package rain.fox.gtetcore.util

import net.minecraft.network.chat.Component
import rain.fox.gtetcore.config.GtetConfig

/**
 * 「多方块共享」那一行 tooltip 的共用出口。
 *
 * 两个语言键都是 GTM 自带的（`gtceu.part_sharing.disabled` / `.enabled`），本 mod 只引用不新增。
 * 它显示的是**全局开关当前值**对应的状态：默认配置下恒显示 Disabled，
 * 对写死隔离的部件（超频仓 / 线程仓 / 并行仓等）就是事实。
 *
 * 调用点写在注册的 `tooltipBuilder` lambda 里（渲染 tooltip 时才取值），
 * 所以改配置不需要重启、也不用重新注册机器。真正决定能不能共享的是机器类的 `canShared()`。
 */
object ETPartSharing {

    /** GTM 自带的「禁止共享」键（描述默认状态）。 */
    private const val DISABLED_KEY: String = "gtceu.part_sharing.disabled"

    /** GTM 自带的「允许共享」键。 */
    private const val ENABLED_KEY: String = "gtceu.part_sharing.enabled"

    /** 按全局开关 [GtetConfig.partsShareable] 返回「多方块共享」那一个 [Component]。 */
    @JvmStatic
    fun line(): Component = Component.translatable(
        if (GtetConfig.partsShareable()) ENABLED_KEY else DISABLED_KEY
    )
}
