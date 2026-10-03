package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import rain.fox.gtetcore.common.data.machine.hatch.ETOverclockHatches
import rain.fox.gtetcore.common.data.machine.hatch.ETTimeFlowHatches
import rain.fox.gtetcore.common.machine.multiblock.part.ETParallelHatchPartMachine
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 机器注册入口。
 *
 * 目前登记三类：**并行仓**（IV 一档，验证注册 / 存档 / MUI 面板 / C2S 同步四条链路）、
 * **超频仓**（17 档，见 [ETOverclockHatches]）、**时序仓**（6 档，见 [ETTimeFlowHatches]）。
 * 老项目的完整并行仓变体表（IV 32 / LuV 128 / ZPM 512 / UV 2048 / UHV 8192 / UEV 32768 /
 * UIV 524288 / UXV 2097152 / OpV 8388608 / MAX 33554432）等支撑类补齐后再上。
 *
 * 中文名走 `LangUtil.BLOCK_LANG` → `ZhCnLangProvider`（英文名走下面的 `langValue`）。
 */
object ETMachines {

    /** IV 并行仓（32 并行）。贴图沿用 GTM 的并行仓 mk4，与老项目一样是临时占位。 */
    @JvmField
    val parallel_hatch_iv: MachineEntry<MachineDefinition> = run {
        LangUtil.BLOCK_LANG["parallel_hatch_iv"] = "${GTValues.VN[GTValues.IV]} 并行仓（32 并行）"

        ETRegistrate.REGISTRATE
            .machineBuilder("parallel_hatch_iv") { info -> ETParallelHatchPartMachine(info, GTValues.IV, 32) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(GTValues.IV)
            .langValue("${GTValues.VN[GTValues.IV]} Parallel Hatch (32 Parallel)")
            .rotationState(RotationState.ALL)
            // 必须是 GTM 的能力对象：多方块结构谓词按 PartAbility.PARALLEL_HATCH 匹配部件
            .abilities(PartAbility.PARALLEL_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            .model(createWorkableTieredHullMachineModel(GTCEu.id("block/machines/parallel_hatch_mk4")))
            // 共享提示走渲染时取值，因为能不能共享由配置里的全局开关决定
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }

    /**
     * 超频仓全部 17 档（4× / 16× / 64× / 256× 各 4 档 + 1024×）。
     *
     * ⚠️ 本件目前只是「能放能拆的标记件」：真正改写超频的 `MixinOverclockingLogic` 属 M5，
     * 移植过来之前它不会影响任何配方。
     */
    @JvmField
    val OVERCLOCK_HATCHES: List<MachineEntry<MachineDefinition>> =
        ETOverclockHatches.register(ETRegistrate.REGISTRATE)

    /** 并行仓全族：目前只登记了 IV 一档，等完整变体表移植过来再补全。 */
    @JvmField
    val PARALLEL_HATCHES: List<MachineEntry<MachineDefinition>> = listOf(parallel_hatch_iv)

    /** 线程仓：变体表还没移植，先给空占位（静态组表拿不齐就不缓存，会自动重算）。 */
    @JvmField
    val THREAD_HATCHES: List<MachineEntry<MachineDefinition>> = emptyList()

    /**
     * 时序仓（TF 供给仓）全族 6 档：UHV 1h / UEV 16h / UIV 64h / UXV 256h / OpV 1024h / MAX 4096h。
     *
     * ⚠️ **登记顺序是硬要求**：这一行必须排在**任何消费它的多方块**之前。
     * `PartAbility#getAllBlocks()` 是懒记忆化的（`PartAbility.java:63` 的 `GTMemoizer`，首取即定），
     * 而多方块结构谓词 `Predicates.abilities(ETPartAbility.TF_HATCH)` 在**构造谓词那一刻**就会取它
     * —— 那时还没登记进去的方块，之后永远不会被插进结构里（而且一声不响）。
     * 老工程靠「`ALLSmachine.init()` 早于 `ALLMmachine.init()`」保证这条；本仓库目前还没有时序多方块，
     * 所以把它放在本 object 的最前面就是安全的。**将来加多方块时把它写在下面、别写上面。**
     *
     * TODO(虚档位切片): 第 7 档 `tf_hatch_16384h`（虚档位 ETV）等 `ETValues` 移植过来再追加，
     *   接法与后果见 `ETTimeFlowHatches.VARIANTS` 上的那段 TODO。
     */
    @JvmField
    val TIME_FLOW_HATCHES: List<MachineEntry<MachineDefinition>> =
        ETTimeFlowHatches.register(ETRegistrate.REGISTRATE)

    init {
        // 归页：并行仓 / 超频仓 / 时序仓 / 线程仓全部进 GTET 机器页。
        // ⚠️ 这里必须用 assignTab 补页而不是在注册前设「当前页」——几张表都是在上面登记的，
        //    时机已经过了；assignTab 内部会取回 ITEM 注册表那份条目再登记（原因见它的注释）。
        ETRegistrate.REGISTRATE.assignTab(
            ETCreativeModeTabs.MACHINE,
            PARALLEL_HATCHES + OVERCLOCK_HATCHES + TIME_FLOW_HATCHES + THREAD_HATCHES,
        )
    }
}
