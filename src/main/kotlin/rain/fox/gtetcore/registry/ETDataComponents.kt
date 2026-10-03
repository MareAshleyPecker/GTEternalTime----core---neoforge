package rain.fox.gtetcore.registry

import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalSettings
import rain.fox.gtetcore.common.item.terminal.TerminalData
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
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, GTETCore.ID)

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
}
