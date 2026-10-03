package rain.fox.gtetcore.common.item.terminal

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.registry.ETDataComponents

/**
 * 高级终端的 8 项设置。
 *
 * 1.20.1 时代这 8 个键是直接写在物品 NBT 顶层的；1.21 取消了物品 NBT，
 * 所以改成**一个数据组件**（`gtetscore:terminal_settings`），编解码由 [CODEC] / [STREAM_CODEC] 负责。
 *
 * ⚠️ 默认值即行为：`noHatch` 默认 **false**（会照 JEI 预览那样把仓室也放上）。
 * 老项目这里默认是 true，导致新终端默认不铺仓室、JEI 里显示的初始仓室位在实搭时全是空气、
 * 机器不成型 —— 这就是那个 bug，移植时一并修掉。
 */
data class AdvancedTerminalSettings(
    /** 线圈等级（0 = 不指定）。 */
    val coilTier: Int = 0,
    /** 重复结构次数。 */
    val repeatCount: Int = 0,
    /** 无仓室模式：仓室格改放对应的机械方块（默认关闭）。 */
    val noHatch: Boolean = false,
    /** 线圈替换模式。 */
    val replaceCoil: Boolean = false,
    /** 使用 AE 物品（AE 链接本阶段不移植，先留着开关）。 */
    val useAe: Boolean = false,
    /** 镜像搭建。 */
    val flip: Boolean = false,
    /** 模块档位（0 = 主结构）。 */
    val module: Int = 0,
    /** 拆除模式。 */
    val demolition: Boolean = false,
) {

    /** 夹到界面上限内，读档与改值都过一遍。 */
    fun clamped(): AdvancedTerminalSettings = copy(
        coilTier = coilTier.coerceAtLeast(0),
        repeatCount = repeatCount.coerceIn(REPEAT_MIN, REPEAT_MAX),
        module = module.coerceIn(MODULE_MIN, MODULE_MAX),
    )

    companion object {

        /** 重复次数的取值范围（界面上限）。 */
        const val REPEAT_MIN: Int = 0
        const val REPEAT_MAX: Int = 1000

        /** 模块档位的取值范围（界面上限）。 */
        const val MODULE_MIN: Int = 0
        const val MODULE_MAX: Int = 100

        val CODEC: Codec<AdvancedTerminalSettings> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("coil_tier", 0).forGetter(AdvancedTerminalSettings::coilTier),
                Codec.INT.optionalFieldOf("repeat_count", 0).forGetter(AdvancedTerminalSettings::repeatCount),
                Codec.BOOL.optionalFieldOf("no_hatch", false).forGetter(AdvancedTerminalSettings::noHatch),
                Codec.BOOL.optionalFieldOf("replace_coil", false).forGetter(AdvancedTerminalSettings::replaceCoil),
                Codec.BOOL.optionalFieldOf("use_ae", false).forGetter(AdvancedTerminalSettings::useAe),
                Codec.BOOL.optionalFieldOf("flip", false).forGetter(AdvancedTerminalSettings::flip),
                Codec.INT.optionalFieldOf("module", 0).forGetter(AdvancedTerminalSettings::module),
                Codec.BOOL.optionalFieldOf("demolition", false).forGetter(AdvancedTerminalSettings::demolition),
            ).apply(instance, ::AdvancedTerminalSettings)
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, AdvancedTerminalSettings> =
            ByteBufCodecs.fromCodec(CODEC)

        /** 缺组件时用的默认值。 */
        @JvmField
        val DEFAULT: AdvancedTerminalSettings = AdvancedTerminalSettings()

        /** 读设置；没写过组件就返回默认值。 */
        @JvmStatic
        fun read(stack: ItemStack): AdvancedTerminalSettings =
            (stack.get(ETDataComponents.TERMINAL_SETTINGS) ?: DEFAULT).clamped()

        /** 写设置（会先夹一遍取值范围）。 */
        @JvmStatic
        fun write(stack: ItemStack, settings: AdvancedTerminalSettings) {
            stack.set(ETDataComponents.TERMINAL_SETTINGS, settings.clamped())
        }

        /** 改单项设置，返回新的设置对象。 */
        @Suppress("unused")
        @JvmStatic
        fun modify(stack: ItemStack, change: (AdvancedTerminalSettings) -> AdvancedTerminalSettings) {
            write(stack, change(read(stack)))
        }
    }
}
