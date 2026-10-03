package rain.fox.gtetcore.api.timeflow

/**
 * 时间流（TF）存储 —— 塔 / 仓 / 钟共用的最小接口。
 *
 * 约定：
 * - TF 一律用 **long** 存（整数），**没有亚 TF 精度**；容量上限也以 TF 计。
 * - [setTimeFlow] 是唯一的写入口，实现方**必须**自行把值夹到 `0..getTimeFlowCapacity()`，
 *   并把夹取后的实际值返回；超容量的部分视作丢弃，调用方要靠返回值判断。
 * - 本期只有一个实现：[rain.fox.gtetcore.common.item.timeflow.TimeClockStorage]（物品数据组件）。
 *
 * @see ETTimeFlow 单位与换算
 */
interface ITimeFlowStorage {

    /** 当前存量（TF）。 */
    fun getTimeFlow(): Long

    /** 直接写入存量；返回夹取后的实际值。 */
    fun setTimeFlow(amount: Long): Long

    /** 容量上限（TF）。 */
    fun getTimeFlowCapacity(): Long

    /** 还能装多少（TF）。 */
    fun getTimeFlowRoom(): Long = (getTimeFlowCapacity() - getTimeFlow()).coerceAtLeast(0L)

    /** 是否已清空。 */
    fun isTimeFlowEmpty(): Boolean = getTimeFlow() <= 0L

    /** 是否已装满。 */
    fun isTimeFlowFull(): Boolean = getTimeFlow() >= getTimeFlowCapacity()

    /**
     * 充入，返回**实际收下**的 TF（装不下的留在调用方手里）。
     *
     * 充入效率 η₁ = 100%（设定 §3.3 基线），所以这里不做折损；折损发生在主控塔的换汇口径上。
     */
    fun receiveTimeFlow(amount: Long): Long {
        if (amount <= 0L) return 0L
        val accepted = amount.coerceAtMost(getTimeFlowRoom())
        if (accepted <= 0L) return 0L
        setTimeFlow(getTimeFlow() + accepted)
        return accepted
    }

    /**
     * 取出，返回**实际取出**的 TF（不够就有多少取多少，不报错）。
     *
     * 取出效率 η₂ = 80%（设定 §3.3 基线），折损同样不在这里做。
     */
    fun extractTimeFlow(amount: Long): Long {
        if (amount <= 0L) return 0L
        val taken = amount.coerceAtMost(getTimeFlow())
        if (taken <= 0L) return 0L
        setTimeFlow(getTimeFlow() - taken)
        return taken
    }
}
