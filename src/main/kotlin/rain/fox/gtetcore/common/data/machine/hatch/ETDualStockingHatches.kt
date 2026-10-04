package rain.fox.gtetcore.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETMEDualStockingPartMachine
import rain.fox.gtetcore.data.lang.Ae2Lang
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「ME 二合一库存输入总成」的注册入口：**一件**部件，一个方块同时挂 `IMPORT_ITEMS` 与 `IMPORT_FLUIDS`。
 *
 * **是**：GTM 自带的物品库存总线与流体库存仓**合到一个方块上**，再加上 GTET 自己的两条策略
 * ——标签白 / 黑名单过滤 + 「每次拉 N 个」的定量拉取（两侧各一套）。**不是**：不重做 GTM 已有的
 * `me_stocking_input_bus` / `me_stocking_input_hatch`（GTM 8.0.0 的 `GTAEMachines` 照旧注册它们，
 * 本文件一件都不碰），也不是「标签过滤那一族」的第二份实现（那两件见 [ETTagFilterHatches]）。
 *
 * ## 档位 / 能力 / 贴图（与 GTM 的库存件逐项一致）
 *
 * tier = `LuV`（GTM 的库存版就是 LuV，非库存版是 EV）；abilities = `PartAbility.IMPORT_ITEMS` **加上**
 * `PartAbility.IMPORT_FLUIDS`（`MachineBuilder#abilities` 是变参，逐个进对应的能力表）；
 * 贴图 = **GTM 自带素材** `gtceu:block/overlay/appeng/me_input_hatch` —— GTM 没有「物品 + 流体合起来」
 * 的覆盖层，所以借流体输入仓那一张（老工程同一选择），将来换本项目自己的贴图只改这一行。
 * ⚠️ 所以本件**不需要任何新 png**：贴图全部来自 `gtceu` 命名空间，本 mod 只引用、不分发、不修改。
 *
 * ## ⚠️ 一件方块被两条能力链接纳的依据
 *
 * `abilities(...)` 把同一方块逐个注册进两份能力表（`PartAbility#register(tier, block)`）；
 * 结构侧 `IMPORT_ITEMS` 与 `IMPORT_FLUIDS` 是两条 `or` 分支上的候选并集、没有互斥，所以同一方块被两条
 * 都接纳 —— 先例是 GTM 自己的 `me_pattern_buffer`（一块挂四种能力）。
 *
 * ## 显示
 *
 * 英文名走 `.langValue(...)`、中文名走 [LangUtil.BLOCK_LANG]（名字里写清「二合一」，靠名字与 GTM 原版
 * 库存件区分）；tooltip 是「GTM 物品 + GTM 流体 + 两行 GTM 库存说明 + 本 mod 功能说明 + 共享说明」，
 * 最后再补一条**渲染时取值**的「多方块共享」行（[ETPartSharing.line]）——
 * 本件的 `canShared()` 是「面板开关 OR 全局配置」，只能渲染时按键取值。
 * ⚠️ 不新增语言键：功能说明与共享说明复用 [Ae2Lang.TOOLTIP] / [Ae2Lang.SHARE_TOOLTIP]
 * （与已提交的标签库存两件同一对键），面板那几条键也全部现成（含流体侧标题 [Ae2Lang.TITLE_FLUIDS]）。
 *
 * ## ⚠️ AE2 缺失时一件都不注册
 *
 * 本件直接继承 GTM 的 AE 部件、类里直接引用 `appeng.*`，AE2 没装时连类都加载不了。
 * GTM 自己的做法就是 `if (GTCEu.Mods.isAE2Loaded()) GTAEMachines.init();`，这里同样挡一道。
 *
 * @author rain fox
 */
object ETDualStockingHatches {

    /** 注册名（命名空间是本 mod 的 `gtetscore`，与 GTM 那两件互不冲突）。 */
    const val DUAL_ID: String = "me_dual_stocking_input"

    /** GTM 自带覆盖层：与 GTM 的 `me_input_hatch` / `me_stocking_input_hatch` 同一张。 */
    private const val OVERLAY_ME_INPUT_HATCH = "block/overlay/appeng/me_input_hatch"

    /**
     * 注册这一件；AE2 缺失时返回空表。
     *
     * @param registrate GTET 自己的注册器（`ETRegistrate.REGISTRATE`）；用 GTM 那份的话方块会进 `gtceu:` 命名空间
     * @return 单元素列表（与其余各族同一形状，便于 `ETMachines` 统一挂 `@JvmField`）
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): List<MachineEntry<MachineDefinition>> {
        if (!GTCEu.Mods.isAE2Loaded()) return emptyList()
        return listOf(registerDual(registrate))
    }

    /** 二合一件：一个方块同时是 ME 库存输入总线与 ME 库存输入仓。 */
    private fun registerDual(registrate: GTRegistrate): MachineEntry<MachineDefinition> {
        LangUtil.BLOCK_LANG[DUAL_ID] = "ME 二合一库存输入总成"
        return registrate
            .machineBuilder(DUAL_ID) { info -> ETMEDualStockingPartMachine(info) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(GTValues.LuV)
            .langValue("ME Dual Stocking Input")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(GTCEu.id(OVERLAY_ME_INPUT_HATCH))
            .tooltips(
                Component.translatable("gtceu.machine.item_bus.import.tooltip"),
                Component.translatable("gtceu.machine.fluid_hatch.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.0"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.0"),
                Component.translatable(Ae2Lang.TOOLTIP),
                Component.translatable(Ae2Lang.SHARE_TOOLTIP),
            )
            // 「多方块共享」那一行仍在原位置 —— 即所有静态行的**最后**：本件 `canShared()` 读全局配置，
            // 所以它只能**渲染时**取值，走 tooltipBuilder。tooltips() 与 tooltipBuilder() 是**追加**关系。
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }
}
