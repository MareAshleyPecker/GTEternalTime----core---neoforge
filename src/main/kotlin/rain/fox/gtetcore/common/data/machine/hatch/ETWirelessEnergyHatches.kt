@file:Suppress("ConstPropertyName")

package rain.fox.gtetcore.common.data.machine.hatch

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import com.gregtechceu.gtceu.common.data.models.GTMachineModels.createOverlayTieredHullMachineModel
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.common.machine.multiblock.part.WirelessEnergyHatchPartMachine
import rain.fox.gtetcore.data.lang.WirelessEnergyHatchLang
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil
import java.util.Locale

/**
 * 「无线能源仓」注册入口 —— **10 电压档 × 11 安培档 = 110 个方块定义**。
 *
 * 与 [ETTimeFlowHatches] 同构：一个注册函数 + 一张变体表，遍历表逐个注册；
 * 用 GTET 自己的 `ETRegistrate`（否则方块会注册进 `gtceu:` 命名空间）。
 *
 * ## 档位与 id
 * 电压 `IV / LuV / ZPM / UV / UHV / UEV / UIV / UXV / OpV / MAX`（10 档）；
 * 安培 小仓 `1 / 16 / 64` + 大仓 `256` 起每档 ×4 到 `4194304`（8 档）。
 * id 规则 `wireless_energy_hatch_<安培>a_<电压名小写>`，电压名走 `GTValues.VN`。
 *
 * ⚠️ **本族没有虚档位**：电压最高只到 `MAX`，所以 `MachineBuilder#tier` 与 `TieredPartMachine`
 * 吃同一个档位，不需要 `ETValues.gtmTierOf` 夹取（与时序仓那族的 `ETV` 不同）。
 *
 * ## 能力：复用 GTM 现成的 `PartAbility.INPUT_ENERGY`，**不新建能力、也不加 mixin**
 * 多方块的结构图案是**懒记忆化**的（`MultiblockMachineDefinition.java:58` 的 `GTMemoizer`），
 * 图案里的 `Predicates.abilities(...)`（`Predicates.java:235-240`）到首次结构检测时才取
 * `getAllBlocks()`（`PartAbility.java:63` 记忆化）—— 那时本族早已注册完，所以天然能被任意
 * GTM / GCYM 多方块的通用能源槽接受。副作用一条：槽位带 GTM 自己的 `setMaxGlobalLimited(2)`，
 * 本族与 GTM 自家能源仓**共用那两个名额**，这是 GTM 的意思。
 *
 * ## 贴图：借 GTM 自带的扁平覆盖层模型（按安培档选）
 * 电压外壳由 helper 按 `tier` 自动套（`GTMachineModels.java:96-105` 的 `tieredHullTextures`），
 * 安培靠正面覆盖层区分：小仓三档各借本安培的能源仓模型（`GTMachines.java:818,856,894`），
 * 大仓 8 档共用 `laser_target_hatch`（GTM 没有 256A 以上的能源仓正面图，代价是大仓不随电压变色）。
 * ⚠️ 贴图是 **GTM 自带素材**（`gtceu` 命名空间），本 mod 只引用、不新增也不修改任何图片文件。
 *
 * ⚠️ 登记顺序仍按本仓库约定：本表的 [register] 调用点必须早于任何消费它的多方块。
 */
object ETWirelessEnergyHatches {

    /** 电压档：`IV` 起、`MAX` 止，共 10 档（**全是 `GTValues` 里真实存在的档位**）。 */
    private val TIERS: List<Int> = listOf(
        GTValues.IV, GTValues.LuV, GTValues.ZPM, GTValues.UV, GTValues.UHV,
        GTValues.UEV, GTValues.UIV, GTValues.UXV, GTValues.OpV, GTValues.MAX
    )

    /** 安培档：小仓 `1 / 16 / 64`，大仓 `256` 起每档 ×4，到 `4194304 = 2²²` 封顶，共 11 档。 */
    private val AMPERAGES: List<Int> = listOf(
        1, 16, 64,
        256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304
    )

    /** 小仓与大仓的分界安培：`<= 64` 是小仓（能源仓正面），`>= 256` 是大仓（激光系列正面）。 */
    private const val SMALL_HATCH_MAX_AMP: Int = 64

    /** 本族应有的方块数（10 × 11 = 110）；表或档位改动时把这一条一起改。 */
    const val EXPECTED_COUNT: Int = 110

    /**
     * 「无线能源仓」变体定义：一个变体 = 一个方块。
     *
     * **电压与安培都写在这一行里**，其余一切（id、名字）都由它们推出。
     *
     * ⚠️ **容量不在这张表里**：它由机器类按 `V[tier] × 64 × amperage` 现算
     * （[WirelessEnergyHatchPartMachine.capacityEu]）—— 两处各写一份迟早会对不上。
     */
    data class WirelessEnergyHatchVariant(val tier: Int, val amperage: Int) {

        /** 中文 / 英文名里的电压名（`IV` / `LuV` / … / `MAX`），与 GTM 自己的仓同一套口径。 */
        val tierName: String get() = GTValues.VN[tier]

        /**
         * 注册名 / 方块 id（同时是名字语言键 `block.gtetscore.<id>` 的后半段）：
         * `wireless_energy_hatch_<安培>a_<电压名小写>`。
         *
         * ⚠️ 小写必须过 [Locale.ROOT]：土耳其语环境里 `I` 会被折成 `ı`（`iv` → `ıv`），id 就变了。
         */
        val id: String get() = "wireless_energy_hatch_${amperage}a_${tierName.lowercase(Locale.ROOT)}"

        init {
            require(tier in GTValues.IV..GTValues.MAX) {
                "WirelessEnergyHatchVariant: tier=$tier 超出 IV..MAX（本族没有虚档位）"
            }
            require(amperage > 0) { "WirelessEnergyHatchVariant: amperage=$amperage 必须为正" }
        }
    }

    /**
     * 全部变体 = 电压档 × 安培档（10 × 11 = 110）。
     *
     * **顺序即注册顺序**（影响物品栏与存档里的出现次序）：外层电压（IV → MAX）、内层安培（1A → 4194304A）。
     * 加档请往末尾追加，不要重排既有行、也不要改既有 id。
     */
    val VARIANTS: List<WirelessEnergyHatchVariant> = TIERS.flatMap { tier ->
        AMPERAGES.map { amp -> WirelessEnergyHatchVariant(tier, amp) }
    }.also { variants ->
        require(variants.size == EXPECTED_COUNT) {
            "无线能源仓变体数 ${variants.size} ≠ $EXPECTED_COUNT（10 档电压 × 11 档安培）"
        }
        require(variants.map { it.id }.toSet().size == variants.size) { "无线能源仓变体 id 有重复" }
    }

    /** GTM 自带素材所属的命名空间（只引用、不分发）。 */
    private const val GTM_NS = "gtceu"

    /** 电压外壳 + 安培正面：模型名 → 完整资源路径的前缀。 */
    private const val GTM_PART_MODEL_PREFIX = "block/machine/part/"

    /**
     * 该安培档借用的 GTM 部件模型：小仓三档各拿本安培的正面，大仓 8 档共用激光系列。
     *
     * ⚠️ 必须走 `ResourceLocation` 这个重载：模型由 `prov.models().getExistingFile(...)` 按命名空间查找，
     * 模型都在 `gtceu` 里（已核对 jar：四个模型文件都在 `assets/gtceu/models/block/machine/part/`）。
     */
    private fun modelFor(amperage: Int): ResourceLocation {
        val name = when {
            amperage <= 1 -> "energy_input_hatch"       // 正面是 overlay_energy_1a_*
            amperage <= 16 -> "energy_input_hatch_16a"  // 正面是 overlay_energy_16a_*
            amperage <= SMALL_HATCH_MAX_AMP -> "energy_input_hatch_64a"
            else -> "laser_target_hatch"                // 256A ~ 4194304A 八档共用
        }
        return ResourceLocation.fromNamespaceAndPath(GTM_NS, GTM_PART_MODEL_PREFIX + name)
    }

    /** 把整张变体表注册成方块；返回顺序与表一致。 */
    @JvmStatic
    @JvmOverloads
    fun register(
        registrate: GTRegistrate,
        variants: List<WirelessEnergyHatchVariant> = VARIANTS,
    ): List<MachineEntry<MachineDefinition>> = variants.map { registerOne(registrate, it) }

    /**
     * 注册单档：中英双语走现有机制（英文名 `.langValue(...)`、中文名 [LangUtil.BLOCK_LANG]）。
     *
     * 提示是**两条**：GTM 自带的「部件不可共享」那一行（走 [ETPartSharing.line]，因为本件
     * `canShared()` 读全局配置、要在渲染时才能决定取 Enabled 还是 Disabled），
     * 加上说明损耗与绑定方式的**共用行** [WirelessEnergyHatchLang.TOOLTIP]（110 档同一条键）。
     */
    private fun registerOne(
        registrate: GTRegistrate,
        v: WirelessEnergyHatchVariant,
    ): MachineEntry<MachineDefinition> {
        val tierName = v.tierName
        val amp = v.amperage

        LangUtil.BLOCK_LANG[v.id] = "$tierName 无线能源仓（${amp}A）"

        return registrate
            .machineBuilder(v.id) { info -> WirelessEnergyHatchPartMachine(info, v.tier, amp) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Wireless Energy Hatch (${amp}A)")
            .rotationState(RotationState.ALL)
            // 复用 GTM 的 INPUT_ENERGY（理由见类注释）
            .abilities(PartAbility.INPUT_ENERGY)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .model(createOverlayTieredHullMachineModel(modelFor(amp)))
            .tooltipBuilder { _, list ->
                list.add(ETPartSharing.line())
                list.add(Component.translatable(WirelessEnergyHatchLang.TOOLTIP))
            }
            .register()
    }
}
