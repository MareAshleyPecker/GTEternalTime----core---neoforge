package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import rain.fox.gtetcore.common.data.machine.hatch.ETOverclockHatches
import rain.fox.gtetcore.common.data.machine.hatch.ETThreadHatches
import rain.fox.gtetcore.common.data.machine.hatch.ETTimeFlowHatches
import rain.fox.gtetcore.common.data.machine.hatch.ETWirelessEnergyHatches
import rain.fox.gtetcore.common.data.machine.multiblock.ETMasterTower
import rain.fox.gtetcore.common.data.machine.multiblock.ETTestMultiblocks
import rain.fox.gtetcore.common.data.machine.multiblock.modular.ETModularTestMultiblocks
import rain.fox.gtetcore.common.machine.multiblock.part.ETParallelHatchPartMachine
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 机器注册入口。
 *
 * 目前登记五类：**并行仓**（IV 一档，验证注册 / 存档 / MUI 面板 / C2S 同步四条链路）、
 * **超频仓**（17 档，见 [ETOverclockHatches]）、**时序仓**（6 档，见 [ETTimeFlowHatches]）、
 * **无线能源仓**（110 档，见 [ETWirelessEnergyHatches]）、
 * **主控塔**（本工程第一台多方块控制器，见 [ETMasterTower]）。
 * 老项目的完整并行仓变体表（IV 32 / LuV 128 / ZPM 512 / UV 2048 / UHV 8192 / UEV 32768 /
 * UIV 524288 / UXV 2097152 / OpV 8388608 / MAX 33554432）等支撑类补齐后再上。
 *
 * 中文名走 `LangUtil.BLOCK_LANG` → `ZhCnLangProvider`（英文名走下面的 `langValue`）。
 */
object ETMachines {

    /**
     * 归页：本 object 里注册的机器**全部**落进 `ETCreativeModeTabs.MACHINE`，**设一次、不复位**。
     *
     * ⚠️ 不能用 `inTab { }` 包住注册语句：机器的物品条目要等 `RegisterEvent` 派发时由
     * `MachineBuilder.createEntry()` → `createAdditionalObjects()` 才建出来（`MachineBuilder.java:637-672`），
     * 而 `GTRegistrate.accept()` 写 `TAB_LOOKUP` 用的是**那一刻**的 `currentTab`（`GTRegistrate.java:332-343`）、
     * 创造页生成器只认这份表（`GTCreativeModeTabs.java:95-97`）—— `inTab` 一复位成 null 就再也进不了页。
     * GTM 自己也是这么干的（`GTMachines` 静态块设一次 `creativeModeTab(...)`）。
     *
     * ⚠️ 副作用：`RegisterEvent` 里条目创建是哈希序，**给不同机器分不同页做不到** —— 机器只能共用一个页。
     */
    init {
        ETRegistrate.REGISTRATE.creativeModeTab(ETCreativeModeTabs.MACHINE)
    }

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

    /**
     * 线程仓全族 8 档：ZPM 4 / UV 8 / UHV 16 / UEV 32 / UIV 64 / UXV 128 / OpV 256 / MAX 512。
     *
     * ⚠️ 本行必须排在**任何消费线程仓的多方块**之前（同 [TIME_FLOW_HATCHES] 的那条顺序约定：
     * `PartAbility#getAllBlocks()` 是懒记忆化的）。
     */
    @JvmField
    val THREAD_HATCHES: List<MachineEntry<MachineDefinition>> =
        ETThreadHatches.register(ETRegistrate.REGISTRATE)

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

    /**
     * 无线能源仓全族 110 档：电压 IV ~ MAX（10 档）× 安培 1A ~ 4194304A（11 档）。
     *
     * ⚠️ 复用 GTM 的 `PartAbility.INPUT_ENERGY`（本族不新建能力）。GTM 多方块的结构图案是懒记忆化的
     * （`MultiblockMachineDefinition.java:58`），到首次结构检测时才取 `getAllBlocks()`，
     * 所以本行只要排在**我们自己的**多方块之前就够（与 [TIME_FLOW_HATCHES] 同一条顺序约定）。
     */
    @JvmField
    val WIRELESS_ENERGY_HATCHES: List<MachineEntry<MachineDefinition>> =
        ETWirelessEnergyHatches.register(ETRegistrate.REGISTRATE)

    /**
     * **主控塔**（本工程第一台多方块控制器）。
     *
     * ⚠️ 本行必须排在 [TIME_FLOW_HATCHES] **之后**（理由同上：`PartAbility#getAllBlocks()` 是懒记忆化的，
     * 将来塔的结构图案一旦用到 `ETPartAbility.TF_HATCH`，就必须在时序仓登记完之后才构造谓词）。
     */
    @JvmField
    val MASTER_TOWER: MachineEntry<MultiblockMachineDefinition> =
        ETMasterTower.register(ETRegistrate.REGISTRATE)

    /**
     * **多方块测试机**：线程内核的第一台消费机器（3×3×3 钢机壳，只吃研磨配方）。
     *
     * ⚠️ 本行必须排在 [THREAD_HATCHES] **之后**（理由同 [MASTER_TOWER]：`PartAbility#getAllBlocks()`
     * 懒记忆化，而本机的结构谓词用的正是 `ETPartAbility.THREAD_HATCH`）。
     */
    @JvmField
    val TEST_MULTIBLOCK: MachineEntry<MultiblockMachineDefinition> =
        ETTestMultiblocks.register(ETRegistrate.REGISTRATE)

    /**
     * **模块化试验台**：模块物品（金 / 钛 / 中子素锭…）决定结构 3³ / 5³ / 7³ 与配方电压上限。
     *
     * 本机的图案只用 GTM 自带的 `autoAbilities`、不消费任何 `ETPartAbility`，所以**没有**
     * `PartAbility#getAllBlocks()` 懒记忆化的顺序要求，放最后一行即可。
     */
    @JvmField
    val MODULAR_TEST_MACHINE: MachineEntry<MultiblockMachineDefinition> =
        ETModularTestMultiblocks.register(ETRegistrate.REGISTRATE)
}
