package rain.fox.gtetcore.common.item.tool

import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.multiblock.error.MismatchError
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.StructureToolLang
import rain.fox.gtetcore.registry.ETItems

/**
 * 结构检测工具（`structure_detect`）的行为 —— 右键多方块控制器，**当场**跑一遍结构检测：
 * 成型成功在聊天栏提示，失败则把错误位置写进物品的数据组件（[StructureDetectData]），
 * 由 [rain.fox.gtetcore.client.StructureOverlayRenderer] 在客户端画成线框。
 *
 * 错误框不会一直挂着：写入坐标时同时记下当时的游戏刻，渲染端按配置 `overlay.detectBoxLifetime`
 * （秒；0 = 不自动消失）判断是否过期 —— 想看就再右键一次控制器。
 *
 * ⚠️ 8.0.0 的结构检测 API 整条换过（老工程是 `IMultiController#getPattern()` + `MultiblockState#clean()`
 * + `BlockPattern#checkPatternAt(state, true)`）：
 * - 控制器身份：`IMultiController` 已删，按具体类 [MultiblockControllerMachine] 判型；
 * - 图案：`controller.getSubstructurePattern("main")` / 默认那份 `IBlockPattern`（[MultiblockControllerMachine.getDefaultStructurePattern]）；
 * - 状态：`MultiblockState` → `PatternState`（[MultiblockControllerMachine.getDefaultPatternState]）；
 * - 检测：`IBlockPattern#checkPatternAt(Level, PatternState, BlockPos, frontFacing, upwardsFacing, savePredicate)`；
 * - 错误：`PatternState#getErrors()` 是一**列** `PatternError`（老工程只有一个 `MultiblockState#error`），
 *   每条的展示方式也从字符串 `errorInfo` 变成了 MUI 控件（`PatternErrorUI#apply(ParentWidget)`），
 *   只有 `MismatchError#lang()` 还留着文本，所以聊天栏只报「处数 + 坐标」，能取到 `MismatchError` 时再补一行细节。
 */
object StructureDetectBehavior : IInteractionItem {

    override fun onItemUseFirst(stack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        if (level.isClientSide) return InteractionResult.PASS

        val controller = MetaMachine.getMachine(level, context.clickedPos) as? MultiblockControllerMachine
            ?: return InteractionResult.PASS

        // 先清掉上一批错误位置（老工程是 stack.removeTagKey("error_pos")）
        StructureDetectData.clear(stack)

        val pattern = controller.defaultStructurePattern ?: run {
            player.sendSystemMessage(
                Component.translatable(StructureToolLang.MSG_NO_PATTERN).withStyle(ChatFormatting.RED)
            )
            return InteractionResult.FAIL
        }
        val state = controller.defaultPatternState ?: run {
            player.sendSystemMessage(
                Component.translatable(StructureToolLang.MSG_NO_PATTERN).withStyle(ChatFormatting.RED)
            )
            return InteractionResult.FAIL
        }

        // 老工程 `MultiblockState#clean()` 的等价物：错误、限次谓词计数、每格缓存三样都清掉，
        // 后两样在 8.0.0 分了家（见 AdvancedTerminalBuilder#clearStateCache）
        state.clearErrors()
        state.context.clearGlobalCounts()
        state.context.clearLayerCounts()
        state.cache.clear()
        // `PatternState.controller` 只由结构检查写入；刚放下 / 刚读档的控制器还没跑过检查，
        // 不补这一句绑定就会拿着空 controller 去检测（同 AdvancedTerminalBuilder.kt:110）
        state.setController(controller, controller.blockPos)

        val formed = pattern.checkPatternAt(
            level, state, controller.blockPos,
            controller.frontFacing, controller.upwardsFacing, true
        )

        if (formed) {
            player.sendSystemMessage(
                Component.translatable("gtceu.top.valid_structure").withStyle(ChatFormatting.GREEN)
            )
            return InteractionResult.SUCCESS
        }

        val errors = state.errors
        val positions = errors.mapNotNull { it.pos }
        if (positions.isNotEmpty()) {
            StructureDetectData.write(stack, StructureDetectData(positions, level.gameTime))
            // 强制把物品同步到客户端：错误框是客户端画的，物品内容靠菜单同步
            player.inventoryMenu.broadcastChanges()
        }
        player.sendSystemMessage(
            Component.translatable(
                StructureToolLang.MSG_DETECT_FAILED,
                errors.size,
                positions.joinToString(", ") { it.toShortString() }.ifEmpty { "-" }
            ).withStyle(ChatFormatting.RED)
        )
        errors.firstNotNullOfOrNull { (it as? MismatchError<*>)?.lang() }?.let { player.sendSystemMessage(it) }
        return InteractionResult.SUCCESS
    }

    /** 错误位置（渲染器每帧都问一次）。 */
    fun getPos(stack: ItemStack): Array<BlockPos>? = StructureDetectData.read(stack).positions.toTypedArray()

    /** 写入这批错误位置时的服务端游戏刻；没有记录时返回 [StructureDetectData.NO_TIME]。 */
    fun getTime(stack: ItemStack): Long = StructureDetectData.read(stack).gameTime

    /** 手里这叠是不是结构检测工具（老工程的三层回退里那层「组件」不需要了）。 */
    fun isItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item: Item = stack.item
        if (item === ETItems.STRUCTURE_DETECT.get()) return true
        return BuiltInRegistries.ITEM.getKey(item) == GTETSCore.id("structure_detect")
    }
}
