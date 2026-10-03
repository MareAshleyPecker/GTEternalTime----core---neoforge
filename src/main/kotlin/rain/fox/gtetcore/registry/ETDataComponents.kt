package rain.fox.gtetcore.registry

import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalSettings
import rain.fox.gtetcore.common.item.terminal.TerminalData
import rain.fox.gtetcore.common.item.timeflow.TimeClockState
import java.util.function.Supplier

/**
 * GTET 的数据组件登记处（1.21 取代了物品 NBT）。
 *
 * 目前只有高级终端的设置；后面移植其它带 NBT 的物品时（M2）都往这里加。
 * ⚠️ 必须在 mod 构造期把 [REGISTRY] 挂到 mod 事件总线上（见 `CommonProxy.kotlinInit`）。
 */
object ETDataComponents {

    @JvmField
    val REGISTRY: DeferredRegister<DataComponentType<*>> =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, GTETSCore.ID)

    /** 高级终端的 8 项设置。 */
    @JvmField
    val TERMINAL_SETTINGS: DeferredHolder<DataComponentType<*>, DataComponentType<AdvancedTerminalSettings>> =
        // ⚠️ 必须显式写 Supplier：NeoForge 这里还有 Function<ResourceLocation, I> 的重载，lambda 会歧义
        REGISTRY.register("terminal_settings", Supplier {
            DataComponentType.builder<AdvancedTerminalSettings>()
                .persistent(AdvancedTerminalSettings.CODEC)
                .networkSynchronized(AdvancedTerminalSettings.STREAM_CODEC)
                .build()
        })

    /** 高级终端的另一棵树：分级组偏好 / 界面当前组 / 分组缓存 / 上次规划目标 / AE 链接。 */
    @JvmField
    val TERMINAL_DATA: DeferredHolder<DataComponentType<*>, DataComponentType<TerminalData>> =
        REGISTRY.register("terminal_data", Supplier {
            DataComponentType.builder<TerminalData>()
                .persistent(TerminalData.CODEC)
                .networkSynchronized(TerminalData.STREAM_CODEC)
                .build()
        })

    /**
     * 时序钟的全部状态（钟内 TF、容量档位、绑定的主控塔）。
     *
     * 1.21 没有物品 NBT，老工程写在那几个 NBT 键上的东西整体搬到这里；
     * 必须 `networkSynchronized` —— tooltip 在客户端渲染。
     */
    @JvmField
    val TIME_CLOCK_DATA: DeferredHolder<DataComponentType<*>, DataComponentType<TimeClockState>> =
        REGISTRY.register("time_clock_data", Supplier {
            DataComponentType.builder<TimeClockState>()
                .persistent(TimeClockState.CODEC)
                .networkSynchronized(TimeClockState.STREAM_CODEC)
                .build()
        })
}
