package rain.fox.gtetcore.common.data.machine.multiblock.modular

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModularMachine
import rain.fox.gtetcore.data.lang.ModuleLang

/**
 * 「模块化测试机」的注册。
 *
 * ⚠️ GTM 一个 `MultiblockMachineDefinition` 存的是**一张具名 substructure 表**
 * （`Map<String, Supplier<IBlockPattern>>`），运行时用哪一套由机器自己决定
 * （见 [ETModularMachine] 的类注释：`main` 被改写成「当前档位那一份」）。
 *
 * 所以这里三套结构都注册进去：
 * - `main` = MK1 3³（`pattern { ... }` 的默认名）——**运行时兜底 / 结构工具 / 预览的默认那一套**；
 * - `tier_2` / `tier_3` = MK2 5³ / MK3 7³ —— 只当**图案仓库**，不会被单独成型
 *   （`ETModularMachine` 的 `init` 会把非 `main` 的 `PatternState` 摘掉）。
 *
 * @author rain fox
 */
object ETModularTestMultiblocks {

    /** 机器 id（同时是方块 id `gtetscore:modular_test_machine` 与 lang 键 `block.gtetscore.<id>`）。 */
    const val MODULAR_TEST_ID: String = ETModularTestMachine.ID

    /** 注册「模块化测试台」；返回注册好的多方块定义。 */
    @JvmStatic
    fun register(registrate: GTRegistrate): MachineEntry<MultiblockMachineDefinition> {
        // 名字 / tooltip / 面板文案统一在 ModuleLang 登记（同一处改动，避免两处漂移）
        return registrate
            .multiblock(MODULAR_TEST_ID) { info -> ETModularTestMachine(info) }
            .langValue("Modular Test Bench")
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            .recipeModifiers(GTRecipeModifiers.OC_NON_PERFECT_SUBTICK, GTRecipeModifiers.BATCH_MODE)
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            // 默认那一套（= substructure `main`）：最小的 MK1，运行时兜底用
            .pattern { definition -> ETModularTestMachine.boxPattern(3, definition) }
            // 其余档位：图案仓库，按档位号取名（第 1 档必须叫 `main`，见 ETModularMachine.substructureName）
            .pattern(ETModularMachine.substructureName(2)) { definition ->
                ETModularTestMachine.boxPattern(5, definition)
            }
            .pattern(ETModularMachine.substructureName(3)) { definition ->
                ETModularTestMachine.boxPattern(7, definition)
            }
            .tooltips(
                Component.translatable("gtceu.multiblock.parallelizable.tooltip"),
                Component.translatable(
                    "gtceu.machine.available_recipe_map_1.tooltip",
                    GTRecipeTypes.MACERATOR_RECIPES.name,
                ),
                Component.translatable(ModuleLang.TEST_TOOLTIP_0),
                Component.translatable(ModuleLang.TEST_TOOLTIP_1),
            )
            // 贴图复用 GTM 现成的（与多方块测试机同一套，不新增 png）：
            // 机壳贴图 = 外观方块自己的贴图；动画覆盖层 = GCYM 大型研磨塔
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/gcym/large_maceration_tower"),
            )
            .register()
    }
}
