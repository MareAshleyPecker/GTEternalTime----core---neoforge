package rain.fox.gtetcore.common.item.terminal

import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

/**
 * 高级终端的行为组件。
 *
 * 本阶段只有一条真正可用的交互：**潜行右键多方块控制器 → 自动搭建**。
 * 搭建只在服务端做，客户端只负责把交互吃掉（避免两端各搭一次）。
 *
 * 老代码里的另外两条交互留给后续切片：右键空气开设置面板（本类留桩）、
 * 潜行右键无线接入点绑 AE（整个不做，AE 切片再补）。
 *
 * 注意老版实现接口 `IItemUIFactory` 在 GTM 8.0.0 已被删除，物品 UI 改走 MUI 的
 * `IItemUIHolder`；本阶段不接它，所以只实现仍存在的 [IInteractionItem]。
 */
object AdvancedTerminalBehavior : IInteractionItem {

    /** 「潜行右键的不是多方块控制器」提示键（老代码这一支是 AE 绑定手势，不提示）。 */
    const val MSG_NOT_CONTROLLER: String = "item.gtetcore.advanced_terminal.build.not_controller"

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
                    Component.translatable(MSG_NOT_CONTROLLER).withStyle(ChatFormatting.RED), true
                )
            }
        }
        // 潜行右键「任意」方块都返回已消耗，避免同一次点击又被方块自己处理一遍
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun use(stack: ItemStack, level: Level, player: Player,
                     hand: InteractionHand): InteractionResultHolder<ItemStack> {
        // TODO(UI 切片)：接 MUI 的 IItemUIHolder 打开设置面板
        // 本阶段留桩：直接放行，不影响其它交互
        return InteractionResultHolder.pass(stack)
    }
}
