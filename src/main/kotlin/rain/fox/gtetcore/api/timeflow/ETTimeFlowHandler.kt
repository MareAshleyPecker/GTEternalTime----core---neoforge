package rain.fox.gtetcore.api.timeflow

import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableRecipeHandlerTrait
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField

/**
 * **TF 内容处理器**：GTM 真正用来扣 TF 的那个口子，内部就是一个 long 缓冲区。
 *
 * ## GTM 什么时候、从哪条路径调它（8.0.0 实测的调用链）
 * 「每 tick 扣一次」成立与否全看下面这条链路：
 * ```
 * RecipeLogic#serverTick                                  (RecipeLogic.java:254)
 *  └─ handleRecipeWorking()                               (:322)
 *      ├─ handleTickRecipe(recipe)                        (:431)
 *      │   ├─ RecipeHelper.matchTickRecipe(...)           (:434 → RecipeHelper.java:188) ← simulated=true
 *      │   └─ handleTickRecipeIO(recipe, IO.IN)           (:445 → :640 → RecipeHelper.java:211) ← simulated=false
 *      │        └─ RecipeHelper.handleRecipe(...)          (RecipeHelper.java:224)
 *      │             └─ RecipeRunner#handleContents()      (RecipeRunner.java:121 → :165 / :226)
 *      │                  └─ RecipeHandlerList#handleRecipe(io, recipe, contents, simulate)  (:178 → :191)
 *      │                       └─ IRecipeHandler#handleRecipe(...)  (IRecipeHandler.java:78-84 的 default)
 *      │                            └─ **本类的 handleRecipeInner(io, recipe, left, simulate)**  (:40)
 *      └─ 成功后 progress++ ；失败则 setWaiting(:336)
 * ```
 * 结论：**只要一条配方的 `tickInputs` 里带 `gtetscore:time_flow`，配方每跑一 tick 就会以
 * `simulate=false` 调一次 [handleRecipeInner]**；匹配阶段（`simulate=true`）每 tick 也会被调一次，
 * 但不扣钱。
 *
 * ## 为什么实现 [NotifiableRecipeHandlerTrait]（= 8.0.0 唯一正确的形态）
 * 老工程（7.5.3）里本类**刻意不继承 `MachineTrait`**，然后在机器那边手工 `RecipeHandlerList.of(...)`；
 * 那条路在 8.0.0 走不通，理由有两个，都是读源码核实的：
 * 1. `MultiblockPartMachine#getHandlerList()` 是拿 `getTraitHolder().getTraitsByInterface(IRecipeHandlerTrait.class)`
 *    收集处理器的（`MultiblockPartMachine.java:131-134`）—— 不在 trait 表里的对象**永远进不去**该列表，
 *    8.0.0 的控制器只认这一条路（`WorkableMultiblockMachine` 成型时把 `part.getRecipeHandlers()` 收进去）；
 * 2. 8.0.0 的存档/同步是 `api.sync_system`（LDLib syncdata 已弃用）：`@SaveField` 只对
 *    `ISyncManaged` 的东西生效（`ClassSyncData.java:53`），而 `MachineTrait` 才是 `ISyncManaged`
 *    （`MachineTrait.java:41`）。不继承它，缓冲量就只能靠手写 NBT 通道另存一份。
 * 继承之后三件事一起到手：**自动进多方块的处理器表、缓冲量自动落盘、变更订阅现成的**，
 * 机器那边一行 `getRecipeHandlers()` 都不用覆写。
 *
 * ## 存档
 * 真值在 [storedTf]（标了 `@SaveField`）。值不是暴露给外部的字段而是包在 [amount] 里的**夹取读**：
 * 8.0.0 的 sync 系统是拿反射/MethodHandle 直接写字段的（`SyncDataHolder$SyncManagedTransformer`，
 * `SyncDataHolder.java:249-260`），**绕不过属性 setter**，所以「换一档更小的仓、读回旧存档的越界值」
 * 这件事只能在读的时候兜住。`nbtKey` 保持老工程那个键名 `time_flow`。
 *
 * ## 与「时序仓 / 动力仓」的分工
 * 本类只管**缓冲与扣费**，不负责从塔里拉货、也不做速率整形；上下限由构造参数给出。
 * 真实仓（时流仓 / 动力仓）各持有一个本类实例，并在自己的 tick 里 [insert] / [extract]。
 *
 * @param initialAmount 初始存量，单位 TF
 * @param capacity      缓冲上限，单位 TF（默认 `Long.MAX_VALUE` = 不设限）
 *
 * @author rain fox
 */
class ETTimeFlowHandler(
    initialAmount: Long = 0L,
    @JvmField val capacity: Long = Long.MAX_VALUE,
) : NotifiableRecipeHandlerTrait<ETTimeFlowStack>() {

    /**
     * 缓冲区里的 TF 存量真值。
     *
     * ⚠️ 会落盘（`@SaveField`），但**不要直接读它** —— 读 [amount]（会夹取）。
     * 读档时 sync 系统直接写这个字段，越界值只可能在这里出现。
     */
    @field:SaveField(nbtKey = "time_flow")
    private var storedTf: Long = initialAmount.coerceIn(0L, capacity)

    /** 缓冲区里的 TF 存量（已按 [capacity] 夹取）。 */
    val amount: Long get() = storedTf.coerceIn(0L, capacity)

    /** 收入 TF（塔 / 动力仓往里灌）。返回**没装下**的那部分（与 [ITimeFlowStorage.receiveTimeFlow] 相反，注意别混）。 */
    fun insert(toInsert: Long): Long {
        if (toInsert <= 0L) return 0L
        val accepted = toInsert.coerceAtMost(capacity - amount)
        if (accepted <= 0L) return toInsert
        storedTf = amount + accepted
        notifyListeners()
        return toInsert - accepted
    }

    /** 取出 TF（时序钟 / 手动取）。返回**实际取到**的数量。 */
    fun extract(toExtract: Long): Long {
        if (toExtract <= 0L) return 0L
        val taken = toExtract.coerceAtMost(amount)
        if (taken <= 0L) return 0L
        storedTf = amount - taken
        notifyListeners()
        return taken
    }

    override fun getCapability(): RecipeCapability<ETTimeFlowStack> = ETTimeFlowCapability.CAP

    /** TF 仓是**输入侧**仓（把 TF 供进配方）。 */
    override fun getHandlerIO(): IO = IO.IN

    override fun getContents(): List<Any> =
        if (amount > 0L) listOf(ETTimeFlowStack(amount)) else emptyList()

    override fun getTotalContentAmount(): Double = amount.toDouble()

    /**
     * 扣费本体。
     *
     * ### 返回值语义（8.0.0 收紧了：**不许返回 `null`**）
     * 7.5.3 的约定是「`null` = 已结清、非 null = 剩下的量」，8.0.0 把返回标成了 `@NotNull`
     * （`IRecipeHandler.java:39-40`，注释里明写 “Returning {@code null} is not allowed.”），
     * **结清要返回空表**。所以这里是**尽量多扣、扣不完的原样退回**（空表 = 结清）。
     *
     * 一台多方块里挂两个 TF 仓时它们会自动合池（`RecipeHandlerList.java:191` 会把没处理完的
     * 交给同组的下一个仓）。
     *
     * 会不会出现「扣了一部分而整 tick 失败 ⇒ 白烧 TF」？不会：
     * `RecipeLogic#handleTickRecipe` 先跑 `simulate=true` 的匹配（`RecipeLogic.java:434`），
     * 匹配不过就直接 `setWaiting` 走人（:336），根本到不了这次真实扣费（:445）。
     *
     * @param io      调用方给的 IO；只有与本仓 [getHandlerIO] 一致时才处理
     * @param simulate `true` = 只试算不扣（匹配阶段）
     * @return 剩余未满足的 TF；**空表**表示已结清
     */
    override fun handleRecipeInner(
        io: IO,
        recipe: GTRecipe?,
        left: List<ETTimeFlowStack>,
        simulate: Boolean,
    ): List<ETTimeFlowStack> {
        if (io != getHandlerIO()) return left

        var remaining = 0L
        for (stack in left) if (stack.amount > 0L) remaining += stack.amount
        if (remaining <= 0L) return emptyList()
        if (amount <= 0L) return left

        val consumed = remaining.coerceAtMost(amount)
        if (!simulate && consumed > 0L) {
            storedTf = amount - consumed
            notifyListeners()
        }

        val leftover = remaining - consumed
        return if (leftover <= 0L) emptyList() else listOf(ETTimeFlowStack(leftover))
    }
}
