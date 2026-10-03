package rain.fox.gtetcore.common.item.timeflow

import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.api.timeflow.ITimeFlowStorage

/**
 * [ITimeFlowStorage] 的**物品实现** —— 时序钟用的就是它。
 *
 * 这是个薄壳：真正的读写都在 [TimeClockData] 里，写进去立刻落在物品的数据组件上，
 * 所以不需要「用完回写」这种同步动作。
 *
 * ⚠️ 老工程这个类叫 `TimeClockNbtStorage` —— 1.21 取消了物品 NBT，名字里的 Nbt 已经不成立，
 * 所以随实现一起改名（类名不是玩家可见的东西，也不在「不许改的 id / 键」之列）。
 *
 * ⚠️ 同一件物品的多个 [ItemStack] 实例各拿各的壳，**互相看不见对方的内存副本**
 * —— 但本实现没有内存副本，读写每次都直查组件，所以不存在跨壳不一致的问题。
 * （注意 `ItemStack` 的组件本身仍是「谁拿着谁改」的语义，客户端改的不会自动同步回服务端。）
 */
class TimeClockStorage private constructor(private val stack: ItemStack) : ITimeFlowStorage {

    override fun getTimeFlow(): Long = TimeClockData.getTimeFlow(stack)

    override fun setTimeFlow(amount: Long): Long = TimeClockData.setTimeFlow(stack, amount)

    override fun getTimeFlowCapacity(): Long = TimeClockData.getCapacity(stack)

    companion object {
        /** 包一层；同一件物品多次调用会得到多个壳（都读写同一个组件，行为一致）。 */
        @JvmStatic
        fun of(stack: ItemStack): TimeClockStorage = TimeClockStorage(stack)
    }
}
