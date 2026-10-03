package rain.fox.gtetcore.common.data.machine.multiblock

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.multiblock.Predicates
import com.gregtechceu.gtceu.api.multiblock.pattern.MultiblockPatternBuilder
import com.gregtechceu.gtceu.api.multiblock.util.RelativeDirection
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.common.machine.multiblock.timeflow.MasterTowerMachine
import rain.fox.gtetcore.data.lang.MasterTowerLang
import rain.fox.gtetcore.registry.ETDataComponents
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * **主控塔**的多方块注册 —— 结构图案、外观、tooltip。
 *
 * 结构：3×3 底面积（控制器嵌在一侧墙里）、塔身 1~[MAX_SEGMENTS] 段（3×3 环、中心空腔）、3×3 顶盖。
 * 图案里的符号：`S` 控制器、`X` 机壳**或** GTM 能源仓（`PartAbility.INPUT_ENERGY`，1~4 个）、`' '` 空气。
 *
 * ⚠️ 塔身段数**不在图案里数**：8.0.0 删了 `MultiblockState#getMatchContext()`，
 * 老工程的段锚点谓词写法作废；改由 [MasterTowerMachine] 从结构缓存数 Y 层。
 *
 * 容量 = 段数 × `GtetConfig.towerSegmentCapacity()`（默认 1,000,000 TF 是**占位值**，待平衡定稿）；
 * 段数上限是结构常量 [MAX_SEGMENTS]，配置项 `towerMaxSegments` 只把计入容量的段数往下夹。
 *
 * @author rain fox
 */
object ETMasterTower {

    /** 注册名（决定方块 id `gtetscore:master_tower` 与 lang 键 `block.gtetscore.<id>`）。 */
    const val MASTER_TOWER_ID: String = "master_tower"

    /** 塔身段数上限（**结构常量**）：图案在编译期固定，配置项只能往下夹。 */
    const val MAX_SEGMENTS: Int = 10

    /** 机壳最少要几格（防「只有控制器 + 几个仓」的畸形塔）。 */
    private const val MIN_SHELL: Int = 16

    /** 塔身机壳（贴图与外观方块都用它）。 */
    private val SHELL: Block get() = GTBlocks.CASING_STEEL_SOLID.get()

    /** 注册主控塔；返回注册好的多方块定义。 */
    @JvmStatic
    fun register(registrate: GTRegistrate): MachineEntry<MultiblockMachineDefinition> {
        LangUtil.BLOCK_LANG[MASTER_TOWER_ID] = "主控塔"

        return registrate
            .multiblock(MASTER_TOWER_ID) { info -> MasterTowerMachine(info) }
            .langValue("Master Tower")
            .tier(GTValues.IV)
            .rotationState(RotationState.ALL)
            // 本机不跑配方：只借 WorkableElectricMultiblockMachine 那套「聚合能源仓」的底座
            .recipeType(GTRecipeTypes.DUMMY_RECIPES)
            .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
            .pattern { definition ->
                MultiblockPatternBuilder
                    // slice 沿 UP 叠（第一个参数是 slice 方向），塔身因此竖着长出来；GTM 自己的 PSS 同款。
                    // ⚠️ 第二个参数必须是 **FRONT**：它决定「同一 slice 里各行往哪边排」，而控制器 S 写在
                    //    基座的**最后一行** —— 只有这样 S 才落在最靠前的那一行，结构整块在控制器**背后**，
                    //    控制器的正面朝外。写成 BACK 会把结构前后镜像，控制器正面朝着塔里面（实机踩过）。
                    // ⚠️ 老工程是 `FactoryBlockPattern.start(charDir=LEFT, stringDir=FRONT, aisleDir=UP)`，
                    //    与新 API 的 `start(sliceDir, stringDir, charDir)` **参数顺序不同**，照位置抄会翻车；
                    //    字符方向 LEFT/RIGHT 对本图案无影响（基座 "XSX"、塔身 "X X" 都左右对称）。
                    .start(RelativeDirection.UP, RelativeDirection.FRONT, RelativeDirection.RIGHT)
                    // 基座：3×3 实心，控制器嵌在一侧墙里
                    .slice("XXX", "XXX", "XSX")
                    // 塔身段：3×3 环、中心空腔；可重复 1~MAX_SEGMENTS 段
                    .sliceRepeatable(1, MAX_SEGMENTS, "XXX", "X X", "XXX")
                    // 顶盖：3×3 实心
                    .slice("XXX", "XXX", "XXX")
                    .where('S', Predicates.controller(definition))
                    .where(
                        'X',
                        Predicates.blocks(SHELL)
                            .setMinGlobalLimited(MIN_SHELL)
                            // ⚠️ 用 and 不用 or：要的是「机壳 ≥ MIN_SHELL **且** 能源仓 1~4 个」
                            //    （`AndPredicate#testGlobalMin` 逐个查下限；GTM 自己的 PSS / 蒸馏塔也是这个写法）
                            .and(
                                Predicates.abilities(PartAbility.INPUT_ENERGY)
                                    .setMinGlobalLimited(1)
                                    .setMaxGlobalLimited(4)
                                    .setPreviewCount(1)
                            )
                    )
                    .where(' ', Predicates.air())
                    .build()
            }
            .tooltips(
                Component.translatable(MasterTowerLang.TOOLTIP_0),
                Component.translatable(MasterTowerLang.TOOLTIP_1),
                Component.translatable(MasterTowerLang.TOOLTIP_2),
            )
            // 掉落物（塔芯）上显示封存的储备；没有储备就一个字都不加
            .tooltipBuilder { stack, components ->
                val sealedTf = stack.getOrDefault(ETDataComponents.MASTER_TOWER_RESERVE.get(), 0L)
                if (sealedTf > 0L) {
                    components.add(
                        Component.translatable(
                            MasterTowerLang.SEALED,
                            FormattingUtil.formatNumbers(sealedTf),
                            FormattingUtil.formatNumbers(ETTimeFlow.tfToEu(sealedTf)),
                        ).withStyle(ChatFormatting.AQUA)
                    )
                }
            }
            // 贴图复用 GTM 现成的（TODO 以后画 GTET 自己的塔贴图，只改这两行）
            .workableCasingModel(
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/multiblock/power_substation"),
            )
            .register()
    }
}
