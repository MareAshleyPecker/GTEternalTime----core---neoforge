package rain.fox.gtetcore.common.machine.multiblock.thread

import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier
import com.gregtechceu.gtceu.api.recipe.modifier.ModifierFunction
import com.gregtechceu.gtceu.api.recipe.modifier.ParallelLogic
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.common.machine.multiblock.part.ParallelHatchPartMachine
import it.unimi.dsi.fastutil.objects.Object2IntMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.api.capability.IThreadedRecipeMachine
import java.util.IdentityHashMap

/** 一条线程的概率缓存类型（`RecipeLogic#makeChanceCaches()` 的返回形状）。 */
private typealias ChanceCaches = IdentityHashMap<RecipeCapability<*>, Object2IntMap<*>>

/**
 * 「多线程配方逻辑内核」：一张线程表，一个线程槽跑一种配方，每条线程**自己计时、自己扣料、自己结算**，
 * 每条线程各吃一遍并行仓的并行倍率。
 *
 * ## 调度（每个 `serverTick`）
 * 1. **推进**：遍历已在跑的线程槽，各自 `checkConditions` → 每 tick 输入/输出 → `progress++`，到点结算；
 * 2. **开新线程**：只在有空闲槽位时搜配方（每 [SEARCH_INTERVAL] tick 一次），分两轮：
 *    第一轮「不同配方各占一条」（原行为），第二轮「已在跑的配方再吃剩下的空闲线程」（吃线程并行）；
 * 3. **上限**：[threadLimit] = 线程仓的 `threadCount`，没装仓就是 1。
 *
 * ## 第二轮为什么「一组一预算、再均分」
 * `ParallelLogic#getParallelAmount` 看的是**当下库存**，而非 tick 输入（EUt）与出料口容量
 * 在开线程那一刻都还是满的（`ParallelLogic.java:29` / `EURecipeCapability#getMaxParallelByInput`），
 * k 条线程会各算一遍同一份额度。所以第二轮：聚合上限 `M × (本组在跑条数 + 空闲槽位数)` 只调一次
 * `getParallelAmount`，再减掉本组已提交份额（[committedUnits]），剩下的按「还能开的线程数」均分。
 * 趟与趟之间轮转（每个配方组各加最多一条），多个配方齐步长。
 *
 * ## 每线程并行（[planThreadParallel]）
 * 每条线程自己读 `MultiblockControllerMachine#getParallelHatch().getCurrentParallel()` 当上限 M，
 * 再套 `ModifierFunction.builder().modifyAllContents(×p).eutMultiplier(×p).parallels(p)`。
 * ⚠️ 因此接入本内核的多方块，其 `recipeModifier` **不能**再含并行修改器，否则并行套两遍
 * （[buildThreadRecipe] 有一道 `parallels > 1` 的防御 + 一次性警告）。
 *
 * ## 契约与已知简化
 * - 接入的机器实现 [IThreadedRecipeMachine]，并把本类交给基类构造器：`WorkableMultiblockMachine(info, ThreadedRecipeLogic())`；
 * - `machine.onWorking()` 每 tick 只调一次（不是每条线程一次）；机器级失败只让线程「等待 + 回退进度」，不打断；
 * - `recipeDirty`（并行仓/线程仓改数值时会被 `markLastRecipeDirty()`）只影响之后新开的线程；
 * - 存档只存「配方 id + 进度」，恢复是尽力而为（配方被改、部件被拆、结构没成型都会让那条线程消失，扣过的料不退）；
 * - `handleRecipeIO(OUT)` 的返回值照旧忽略（与基类 `RecipeLogic.java:594` 一致，出料口满就丢产物）。
 *
 * ⚠️ 8.0.0 的基类字段在 Kotlin 侧只读（`getDuration()` / `getLastRecipe()` 没有 setter），
 * 所以「把某条线程镜像进基类单进度字段」改成覆写这几个 getter，见 [getLastRecipe]。
 */
class ThreadedRecipeLogic : RecipeLogic() {

    /**
     * 一条线程 = 一个正在跑的配方。
     *
     * @param recipe   这条线程**自己那份**配方（已套机器修改器、并行、ranged 预掷）
     * @param unrolled 同一份配方在预掷**之前**的副本；`doTickPrerolls` 要拿它掷每 tick 的 ranged 内容
     * @param progress 这条线程自己的进度（tick）
     * @param duration 开线程时钉下来的耗时（tick），不每 tick 读 `recipe.duration`
     */
    data class ThreadRec(
        val recipe: GTRecipe,
        val unrolled: GTRecipe,
        var progress: Int,
        val duration: Int,
    ) {
        /** 这条线程的进度百分比（0~100）。 */
        val percent: Int get() = if (duration <= 0) 0 else progress * 100 / duration
    }

    /** 提供线程仓的机器；拿不到就退化成单线程。 */
    private val threadedMachine: IThreadedRecipeMachine? get() = getRLMachine() as? IThreadedRecipeMachine

    /** 线程槽表：下标 = 槽位，`null` = 空闲。只增不减（见 [ensureSlots]）。 */
    private val threads: MutableList<ThreadRec?> = ArrayList()

    /** 与 [threads] 同下标的每线程概率缓存。 */
    private val threadChanceCaches: MutableList<ChanceCaches?> = ArrayList()

    /** 找配方的节流计数。 */
    private var searchCooldown: Int = 0

    /** 「上游修改器已经套过并行」这条警告只打一次，免得刷屏。 */
    private var warnedAlreadyParallel: Boolean = false

    /** 线程表落盘节流：形状一变就置 0，下个 tick 重新落一次。 */
    private var saveCooldown: Int = 0

    /**
     * 存档里的线程表（`gtet_threads` → `[{recipe, progress}]`）。
     *
     * ⚠️ 8.0.0 的 trait 没有 `saveCustomPersistedData` 钩子，数据只能挂在 `@SaveField` 字段上由
     * sync 系统序列化（`SyncDataHolder.java:64-83`）；`CompoundTag` 是现成注册过的类型
     * （`ValueTransformers.java:155`）。
     */
    @field:SaveField
    private var savedThreads: CompoundTag? = null

    // ────────────────────────────────────────────────
    //  对外只读状态
    // ────────────────────────────────────────────────

    /** 当前生效的线程数上限。 */
    val threadLimit: Int get() = threadedMachine?.threadCount ?: 1

    /** 正在跑的线程数。 */
    val runningThreadCount: Int get() = threads.count { it != null }

    /** 正在跑的线程们（只读快照）。 */
    val runningThreads: List<ThreadRec> get() = threads.filterNotNull()

    /** 正在跑的线程的「槽位 + 记录」快照（**服务端状态**：线程表没有同步到客户端）。 */
    val runningThreadSlots: List<Pair<Int, ThreadRec>>
        get() = threads.withIndex().filter { it.value != null }.map { it.index to it.value!! }

    /** 所有在跑线程的运行次数之和（Σ `GTRecipe#getTotalRuns()`），即整机「同时处理多少次配方运行」。 */
    val runningTotalRuns: Long get() = runningThreads.sumOf { it.recipe.totalRuns.toLong() }

    // ────────────────────────────────────────────────
    //  单进度镜像（基类那套 UI / Jade 用的就是这几个 getter）
    // ────────────────────────────────────────────────

    /**
     * 把「下标最小的那条在跑线程」镜像进基类的单进度字段，让 GTM 自带的单进度显示不至于空白。
     *
     * ⚠️ 必须写**字段**，不能只覆写 `getLastRecipe` / `getProgress` / `getDuration`：
     * `getProgressPercent()` 直接读 `progress` / `duration` 两个字段、**不经过 getter**
     * （`RecipeLogic.java:229-236`，javap 确认是 `getfield`），只覆写 getter 会让百分比恒为 0，
     * 而 GTM 的多方块进度行同时显示「进度/时长」和百分比（`GTMultiblockTextUtil#addProgressLine`
     * 两个 SyncValue 分别取 `getProgress()`/`getMaxProgress()` 与 `getProgressPercent()`），两者会自相矛盾。
     *
     * 多条线程同时在跑时这套单进度字段只反映其中一条；逐线程的真实进度走 [ThreadedRecipeStatus]。
     */
    private fun mirrorToBaseFields() {
        val primary = primaryThread()
        lastRecipe = primary?.recipe
        lastUnrolledRecipe = primary?.unrolled
        progress = primary?.progress ?: 0
        duration = primary?.duration ?: 0
        isActive = primary != null
    }

    private fun primaryThread(): ThreadRec? = threads.firstOrNull { it != null }

    // ────────────────────────────────────────────────
    //  主循环
    // ────────────────────────────────────────────────

    override fun serverTick() {
        // 与基类一致：SUSPEND（玩家关掉了机器）时一条线程都不推进。
        if (isSuspend) return

        restoreThreadsIfPossible()
        ensureSlots()

        val limit = threadLimit
        var progressed = false
        var waitingNow = false
        var waitReason: Component? = null

        if (runningThreadCount > 0) {
            // 基类每 tick 调一次 machine.onWorking()；这里整台机器也只调一次，部件回调不被线程数放大。
            if (getRLMachine().onWorking()) {
                for (slot in threads.indices) {
                    val rec = threads[slot] ?: continue
                    val reason = advanceThread(slot, rec)
                    if (reason == null) progressed = true else {
                        waitingNow = true
                        waitReason = reason
                    }
                }
            } else {
                waitingNow = true
            }
        }

        var searched = false
        if (searchCooldown > 0) searchCooldown--
        if (searchCooldown <= 0) {
            searchCooldown = SEARCH_INTERVAL
            searched = true
            if (freeThreadSlot(limit) != null) tryStartThreads(limit)
        }

        // 与基类一致：整个逻辑「这一 tick 有没有推进」的口径是「有没有线程真的前进过」
        if (progressed) totalContinuousRunningTime++

        mirrorToBaseFields()
        syncStatus(progressed, waitingNow, waitReason)
        flushThreadTableIfDue()

        // 一条线程都没在跑、这一轮也搜不到活干 → 退订，等部件内容变化时由 updateTickSubscription() 唤醒
        // （与基类在多方块上 keepSubscribing == false 的行为一致）。
        if (searched && runningThreadCount == 0) {
            subscription?.unsubscribe()
            subscription = null
        }
    }

    /**
     * 推进一条线程。
     *
     * @return `null` = 这一 tick 正常推进了；非 null = 没推进 +（可能为 null 的）原因
     */
    private fun advanceThread(slot: Int, rec: ThreadRec): Component? {
        val conditions = RecipeHelper.checkConditions(rec.recipe, this)
        if (!conditions.isSuccess) {
            regress(rec)
            return conditions.reason
        }

        val tick = handleThreadTickRecipe(slot, rec)
        if (!tick.isSuccess) {
            regress(rec)
            return tick.reason
        }

        rec.progress++
        if (rec.progress >= rec.duration) finishThread(slot, rec)
        return null
    }

    /**
     * 一条线程的 tick IO，等价于基类 `handleTickRecipe`，只是换成**它自己的**概率缓存。
     *
     * 顺序照 `RecipeLogic.java:431-450`：`matchTickRecipe` → `doTickPrerolls` → `handleTickRecipeIO(IN)` → `(OUT)`。
     */
    private fun handleThreadTickRecipe(slot: Int, rec: ThreadRec): ActionResult {
        val recipe = rec.recipe
        if (!recipe.hasTick()) return ActionResult.SUCCESS

        val caches = threadChanceCaches[slot] ?: makeChanceCaches().also { threadChanceCaches[slot] = it }

        val match = RecipeHelper.matchTickRecipe(getRLMachine(), recipe)
        if (!match.isSuccess) return match

        // ranged 的每 tick 内容必须每 tick 现掷（`RecipeHelper.java:500`），否则会按未展开的区间当定量用。
        val running = RecipeHelper.doTickPrerolls(recipe, caches, rec.unrolled)

        val input = RecipeHelper.handleTickRecipeIO(getRLMachine(), running, IO.IN, caches)
        if (!input.isSuccess) return input

        return RecipeHelper.handleTickRecipeIO(getRLMachine(), running, IO.OUT, caches)
    }

    /** 与基类 `regressRecipe()` 一致：等待时把进度退回 1，条件取 `regressWhenWaiting`。 */
    private fun regress(rec: ThreadRec) {
        if (rec.progress > 0 && regressWhenWaiting) rec.progress = 1
    }

    /** 到点：先 `afterWorking()` 再出料（顺序同 `RecipeLogic.java:588-594`），然后释放槽位。 */
    private fun finishThread(slot: Int, rec: ThreadRec) {
        getRLMachine().afterWorking()
        val caches = threadChanceCaches[slot] ?: makeChanceCaches()
        RecipeHelper.handleRecipeIO(getRLMachine(), rec.recipe, IO.OUT, caches)
        threads[slot] = null
        threadChanceCaches[slot] = null
        markThreadTableDirty()
    }

    // ────────────────────────────────────────────────
    //  开新线程
    // ────────────────────────────────────────────────

    /**
     * 给空闲槽位找配方（用 GTM 现成的 `GTRecipeType#searchRecipe`，不自己重写匹配）。
     *
     * 池子里装的是**配方库给的原始候选**：已在跑线程的 `rec.recipe` 已套过修改器与并行，拿它再
     * `fullModifyRecipe` 一遍会重复超频。`fullModifyRecipe` 每次新建副本，所以同一轮里对同一条候选反复算并行是安全的。
     */
    private fun tryStartThreads(limit: Int) {
        // ① 第一轮：不同配方各一条线程，池子顺便就地攒
        val repeats = ArrayList<GTRecipe>()
        val iterator = getRLMachine().recipeType.searchRecipe(getRLMachine()) { true }
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (isRunningElsewhere(candidate)) {
                if (repeats.none { sameRecipe(it, candidate) }) repeats.add(candidate)
                continue
            }
            val slot = freeThreadSlot(limit) ?: return
            if (startThread(slot, candidate, fanOut = false, limit = limit)) {
                // 刚开出来的也要进池子：短配方可能在下一次搜索之前就跑完
                if (repeats.none { sameRecipe(it, candidate) }) repeats.add(candidate)
            }
        }
        if (repeats.isEmpty()) return

        // ② 第二轮：轮转着把同一种配方再开一条线程；某一趟一条都没开出来就整轮收手
        while (true) {
            var opened = false
            for (candidate in repeats) {
                val slot = freeThreadSlot(limit) ?: return
                if (startThread(slot, candidate, fanOut = true, limit = limit)) opened = true
            }
            if (!opened) return
        }
    }

    /**
     * 开一条线程：机器修改器 → 本线程并行 → 条件 → 预掷 → 模拟匹配 → `beforeWorking` → **真正扣料** → 占槽。
     *
     * @param fanOut `false` = 第一轮；`true` = 第二轮（并行倍数走 [planThreadParallel] 的「一组一预算、再均分」）
     */
    private fun startThread(slot: Int, origin: GTRecipe, fanOut: Boolean, limit: Int): Boolean {
        val threaded = buildThreadRecipe(origin, fanOut, limit) ?: return false

        val conditions = RecipeHelper.checkConditions(threaded, this)
        if (!conditions.isSuccess) {
            // 8.0.0 的三参重载只认 IRecipeLogicMachine，传 RecipeLogic 会静默什么都不做（`RecipeLogic.java:697-701`）
            RecipeLogic.putFailureReason(this, origin, conditions.reason, Double.POSITIVE_INFINITY)
            return false
        }

        val caches = makeChanceCaches()
        val running = RecipeHelper.doPrerolls(threaded, caches)

        if (!RecipeHelper.matchContents(getRLMachine(), running).isSuccess) return false
        if (!getRLMachine().beforeWorking(running)) return false

        threads[slot] = ThreadRec(running, threaded.copy(), 0, running.duration)
        threadChanceCaches[slot] = caches

        val consumed = RecipeHelper.handleRecipeIO(getRLMachine(), running, IO.IN, caches)
        if (!consumed.isSuccess) {
            threads[slot] = null
            threadChanceCaches[slot] = null
            return false
        }
        markThreadTableDirty()
        return true
    }

    /**
     * 「机器配方修改器 + 本线程并行」两步，开线程与存档恢复共用。
     *
     * @return 这条线程要跑的配方；`null` = 当下开不出线程
     */
    private fun buildThreadRecipe(origin: GTRecipe, fanOut: Boolean, limit: Int): GTRecipe? {
        val modified = getRLMachine().fullModifyRecipe(origin) ?: return null

        // 防御：上游修改器已经并行过了就别再叠（会变成 并行²）。这种配方也不参与 fan-out
        // —— 它的倍数是上游算的，拿不到干净的「本组已提交多少」口径，硬叠只会超发。
        if (modified.parallels > 1) {
            warnAlreadyParallel(modified)
            return if (fanOut) null else modified
        }

        val parallels = planThreadParallel(modified, fanOut, limit) ?: return null
        return applyParallel(modified, parallels)
    }

    /** 「上游修改器已经套过并行」这条警告只打一次（fan-out 每轮搜索都可能再撞上同一批配方）。 */
    private fun warnAlreadyParallel(recipe: GTRecipe) {
        if (warnedAlreadyParallel) return
        warnedAlreadyParallel = true
        GTETSCore.LOGGER.warn(
            "[GTET] 线程仓：配方 {} 在机器配方修改器里已经吃过并行（parallels={}），线程逻辑不再叠加。",
            recipe.getId(), recipe.parallels
        )
    }

    /**
     * 算「这条新线程该拿几倍并行」。
     *
     * - [fanOut] = false：上限取并行仓 `getCurrentParallel()`（M），再用
     *   `ParallelLogic#getParallelAmount` 收缩到「当下真的喂得起」的倍数；
     * - [fanOut] = true：`聚合上限 = M × (本组在跑条数 + 空闲槽位数)` 只调一次 `getParallelAmount`，
     *   减掉 [committedUnits] 之后按「本组还能开的线程数」均分，每条再夹到 M。
     *
     * @return 该线程的并行倍数（≥1）；`null` = 连 1 份都喂不起
     */
    private fun planThreadParallel(recipe: GTRecipe, fanOut: Boolean, limit: Int): Int? {
        val hatch = parallelHatch() ?: return 1
        val cap = hatch.currentParallel
        if (cap <= 1) return 1

        if (!fanOut) {
            val achievable = ParallelLogic.getParallelAmount(getMachine(), recipe, cap)
            return if (achievable <= 0) null else achievable
        }

        val running = countRunningSameRecipe(recipe)
        val committed = committedUnits(recipe, unitRuns(recipe))
        val free = countFreeSlots(limit)
        if (free <= 0) return null

        // 先按 Long 乘再夹到 Int，免得高位 tier 直接溢出
        val threadsForGroup = (running + free).toLong()
        val aggregateCap = minOf(cap.toLong() * threadsForGroup, Int.MAX_VALUE.toLong()).toInt()

        // 便宜早退：`getParallelAmount` 的结果永远 ≤ 传进去的上限，所以上限已 ≤ 已提交时一定没余量
        // （大倍数下 `limitByOutputMerging` 是二分搜索，每 5 tick 每个空闲槽位都调一次不便宜）。
        if (aggregateCap.toLong() <= committed) return null

        val affordable = ParallelLogic.getParallelAmount(getMachine(), recipe, aggregateCap)
        val extra = affordable.toLong() - committed
        if (extra < 1L) return null

        val openable = minOf(free.toLong(), extra)
        return minOf(cap.toLong(), maxOf(1L, extra / openable)).toInt()
    }

    /**
     * 「当下这份配方跑一次」等于多少次配方运行：`subtickParallels × batchParallels`
     * （此时 `parallels` 还没套，所以就是 `getTotalRuns()`）。这是与 [committedUnits] 之间的换算单位。
     */
    private fun unitRuns(recipe: GTRecipe): Long =
        recipe.subtickParallels.toLong() * recipe.batchParallels.toLong()

    /**
     * 本组（同 [sameRecipe]）已在跑线程**已提交**的份额，单位 = [unitRuns]，**向上取整**。
     *
     * 必须自己记这份账：非 tick 输入在开线程时就被真扣掉了（库存自己变小），而 tick 输入与出料口容量
     * 不会，`ParallelLogic` 每次去看都还是「满的」。向上取整是有意的：宁可高估（少开一条）也不超发。
     */
    private fun committedUnits(recipe: GTRecipe, unit: Long): Long {
        var sum = 0L
        for (rec in threads) {
            if (rec == null || !sameRecipe(rec.recipe, recipe)) continue
            val runs = rec.recipe.totalRuns.toLong()
            sum += if (unit <= 0L) runs else (runs + unit - 1L) / unit
        }
        return sum
    }

    /**
     * 把并行倍数套到配方上：内容 ×p、EUt ×p、`parallels = p`（三样缺一不可）。
     * 走 `ModifierFunction` 链路而不是手改内容表，保住概率逻辑与 `getTotalRuns()` 的既有语义。
     */
    private fun applyParallel(recipe: GTRecipe, parallels: Int): GTRecipe? {
        if (parallels <= 1) return recipe
        return ModifierFunction.builder()
            .modifyAllContents(ContentModifier.multiplier(parallels.toDouble()))
            .eutMultiplier(parallels.toDouble())
            .parallels(parallels)
            .build()
            .apply(recipe)
    }

    /** 这一种配方此刻有几条线程在跑。 */
    private fun countRunningSameRecipe(recipe: GTRecipe): Int {
        var n = 0
        for (rec in threads) if (rec != null && sameRecipe(rec.recipe, recipe)) n++
        return n
    }

    /** 线程上限以内的空闲槽位数。 */
    private fun countFreeSlots(limit: Int): Int {
        var n = 0
        for (slot in 0 until minOf(limit, threads.size)) if (threads[slot] == null) n++
        return n
    }

    /**
     * 控制器上挂着的并行仓。
     *
     * ⚠️ 8.0.0 删掉了 `IParallelHatch`，`getParallelHatch()` 直接返回 GTM 自己的
     * `ParallelHatchPartMachine`（`MultiblockControllerMachine.java:183`），所以 GTET 的并行仓
     * 必须继承它（见 `ETParallelHatchPartMachine`）。
     */
    private fun parallelHatch(): ParallelHatchPartMachine? =
        (getRLMachine() as? MultiblockControllerMachine)?.parallelHatch?.orElse(null)

    /** 这条配方是不是已经有（别的）线程在跑。 */
    private fun isRunningElsewhere(candidate: GTRecipe): Boolean =
        threads.any { it != null && sameRecipe(it.recipe, candidate) }

    private fun sameRecipe(a: GTRecipe, b: GTRecipe): Boolean = isSameRecipe(a, b)

    // ────────────────────────────────────────────────
    //  槽位 / 状态 / 存档
    // ────────────────────────────────────────────────

    /** 按当前上限把槽表**加长**；**不缩短**（玩家下调线程数时已在跑的线程跑完自然释放）。 */
    private fun ensureSlots() {
        val limit = threadLimit.coerceAtLeast(1)
        while (threads.size < limit) {
            threads.add(null)
            threadChanceCaches.add(null)
        }
    }

    /** 第一个空闲槽位（只看上限以内的槽位）。 */
    private fun freeThreadSlot(limit: Int): Int? {
        for (slot in 0 until minOf(limit, threads.size)) if (threads[slot] == null) return slot
        return null
    }

    /** 状态机：有线程在推进 = WORKING；有线程但在等 = WAITING；一条线程都没有 = IDLE。 */
    private fun syncStatus(progressed: Boolean, waitingNow: Boolean, reason: Component?) {
        if (runningThreadCount == 0) {
            // 玩家在跑的过程中关掉机器时基类会置 suspendAfterFinish（`RecipeLogic.java:552-553`），
            // 线程跑完必须落到 SUSPEND；只看 IDLE 的话下一轮又会重新开线程（等于关不掉）。
            val done = if (isSuspendAfterFinish()) Status.SUSPEND else Status.IDLE
            if (status != done) status = done
            return
        }
        when {
            progressed -> status = Status.WORKING
            // setWaiting 每次都调 machine.onWaiting()，所以只在状态真的变了的时候调
            waitingNow -> if (status != Status.WAITING) setWaiting(reason)
            status != Status.WORKING && status != Status.WAITING -> status = Status.WORKING
        }
    }

    /** 结构失效时基类会 `resetRecipeLogic()`；线程表也必须一起清掉（否则留下跑不了的幽灵线程）。 */
    override fun resetRecipeLogic() {
        super.resetRecipeLogic()
        threads.clear()
        threadChanceCaches.clear()
        savedThreads = null
    }

    private fun markThreadTableDirty() {
        saveCooldown = 0
    }

    /** 落盘节流：每 [SAVE_INTERVAL] tick 把进度重写一次，形状变化时立即写。 */
    private fun flushThreadTableIfDue() {
        if (saveCooldown > 0) {
            saveCooldown--
            return
        }
        saveCooldown = SAVE_INTERVAL
        if (runningThreadCount == 0 && savedThreads == null) return
        flushThreadTable()
    }

    /** 只存「配方 id + 进度」：配方本体从 `RecipeManager` 能重新取到，存 id 就够。 */
    private fun flushThreadTable() {
        if (runningThreadCount == 0) {
            savedThreads = null
            return
        }
        val list = ListTag()
        for (rec in threads) {
            val running = rec ?: continue
            val id = running.recipe.getId() ?: continue
            val entry = CompoundTag()
            entry.putString(TAG_RECIPE, id.toString())
            entry.putInt(TAG_PROGRESS, running.progress)
            list.add(entry)
        }
        savedThreads = CompoundTag().apply { put(TAG_THREADS, list) }
    }

    /**
     * 恢复存档里没跑完的线程（**尽力而为**）：配方从 `RecipeManager` 重取，重跑一遍
     * `fullModifyRecipe` + 本线程并行 —— **不再扣一次料**（料在存档前就扣过了）。
     *
     * 同一种配方可能有好几条线程（fan-out 开出来的），所以除第一条之外按 fan-out 预算走。
     */
    private fun restoreThreadsIfPossible() {
        val saved = savedThreads ?: return
        if (!getRLMachine().isRecipeLogicAvailable) return
        savedThreads = null

        ensureSlots()
        val limit = threadLimit
        var slot = 0
        val list = saved.getList(TAG_THREADS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            if (slot >= limit) break
            val entry = list.getCompound(i)
            val id = ResourceLocation.tryParse(entry.getString(TAG_RECIPE)) ?: continue
            val savedProgress = entry.getInt(TAG_PROGRESS)
            val origin = recipeManager.byKey(id).orElse(null)?.value as? GTRecipe ?: continue

            val fanOut = countRunningSameRecipe(origin) > 0
            val threaded = buildThreadRecipe(origin, fanOut, limit) ?: continue
            val caches = makeChanceCaches()
            val running = RecipeHelper.doPrerolls(threaded, caches)
            threads[slot] = ThreadRec(
                running,
                threaded.copy(),
                savedProgress.coerceIn(0, running.duration),
                running.duration,
            )
            threadChanceCaches[slot] = caches
            slot++
        }
    }

    companion object {

        /** 空闲槽位找配方的节流间隔（tick）。 */
        const val SEARCH_INTERVAL: Int = 5

        /** 存档里线程进度重写的节流间隔（tick，20 = 1 秒）。 */
        private const val SAVE_INTERVAL: Int = 20

        private const val TAG_THREADS: String = "gtet_threads"
        private const val TAG_RECIPE: String = "recipe"
        private const val TAG_PROGRESS: String = "progress"

        /**
         * 两条配方算不算「同一种」：两边都有 `id` 就比 `id`，否则退化成引用相等。
         *
         * 不用 `GTRecipe#equals`：它只比 id，且对 `id == null` 会 NPE。
         * 公开出来是给 [ThreadedRecipeStatus.groupSnapshots] 复用同一条判定，免得两处口径漂移。
         */
        @JvmStatic
        fun isSameRecipe(a: GTRecipe, b: GTRecipe): Boolean {
            val idA = a.getId()
            val idB = b.getId()
            return if (idA != null && idB != null) idA == idB else a === b
        }
    }
}
