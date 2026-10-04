package rain.fox.gtetcore.registry

import com.mojang.serialization.Codec
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.item.recipe.RecipeEditorData
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalSettings
import rain.fox.gtetcore.common.item.terminal.TerminalData
import rain.fox.gtetcore.common.item.timeflow.TimeClockState
import rain.fox.gtetcore.common.item.tool.StructureDetectData
import rain.fox.gtetcore.common.item.tool.StructureWriterData
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

    /**
     * 主控塔被敲掉时封存进掉落物的 TF 储备（塔芯）；`MetaMachine#collectImplicitComponents` 写、
     * `applyImplicitComponents` 读（`MetaMachineBlock.java:252` 的 `saveToItem` 是入口）。
     * 必须 `networkSynchronized` —— 掉落物 tooltip 在客户端渲染。
     */
    @JvmField
    val MASTER_TOWER_RESERVE: DeferredHolder<DataComponentType<*>, DataComponentType<Long>> =
        REGISTRY.register("master_tower_reserve", Supplier {
            DataComponentType.builder<Long>()
                .persistent(Codec.LONG)
                .networkSynchronized(ByteBufCodecs.VAR_LONG)
                .build()
        })

    /**
     * 结构工具（`structure_tools`）的选区与朝向（老工程物品 NBT 的 `structure_writer` 子树）。
     * 必须 `networkSynchronized` —— 选区的半透明立方体是客户端画的。
     */
    @JvmField
    val STRUCTURE_WRITER: DeferredHolder<DataComponentType<*>, DataComponentType<StructureWriterData>> =
        REGISTRY.register("structure_writer", Supplier {
            DataComponentType.builder<StructureWriterData>()
                .persistent(StructureWriterData.CODEC)
                .networkSynchronized(StructureWriterData.STREAM_CODEC)
                .build()
        })

    /**
     * 结构检测工具（`structure_detect`）标出的错误位置与检测时刻
     * （老工程物品 NBT 的 `error_pos` 子树）。同上，错误框在客户端画。
     */
    @JvmField
    val STRUCTURE_DETECT: DeferredHolder<DataComponentType<*>, DataComponentType<StructureDetectData>> =
        REGISTRY.register("structure_detect", Supplier {
            DataComponentType.builder<StructureDetectData>()
                .persistent(StructureDetectData.CODEC)
                .networkSynchronized(StructureDetectData.STREAM_CODEC)
                .build()
        })

    /**
     * 配方编辑器（`recipe_editor`）的整份草稿（老工程物品 NBT 的 `recipe_editor` 子树）。
     *
     * 必须 `networkSynchronized`：面板在客户端与服务端各建一次，客户端那一刻要靠它拿到
     * 「这次打开时草稿长什么样」（配方种类 / 各段槽数 / 代码预览文案），否则会先闪一下空状态。
     */
    @JvmField
    val RECIPE_EDITOR: DeferredHolder<DataComponentType<*>, DataComponentType<RecipeEditorData>> =
        REGISTRY.register("recipe_editor", Supplier {
            DataComponentType.builder<RecipeEditorData>()
                .persistent(RecipeEditorData.CODEC)
                .networkSynchronized(RecipeEditorData.STREAM_CODEC)
                .build()
        })
}
