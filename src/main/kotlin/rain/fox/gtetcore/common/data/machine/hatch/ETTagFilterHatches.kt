package rain.fox.gtetcore.common.data.machine.hatch

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETTagFilterStockBusPartMachine
import rain.fox.gtetcore.common.machine.multiblock.part.ae.ETTagFilterStockHatchPartMachine
import rain.fox.gtetcore.data.lang.Ae2Lang
import rain.fox.gtetcore.registry.machineBuilder
import rain.fox.gtetcore.util.ETPartSharing
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 「ME 标签库存输入总线 / 输入仓」的注册入口：两件部件，归页沿用 `ETMachines` 设过的 `MACHINE` 页。
 *
 * **是**：GTM 自带的 `me_stocking_input_bus` / `me_stocking_input_hatch` **加上** GTET 自己的两条策略
 * —— 标签白 / 黑名单过滤 + 「每次拉 N 个」的定量拉取。**不是**：不重做 GTM 已有的那两件
 * （GTM 8.0.0 的 `GTAEMachines` 照旧注册它们，本文件一件都不碰）。
 *
 * ## 档位与贴图（与 GTM 对应件逐项一致）
 *
 * tier = `LuV`（GTM 的库存版就是 LuV，非库存版是 EV）；abilities = `PartAbility.IMPORT_ITEMS` /
 * `PartAbility.IMPORT_FLUIDS`；贴图 = **GTM 自带素材** `gtceu:block/overlay/appeng/me_input_bus` /
 * `me_input_hatch`（已核对 8.0.0 的 jar：`assets/gtceu/textures/block/overlay/appeng/me_input_bus.png`
 * 与 `me_input_hatch.png` 都在），走 `colorOverlayTieredHullModel` —— 这正是 GTM 8.0.0 自己注册
 * `me_stocking_input_bus` 用的那一句（`GTAEMachines` 字节码可证），底座是 LuV 电压外壳。
 * ⚠️ 所以本族**不需要任何新 png**：贴图全部来自 `gtceu` 命名空间，本 mod 只引用、不分发、不修改。
 * ⚠️ 这两张是**平面覆盖层**、不是 `IDLE` / `WORKING` 成对的可运转覆盖层，所以用
 * `colorOverlayTieredHullModel` 而**不是**线程仓那族的 `createWorkableTieredHullMachineModel`。
 *
 * ## 显示
 *
 * 英文名走 `.langValue(...)`、中文名走 [LangUtil.BLOCK_LANG]（名字里直接写清「标签库存」，
 * 靠名字与 GTM 原版库存件区分）；tooltip 是「GTM 两行 + GTET 功能说明 + 共享说明」，
 * 最后再补一条**渲染时取值**的「多方块共享」行（[ETPartSharing.line]）——
 * 两件的 `canShared()` 都是「面板开关 OR 全局配置」，所以只能渲染时按键取值。
 *
 * ## ⚠️ AE2 缺失时一件都不注册
 *
 * 本族直接继承 GTM 的 AE 部件、类里直接引用 `appeng.*`，AE2 没装时连类都加载不了。
 * GTM 自己的做法就是 `if (GTCEu.Mods.isAE2Loaded()) GTAEMachines.init();`，这里同样挡一道。
 *
 * @author rain fox
 */
object ETTagFilterHatches {

    /** 物品件注册名。 */
    const val ITEM_BUS_ID: String = "me_tag_filter_stocking_bus"

    /** 流体件注册名。 */
    const val FLUID_HATCH_ID: String = "me_tag_filter_stocking_hatch"

    /** GTM 自带覆盖层：与 GTM 的 `me_input_bus` / `me_stocking_input_bus` 同一张。 */
    private const val OVERLAY_ME_INPUT_BUS = "block/overlay/appeng/me_input_bus"

    /** GTM 自带覆盖层：与 GTM 的 `me_input_hatch` / `me_stocking_input_hatch` 同一张。 */
    private const val OVERLAY_ME_INPUT_HATCH = "block/overlay/appeng/me_input_hatch"

    /**
     * 注册两件部件；返回顺序 = 物品件 → 流体件。
     *
     * @param registrate GTET 自己的注册器（`ETRegistrate.REGISTRATE`）；用 GTM 那份的话方块会进 `gtceu:` 命名空间
     * @return 按注册顺序排列的 [MachineEntry]；AE2 缺失时是空表
     */
    @JvmStatic
    fun register(registrate: GTRegistrate): List<MachineEntry<MachineDefinition>> {
        if (!GTCEu.Mods.isAE2Loaded()) return emptyList()
        return listOf(registerItemBus(registrate), registerFluidHatch(registrate))
    }

    /** 物品件：ME 库存输入总线 + 标签过滤 + 定量拉取。 */
    private fun registerItemBus(registrate: GTRegistrate): MachineEntry<MachineDefinition> {
        LangUtil.BLOCK_LANG[ITEM_BUS_ID] = "ME 标签库存输入总线"
        return registrate
            .machineBuilder(ITEM_BUS_ID) { info -> ETTagFilterStockBusPartMachine(info) }
            // tier 必须最先设置：abilities 与分级外壳贴图都要读它
            .tier(GTValues.LuV)
            .langValue("ME Tag-Filtered Stocking Input Bus")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_ITEMS)
            .colorOverlayTieredHullModel(GTCEu.id(OVERLAY_ME_INPUT_BUS))
            .tooltips(
                Component.translatable("gtceu.machine.item_bus.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_item.tooltip.0"),
                Component.translatable(Ae2Lang.TOOLTIP),
                Component.translatable(Ae2Lang.SHARE_TOOLTIP),
            )
            // 「多方块共享」那一行仍在原位置 —— 即所有静态行的**最后**：本件 `canShared()` 读全局配置，
            // 所以它只能**渲染时**取值，走 tooltipBuilder。tooltips() 与 tooltipBuilder() 是**追加**关系。
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }

    /** 流体件：ME 库存输入仓 + 标签过滤 + 定量拉取。 */
    private fun registerFluidHatch(registrate: GTRegistrate): MachineEntry<MachineDefinition> {
        LangUtil.BLOCK_LANG[FLUID_HATCH_ID] = "ME 标签库存输入仓"
        return registrate
            .machineBuilder(FLUID_HATCH_ID) { info -> ETTagFilterStockHatchPartMachine(info) }
            .tier(GTValues.LuV)
            .langValue("ME Tag-Filtered Stocking Input Hatch")
            .rotationState(RotationState.ALL)
            .abilities(PartAbility.IMPORT_FLUIDS)
            .colorOverlayTieredHullModel(GTCEu.id(OVERLAY_ME_INPUT_HATCH))
            .tooltips(
                Component.translatable("gtceu.machine.fluid_hatch.import.tooltip"),
                Component.translatable("gtceu.machine.me.stocking_fluid.tooltip.0"),
                Component.translatable(Ae2Lang.TOOLTIP),
                Component.translatable(Ae2Lang.SHARE_TOOLTIP),
            )
            .tooltipBuilder { _, list -> list.add(ETPartSharing.line()) }
            .register()
    }
}
