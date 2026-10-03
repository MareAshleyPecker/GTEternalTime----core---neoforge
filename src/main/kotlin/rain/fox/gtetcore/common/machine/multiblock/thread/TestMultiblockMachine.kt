package rain.fox.gtetcore.common.machine.multiblock.thread

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.utils.Alignment
import brachy.modularui.value.sync.DynamicLinkedSyncHandler
import brachy.modularui.value.sync.GenericListSyncHandler
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widgets.dynamic.DynamicWidget
import brachy.modularui.widgets.layout.Flow
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.common.mui.GTByteBufAdapters
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.api.capability.IThreadedRecipeMachine
import java.util.function.Supplier

/**
 * GTET 的**第一台多方块机器** —— 「多方块测试机」，[ThreadedRecipeLogic] 的试验台。
 *
 * 只吃研磨配方（全 GTCEu 最多的一类，最容易验证「不同配方各自跑」），装线程仓后最多同时跑
 * `threadCount` 条线程；没装仓时 [IThreadedRecipeMachine.threadCount] 默认 1，退化回 GTM 原版单配方机器。
 *
 * ⚠️ 8.0.0 删了 `createRecipeLogic` 工厂：配方逻辑改成**构造器注入**，本类直接把
 * [ThreadedRecipeLogic]（无参构造）交给 super（`WorkableMultiblockMachine.java` 的两参构造会
 * `attachTrait(recipeLogic)`，机器就是在那里接上的）。来源：`IThreadedRecipeMachine.kt:9`。
 *
 * ⚠️ 本机的 `recipeModifier` **不含**并行修改器（见 `ETTestMultiblocks.register`）：并行由内核逐线程
 * 按并行仓的 `getCurrentParallel()` 施加，再挂一条等于并行² —— 契约见 `ThreadedRecipeLogic.kt:47-49`。
 *
 * @author rain fox
 */
class TestMultiblockMachine(info: BlockEntityCreationInfo) :
    WorkableElectricMultiblockMachine(info, ThreadedRecipeLogic()),
    IThreadedRecipeMachine {

    /**
     * 往标准多方块面板后面追加「线程状态」几行。
     *
     * ⚠️ 8.0.0 的 `IDisplayUIMachine#addDisplayText` 已删，面板改成 MUI：扩展点是
     * `WorkableElectricMultiblockMachine#getWidgetsForDisplay(PanelSyncManager)`（javap：该类同时有
     * `buildMainUI` 和这个方法，`getMainTextPanel` 把本方法的返回值塞进 `ListWidget.children(...)`），
     * 所以在这里追加一个控件即可，别的行一行都不动。
     *
     * 线程表**不是同步字段**，只能服务端求值：`GenericListSyncHandler` + `DynamicWidget` 是 GTM 自己的
     * 同一套写法（`GTMultiblockTextUtil.addUnformedWarning` 就是这么同步结构错误列表的）。
     */
    override fun getWidgetsForDisplay(syncManager: PanelSyncManager): List<IWidget> {
        val widgets = super.getWidgetsForDisplay(syncManager)
        widgets.add(threadStatusWidget(syncManager))
        return widgets
    }

    /**
     * 线程状态控件：一个 `DynamicWidget`，内容由服务端算好的文本行列表驱动。
     *
     * 行数是不定的（每个配方组两行），所以不能用固定数量的 `TextWidget`；列表为空时（没装线程仓 /
     * 一条线程都没跑）渲染出一个没有子控件的 Flow，等于不显示 —— 与
     * [ThreadedRecipeStatus.appendDisplayLines] 的「返回空列表」语义一致。
     */
    private fun threadStatusWidget(syncManager: PanelSyncManager): IWidget {
        @Suppress("UNCHECKED_CAST")
        val handler = syncManager.getOrCreateSyncHandler(
            THREAD_STATUS_KEY,
            GenericListSyncHandler::class.java,
            Supplier { newThreadStatusHandler() },
        ) as GenericListSyncHandler<FriendlyByteBuf, Component>

        val widget = DynamicWidget<Nothing>()
        widget.widthRel(1f)
        widget.coverChildrenHeight()
        widget.syncHandler(
            DynamicLinkedSyncHandler(handler).widgetProvider { _, lines -> threadLines(lines) }
        )
        return widget
    }

    /** 服务端侧的文本行来源：`ThreadedRecipeStatus` 自己带「客户端直接收手」与「线程上限 ≤ 1 不显示」两道闸。 */
    private fun newThreadStatusHandler(): GenericListSyncHandler<FriendlyByteBuf, Component> =
        GenericListSyncHandler.builder<FriendlyByteBuf, Component>()
            .getter(Supplier { threadStatusLines() })
            .adapter(GTByteBufAdapters.COMPONENT)
            .build()

    /** 把内核的线程状态收成机器面板的文本行（每调用一次返回一个新表，调用方可能持有它）。 */
    private fun threadStatusLines(): MutableList<Component> {
        val lines = ArrayList<Component>()
        val logic = recipeLogic as? ThreadedRecipeLogic ?: return lines
        ThreadedRecipeStatus.appendDisplayLines(lines, logic)
        return lines
    }

    /** 一行一个 `TextWidget`（照 `GTMultiblockTextUtil.addUnformedWarning`：`Flow.col()` + 逐行 child）。 */
    private fun threadLines(handler: GenericListSyncHandler<FriendlyByteBuf, Component>): IWidget {
        val column = Flow.col()
        column.coverChildrenHeight()
        column.collapseDisabledChildren()
        column.crossAxisAlignment(Alignment.CrossAxis.START)
        column.widthRel(1f)

        val lines = handler.value ?: return column
        for (line in lines) column.child(Text.comp(line).asWidget())
        return column
    }

    companion object {

        /** 本面板的同步处理器名（同一面板里唯一即可；`getOrCreateSyncHandler` 按它复用）。 */
        private const val THREAD_STATUS_KEY: String = "gtetThreadStatus"
    }
}
