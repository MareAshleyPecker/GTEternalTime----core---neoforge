package rain.fox.gtetcore.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import rain.fox.gtetcore.api.capability.IOverclockHatch
import rain.fox.gtetcore.config.GtetConfig

import brachy.modularui.api.drawable.Text
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget

/**
 * 「超频仓」多方块部件（一个变体 = 一个方块，S / E 由变体表在构造时注入，之后不再变化）。
 *
 * 实现 [IOverclockHatch] 让超频改写逻辑能认出它；真正的改写发生在
 * `MixinOverclockingLogic`（M5 移植），在它落地之前本件只是个能放能拆的标记件。
 *
 * 面板里只有名字一行（名字里已带「电压等级 + S + E」，如「ZPM 超频仓（4× Speed|×32 Energy）」），
 * 没有输入控件；所以没有任何同步字段，也不需要 `@SyncToClient`。
 */
class ETOverclockHatchPartMachine(
    info: BlockEntityCreationInfo,
    tier: Int,
    private val speed: Int,
    private val energyFactor: Double,
) : TieredPartMachine(info, tier), IMuiMachine, IOverclockHatch {

    override val overclockSpeed: Int get() = speed

    override val overclockEnergyFactor: Double get() = energyFactor

    /** MUI 面板：只有名字一行（语言键在客户端按当前语言解析）。 */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        mainWidget.child(Text.lang("block.gtetcore.${definition.name}").asWidget().margin(6))
    }

    /**
     * 部件共享闸门：返回全局开关 [GtetConfig.partsShareable]（默认 false = 禁止共享）。
     *
     * 打开配置就恢复串配方风险：超频改写扫的是控制器的 `getParts()`，
     * 共享后两个控制器会同时按本件的 S / E 改写自己的配方。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()
}
