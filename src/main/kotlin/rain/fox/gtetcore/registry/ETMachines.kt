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
import rain.fox.gtetcore.common.machine.multiblock.part.ETParallelHatchPartMachine
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 机器注册入口。
 *
 * 目前只登记「IV 并行仓」一台，用来验证移植后的注册 / 存档 / MUI 面板 / C2S 同步四条链路；
 * 老项目的完整变体表（IV 32 / LuV 128 / ZPM 512 / UV 2048 / UHV 8192 / UEV 32768 /
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
}
