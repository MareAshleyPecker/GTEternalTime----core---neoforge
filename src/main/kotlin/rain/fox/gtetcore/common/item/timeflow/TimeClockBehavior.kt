package rain.fox.gtetcore.common.item.timeflow

import com.gregtechceu.gtceu.api.item.component.IAddInformation
import com.gregtechceu.gtceu.api.item.component.IInteractionItem
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.api.timeflow.TimeFlowTowers
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.TimeClockLang
import java.util.Locale

/**
 * 时序钟的行为组件 —— 负责 **tooltip** 与 **绑定手势**。
 *
 * 动态 tooltip 的做法：`IAddInformation#appendHoverText` 每次渲染 tooltip 都会被调
 * （`ComponentItem#appendHoverText` 逐个组件转发，没有缓存），所以汇率天然实时刷新。
 * 汇率是 `f(gameTime)` 的**纯函数**，客户端自己算，**不需要同步包**。
 *
 * 显示内容（设定 §5 已定，不另做 HUD）：
 * 当前档位与容量上限、钟内 TF 与其折算 EU、`1 TF = 8192 EU`、当前汇率与相位（含距峰谷时间）、
 * 当前往返损耗、绑定状态。
 *
 * 绑定 / 解绑的手势在 [onItemUseFirst] / [useOn]，扣费入口在 [TimeClockData]；
 * 「右键机器推进配方进度」本期**不做**（它依赖另一条还在验证的 RecipeLogic 路线），只留数据结构与接口。
 *
 * @author rain fox
 */
object TimeClockBehavior : IAddInformation, IInteractionItem {

    /**
     * 每次渲染 tooltip 都算一遍。
     *
     * ⚠️ **1.21 的签名变了**：老工程是 `appendHoverText(ItemStack, Level?, List, TooltipFlag)`，
     * 1.21 把 `Level` 换成了 `Item.TooltipContext`（`Item#appendHoverText` 的新形参）。
     * 世界仍从 `context.level()` 取，而且**照样可能是 null**（创造栏 / JEI 预览只有 registries），
     * 所以下游还是按可空处理。
     */
    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val tier = TimeClockData.getTier(stack)
        val stored = TimeClockData.getTimeFlow(stack)
        val capacity = TimeClockData.getCapacity(stack)

        tooltip.add(
            Component.translatable(TimeClockLang.TIER, tierName(tier), num(capacity))
                .withStyle(ChatFormatting.AQUA)
        )
        tooltip.add(
            Component.translatable(TimeClockLang.CONTENT, num(stored), num(ETTimeFlow.tfToEu(stored)))
                .withStyle(ChatFormatting.GREEN)
        )
        tooltip.add(Component.translatable(TimeClockLang.UNIT).withStyle(ChatFormatting.GRAY))

        appendTideLines(context.level(), tooltip)

        tooltip.add(
            Component.translatable(TimeClockLang.LOSS, percent(ETTimeFlow.ROUND_TRIP_LOSS))
                .withStyle(ChatFormatting.GOLD)
        )

        val bound = TimeClockData.getBoundTower(stack)
        tooltip.add(
            (if (bound == null) Component.translatable(TimeClockLang.UNBOUND_TIP)
            else Component.translatable(TimeClockLang.BOUND_TIP, describe(bound)))
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }

    /**
     * 汇率 / 相位两行。
     *
     * ⚠️ 时间基准用 `Level#getGameTime()`，**不是** `getDayTime()`（后者会被 `/time set` 操纵）。
     * 不在世界内时（创造物品栏、JEI 预览）`level` 为 `null`，退化成一行提示而不是乱算。
     */
    private fun appendTideLines(level: Level?, tooltip: MutableList<Component>) {
        val gameTime = level?.gameTime
        if (gameTime == null) {
            tooltip.add(Component.translatable(TimeClockLang.RATE_UNKNOWN).withStyle(ChatFormatting.GRAY))
            return
        }

        val rate = ETTimeFlow.tideRate(gameTime)
        val phase = ETTimeFlow.tidePhase(gameTime)
        tooltip.add(
            Component.translatable(TimeClockLang.RATE, fixed(rate, 4), percent(phase))
                .withStyle(ChatFormatting.LIGHT_PURPLE)
        )

        if (GtetConfig.tideAmplitude() > 0.0) {
            val peakSeconds = ETTimeFlow.ticksToNextPeak(gameTime) / ETTimeFlow.TICKS_PER_SECOND
            val troughSeconds = ETTimeFlow.ticksToNextTrough(gameTime) / ETTimeFlow.TICKS_PER_SECOND
            tooltip.add(
                Component.translatable(TimeClockLang.TIDE_NEXT, peakSeconds, troughSeconds)
                    .withStyle(ChatFormatting.GRAY)
            )
        } else {
            tooltip.add(Component.translatable(TimeClockLang.TIDE_OFF).withStyle(ChatFormatting.DARK_GRAY))
        }
    }

    // ======================== 绑定手势（右键主控塔） ========================

    /**
     * **绑定手势的主入口** —— `Item#onItemUseFirst`。
     *
     * ## 为什么是 `onItemUseFirst` 而不是只用 `useOn`
     * Forge/NeoForge 给 `ServerPlayerGameMode#useItemOn` 打的补丁里，顺序是：
     * 1. `onItemUseFirst(...)` —— **最先**，返回非 `PASS` 就直接结束；
     * 2. `blockState.use(...)` —— 方块自己的交互；
     * 3. `ItemStack#useOn(...)` —— 只有第 2 步没吃掉这次交互时才会轮到。
     *
     * 主控塔是 GTM 的控制器：`MetaMachineBlock#use` 在**没潜行**时会 `tryToOpenUI(...)` 把右键吃掉，
     * 所以第 3 步**永远不会被调到** —— 这正是「手持时序钟右键塔没反应」的根因。
     * 把手势放在第 1 步就绕开了它。
     *
     * 语义（与 GTM 闪存的 `onDataStickUse` / `onDataStickShiftUse` 同构）：
     * - **右键 = 绑定**（把塔的维度 + 坐标 + 交互面写进钟的数据组件，见 [TimeClockData.bindTower]）；
     * - **潜行右键 = 解绑**。
     *
     * 副作用（如实说）：这两种手势都会吃掉这次右键，于是**手持时序钟时打不开塔的面板** ——
     * 换空手右键即可。绑定比开面板常用，且与闪存的既有手感一致，所以这样定。
     *
     * 右键的那一格**不是**主控塔时返回 `PASS`：什么都不做，右键交还给方块本身（不给错误提示，
     * 免得玩家在自己家墙上右键一下就弹红字）。
     *
     * 主控塔（`MasterTowerMachine`）实现 `ITimeFlowTower` 之后，[TimeFlowTowers.find] 就能查到它。
     */
    override fun onItemUseFirst(itemStack: ItemStack, context: UseOnContext): InteractionResult =
        towerGesture(context)

    /**
     * `IInteractionItem#useOn` —— 常规的「物品右键方块」入口，实现成与 [onItemUseFirst] **等价**。
     *
     * 有了第 1 步之后它其实轮不到（塔的 `use` 会先吃掉交互；潜行那条路也已经在上一步处理完），
     * 但留着它有两个好处：① 手感万一被别的 mod 改了顺序，手势依旧成立；
     * ② 将来若把塔做成「非 GTM 方块」或被别的实现复用，这条路自然是通的。
     */
    override fun useOn(context: UseOnContext): InteractionResult = towerGesture(context)

    /** [onItemUseFirst] 与 [useOn] 共用的手势实现。 */
    private fun towerGesture(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val stack = context.itemInHand
        // 手里必须是时序钟（本组件挂在时序钟上，但别处也可能拿到这个组件）
        if (!TimeClockData.isTimeClock(stack)) return InteractionResult.PASS
        // 右键的那一格必须真的是一座主控塔；不是就什么都不做（塔未移植 ⇒ 恒为 null）
        val tower = TimeFlowTowers.find(level, context.clickedPos) ?: return InteractionResult.PASS

        if (!level.isClientSide) {
            val pos = context.clickedPos
            if (player.isShiftKeyDown) {
                TimeClockData.unbindTower(stack)
                player.displayClientMessage(Component.translatable(TimeClockLang.UNBOUND), true)
            } else {
                TimeClockData.bindTower(stack, level, pos, context.clickedFace)
                // 绑定与「有没有权限取用」是两回事：没权限也照绑，但要让玩家知道扣费会回落到钟内
                val messageKey = if (tower.canUseTimeFlow(player)) TimeClockLang.BOUND else TimeClockLang.BOUND_DENIED
                player.displayClientMessage(Component.translatable(messageKey, pos.x, pos.y, pos.z), true)
            }
        }
        // 两端都返回「已消耗」，免得客户端再跑一遍同样的手势
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // ======================== 格式化 ========================

    private fun tierName(tier: Int): String = "L$tier"

    private fun num(value: Long): String = FormattingUtil.formatNumbers(value)

    /** 0~1 的小数 → 带一位小数的百分数串（不含 `%`）。 */
    private fun percent(fraction: Double): String = fixed(fraction * 100.0, 1)

    private fun fixed(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun describe(pos: GlobalPos): String {
        val dim = pos.dimension().location()
        return "$dim ${pos.pos().x}, ${pos.pos().y}, ${pos.pos().z}"
    }
}
