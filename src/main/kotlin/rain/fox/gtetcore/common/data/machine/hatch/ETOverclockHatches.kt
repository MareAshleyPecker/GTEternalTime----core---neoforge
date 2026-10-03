package rain.fox.gtetcore.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createWorkableTieredHullMachineModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.api.capability.ETPartAbility
import rain.fox.gtetcore.common.machine.multiblock.part.ETOverclockHatchPartMachine
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 的「超频仓」注册表（17 档：4× / 16× / 64× / 256× 各 4 档 + 1024× 一档）。
 *
 * 每级超频的收益固定是「耗时 ÷S、EUt ×(E×S)」，所以名字里的「×N Energy」就是 `E × S`。
 * 正面贴图来自 GTOCore 的素材（LGPL-3.0，见 `assets/gtocore/LICENSE.txt`），
 * 编号规则 `mk = tier - ZPM`，ZPM 档收进 mk1。
 */
object ETOverclockHatches {

    /**
     * 一个变体 = 一个方块。
     *
     * @param speed        速度倍率 S：每消耗 1 级超频，配方耗时 ÷S
     * @param energyFactor 能效系数 E：每消耗 1 级超频，EUt × (E × S)
     * @param tooltip      可选说明行（中英成对），`null` = 这一档不加说明行
     */
    data class OverclockHatchVariant(
        val id: String,
        val speed: Int,
        val energyFactor: Double,
        val tier: Int,
        val tooltip: HatchTooltip? = null,
    ) {

        /** 每级超频实际的 EUt 倍率 = `E × S`（名字里显示的那个数）。 */
        val eutPerLevel: Double get() = energyFactor * speed
    }

    /** 一条提示文案，中英必须成对给（英文进 registrate 的 en_us，中文进 zh_cn）。 */
    data class HatchTooltip(val cn: String, val en: String)

    /** GTOCore 素材命名空间。 */
    private const val GTOCORE_NS = "gtocore"

    /** GTOCore 超频仓覆盖层目录前缀（完整路径 = 本前缀 + mk 编号）。 */
    private const val overclock_overlay_root = "block/machines/overclock_hatch/overclock_hatch_mk"

    /** GTOCore 的 mk 编号区间：`mk1` ↔ UV，`mk7` ↔ MAX；本族多出的 ZPM 档收进 mk1。 */
    private const val overclock_mk_min = 1
    private const val overclock_mk_max = 7

    private fun overlayFor(v: OverclockHatchVariant): ResourceLocation = GTETCore.id(
        GTOCORE_NS,
        overclock_overlay_root + (v.tier - GTValues.ZPM).coerceIn(overclock_mk_min, overclock_mk_max)
    )

    /** 全部超频仓变体。 */
    val VARIANTS: List<OverclockHatchVariant> = listOf(
        // 4× 系列：ZPM / UV / UHV / UEV
        OverclockHatchVariant(
            "overclock_hatch_4x_lossy4", 4, 8.0, GTValues.ZPM,
            tooltip = HatchTooltip("看起来并不好用", "Looks pretty useless")
        ),
        OverclockHatchVariant("overclock_hatch_4x_lossy2", 4, 4.0, GTValues.UV),
        OverclockHatchVariant("overclock_hatch_4x_perfect", 4, 2.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_4x_saving", 4, 1.0, GTValues.UEV),
        // 16× 系列：UV / UHV / UEV / UIV
        OverclockHatchVariant("overclock_hatch_16x_lossy4", 16, 8.0, GTValues.UV),
        OverclockHatchVariant("overclock_hatch_16x_lossy2", 16, 4.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_16x_perfect", 16, 2.0, GTValues.UEV),
        OverclockHatchVariant("overclock_hatch_16x_saving", 16, 1.0, GTValues.UIV),
        // 64× 系列：UHV / UEV / UIV / UXV
        OverclockHatchVariant("overclock_hatch_64x_lossy4", 64, 8.0, GTValues.UHV),
        OverclockHatchVariant("overclock_hatch_64x_lossy2", 64, 4.0, GTValues.UEV),
        OverclockHatchVariant("overclock_hatch_64x_perfect", 64, 2.0, GTValues.UIV),
        OverclockHatchVariant("overclock_hatch_64x_saving", 64, 1.0, GTValues.UXV),
        // 256× 系列：UEV / UIV / UXV / OpV
        OverclockHatchVariant("overclock_hatch_256x_lossy4", 256, 4.0, GTValues.UEV),
        OverclockHatchVariant("overclock_hatch_256x_lossy2", 256, 2.0, GTValues.UIV),
        OverclockHatchVariant("overclock_hatch_256x_perfect", 256, 1.0, GTValues.UXV),
        OverclockHatchVariant("overclock_hatch_256x_saving", 256, 0.5, GTValues.OpV),
        // 1024× 档：本表终点，铭牌 MAX
        OverclockHatchVariant(
            "overclock_hatch_1024x_saving_max", 1024, 0.25, GTValues.MAX,
            tooltip = HatchTooltip("so good~", "so goooooood~")
        ),
    )

    /** 把整张变体表注册成方块；返回顺序与表一致。 */
    @JvmStatic
    @JvmOverloads
    fun register(
        registrate: GTRegistrate,
        variants: List<OverclockHatchVariant> = VARIANTS,
    ): List<MachineEntry<MachineDefinition>> = variants.map { registerOne(registrate, it) }

    /** 注册单档：中文名与说明行登记进语言缓存，英文名走 `langValue`。 */
    private fun registerOne(registrate: GTRegistrate, v: OverclockHatchVariant): MachineEntry<MachineDefinition> {
        val tierName = GTValues.VN[v.tier]
        val eut = v.eutPerLevel.toInt()

        LangUtil.BLOCK_LANG[v.id] = "$tierName 超频仓（${v.speed}× Speed|×$eut Energy）"
        v.tooltip?.let {
            val key = "gtetcore.machine.${v.id}.tooltip.0"
            LangUtil.add(key, it.en, it.cn)
        }

        val builder = registrate
            .machineBuilder(v.id) { info ->
                ETOverclockHatchPartMachine(info, v.tier, v.speed, v.energyFactor)
            }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Overclock Hatch (${v.speed}x Speed / x$eut Energy)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.OVERCLOCK_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            .model(createWorkableTieredHullMachineModel(overlayFor(v)))

        // 只有少数档位有额外说明行
        v.tooltip?.let { builder.tooltips(Component.translatable("gtetcore.machine.${v.id}.tooltip.0")) }

        // 共享提示走渲染时取值，因为能不能共享由配置里的全局开关决定
        return builder.tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }.register()
    }
}
