package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.common.data.machine.multiblock.ETTestMultiblocks
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「多方块测试机」的双语条目。
 *
 * - **机器名**：中文进 [LangUtil.BLOCK_LANG]，英文由注册时的 `.langValue(...)` 写进 en_us；
 * - **tooltip 两行**：`gtetscore.machine.<id>.tooltip.<n>`（与 `MasterTowerLang` 同形）。
 *
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成。
 *
 * @author rain fox
 */
object TestMultiblockLang {

    /** 多方块提示的键前缀（`block.gtetscore.<id>` 那套是 registrate 自己写的，不走这里）。 */
    private const val MACHINE_PREFIX: String = "gtetscore.machine." + ETTestMultiblocks.TEST_MULTIBLOCK_ID + ".tooltip."

    /** tooltip 第 0 行：这台机器存在的意义。 */
    const val TOOLTIP_0: String = MACHINE_PREFIX + "0"

    /** tooltip 第 1 行：结构与可插部件。 */
    const val TOOLTIP_1: String = MACHINE_PREFIX + "1"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.BLOCK_LANG[ETTestMultiblocks.TEST_MULTIBLOCK_ID] = "多方块测试机"
        LangUtil.add(
            TOOLTIP_0,
            "Test bench for GTET thread hatches. Runs Macerator recipes only.",
            "GTET 线程仓试验台，只跑研磨机（Macerator）配方。"
        )
        LangUtil.add(
            TOOLTIP_1,
            "3x3x3 steel casing shell; takes GT energy / item / maintenance / parallel hatches and GTET thread hatches.",
            "3×3×3 钢机壳外壳；可插 GTM 能源仓、输入输出仓、维护仓、并行仓与 GTET 线程仓。"
        )
    }
}
