package rain.fox.gtetcore.common.item.recipe

import brachy.modularui.utils.handlers.fluid.IMultiTankFluidHandler
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.fluids.IFluidTank
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/**
 * 配方编辑器的多槽「幽灵流体罐」—— 只存草稿，不参与任何真实物流。
 *
 * 为什么不直接用 NeoForge 的 `FluidTank`：
 *  1. `FluidTank` 是**单槽**，而配方编辑器一个配方可能要 4 个流体输入（装配线）、
 *     6 个流体输出（离心机 / 电解机），必须多槽；
 *  2. `FluidTank` 有真实容量上限并会按上限截断，而草稿里的量是「配方要多少」，
 *     应该原样保存（用户填 2000 mB 就该存 2000 mB，不该被罐子容量砍掉）；
 *  3. 要能整份写进物品的数据组件（跟 [RecipeDraft.inputs] / [RecipeDraft.outputs] 一个套路）。
 *
 * ⚠️ 老工程（1.20.1 / MUI 前身 LDLib）里这个类实现的是 Forge 的 `IFluidHandler`，
 * 界面侧交给 GTCEu 的 `PhantomFluidWidget`；新工程的界面是 MUI，MUI 的 `FluidSlot`
 * 要的是 [IMultiTankFluidHandler]（`getFluidTank(int)` 逐槽给出 [IFluidTank]），
 * 所以这里换成实现它 —— 语义（越界不抛、原样存、按容量画液面）一字不改。
 *
 * @author rain fox
 */
class DraftFluidTanks(
    /** 槽位数量（构造时定死，之后永不改变 —— 见 [setFluid] 与 [RecipeDraft.fromData]）。 */
    private val capacity: Int,
) : IMultiTankFluidHandler {

    private val tanks: Array<FluidStack> = Array(capacity) { FluidStack.EMPTY }

    /**
     * 「任一槽被真的写脏了」的回调。
     *
     * ⚠️ MUI 的 `IFluidTank` 没有 contents-changed 事件（`CustomFluidTank` 有，但它只支持单槽
     * 且会按容量截断，两条都不合本类的要求），所以回调由本类自己在 [setFluid] 末尾发。
     * 面板把它接到「把草稿写回物品数据组件」上（见 `RecipeEditorPanel` 的 `touch()`）。
     */
    var onChanged: (() -> Unit)? = null

    /**
     * 「拖入流体时用多少 mB」的默认量；返回 `0` = 用流体自带的数量。
     *
     * 老界面有一个独立的「流体量 (mB)」输入框，`PhantomFluidWidget` 的 setter 装填时会按它
     * **覆盖**拖进来的量（GT 配方里 144 / 576 / 2000 都常见，照搬一桶 1000 做不出配方）。
     * MUI 的 `FluidSlotSyncHandler` 装填走的就是 [IFluidTank.fill]，所以那个覆盖改在这里做 ——
     * 用 lambda 而不是 Int 是因为「流体量」字段在界面上随时会改。
     */
    var defaultFillAmount: () -> Int = { 0 }

    // ======================== 草稿侧读写 ========================

    /** 第 [i] 个槽的流体（越界返回空，绝不抛异常）。 */
    fun getFluid(i: Int): FluidStack = if (i in tanks.indices) tanks[i] else FluidStack.EMPTY

    /**
     * 设置第 [i] 个槽。`null` / 空栈都表示「清空」。
     *
     * [amount] 大于 0 时用它覆盖流体自带的量；存进去的是 [FluidStack.copy]，
     * 不是调用方的那个对象（拖进来的栈可能来自 JEI/EMI 的缓存，直接持有引用的话别人一改草稿就跟着变）。
     *
     * 内容没有真的变化时**不发** [onChanged]（否则界面每 tick 的同步都会被当成一次编辑）。
     */
    fun setFluid(i: Int, stack: FluidStack?, amount: Int = 0) {
        if (i !in tanks.indices) return
        val old = tanks[i]
        if (stack == null || stack.isEmpty) {
            if (old.isEmpty) return
            tanks[i] = FluidStack.EMPTY
        } else {
            val copy = stack.copy()
            if (amount > 0) copy.amount = amount
            if (FluidStack.isSameFluidSameComponents(copy, old) && copy.amount == old.amount) return
            tanks[i] = copy
        }
        onChanged?.invoke()
    }

    /** 全部槽清空（读存档前用，见 [RecipeDraft.fromData]）。 */
    fun clear() {
        tanks.fill(FluidStack.EMPTY)
    }

    /** 是否全空。 */
    fun isEmpty(): Boolean = tanks.all { it.isEmpty }

    /** 给数据组件用的快照（定长，空槽也占位）。 */
    fun snapshot(): List<FluidStack> = tanks.map { if (it.isEmpty) FluidStack.EMPTY else it.copy() }

    // ======================== IFluidHandler / IMultiTankFluidHandler ========================
    // 这些方法只服务于界面控件的显示与交互，不是真实物流。

    override fun getTanks(): Int = capacity

    override fun getFluidInTank(tank: Int): FluidStack = getFluid(tank)

    /**
     * 单槽「容量」。
     *
     * ⚠️ 这里返回的**不是**罐子的真实上限（草稿本来就没有上限），而是刻意返回
     * `max(1 桶, 当前量)`：
     *  - 槽控件画流体时算的是 `量 / 容量`，容量取真实大数（比如 1000000）的话，
     *    填 1000 mB 的槽只画出千分之一的液面，幽灵槽看起来就跟空的一样；
     *  - 控件悬浮提示里的「当前量 / 容量」读起来是「1000 / 1000 mB」，
     *    对一个幽灵槽来说比「1000 / 1000000 mB」合理得多。
     */
    override fun getTankCapacity(tank: Int): Int = maxOf(FluidType.BUCKET_VOLUME, getFluid(tank).amount)

    override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = true

    /** 单槽视图：MUI 的 `FluidSlot` / `FluidSlotSyncHandler` 只认 [IFluidTank]。 */
    override fun getFluidTank(tank: Int): IFluidTank = TankView(tank)

    /** 找第一个空槽或同流体槽放进去（幽灵语义：直接覆盖，不按容量截断）。 */
    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
        if (resource.isEmpty) return 0
        val target = tanks.indexOfFirst { it.isEmpty || FluidStack.isSameFluidSameComponents(it, resource) }
        if (target < 0) return 0
        if (action.execute()) {
            val amount = if (tanks[target].isEmpty) defaultFillAmount() else 0
            setFluid(target, resource, amount)
        }
        return resource.amount
    }

    /** 按流体种类取走整槽。 */
    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if (resource.isEmpty) return FluidStack.EMPTY
        val target = tanks.indexOfFirst { !it.isEmpty && FluidStack.isSameFluidSameComponents(it, resource) }
        if (target < 0) return FluidStack.EMPTY
        val drained = tanks[target].copy()
        if (action.execute()) setFluid(target, null)
        return drained
    }

    /** 按数量取走第一个非空槽（数量不足就只给这么多）。 */
    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        if (maxDrain <= 0) return FluidStack.EMPTY
        val target = tanks.indexOfFirst { !it.isEmpty }
        if (target < 0) return FluidStack.EMPTY
        val stack = tanks[target]
        val drained = stack.copy()
        drained.amount = minOf(maxDrain, stack.amount)
        if (action.execute()) {
            if (drained.amount >= stack.amount) {
                setFluid(target, null)
            } else {
                val rest = stack.copy()
                rest.amount = stack.amount - drained.amount
                setFluid(target, rest)
            }
        }
        return drained
    }

    /** 某一槽的单槽视图；所有写入都绕回外层 [setFluid]，所以 [onChanged] 照常触发。 */
    private inner class TankView(private val index: Int) : IFluidTank {

        override fun getFluid(): FluidStack = getFluid(index)

        override fun getFluidAmount(): Int = getFluid(index).amount

        override fun getCapacity(): Int = getTankCapacity(index)

        override fun isFluidValid(stack: FluidStack): Boolean = true

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
            if (resource.isEmpty) return 0
            if (action.execute()) {
                val amount = if (getFluid(index).isEmpty) defaultFillAmount() else 0
                setFluid(index, resource, amount)
            }
            return resource.amount
        }

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
            if (maxDrain <= 0) return FluidStack.EMPTY
            val stack = getFluid(index)
            if (stack.isEmpty) return FluidStack.EMPTY
            val drained = stack.copy()
            drained.amount = minOf(maxDrain, stack.amount)
            if (action.execute()) {
                if (drained.amount >= stack.amount) {
                    setFluid(index, null)
                } else {
                    val rest = stack.copy()
                    rest.amount = stack.amount - drained.amount
                    setFluid(index, rest)
                }
            }
            return drained
        }

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
            if (resource.isEmpty) return FluidStack.EMPTY
            val stack = getFluid(index)
            if (stack.isEmpty || !FluidStack.isSameFluidSameComponents(stack, resource)) return FluidStack.EMPTY
            val drained = stack.copy()
            if (action.execute()) setFluid(index, null)
            return drained
        }
    }
}
