package rain.fox.gtetcore.common.item.recipe

import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.factory.PlayerInventoryUIFactory
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.mui.IItemUIHolder
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.common.item.behavior.IntCircuitBehaviour
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.client.mui.RecipeEditorPanel
import rain.fox.gtetcore.data.lang.RecipeEditorLang

/**
 * 配方编辑器 —— 手持物品右键空气打开；对着工作站 / GT 控制器右键则直接切到对应配方类型。
 *
 * 界面三页（顶部按钮切换，靠 `setEnabledIf` 切可见性，不重开界面）：
 * ① **配方**：字段（id / 时间 / 耗电 / 电压 / 幽灵电路）+ 幽灵槽（物品与流体的输入输出四段，
 *    每段几个槽按当前配方类型的真实能力算）+ 玩家物品栏 + 导出按钮；
 * ② **类型**：原版七种 + `Registries.RECIPE_TYPE` 里全部 GT 类型，多列滚动；
 * ③ **代码**：整页代码预览 + 导出 / 复制。
 *
 * 状态存手持物品的数据组件（见 [RecipeEditorData]），导出走 [RecipeCodeWriter]。
 *
 * ## ⚠️ 界面从 LDLib 1.x 换成 MUI
 *
 * 老工程本类实现的是 `com.gregtechceu.gtceu.api.item.component.IItemUIFactory`
 * （`createUI(HeldItemUIFactory.HeldItemHolder, Player)` 返回 LDLib 的 `ModularUI`），
 * 8.0.0 里那个接口整个没有了。新工程照 `StructureWriteBehavior` / `AdvancedTerminalBehavior`
 * 那套走 [IItemUIHolder]：`ComponentItem#buildUI` 会把界面委托给挂在自己身上的、
 * 第一个实现该接口的组件。面板本体在 [RecipeEditorPanel]。
 *
 * 交互分派（与老工程一致）：
 * - **右键方块**（[onItemUseFirst]，先于方块自身的交互）：认得出是工作站 / GT 机器就切配方类型并开界面，
 *   认不出就 `PASS`；见 [useOn] 的说明。
 * - **右键空气**：走 [IItemUIHolder] 的默认 `use` —— 服务端 `PlayerInventoryUIFactory.openFromHand`。
 *
 * @author rain fox
 */
object RecipeEditorBehavior : IItemUIHolder {

    // ======================== 物品行为 ========================

    /**
     * 对着工作站 / GT 机器控制器右键：自动切到对应配方类型再打开。
     * 认不出来就 PASS（右键空气仍按上次选的类型打开）。
     *
     * ⚠️ `openFromHand` 只在服务端调；客户端那次只负责把交互吃掉（返回 SUCCESS），
     * 否则原版会把这次右键再交给方块自己处理一遍（右键工作台时工作台界面会跟着弹出来）。
     */
    override fun onItemUseFirst(stack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val pos = context.clickedPos

        val gtType = gtRecipeTypeAt(level, pos)
        val vanillaKind = if (gtType == null) vanillaKind(level.getBlockState(pos).block) else null
        if (gtType == null && vanillaKind == null) return InteractionResult.PASS

        if (!level.isClientSide) {
            val held = player.getItemInHand(context.hand)
            val draft = RecipeEditorData.loadDraft(held)
            if (gtType != null) {
                draft.kind = RecipeDraft.Kind.GT
                draft.gtType = gtType.id.toString()
            } else if (vanillaKind != null) {
                draft.kind = vanillaKind
            }
            syncCircuit(draft)
            RecipeEditorData.saveDraft(held, draft)
            if (player is ServerPlayer) {
                PlayerInventoryUIFactory.INSTANCE.openFromHand(player, context.hand)
            }
        }
        return InteractionResult.SUCCESS
    }

    /**
     * 右键方块走的是 [onItemUseFirst]，这里**不能**返回界面的默认行为：
     * [IItemUIHolder] 的默认 `useOn` 是「开面板」，留着它会让「右键任意方块」都弹出配方编辑器
     * （老工程的 `IItemUIFactory` 没有这个默认实现，所以老手感是「只有右键空气才开」）。
     */
    override fun useOn(context: UseOnContext): InteractionResult = InteractionResult.PASS

    /** 建界面。MUI 会在服务端与客户端**各调一次**（服务端那次是为了登记同步值），细节见 [RecipeEditorPanel]。 */
    override fun buildUI(
        data: PlayerInventoryGuiData<*>,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ): ModularPanel<*> = RecipeEditorPanel.build(data, syncManager)

    // ======================== 面板动作 ========================

    /**
     * 把当前草稿写成可直接粘进 datagen 的代码片段并落盘。
     *
     * ⚠️ 只在服务端跑：面板里的按钮是 `.allowC2S()` 的同步值，客户端那次只会改本地的镜像值。
     * ⚠️ 不像结构工具那样受 `dev.exportModeEnabled` 约束 —— 老工程的配方导出**没有**那个开关，
     * 加一个会让「关掉结构导出的人顺手丢掉配方导出」。
     */
    @JvmStatic
    fun export(player: Player, draft: RecipeDraft) {
        if (player.level().isClientSide) return
        try {
            val file = RecipeCodeWriter.export(draft)
            player.displayClientMessage(
                Component.translatable(RecipeEditorLang.MSG_EXPORTED, file.name).withStyle(ChatFormatting.GREEN),
                false,
            )
        } catch (e: Exception) {
            // 老工程是 `runCatching {...}.onFailure {...}`：目录建不出来（`error(...)` 抛 IllegalStateException）
            // 与写盘失败（IOException）都要给玩家一句反馈
            GTETSCore.LOGGER.error("[gtetscore] 配方导出失败", e)
            player.displayClientMessage(
                Component.translatable(RecipeEditorLang.MSG_EXPORT_FAILED, e.message ?: "unknown")
                    .withStyle(ChatFormatting.RED),
                false,
            )
        }
    }

    // ======================== 判定 ========================

    /** 原版工作站方块 → 固定配方种类。 */
    @JvmStatic
    fun vanillaKind(block: Block): RecipeDraft.Kind? = when (block) {
        Blocks.CRAFTING_TABLE -> RecipeDraft.Kind.CRAFTING_SHAPED
        Blocks.FURNACE -> RecipeDraft.Kind.SMELTING
        Blocks.BLAST_FURNACE -> RecipeDraft.Kind.BLASTING
        Blocks.SMOKER -> RecipeDraft.Kind.SMOKING
        Blocks.SMITHING_TABLE -> RecipeDraft.Kind.SMITHING
        Blocks.STONECUTTER -> RecipeDraft.Kind.STONECUTTING
        else -> null
    }

    /**
     * 方块所在位置的 GT 元机器暴露的第一个配方类型（不是 GT 机器就返回 null）。
     *
     * ⚠️ 8.0.0 删掉了 `MetaMachineBlockEntity`（改叫 `BlockEntityCreationInfo` 那套），
     * 取机器统一走 `MetaMachine.getMachine(level, pos)`。
     */
    @JvmStatic
    fun gtRecipeTypeAt(level: Level, pos: BlockPos): GTRecipeType? {
        val machine = MetaMachine.getMachine(level, pos)
        if (machine is IRecipeLogicMachine) {
            return machine.recipeTypes.firstOrNull()
        }
        return null
    }

    /** 幽灵电路：把当前配置号同步到显示槽里的电路物品上。 */
    @JvmStatic
    fun syncCircuit(draft: RecipeDraft) {
        if (draft.circuit < 0) {
            draft.circuitSlot.setStackInSlot(0, ItemStack.EMPTY)
            return
        }
        val value = minOf(RecipeDraft.CIRCUIT_MAX, draft.circuit)
        draft.circuit = value
        val display = draft.circuitSlot.getStackInSlot(0)
        if (display.isEmpty || !IntCircuitBehaviour.isIntegratedCircuit(display)) {
            draft.circuitSlot.setStackInSlot(0, IntCircuitBehaviour.stack(value))
        } else {
            IntCircuitBehaviour.setCircuitConfiguration(display, value)
        }
    }

    /**
     * 全部已注册的 GT 配方类型（含各附属注册的），按 id 排序。
     *
     * ⚠️ 8.0.0 没有 `GTRegistries.RECIPE_TYPES` 了：配方类型注册进原版的 `Registries.RECIPE_TYPE`
     * （`GTRecipeType` 自己实现了 `RecipeType<GTRecipe>`），所以这里遍历内置注册表再按类型过滤。
     * 这个注册表两端都有内容（mod 注册期就填好了），客户端可以直接算，不需要同步。
     */
    @JvmStatic
    fun gtTypes(): List<Pair<ResourceLocation, GTRecipeType>> = BuiltInRegistries.RECIPE_TYPE
        .entrySet()
        .mapNotNull { entry -> (entry.value as? GTRecipeType)?.let { entry.key.location() to it } }
        .sortedBy { it.first.toString() }
}
