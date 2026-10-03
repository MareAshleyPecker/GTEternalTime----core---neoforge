package rain.fox.gtetcore.common.item.terminal

import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.mui.IItemUIHolder
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import rain.fox.gtetcore.client.terminal.AdvancedTerminalPanel

/**
 * 高级终端的行为组件。
 *
 * 交互分派：
 * - 右键空气 / 未潜行右键方块 → MUI 的 [IItemUIHolder] 默认 `use`：打开设置面板
 *   （服务端调 `PlayerInventoryUIFactory.openFromHand`，客户端只负责把交互吃掉）；
 * - **潜行右键方块** → 本类的 [useOn]：目标是多方块控制器就自动搭建一次，否则给一句反馈；
 *   两支都**总是**返回「已消耗交互」。
 *
 * 搭建只在服务端做，客户端只负责吃掉交互（避免两端各搭一次）。
 *
 * ⚠️ GTM 8.0.0 删掉了老的 `IItemUIFactory`（LDLib 那套），物品界面改走 MUI 的 [IItemUIHolder]：
 * 它是 `IUIHolder<PlayerInventoryGuiData<?>>` + `IInteractionItem` 的组合，而 `ComponentItem.buildUI`
 * 会把界面委托给挂在自己身上的、第一个实现了该接口的组件（`shouldOpenUI()` 同理）。
 * 所以本类就是那个组件；面板本体在 [AdvancedTerminalPanel]。
 *
 * @author rain fox
 */
@Suppress("ConstPropertyName")
object AdvancedTerminalBehavior : IItemUIHolder {

    /** 「潜行右键的不是多方块控制器」提示键（老代码这一支是 AE 绑定手势，不提示）。 */
    const val msg_not_controller: String = "item.gtetscore.advanced_terminal.build.not_controller"

    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        if (!player.isShiftKeyDown) return InteractionResult.PASS

        val level: Level = context.level
        if (!level.isClientSide) {
            val terminal = context.itemInHand
            val pos = context.clickedPos
            // ⚠️ GTM 8.0.0 删掉了 `IMultiController` 接口：控制器自己就是方块实体，按具体类判型
            if (MetaMachine.getMachine(level, pos) is MultiblockControllerMachine) {
                AdvancedTerminalBuilder.run(player, terminal, pos)
            } else {
                // TODO(AE 切片)：老代码在这里当「绑定无线接入点」的手势用（`AdvancedTerminalBind.toggle`），
                //  AE 未移植 → 退化成给玩家一句明确反馈，免得「潜行右键没反应」被当成 bug
                player.displayClientMessage(
                    Component.translatable(msg_not_controller).withStyle(ChatFormatting.RED), true
                )
            }
        }
        // 潜行右键「任意」方块都返回已消耗，避免同一次点击又被方块自己处理一遍
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    /**
     * 建设置面板。
     *
     * MUI 会在服务端与客户端**各调一次**（服务端那次是为了登记同步值），所以这里只能依赖
     * 「两端相同」的数据，可变状态一律交给 `syncManager` —— 细节见 [AdvancedTerminalPanel]。
     */
    override fun buildUI(data: PlayerInventoryGuiData<*>, syncManager: PanelSyncManager,
                         settings: UISettings): ModularPanel<*> =
        AdvancedTerminalPanel.build(data, syncManager, settings)
}
