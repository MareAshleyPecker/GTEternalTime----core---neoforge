package rain.fox.gtetcore.common.data.machine.hatch

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
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.api.capability.ETPartAbility
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.common.machine.multiblock.timeflow.TimeFlowHatchPartMachine
import rain.fox.gtetcore.data.lang.TimeFlowHatchLang
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「时序仓」（TF 供给仓）注册入口 —— 一档一个方块，与 [ETOverclockHatches] 同构。
 *
 * 一个注册函数 + 一张变体表：遍历 [TimeFlowHatchVariant] 表逐个注册。与超频仓一致的四处约定：
 * 1. 用本模组自己的 `GTRegistrate`（`ETRegistrate.REGISTRATE`），否则方块会注册进 `gtceu:` 命名空间；
 * 2. 每个变体**只**生成名字语言键 `block.gtetscore.<id>`，名字里直接带电压等级 + 小时数 + 容量，
 *    英文名走 `.langValue(...)`、中文名走 [LangUtil.BLOCK_LANG]；
 * 3. `tier` 必须**最先**设置（abilities 与分级外壳贴图都要读它）；
 * 4. 「多方块共享」那一行走 `tooltipBuilder`（渲染时才按配置取值，见 [ETPartSharing.line]）。
 *
 * ## 档位（⚠️ 这一族只有 6 档，虚档位留了 TODO）
 * 本表是 **UHV ~ MAX 六档真实档位**（1h / 16h / 64h / 256h / 1024h / 4096h）。
 * 老工程是七档（UHV ~ `ETV`），第七档 `16384h` 落在**虚档位 `ETV` = `MAX + 1`** 上；
 * 本工程**还没有移植 `ETValues`**（虚档位那一套：`ETV` / `ETV_VOLTAGE` / `nameOf` / `gtmTierOf`），
 * 硬写就等于把那几个常量的定义抄第二遍，所以本切片只做真实档位，第七档的接法与后果写在
 * [VARIANTS] 下面那段 `TODO(虚档位切片)` 里。
 *
 * ## 接线位置与**注册顺序**（⚠️ 这条是硬要求）
 * 本文件的 [register] 由 `ETMachines.TIME_FLOW_HATCHES` 调用一次。
 * 它必须**早于任何消费它的多方块**（老工程是靠 `ALLSmachine.init()` 早于 `ALLMmachine.init()` 保证的）：
 * `PartAbility#getAllBlocks()` 是**懒记忆化**的（`PartAbility.java:63` 的 `GTMemoizer`，首取即定、
 * 之后不再变），而 `Predicates.abilities(ETPartAbility.TF_HATCH)` 会在**构造谓词那一刻**就把它取出来
 * （内部是立即求值的 flatMap-toArray）。本仓库目前还没有消费 TF 的多方块，所以现在把时序仓排在
 * `ETMachines` 的最前面就是安全的；**将来加时序多方块时，它的结构图案必须排在时序仓之后登记**
 * （或者用 `Supplier` 延迟到运行期构造），否则会静默地一个都插不进去。
 *
 * @author rain fox
 */
object ETTimeFlowHatches {

    /**
     * 「时序仓」变体定义：一个变体 = 一个方块。
     *
     * **小时数与容量都写在这一行里**（与 `OverclockHatchVariant` 同构：规格是表的显式参数，
     * 扫一眼表就知道每档多少 TF），构造时由 [registerOne] 原样传给 [TimeFlowHatchPartMachine]
     * —— 表里写什么就是什么，本类不做任何推导。
     *
     * ⚠️ **小时数与容量必须自己对上**（`容量 = 小时数 × 3600`，`1 小时 = 3600 TF`，[ETTimeFlow.TF_PER_HOUR]）；
     * 这一行要改就两列一起改。构造时的 `require` 会把写错的那一行直接炸出来，免得变成
     * 「名字写 16h、仓里其实装 1h」这种只在游戏里才发现的问题。
     *
     * ⚠️ 容量梯子**不是**纯 ×4 数列：`1h` 之后直接跳到 `16h`（×16），之后每档 ×4，到 `4096h` 封顶
     * （设定 §2.2 已定：明确不设 `4h` 与 `65536h`）。
     *
     * @param id         注册名（同时决定方块 id 与名字语言键 `block.gtetscore.<id>`）
     * @param hours      该档的小时数（只用于显示与校验，容量另给）
     * @param capacityTf 该档容量上限（TF），= `hours × ETTimeFlow.TF_PER_HOUR`
     * @param tier       电压档位（**真实** GTM 档位，见类注释）
     * @param tooltip    可选说明行（中英成对）；`null` = 这一档不加说明行
     */
    data class TimeFlowHatchVariant(
        val id: String,
        val hours: Int,
        val capacityTf: Long,
        val tier: Int,
        val tooltip: TimeFlowHatchTooltip? = null,
    ) {
        init {
            require(tier in 0..GTValues.MAX) {
                "TimeFlowHatchVariant '$id': tier=$tier 超出 GTM 的 0..${GTValues.MAX}" +
                    "（虚档位要另立一套逻辑档位，见 ETTimeFlowHatches 的类注释）"
            }
            require(capacityTf == hours.toLong() * ETTimeFlow.TF_PER_HOUR) {
                "TimeFlowHatchVariant '$id': capacityTf=$capacityTf 与 ${hours}h×${ETTimeFlow.TF_PER_HOUR} 对不上"
            }
        }
    }

    /**
     * 一条物品提示（tooltip）文案，中英**必须成对**给。
     *
     * 中文写进 `zh_cn`、英文写进 `en_us`，键名由 [registerOne] 生成成
     * `gtetscore.machine.<id>.tooltip.0`（与超频仓同一套）。
     * 目前六档都不需要额外说明行 —— 这个口子留着是给**虚档位 `16384h`** 那档用的
     * （它的电压 GTM 表达不出来，必须在提示里写明「这一档是 ETV」）。
     */
    data class TimeFlowHatchTooltip(val cn: String, val en: String)

    /**
     * 时序仓正面覆盖层的来源命名空间。
     *
     * ⚠️ 这批贴图是 **GTOCore 的素材**（版权归 GTOCore 作者所有，LGPL-3.0），
     * 随本 mod 一起分发、只引用不修改；来源与授权原文见 `assets/gtocore/LICENSE.txt`。
     */
    private const val GTOCORE_NS = "gtocore"

    /**
     * 本族用的 GTOCore 覆盖层目录：**加速仓**（`accelerate_hatch`）。
     *
     * 时序仓没有自己的美术资源，这里按「材质先用现成的」的既有约定借一张语义最接近的
     * （加速 / 时间），只作占位。目录里有 `overlay_front` 与 `overlay_front_active`，
     * IDLE 与 WORKING 是两张不同的正面贴图（与超频仓那套一致）。
     * TODO 以后画 GTET 自己的时序仓贴图，把这个常量换成自己的目录即可。
     */
    private const val accelerate_overlay_root = "block/machines/accelerate_hatch/accelerate_hatch_mk"

    /** GTOCore 加速仓目录里实际存在的 mk 编号区间（本仓资源里 `mk1` ~ `mk14`）。 */
    private const val accelerate_mk_min = 1
    private const val accelerate_mk_max = 14

    /**
     * 变体对应的 GTOCore 覆盖层目录（`createWorkableTieredHullMachineModel` 的 `overlayDir` 参数）。
     *
     * 编号规则与超频仓一致：`mk = 档位 - ZPM` ⇒ 本族 UHV(9)..MAX(14) 取到 `mk2`..`mk7`，
     * 逐档一一对应，不会两档撞同一张。
     */
    private fun overlayFor(v: TimeFlowHatchVariant): ResourceLocation = GTETSCore.id(
        GTOCORE_NS,
        accelerate_overlay_root + (v.tier - GTValues.ZPM).coerceIn(accelerate_mk_min, accelerate_mk_max)
    )

    /**
     * 全部时序仓变体（6 档，UHV ~ MAX），容量按设定 §2.2 定稿值写死：
     * `1h=3600 / 16h=57600 / 64h=230400 / 256h=921600 / 1024h=3686400 / 4096h=14745600`（TF）。
     *
     * ⚠️ **顺序即注册顺序**（影响物品栏与存档里的方块出现次序），加档请往末尾追加、
     * 不要重排既有行、也不要改既有 id。
     *
     * ```
     * TODO(虚档位切片): 追加第七档 `tf_hatch_16384h`（16384h = 58_982_400 TF，电压 `ETV`）。
     *   它落在虚档位 `ETV = GTValues.MAX + 1` 上，需要先把老工程的 `ETValues` 移植过来，然后：
     *   ① 变体表存**逻辑档位**（可能是 15），交给 GTM 的一律先过 `ETValues.gtmTierOf` 夹回 MAX；
     *   ② 名字与提示改用 `ETValues.nameOf`，容量那一档的提示写明 ETV 电压是 8589934592 EU/t
     *      （`ETValues.ETV_VOLTAGE`，GTM 的 VN 里没有 ETV，硬取越界）；
     *   ③ 覆盖层编号要用**逻辑档位**算：`ETV(15) - ZPM(7) = mk8`（资源里有），
     *      否则它和 4096h 那一档会共用 mk7、长得一模一样。
     *   ④ 后果（有意为之，不是 bug）：`4096h=MAX` 与 `16384h=ETV` 注册到 GTM 的档位**都是 MAX**
     *      —— 电压相同、外壳贴图相同，只有容量与正面覆盖层不同。这是虚档位的必然结果。
     * ```
     */
    val VARIANTS: List<TimeFlowHatchVariant> = listOf(
        TimeFlowHatchVariant("tf_hatch_1h", 1, 3_600L, GTValues.UHV),
        TimeFlowHatchVariant("tf_hatch_16h", 16, 57_600L, GTValues.UEV),
        TimeFlowHatchVariant("tf_hatch_64h", 64, 230_400L, GTValues.UIV),
        TimeFlowHatchVariant("tf_hatch_256h", 256, 921_600L, GTValues.UXV),
        TimeFlowHatchVariant("tf_hatch_1024h", 1024, 3_686_400L, GTValues.OpV),
        TimeFlowHatchVariant("tf_hatch_4096h", 4096, 14_745_600L, GTValues.MAX),
    )

    /** 把整张变体表注册成方块；返回顺序与表一致。 */
    @JvmStatic
    @JvmOverloads
    fun register(
        registrate: GTRegistrate,
        variants: List<TimeFlowHatchVariant> = VARIANTS,
    ): List<MachineEntry<MachineDefinition>> = variants.map { registerOne(registrate, it) }

    /**
     * 注册单档。
     *
     * 中英双语走现有机制，**名字是必有的一条键**：中文名里带「电压等级 + 时序仓（小时数 / 容量 TF）」，
     * 一眼分得出六档；英文名同形走 `langValue`。
     * 提示只有两条：GTM 自带的「部件不可共享」+ 怎么绑定主控塔（[TimeFlowHatchLang.TOOLTIP]，中英共用一条键）；
     * 变体表里填了 [TimeFlowHatchVariant.tooltip] 的档位会再多一条
     * `gtetscore.machine.<id>.tooltip.0`（目前没有这样的档位，见 [TimeFlowHatchTooltip]）。
     */
    private fun registerOne(registrate: GTRegistrate, v: TimeFlowHatchVariant): MachineEntry<MachineDefinition> {
        val tierName = GTValues.VN[v.tier]
        val capacity = v.capacityTf

        // 名字里的容量与仓的上限**同源**：都取变体表这一行的 capacityTf
        LangUtil.BLOCK_LANG[v.id] = "$tierName 时序仓（${v.hours} 小时 / $capacity TF）"
        v.tooltip?.let {
            LangUtil.add("gtetscore.machine.${v.id}.tooltip.0", it.en, it.cn)
        }

        val builder = registrate
            .machineBuilder(v.id) { info -> TimeFlowHatchPartMachine(info, v.tier, capacity) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            // ⚠️ 分级外壳贴图内部是 `GTValues.VN[tier]`（GTMachineModels.getTieredHullTexture，无边界检查），
            //    所以这里只能是真实档位 0..MAX（虚档位那一档见 VARIANTS 的 TODO）
            .tier(v.tier)
            .langValue("$tierName Time Flow Hatch (${v.hours}h / $capacity TF)")
            .rotationState(RotationState.ALL)
            // ⚠️ 本能力就是设定 §2.3「这台多方块支持 TF」的标记位，见 ETPartAbility.TF_HATCH
            .abilities(ETPartAbility.TF_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图借 GTOCore 的加速仓覆盖层（按档位取 mk 编号，规则见 overlayFor）。
            // helper 内部把父模型定成 `gtceu:block/casings/voltage/<tier>`（电压等级外壳），
            // 再把 overlayDir 下的 overlay_front / overlay_front_active 叠在正面。
            // ⚠️ 素材版权归 GTOCore 作者所有（LGPL-3.0），见 `assets/gtocore/LICENSE.txt`；只引用不修改。
            .model(createWorkableTieredHullMachineModel(overlayFor(v)))

        // 只有少数档位有额外说明行（目前没有；虚档位那一档会用到）
        v.tooltip?.let { builder.tooltips(Component.translatable("gtetscore.machine.${v.id}.tooltip.0")) }

        // 共享提示走渲染时取值，因为能不能共享由配置里的全局开关决定
        return builder
            .tooltipBuilder { _, list ->
                list.add(ETPartSharing.line())
                list.add(Component.translatable(TimeFlowHatchLang.TOOLTIP))
            }
            .register()
    }
}
