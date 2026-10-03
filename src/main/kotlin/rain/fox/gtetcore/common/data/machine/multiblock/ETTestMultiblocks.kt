package rain.fox.gtetcore.common.data.machine.multiblock

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.multiblock.Predicates
import com.gregtechceu.gtceu.api.multiblock.pattern.MultiblockPatternBuilder
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import rain.fox.gtetcore.api.capability.ETPartAbility
import rain.fox.gtetcore.common.machine.multiblock.thread.TestMultiblockMachine
import rain.fox.gtetcore.data.lang.TestMultiblockLang

/**
 * **多方块测试机**的注册 —— 3×3×3 钢机壳方块、只吃研磨配方、装上线程仓就多线程。
 *
 * 老工程（1.20.1 / GTM 7.5.3）对应文件：`ETTestMultiblocks.kt`。
 *
 * `.tier(GTValues.IV)` 只是「注册/铭牌等级」（物品 tier 染色），**不**限制实际配方等级：
 * 配方等级由插进去的能源仓在运行时决定（与 [ETMasterTower] 同一条约定）。
 *
 * ⚠️ 登记顺序：本表的 [register] 由 `ETMachines.TEST_MULTIBLOCK` 调用，必须排在
 * `ETMachines.THREAD_HATCHES` **之后** —— `PartAbility#getAllBlocks()` 是懒记忆化的
 * （`PartAbility.java:63`，首取即定），而 `Predicates.abilities(ETPartAbility.THREAD_HATCH)`
 * 在**构造谓词那一刻**就会取它。
 *
 * @author rain fox
 */
object ETTestMultiblocks {

    /** 注册名（决定方块 id `gtetscore:test_multiblock` 与 lang 键 `block.gtetscore.<id>`）。 */
    const val TEST_MULTIBLOCK_ID: String = "test_multiblock"

    /** 机壳最少要几格（外壳一共 25 格，防「只有控制器 + 几个仓」的畸形结构）。 */
    private const val MIN_SHELL: Int = 15

    /** 外壳机壳（贴图与外观方块都用它）。 */
    private val SHELL: Block get() = GTBlocks.CASING_STEEL_SOLID.get()

    /** 注册「多方块测试机」；返回注册好的多方块定义。 */
    @JvmStatic
    fun register(registrate: GTRegistrate): MachineEntry<MultiblockMachineDefinition> {
        return registrate
            .multiblock(TEST_MULTIBLOCK_ID) { info -> TestMultiblockMachine(info) }
            .langValue("Multiblock Test Bench")
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            // 只吃研磨配方 —— 配方最多，最容易验证「不同配方各自跑」
            .recipeType(GTRecipeTypes.MACERATOR_RECIPES)
            // ⚠️ 这里**故意没有** GTRecipeModifiers.PARALLEL_HATCH：本机的配方逻辑是 ThreadedRecipeLogic，
            // 并行由它逐线程按并行仓的 getCurrentParallel() 施加（线程数 × 并行倍数 = 总处理次数上限）。
            // 再挂一条并行修改器等于把并行套两遍（并行²）—— 契约见 `ThreadedRecipeLogic.kt:47-49`。
            .recipeModifiers(GTRecipeModifiers.OC_NON_PERFECT_SUBTICK, GTRecipeModifiers.BATCH_MODE)
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            // 前三行照 GTM 自己的大型研磨塔（`GCYMMachines.java` 的 LARGE_MACERATION_TOWER）：
            // 并行提示 + 配方类型行（配方类型名取 `GTRecipeTypeEntry#getName()`，1.21 起键名是
            // `recipe_type.gtceu.macerator`，`gtceu.macerator` 那条已不存在）+ 本机自己的两行
            .tooltips(
                Component.translatable("gtceu.multiblock.parallelizable.tooltip"),
                Component.translatable(
                    "gtceu.machine.available_recipe_map_1.tooltip",
                    GTRecipeTypes.MACERATOR_RECIPES.name
                ),
                Component.translatable(TestMultiblockLang.TOOLTIP_0),
                Component.translatable(TestMultiblockLang.TOOLTIP_1),
            )
            .pattern { definition ->
                MultiblockPatternBuilder
                    // 8.0.0 的 `aisle` 改叫 `slice`；无参 `start()` 就是默认三轴（GTM 自己的多方块也用它）。
                    // 3×3×3 立方体对轴向不敏感：3 个 slice × 每 slice 3 行 × 每行 3 字符，
                    // 中间那层的 `X X` 就是空腔，控制器嵌在第三个 slice 的中行中列。
                    .start()
                    .slice("XXX", "XXX", "XXX")
                    .slice("XXX", "X X", "XXX")
                    .slice("XXX", "XSX", "XXX")
                    .where('S', Predicates.controller(definition))
                    .where(
                        'X',
                        Predicates.blocks(SHELL)
                            .setMinGlobalLimited(MIN_SHELL)
                            // ⚠️ 8.0.0 要把 `.or(...)` 换成 `.and(...)`（`AndPredicate#testGlobalMin` 逐个查下限）：
                            // 写法与 GTM 自己的多方块一致（`GCYMMachines.java` 的机壳谓词就是
                            // `blocks(SHELL).setMinGlobalLimited(N).and(autoAbilities(配方类型)).and(autoAbilities(true,false,true))`）。
                            // 三条分别是：按配方类型自动配 IO 与能源仓；维护仓 + 并行仓；本机专属的线程仓槽。
                            .and(Predicates.autoAbilities(*definition.recipeTypes))
                            .and(Predicates.autoAbilities(true, false, true))
                            .and(
                                Predicates.abilities(ETPartAbility.THREAD_HATCH)
                                    .setMaxGlobalLimited(1)
                                    .setPreviewCount(1)
                            )
                    )
                    .where(' ', Predicates.air())
                    .build()
            }
            // 贴图全部复用 GTM 现成的：机壳贴图 = 外观方块自己的贴图；动画覆盖层 = GCYM 大型研磨塔
            // （同为研磨主题，`assets/gtceu/textures/block/multiblock/gcym/large_maceration_tower/`）。
            // ⚠️ 别改成「先 modelProperty 再 createWorkableCasingMachineModel」那套：`workableCasingModel`
            // 内部已经做过 `modelProperty(RECIPE_LOGIC_STATUS, IDLE)`，缺那句才会在 forAllStates 里炸。
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/gcym/large_maceration_tower"),
            )
            .register()
    }
}
