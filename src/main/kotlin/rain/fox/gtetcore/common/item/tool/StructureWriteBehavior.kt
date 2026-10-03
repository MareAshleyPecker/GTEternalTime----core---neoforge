package rain.fox.gtetcore.common.item.tool

import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.factory.PlayerInventoryUIFactory
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.mui.IItemUIHolder
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.client.mui.StructureExportPanel
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.StructureToolLang
import rain.fox.gtetcore.registry.ETItems
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 结构工具（`structure_tools`）的行为与**导出**。
 *
 * 交互分派（与老工程一致）：
 * - **右键方块**（[onItemUseFirst]，先于方块自身的交互）：第一下钉住起点，之后每次挪对角终点；
 *   潜行右键 = 清空选区。整支**总是**返回 SUCCESS，所以不会漏给方块、也不会开界面。
 * - **右键空气**（[use]）：潜行 = 清空选区；否则开导出面板。
 *
 * ⚠️ 界面从 LDLib 1.x 换成 MUI：老工程的 `IItemUIFactory#createUI(HeldItemHolder, Player)`
 * 在 8.0.0 已不存在，物品界面改走 GTM 的 [IItemUIHolder]（`ComponentItem#buildUI` 会把界面
 * 委托给挂在自己身上的、第一个实现该接口的组件，`ComponentItem` 自己也实现了它）。
 * 面板本体在 [StructureExportPanel]。
 *
 * ⚠️ 选区状态从物品 NBT 换成数据组件（[StructureWriterData] / `gtetscore:structure_writer`）。
 *
 * @author [ialdaiaxiariyay](https://github.com/ialdaiaxiariyay/BetterGregTechAndAppliedEnergistics)
 */
object StructureWriteBehavior : IItemUIHolder {

    /** 6 向固定循环；`Direction#getClockWise` 在竖直轴上会返回自己，转不动。 */
    private val CYCLE_X = arrayOf(Direction.NORTH, Direction.UP, Direction.SOUTH, Direction.DOWN)
    private val CYCLE_Y = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
    private val CYCLE_Z = arrayOf(Direction.WEST, Direction.UP, Direction.EAST, Direction.DOWN)

    // ======================== 交互 ========================

    override fun onItemUseFirst(stack: ItemStack, context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.SUCCESS
        val held = player.getItemInHand(context.hand)
        if (!player.isShiftKeyDown) {
            StructureWriterData.addPos(held, context.clickedPos)
            // 1.21 的物品内容靠菜单同步：不改这个的话客户端手里的选区还是旧的，覆盖层画不出来
            player.containerMenu.broadcastChanges()
            if (player is ServerPlayer) {
                val data = StructureWriterData.read(held)
                val size = data.size()
                player.displayClientMessage(
                    if (size == null) {
                        Component.translatable(StructureToolLang.MSG_START, context.clickedPos.toShortString())
                    } else {
                        Component.translatable(
                            StructureToolLang.MSG_RANGE,
                            data.start?.toShortString() ?: "-",
                            data.end?.toShortString() ?: "-",
                            size.first, size.second, size.third
                        )
                    }.withStyle(ChatFormatting.GREEN),
                    true
                )
            }
        } else {
            StructureWriterData.clear(held)
            player.containerMenu.broadcastChanges()
            if (player is ServerPlayer) {
                player.displayClientMessage(
                    Component.translatable(StructureToolLang.MSG_CLEARED).withStyle(ChatFormatting.RED), true
                )
            }
        }
        return InteractionResult.SUCCESS
    }

    /**
     * 右键方块走的是 [onItemUseFirst]，这里**不能**返回界面的默认行为：
     * [IItemUIHolder] 的默认 `useOn` 是「开面板」，留着它会让「右键方块」在
     * `onItemUseFirst` 万一放行时弹出导出页。
     */
    override fun useOn(context: UseOnContext): InteractionResult = InteractionResult.PASS

    override fun use(
        stack: ItemStack,
        level: Level,
        player: Player,
        usedHand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        if (player.isShiftKeyDown) {
            if (!level.isClientSide) {
                val held = player.getItemInHand(usedHand)
                StructureWriterData.clear(held)
                player.containerMenu.broadcastChanges()
                player.displayClientMessage(
                    Component.translatable(StructureToolLang.MSG_CLEARED).withStyle(ChatFormatting.RED), true
                )
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(usedHand), level.isClientSide)
        }
        // 照 IItemUIHolder#use 的默认实现：服务端开界面，客户端只把交互吃掉
        // （`PlayerInventoryUIFactory#openFromHand`，见 `IItemUIHolder.java` 的 use 默认方法）
        if (player is ServerPlayer) {
            PlayerInventoryUIFactory.INSTANCE.openFromHand(player, usedHand)
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(usedHand), level.isClientSide)
    }

    /**
     * 建导出面板。MUI 会在服务端与客户端**各调一次**（服务端那次是为了登记同步值），
     * 所以树结构只能由「两端一样」的数据决定，可变状态一律走 `syncManager`（见 [StructureExportPanel]）。
     */
    override fun buildUI(
        data: PlayerInventoryGuiData<*>,
        syncManager: PanelSyncManager,
        settings: UISettings
    ): ModularPanel<*> = StructureExportPanel.build(data, syncManager)

    // ======================== 面板动作（都由服务端执行）========================

    /** 绕 X 转一格。 */
    @JvmStatic
    fun rotateX(stack: ItemStack) = StructureWriterData.rotate(stack, CYCLE_X)

    /** 绕 Y 转一格。 */
    @JvmStatic
    fun rotateY(stack: ItemStack) = StructureWriterData.rotate(stack, CYCLE_Y)

    /** 绕 Z 转一格。 */
    @JvmStatic
    fun rotateZ(stack: ItemStack) = StructureWriterData.rotate(stack, CYCLE_Z)

    /**
     * 把当前选区扫成图案并写盘。
     *
     * 只在服务端跑（客户端那次调用是 `.allowC2S()` 同步值的本地副作用，必须空转）。
     */
    @JvmStatic
    fun export(player: Player, stack: ItemStack) {
        if (player.level().isClientSide) return
        if (!GtetConfig.exportModeEnabled()) {
            player.displayClientMessage(
                Component.translatable(StructureToolLang.MSG_EXPORT_DISABLED).withStyle(ChatFormatting.RED), true
            )
            return
        }
        val data = StructureWriterData.read(stack)
        val corners = data.corners()
        if (corners == null) {
            player.displayClientMessage(
                Component.translatable(StructureToolLang.MSG_NO_SELECTION).withStyle(ChatFormatting.RED), true
            )
            return
        }

        val pattern = DebugBlockPattern(
            player.level(),
            corners[0].x, corners[0].y, corners[0].z,
            corners[1].x, corners[1].y, corners[1].z
        )
        val dirs = DebugBlockPattern.getDir(data.dir)
        pattern.changeDir(dirs[0], dirs[1], dirs[2])

        val fileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".kt"
        val logDir = File(GtetConfig.exportDirectory())
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val logFile = File(logDir, fileName)
        try {
            BufferedWriter(FileWriter(logFile)).use { writer -> writer.write(renderPattern(pattern)) }
        } catch (e: IOException) {
            GTETSCore.LOGGER.error("Error writing to log file: {}", e.message)
            player.displayClientMessage(
                Component.translatable(StructureToolLang.MSG_EXPORT_FAILED, e.message ?: "IOException")
                    .withStyle(ChatFormatting.RED),
                true
            )
            return
        }
        player.displayClientMessage(
            Component.translatable(StructureToolLang.MSG_EXPORTED, logFile.path).withStyle(ChatFormatting.GREEN),
            false
        )
    }

    /**
     * 图案 → 可直接粘进新工程的多方块定义片段。
     *
     * ⚠️ 与老工程的**唯一**实质性改写：老工程吐的是 1.20.1 时代的 DSL
     * （`FactoryBlockPattern.start()` / `.aisle("A", "B")` / `.where("A", …)`），
     * 那套 API 在 GTM 8.0.0 里已经没有了（`FactoryBlockPattern` / `BlockPattern` 被
     * `MultiblockPatternBuilder` / `IBlockPattern` 取代，`where` 也改成吃 `Char`），
     * 照抄出来是一段编译不过的死代码，所以这里按 8.0.0 的写法生成：
     * - `aisle` → `slice`（`MultiblockPatternBuilder.java`）；
     * - `where("X", …)` → `where('X', …)`；
     * - 结尾补 `.build()`；
     * - 方块引用写成注册名查表（老工程用 `RegistriesUtil.getBlock("…")`，新工程没有那个工具）。
     *
     * 轴序与老工程一致：`DebugBlockPattern.getDir(Direction.NORTH)` 给的是 `(BACK, UP, RIGHT)`，
     * 正好等于 `MultiblockPatternBuilder.start()` 的默认三元组，所以不需要显式传轴。
     */
    private fun renderPattern(pattern: DebugBlockPattern): String {
        val builder = StringBuilder()
        builder.append("// 需要的 import：\n")
        builder.append("//   com.gregtechceu.gtceu.api.multiblock.Predicates\n")
        builder.append("//   com.gregtechceu.gtceu.api.multiblock.pattern.MultiblockPatternBuilder\n")
        builder.append("//   net.minecraft.core.registries.BuiltInRegistries\n")
        builder.append("//   net.minecraft.resources.ResourceLocation\n")
        builder.append(".pattern { definition ->\n")
        builder.append("    MultiblockPatternBuilder.start()\n")
        for (strings in pattern.pattern) {
            builder.append("        .slice(\"").append(strings.joinToString("\", \"")).append("\")\n")
        }
        builder.append("        .where('~', Predicates.controller(definition))\n")
        builder.append("        .where(' ', Predicates.air())\n")
        pattern.legend.forEach { (block, c) ->
            if (c == ' ') return@forEach
            val id = BuiltInRegistries.BLOCK.getKey(block)
            builder.append("        .where('").append(c)
                .append("', Predicates.blocks(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(\"")
                .append(id).append("\"))))\n")
        }
        builder.append("        .build()\n")
        builder.append("}\n")
        return builder.toString()
    }

    // ======================== 判定 ========================

    /**
     * 手里这叠是不是结构工具（覆盖层渲染器每帧都问一次）。
     *
     * 老工程有三层回退（引用 → 组件 → 注册名）；新工程用注册表条目比对 + 注册名回退，
     * 「组件」那层不需要了 —— `STRUCTURE_TOOLS_ITEM` 那种「注册时回填静态字段」的写法
     * 在 Kotlin `object` 里就是直接读 [ETItems] 的条目。
     */
    @JvmStatic
    fun isItemStructureWriter(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val item: Item = stack.item
        if (item === ETItems.STRUCTURE_TOOLS.get()) return true
        return BuiltInRegistries.ITEM.getKey(item) == GTETSCore.id("structure_tools")
    }

    /** 选区对外接口（渲染器用）：`{最小角, 最大角}`，没有选区时 null。 */
    @JvmStatic
    fun getPos(stack: ItemStack): Array<BlockPos>? = StructureWriterData.read(stack).corners()
}
