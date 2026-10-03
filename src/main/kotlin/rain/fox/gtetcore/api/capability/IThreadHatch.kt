package rain.fox.gtetcore.api.capability

/**
 * 「线程仓」部件能力接口：把 N 台同型机器融合成一台。
 *
 * 语义只有两条：一台机器最多同时跑 [threadCount] 条线程，上限是 [maxThreads]。
 * ⚠️ 单位是**线程条数**而不是「配方种数」——空闲线程会发给已经在跑的同一种配方
 * （见 `ThreadedRecipeLogic` 的两轮调度），所以线程条数 ≥ 同时跑的配方种数。
 *
 * 独立能力 [ETPartAbility.THREAD_HATCH]：不复用 `PartAbility.PARALLEL_HATCH`
 * （那会让线程仓与并行仓在结构里互斥），也不继承 GTM 的并行仓类
 * （8.0.0 控制器只缓存一个 `ParallelHatchPartMachine`，见 `MultiblockControllerMachine.java:183`）。
 */
interface IThreadHatch {

    /** 该仓**当前生效**的线程数（玩家可以在部件面板里往下调，但不超过 [maxThreads]）。 */
    val threadCount: Int

    /** 该仓提供的线程数上限，由 `ETThreadHatches.VARIANTS` 变体表显式给出。 */
    val maxThreads: Int
}
