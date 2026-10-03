package rain.fox.gtetcore.common.item.timeflow

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import java.util.Optional

/**
 * 时序钟的**全部状态**（一个不可变数据类 = 一个数据组件 `gtetscore:time_clock_data`）。
 *
 * ## 为什么是数据组件
 * 1.20.1 时代这些键直接写在物品 NBT 顶层（`time_flow` / `capacity_tier` / `tf_tower_*`）。
 * **1.21 取消了物品 NBT**，`ItemStack#getOrCreateTag` 已不存在，所以整体改成
 * 「不可变 data class + Codec + StreamCodec + 数据组件」——与 [rain.fox.gtetcore.common.item.terminal.AdvancedTerminalSettings]
 * 同一套写法。写入 = 整个对象替换，字段少、没有性能问题。
 *
 * ## 字段与原 NBT 键的对应
 *
 * | 老 NBT 键 | 本类字段 | 说明 |
 * |---|---|---|
 * | `time_flow` | [timeFlow] | 钟内 TF（long） |
 * | `capacity_tier` | [capacityTier] | 容量档位 1~3（L1 / L2 / L3） |
 * | `tf_tower_x/y/z` + `tf_tower_dim` | [tower] | 合并成一个 [GlobalPos]（维度 + 坐标） |
 * | `tf_tower_face` | [towerFace] | 绑定时记下的交互面，按 `Direction#getName()` 存字符串 |
 *
 * 维度的键名从 `tf_tower_dim` 变成 [GlobalPos.CODEC] 自带的形态 —— 这是**语义等价**的改写，
 * 不涉及玩家可见的东西（不是语言键也不是配置键）。
 *
 * ⚠️ 组件必须 `networkSynchronized`：tooltip 在客户端渲染，客户端要能看到钟内 TF 与绑定状态。
 */
data class TimeClockState(
    /** 钟内 TF（long，没有亚 TF 精度）。 */
    val timeFlow: Long = 0L,
    /** 容量档位 1~3；越界值在使用侧夹取。 */
    val capacityTier: Int = ETTimeFlow.CLOCK_TIER_MIN,
    /** 绑定的主控塔（维度 + 坐标）；没绑过是 `null`。 */
    val tower: GlobalPos? = null,
    /** 绑定时记下的交互面；没绑过是 `null`。 */
    val towerFace: Direction? = null,
) {

    companion object {

        val CODEC: Codec<TimeClockState> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.optionalFieldOf("time_flow", 0L).forGetter(TimeClockState::timeFlow),
                Codec.INT.optionalFieldOf("capacity_tier", ETTimeFlow.CLOCK_TIER_MIN)
                    .forGetter(TimeClockState::capacityTier),
                GlobalPos.CODEC.optionalFieldOf("tf_tower")
                    .forGetter { Optional.ofNullable(it.tower) },
                Codec.STRING.optionalFieldOf("tf_tower_face", "")
                    .forGetter { it.towerFace?.name ?: "" },
            ).apply(instance) { timeFlow, tier, tower, face ->
                TimeClockState(
                    timeFlow = timeFlow,
                    capacityTier = tier,
                    tower = tower.orElse(null),
                    towerFace = face.takeIf { it.isNotEmpty() }?.let(Direction::byName),
                )
            }
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, TimeClockState> =
            ByteBufCodecs.fromCodec(CODEC)

        /** 缺组件时用的默认值：空钟、L1 档、未绑定。 */
        @JvmField
        val EMPTY: TimeClockState = TimeClockState()
    }
}
