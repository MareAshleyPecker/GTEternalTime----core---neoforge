package rain.fox.gtetcore.api.capability

/**
 * 「超频仓」部件能力接口。
 *
 * 任何多方块部件实现本接口，`MixinOverclockingLogic`（M5 移植）就会在控制器算超频时认出它，
 * 把该多方块的**普通超频整体替换**成 [overclockSpeed] / [overclockEnergyFactor] 描述的特殊超频：
 *
 * ```
 * 每消耗 1 级超频：duration × (1 / S)，EUt × (E × S)
 * ```
 *
 * 两个字段描述的是二维语义（速度倍率 + 能效系数），GTM 那个只管并行的
 * `ParallelHatchPartMachine#getCurrentParallel()` 表达不了，所以单独建这个能力。
 */
interface IOverclockHatch {

    /** 每消耗 1 级超频的速度倍率 S：配方耗时 ÷S。 */
    val overclockSpeed: Int

    /** 每消耗 1 级超频的能效系数 E：EUt × (E × S)；E < 1 省电、E > 1 损能、E = 1 即 perfect。 */
    val overclockEnergyFactor: Double
}
