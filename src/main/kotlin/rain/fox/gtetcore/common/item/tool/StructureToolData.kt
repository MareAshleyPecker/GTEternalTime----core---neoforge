package rain.fox.gtetcore.common.item.tool

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.registry.ETDataComponents

/**
 * 结构工具两件道具的状态。
 *
 * 老工程（1.20.1）里这些键直接写在物品 NBT 上（`structure_writer` 子树 / `error_pos` 子树）；
 * 1.21 取消了物品 NBT，所以整体搬成两个数据组件（`gtetscore:structure_writer` / `gtetscore:structure_detect`）。
 *
 * ⚠️ 两个组件都必须 `networkSynchronized`：客户端渲染覆盖层（[rain.fox.gtetcore.client.StructureOverlayRenderer]）
 * 要读手里的这份状态。
 */

/**
 * 结构工具（`structure_tools`）的选区与朝向。
 *
 * `start` / `end` 是**有起点语义**的一对角：起点定死不动，之后每次右键只挪终点。
 * 老工程为了兼容更早的只存 min/max 的存档，读的时候还要兜一层 `minX..`；新工程是空盘起步，
 * 没有历史存档，所以那层兼容去掉了，只保留新格式。
 */
data class StructureWriterData(
    /** 固定起点；null = 还没有选区。 */
    val start: BlockPos? = null,
    /** 对角的终点。 */
    val end: BlockPos? = null,
    /** 导出/渲染用的朝向。 */
    val dir: Direction = Direction.WEST,
) {

    /** 选区对外接口：返回 `{最小角, 最大角}`（含端点）。没有选区返回 null。 */
    fun corners(): Array<BlockPos>? {
        val a = start ?: return null
        val b = end ?: return null
        return arrayOf(
            BlockPos(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)),
            BlockPos(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z))
        )
    }

    /** 选区尺寸（含端点，所以最小是 1×1×1）；没有选区返回 null。 */
    fun size(): Triple<Int, Int, Int>? {
        val (min, max) = corners() ?: return null
        return Triple(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
    }

    companion object {

        val CODEC: Codec<StructureWriterData> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockPos.CODEC.optionalFieldOf("start").forGetter { java.util.Optional.ofNullable(it.start) },
                BlockPos.CODEC.optionalFieldOf("end").forGetter { java.util.Optional.ofNullable(it.end) },
                Direction.CODEC.optionalFieldOf("dir", Direction.WEST).forGetter(StructureWriterData::dir),
            ).apply(instance) { start, end, dir ->
                StructureWriterData(start.orElse(null), end.orElse(null), dir)
            }
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, StructureWriterData> =
            ByteBufCodecs.fromCodec(CODEC)

        @JvmField
        val EMPTY: StructureWriterData = StructureWriterData()

        /** 读选区；没写过组件就是空选区。 */
        @JvmStatic
        fun read(stack: ItemStack): StructureWriterData =
            stack.get(ETDataComponents.STRUCTURE_WRITER) ?: EMPTY

        @JvmStatic
        fun write(stack: ItemStack, data: StructureWriterData) {
            stack.set(ETDataComponents.STRUCTURE_WRITER, data)
        }

        /**
         * 把点击位置并入选区：**起点定死，只动对角终点**（往外点变大、往内点变小）。
         *
         * 第一下建立起点（终点 = 起点，零体积），第二下起形成/调整矩形；与老工程 `addPos` 一致。
         */
        @JvmStatic
        fun addPos(stack: ItemStack, pos: BlockPos) {
            val data = read(stack)
            write(stack, if (data.start == null) data.copy(start = pos, end = pos) else data.copy(end = pos))
        }

        /** 清空选区（朝向保留，与老工程只删六个坐标键一致）。 */
        @JvmStatic
        fun clear(stack: ItemStack) {
            write(stack, read(stack).copy(start = null, end = null))
        }

        /**
         * 在给定循环里把朝向推进一格。
         *
         * 老工程用固定 6 向循环（`CYCLE_X/Y/Z`）而不是 `Direction#getClockWise`：
         * 后者在竖直轴上会返回自己，转不动。
         */
        @JvmStatic
        fun rotate(stack: ItemStack, cycle: Array<Direction>) {
            val cur = read(stack).dir
            val index = cycle.indexOf(cur)
            val next = if (index < 0) cycle[0] else cycle[(index + 1) % cycle.size]
            write(stack, read(stack).copy(dir = next))
        }
    }
}

/**
 * 结构检测工具（`structure_detect`）标出的错误位置。
 *
 * [gameTime] 是「这批位置是什么时候检测出来的」（服务端 `Level#getGameTime`）：
 * 客户端拿自己的游戏刻相减判断还在不在配置的停留时间内 —— 两边的游戏刻同步推进（暂停时都不走），
 * 所以「停留 N 秒」和玩家直觉一致。
 */
data class StructureDetectData(
    /** 检测失败的位置。 */
    val positions: List<BlockPos> = emptyList(),
    /** 写入时的服务端游戏刻；[NO_TIME] = 没有时间戳（老物品 / 手改的组件），渲染端当「早过期」。 */
    val gameTime: Long = NO_TIME,
) {

    companion object {

        /** 读不到时间戳时的返回值。 */
        const val NO_TIME: Long = -1L

        val CODEC: Codec<StructureDetectData> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockPos.CODEC.listOf().optionalFieldOf("pos", emptyList())
                    .forGetter(StructureDetectData::positions),
                Codec.LONG.optionalFieldOf("time", NO_TIME).forGetter(StructureDetectData::gameTime),
            ).apply(instance, ::StructureDetectData)
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, StructureDetectData> =
            ByteBufCodecs.fromCodec(CODEC)

        @JvmField
        val EMPTY: StructureDetectData = StructureDetectData()

        @JvmStatic
        fun read(stack: ItemStack): StructureDetectData =
            stack.get(ETDataComponents.STRUCTURE_DETECT) ?: EMPTY

        @JvmStatic
        fun write(stack: ItemStack, data: StructureDetectData) {
            stack.set(ETDataComponents.STRUCTURE_DETECT, data)
        }

        /** 清掉上一批错误位置（右键检测时先清，与老工程 `stack.removeTagKey("error_pos")` 一致）。 */
        @JvmStatic
        fun clear(stack: ItemStack) {
            stack.set(ETDataComponents.STRUCTURE_DETECT, EMPTY)
        }
    }
}
