package rain.fox.gtetcore.common.item.tool

import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import rain.fox.gtetcore.data.lang.StructureToolLang

/**
 * 结构刷新工具（`structure_checker`）—— 潜行右键多方块控制器，强制**立刻**重新检测一遍结构。
 *
 * ⚠️ 8.0.0 没有老工程那对 `IMultiController#setWaitingTime(0)` + `#requestCheck()`（那套是
 * 「往 `MultiblockWorldSavedData` 排一次异步复检」）。新版的等价物是
 * [MultiblockControllerMachine.invalidateStructure] + [MultiblockControllerMachine.checkAndFormStructure]，
 * 中间补一句 `PatternState#setShouldUpdate(true)`：
 * `checkStructurePattern` 只在 `shouldUpdate()` 为真时才真的跑检测
 * （`MultiblockControllerMachine.java` 的 `checkStructurePattern` 字节码里有一道 `shouldUpdate` 闸门），
 * 而这个标志平时只由「方块变了」置起来，手动重检必须自己抬。
 */
object TerminalBehavior : IInteractionItem {

    override fun onItemUseFirst(itemStack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val controller = MetaMachine.getMachine(level, context.clickedPos) as? MultiblockControllerMachine
            ?: return InteractionResult.PASS
        if (!player.isShiftKeyDown) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)

        // 先判成未成型（顺带清掉部件与渲染状态），再抬开闸门、立刻重跑一次检测并成型
        controller.invalidateStructure()
        controller.defaultPatternState?.setShouldUpdate(true)
        controller.checkAndFormStructure()

        player.displayClientMessage(
            Component.translatable(StructureToolLang.MSG_RECHECK, Component.translatable(controller.definition.descriptionId))
                .withStyle(ChatFormatting.YELLOW),
            true
        )
        return InteractionResult.sidedSuccess(false)
    }
}
