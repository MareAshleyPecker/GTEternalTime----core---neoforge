package rain.fox.gtetcore.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.data.machine.hatch.ETMEPatternBufferHatches.PROXY_ID
import rain.fox.gtetcore.common.data.machine.hatch.ETMEPatternBufferHatches.PROXY_TIERS_TOOLTIP_KEY
import rain.fox.gtetcore.common.data.machine.hatch.ETMEPatternBufferHatches.STAGES
import rain.fox.gtetcore.common.data.machine.hatch.ETMEPatternBufferHatches.register
import rain.fox.gtetcore.common.data.machine.hatch.ETMEPatternBufferHatches.registerProxy
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferPartMachine
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEPatternBufferProxyPartMachine
import rain.fox.gtetcore.data.lang.PatternBufferLang
import rain.fox.gtetcore.integration.ae2.ETPatternBufferCapacities
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「多阶段 ME 样板总成 + 通用镜像」注册入口：**四档总成 + 一件镜像**。
 *
 * ## 这两件是什么 / 不是什么
 *
 * **是**：GTM 自带的 `me_pattern_buffer`（AE2 集成式样板供应器）**容量加大版** —— GTM 那份的
 * 容量写死 27（`MEPatternBufferPartMachine.MAX_PATTERN_COUNT`，且用在父类字段初始化里），
 * 本族按档给 27 / 63 / 126 / 216，其余行为（四种能力、AE 终端、共享库存/流体仓、取回、闪存绑定）
 * 全部沿用 GTM 实现。
 *
 * **不是**：不是重做整份样板总成。实现路线是把父类内联的几处 `27` 改成按档查表
 * （mixin，见 `MixinMEPatternBufferCapacity` 与 [ETPatternBufferCapacities] 的类注释），
 * **没有**复制 GTM 那 1192 行 —— 复制版会跟着 GTM 版本漂移，而查表版在 GTM 改动那几处初始化时
 * 会**启动即报错**（`require`），不会静默退化成 27 格。
 *
 * ## 档位 / 能力 / 贴图
 *
 * - tier：LuV / UV / UEV / UXV（四档，逐个来自 [STAGES] 表）；
 * - abilities：`IMPORT_ITEMS` + `IMPORT_FLUIDS` **两条**，与 GTM 8.0.0 的 `me_pattern_buffer`
 *   逐项一致（⚠️ 7.5.3 是四条 —— `EXPORT_FLUIDS`/`EXPORT_ITEMS` 在 8.0.0 被上游删掉了，
 *   详见报告；本族跟着上游走，保证与 GTM 自己的那件在结构里行为一致）；
 * - 贴图：GTM 的 `block/overlay/appeng/me_buffer_hatch`（总成）与 `..._proxy`（镜像），
 *   两张 png 已核实确实在 GTM 8.0.0 的 jar 里（`assets/gtceu/textures/block/overlay/appeng/`），
 *   走 `colorOverlayTieredHullModel`，所以外观与 GTM 自己那件**只有外壳电压等级不同**。
 *
 * ## 显示
 *
 * 与 [ETThreadHatches] / [ETTimeFlowHatches] 同一套约定：英文名走 `.langValue(...)`、
 * 中文名走 [LangUtil.BLOCK_LANG]，**档位与容量并进名字**；总成 tooltip 保留 GTM 那三条功能说明
 * （`block.gtceu.pattern_buffer.desc.[0-2]`，它讲清了"闪存绑定镜像"的用法）+
 * 一条本 mod 的容量说明（键带 `%s`，四档共用）+ GTM 的「多方块共享」那一行；
 * 镜像那件换成 [PROXY_TIERS_TOOLTIP_KEY]（说明它能连所有档位）。
 *
 * ⚠️ 共享那一行由 [ETPartSharing.line] 在**渲染时**给出（走 `MachineBuilder#tooltipBuilder`）：
 * 这两件的机器类都覆写了 `canShared(controller, substructureName)` 读全局配置
 * `multiblock.partsShareable`，默认 false = 禁止共享，写成 enabled 就是在骗玩家。
 * ⚠️ `tooltips()` 与 `tooltipBuilder()` 在 MachineBuilder 里是**追加**关系、不是覆盖
 * （`MachineBuilder.java:272-273` 与 `tooltips` 都往同一个 `properties` 里加），上面几行一行没动。
 *
 * ## 镜像为什么只有一件、却能连所有档位
 *
 * 用户口径是「样板总成镜像只留 LuV 的，并让其他的都可以连上」。在 GTM 8.0.0 里这件事**白来**：
 * 镜像的转发逻辑整段交给宿主的 `BufferRecipeHandlerList`（见 [ETMEPatternBufferProxyPartMachine]
 * 的类注释），镜像这一侧没有任何按槽位定长的表，所以 27 / 63 / 126 / 216 任何一档都连得上。
 *
 * @author rain fox
 */
object ETMEPatternBufferHatches {

    /** GTM 的 AE 覆盖层命名空间：贴图在 GTM 自己的 jar 里，我们只引用。 */
    private const val GTCEU_NS = "gtceu"

    /** 总成正面覆盖层：与 GTM 的 `me_pattern_buffer` 用同一张。 */
    private const val OVERLAY_BUFFER = "block/overlay/appeng/me_buffer_hatch"

    /** 镜像正面覆盖层：与 GTM 的 `me_pattern_buffer_proxy` 用同一张。 */
    private const val OVERLAY_PROXY = "block/overlay/appeng/me_buffer_hatch_proxy"

    /**
     * 一档「ME 样板总成」。
     *
     * 容量就是这一行的字面值：注册时既写进 [ETPatternBufferCapacities]（mixin 在父类构造期按
     * 方块定义查它来决定样板槽位数），也写进名字与 tooltip。**一个容量只有一个来源**，
     * 所以名字、面板、实际槽位三者不会打架。
     *
     * ⚠️ 容量按「9 列一块、最多两块并排、每块最多 7 行」规整过（见 [ETMEPatternBufferPartMachine]）：
     * 64 → 63（= 7×9）、125 → 126（= 7×18）；27 与 216 本来就是整块。
     *
     * ⚠️ 镜像**不在**这张表里：镜像只有一件、且能连所有档位（见 [registerProxy]）。
     *
     * @param id       总成注册名（同时决定方块 id 与语言键 `block.gtetscore.<id>`）
     * @param capacity 样板槽位数
     * @param tier     电压等级，决定外壳贴图；⚠️ 非能源部件不看 tier，这里纯外观与档位标识
     */
    data class PatternBufferStage(
        val id: String,
        val capacity: Int,
        val tier: Int
    )

    /** 唯一那件镜像的注册名（保留历史 id：不动存档、也不动已产出资源名）。 */
    private const val PROXY_ID = "me_pattern_buffer_proxy_luv"

    /** 唯一那件镜像的电压等级（LuV；非能源部件不看 tier，只影响外壳贴图）。 */
    private const val PROXY_TIER = GTValues.LuV

    /**
     * 四档总成的**唯一**来源：容量 / tier / 名字全部从这一行取。
     *
     * ⚠️ 顺序即注册顺序（影响物品栏与存档里的方块出现次序），加档请往末尾追加、
     * 不要重排既有行、也不要改既有 id。
     */
    val STAGES: List<PatternBufferStage> = listOf(
        PatternBufferStage("me_pattern_buffer_luv", 27, GTValues.LuV),
        PatternBufferStage("me_pattern_buffer_uv", 63, GTValues.UV),
        PatternBufferStage("me_pattern_buffer_uev", 126, GTValues.UEV),
        PatternBufferStage("me_pattern_buffer_uxv", 216, GTValues.UXV),
    )

    /** 容量说明的 tooltip 键（`%s` = 槽位数；四档总成共用一条键）。 */
    private const val CAPACITY_TOOLTIP_KEY = PatternBufferLang.CAPACITY

    /** 镜像那条「能连所有档位」的 tooltip 键（镜像只有一件，槽位数不固定）。 */
    private const val PROXY_TIERS_TOOLTIP_KEY = PatternBufferLang.PROXY_TIERS

    /**
     * 注册五件（四档总成 + 一件通用镜像）。
     *
     * ⚠️ AE2 没装时**一件都不注册**（返回空表）：这些类继承 GTM 的 AE 部件、直接引用 `appeng.*`，
     * AE2 缺失时连类都加载不了。这与 GTM 自己的做法一致（GTM 的 `GTAEMachines.init()` 外面是
     * `if (GTCEu.Mods.isAE2Loaded())`），所以这里也必须用 [GTCEu.Mods.isAE2Loaded] 把它挡在外面，
     * 而不是无条件调用。**守卫必须是本函数的第一句** —— 挪到后面去就会在没装 AE2 的整合里
     * 加载到 `appeng.*` 而崩。
     *
     * ⚠️ 每一档的容量在 `.register()` **之前**写进 [ETPatternBufferCapacities]：mixin 要按方块定义
     * 查它，而机器实例只会在方块实体创建时构造（必然晚于注册），顺序是安全的。
     * 镜像不用登记（它不构造样板总成，容量表只在总成构造期被读）。
     *
     * @param registrate GTET 的注册器；用 GTET 自己的，否则方块会进 `gtceu:` 命名空间
     * @return 按注册顺序排列的四档总成 + 一件镜像；AE2 缺失时是空表
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): List<MachineEntry<MachineDefinition>> {
        if (!GTCEu.Mods.isAE2Loaded()) return emptyList()
        PatternBufferLang.register()
        return STAGES.map { registerBuffer(registrate, it) } + registerProxy(registrate)
    }

    /** 一档总成：ME 样板总成，容量 = `stage.capacity`。 */
    private fun registerBuffer(
        registrate: GTRegistrate,
        stage: PatternBufferStage,
    ): MachineEntry<MachineDefinition> {
        val tierName = GTValues.VN[stage.tier]
        registerCapacity(stage)
        LangUtil.BLOCK_LANG[stage.id] = "ME 样板总成（$tierName · ${stage.capacity} 样板）"
        return registrate
            .machineBuilder(stage.id) { info -> ETMEPatternBufferPartMachine(info) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(stage.tier)
            .langValue("ME Pattern Buffer ($tierName, ${stage.capacity} Patterns)")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_BUFFER))
            .tooltips(
                Component.translatable("block.gtceu.pattern_buffer.desc.0"),
                Component.translatable("block.gtceu.pattern_buffer.desc.1"),
                Component.translatable("block.gtceu.pattern_buffer.desc.2"),
                Component.translatable(CAPACITY_TOOLTIP_KEY, stage.capacity)
            )
            // 「多方块共享」那一行在最后：本件 canShared() 读全局配置，所以只能渲染时取值
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }

    /**
     * 唯一那件镜像：贴别处、把配方输入转给宿主总成，**能连任何一档的宿主**。
     *
     * ⚠️ 为什么只有一件（而不是按档一件）：用户实机验收后要求「只留 luv 的，并让其他的都可以连上」。
     * 8.0.0 的镜像本来就不按宿主容量建任何表（转发整段下沉到宿主，见
     * [ETMEPatternBufferProxyPartMachine]），所以一件 LuV 镜像天然服务四档；
     * tier 只影响外壳贴图，非能源部件不参与配方运算。
     */
    private fun registerProxy(registrate: GTRegistrate): MachineEntry<MachineDefinition> {
        val tierName = GTValues.VN[PROXY_TIER]
        LangUtil.BLOCK_LANG[PROXY_ID] = "ME 样板总成镜像（$tierName · 全档通用）"
        return registrate
            .machineBuilder(PROXY_ID) { info -> ETMEPatternBufferProxyPartMachine(info) }
            .tier(PROXY_TIER)
            .langValue("ME Pattern Buffer Proxy ($tierName, All Tiers)")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(gtmOverlay(OVERLAY_PROXY))
            .tooltips(
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.0"),
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.1"),
                Component.translatable("block.gtceu.pattern_buffer_proxy.desc.2"),
                Component.translatable(PROXY_TIERS_TOOLTIP_KEY)
            )
            // 同上：共享行仍在最后，改走 tooltipBuilder 以便按配置取 enabled / disabled。
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }

    /**
     * 把一档容量登记进 [ETPatternBufferCapacities]。
     *
     * ⚠️ 定义 id 必须与注册名逐字一致（注册器的命名空间就是本 mod 的 `gtetscore`），
     * 否则运行时会走"查不到 → 按 27 建"的兜底分支并打警告。
     */
    private fun registerCapacity(stage: PatternBufferStage) {
        ETPatternBufferCapacities.register(GTETSCore.id(stage.id), stage.capacity)
    }

    /** GTM 的贴图路径 → `ResourceLocation`（命名空间固定 `gtceu`）。 */
    private fun gtmOverlay(path: String): ResourceLocation = GTETSCore.id(GTCEU_NS, path)
}
