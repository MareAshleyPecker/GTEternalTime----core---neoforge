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
import rain.fox.gtetcore.api.capability.ETPartAbility
import rain.fox.gtetcore.common.machine.multiblock.part.ThreadHatchPartMachine
import rain.fox.gtetcore.data.lang.ThreadHatchLang
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「线程仓」注册入口 —— ZPM ~ MAX 共 **8 档真实档位**，一个注册函数 + 一张变体表。
 *
 * 与 [ETTimeFlowHatches] / [ETWirelessEnergyHatches] 同构：用本模组自己的 `ETRegistrate`，
 * 每个变体只生成名字语言键 `block.gtetscore.<id>`，`tier` 必须最先设置。
 *
 * ⚠️ 本族**没有虚档位**：交给 GTM 的 `tier` 就是变体表那个真实档位。
 *
 * ⚠️ 登记顺序：本表的 [register] 由 `ETMachines.THREAD_HATCHES` 调用，必须早于任何消费它的多方块
 * —— `PartAbility#getAllBlocks()` 是懒记忆化的（`PartAbility.java:63`，首取即定）。
 *
 * @author rain fox
 */
object ETThreadHatches {

    /**
     * 「线程仓」变体定义：一个变体 = 一个方块，**线程数就写在这一行里**。
     *
     * ⚠️ 线程数与 tier 必须自己对上（4↔ZPM、8↔UV、16↔UHV、32↔UEV、64↔UIV、128↔UXV、256↔OpV、512↔MAX，
     * 从 ZPM 起每档相对上一档翻倍）。
     *
     * ⚠️ 「线程数」与并行仓的「并行数」不是同一个量：线程是同时能跑的**配方实例数**上限，
     * 并行仓那个数是**每条线程**各吃的并行倍率，整机处理量 ≈ 线程数 × 并行倍数 —— 两族档位不用对齐。
     *
     * @param id      注册名（同时是方块 id 与名字语言键 `block.gtetscore.<id>`）
     * @param threads 该档的线程数上限
     * @param tier    电压档位（决定外壳与正面覆盖层）
     */
    data class ThreadHatchVariant(
        val id: String,
        val threads: Int,
        val tier: Int,
    ) {
        init {
            require(tier in GTValues.ZPM..GTValues.MAX) {
                "ThreadHatchVariant '$id': tier=$tier 超出 ZPM..MAX（本族没有虚档位）"
            }
            require(threads >= ThreadHatchPartMachine.MIN_THREAD) {
                "ThreadHatchVariant '$id': threads=$threads 必须 ≥ ${ThreadHatchPartMachine.MIN_THREAD}"
            }
        }
    }

    /**
     * 正面覆盖层的来源命名空间。
     *
     * ⚠️ 这批贴图是 **GTOCore 的素材**（版权归 GTOCore 作者所有，LGPL-3.0），
     * 随本 mod 一起分发、只引用不修改；来源与授权原文见 `assets/gtocore/LICENSE.txt`。
     */
    private const val GTOCORE_NS = "gtocore"

    /** GTOCore 线程仓覆盖层目录前缀：完整路径 = 本前缀 + mk 编号（`..._mk1` … `..._mk7`）。 */
    private const val THREAD_OVERLAY_ROOT = "block/machines/thread_hatch/thread_hatch_mk"

    /**
     * 变体对应的 GTOCore 覆盖层目录（`createWorkableTieredHullMachineModel` 的 `overlayDir` 参数）。
     *
     * ⚠️ GTOCore 的素材只有 `mk1` ~ `mk7`，而本族的编号是 `mk = tier - ZPM` ⇒ **ZPM 那一档算出 `mk0`，
     * 资源里没有 ⇒ 该档没有正面覆盖层，是纯外壳**（UV 起才有图：uv→mk1 … max→mk7）。
     * 老工程同样如此，这里原样保留、不夹取（夹到 mk1 会让 ZPM 与 UV 长得一模一样）。
     */
    private fun overlayFor(v: ThreadHatchVariant): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(GTOCORE_NS, THREAD_OVERLAY_ROOT + (v.tier - GTValues.ZPM))

    /**
     * 全部线程仓变体（8 档）：ZPM 起每级翻倍，一路到 MAX。
     *
     * ⚠️ 顺序即注册顺序（影响物品栏与存档里的出现次序），加档请往末尾追加、不要重排既有行、也不要改既有 id。
     */
    val VARIANTS: List<ThreadHatchVariant> = listOf(
        ThreadHatchVariant("thread_hatch_zpm", 4, GTValues.ZPM),
        ThreadHatchVariant("thread_hatch_uv", 8, GTValues.UV),
        ThreadHatchVariant("thread_hatch_uhv", 16, GTValues.UHV),
        ThreadHatchVariant("thread_hatch_uev", 32, GTValues.UEV),
        ThreadHatchVariant("thread_hatch_uiv", 64, GTValues.UIV),
        ThreadHatchVariant("thread_hatch_uxv", 128, GTValues.UXV),
        ThreadHatchVariant("thread_hatch_opv", 256, GTValues.OpV),
        ThreadHatchVariant("thread_hatch_max", 512, GTValues.MAX),
    )

    /** 把整张变体表注册成方块；返回顺序与表一致。 */
    @JvmStatic
    @JvmOverloads
    fun register(
        registrate: GTRegistrate,
        variants: List<ThreadHatchVariant> = VARIANTS,
    ): List<MachineEntry<MachineDefinition>> = variants.map { registerOne(registrate, it) }

    /**
     * 注册单档：**只登记名字这一条键**（中英都带「电压等级 + 线程数」，一眼分得出八档）。
     *
     * 提示是**两条**：GTM 自带的「部件不可共享」那一行（走 [ETPartSharing.line]，因为本件 `canShared()`
     * 读全局配置、要在渲染时才能决定取 Enabled 还是 Disabled），加上说明线程语义的共用行
     * [ThreadHatchLang.TOOLTIP]（八档同一条键，参数是本档线程数）。
     */
    private fun registerOne(registrate: GTRegistrate, v: ThreadHatchVariant): MachineEntry<MachineDefinition> {
        val tierName = GTValues.VN[v.tier]
        val threads = v.threads

        LangUtil.BLOCK_LANG[v.id] = "$tierName 线程仓（$threads 线程）"

        return registrate
            .machineBuilder(v.id) { info -> ThreadHatchPartMachine(info, v.tier, threads) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(v.tier)
            .langValue("$tierName Thread Hatch ($threads Threads)")
            .rotationState(RotationState.ALL)
            .abilities(ETPartAbility.THREAD_HATCH)
            .modelProperty(GTMachineModelProperties.IS_FORMED, false)
            .modelProperty(GTMachineModelProperties.RECIPE_LOGIC_STATUS, RecipeLogic.Status.IDLE)
            // 贴图用 GTOCore 的线程仓覆盖层；这几套目录里 IDLE 与 WORKING 是两张不同的正面贴图。
            // `addReplaceableTextures` 让上 / 下 / 侧面能随结构外壳替换（GTOCore 覆盖层只提供正面）。
            // ⚠️ 素材版权归 GTOCore 作者所有（LGPL-3.0），见 `assets/gtocore/LICENSE.txt`；只引用不修改。
            .model(
                createWorkableTieredHullMachineModel(overlayFor(v))
                    .andThen { _, _, model -> model.addReplaceableTextures("bottom", "top", "side") }
            )
            .tooltipBuilder { _, list ->
                list.add(ETPartSharing.line())
                list.add(Component.translatable(ThreadHatchLang.TOOLTIP, threads))
            }
            .register()
    }
}
