package rain.fox.gtetcore.common.item.terminal

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.GlobalPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/**
 * 高级终端的"另一棵树"数据（老项目里是物品 NBT 的 `gtet_terminal` 子树）。
 *
 * 与 [AdvancedTerminalSettings] 那 8 项开关互不相干；两者各占一个数据组件。
 *
 * ⚠️ 分组缓存与「上次规划目标」是**两件事**：静态组（[TerminalStaticGroups]）只写分组、
 * 不写目标；只有真正 Shift+右键扫描过控制器才会有目标。老项目靠"plan 里没有 dim 键"
 * 来表达这件事，这里就拆成两个字段。
 */
data class TerminalData(
    /** 分级组偏好：组键 → 物品注册名。 */
    val prefs: Map<String, String> = emptyMap(),
    /** 右下「分级方块」面板当前显示哪一组；null = 面板自己退回第 1 组。 */
    val uiGroup: String? = null,
    /** 分级组候选缓存：组键 → 候选物品 id（静态组 + 上次扫描结果）。 */
    val groups: Map<String, List<String>> = emptyMap(),
    /** 上次规划涉及的控制器位置；没扫描过就是 null。 */
    val planTarget: GlobalPos? = null,
    /** AE 链接位置（AE 切片还没移植，先只是存着）。 */
    val aeLink: GlobalPos? = null,
) {

    companion object {

        val CODEC: Codec<TerminalData> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("group_prefs", emptyMap())
                    .forGetter(TerminalData::prefs),
                Codec.STRING.optionalFieldOf("ui_group", "").forGetter { it.uiGroup ?: "" },
                Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf())
                    .optionalFieldOf("groups", emptyMap())
                    .forGetter(TerminalData::groups),
                GlobalPos.CODEC.optionalFieldOf("plan_target").forGetter { java.util.Optional.ofNullable(it.planTarget) },
                GlobalPos.CODEC.optionalFieldOf("ae_link").forGetter { java.util.Optional.ofNullable(it.aeLink) },
            ).apply(instance) { prefs, uiGroup, groups, target, aeLink ->
                TerminalData(prefs, uiGroup.ifEmpty { null }, groups, target.orElse(null), aeLink.orElse(null))
            }
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, TerminalData> = ByteBufCodecs.fromCodec(CODEC)

        @JvmField
        val EMPTY: TerminalData = TerminalData()
    }
}
