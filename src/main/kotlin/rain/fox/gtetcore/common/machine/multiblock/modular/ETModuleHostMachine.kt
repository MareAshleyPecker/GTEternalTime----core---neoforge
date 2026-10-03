package rain.fox.gtetcore.common.machine.multiblock.modular

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.LongSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.data.lang.ModuleLang
import java.util.function.IntSupplier
import java.util.function.LongSupplier
import java.util.function.Supplier

/**
 * 模块化多方块的**主机（核心）**：单元挂在它身上借电，并继承它的等级。
 *
 * 一句话机器逻辑：**主机只负责「供电」与「等级」两件事** —— 单元能不能跑那条配方由单元自己按
 * `主机等级 + 主机电量` 判（GTO 净化水工厂是核心代管进度，这里先把这两件事做成可复用底座）。
 *
 * ⚠️ 8.0.0 删了 `onStructureFormed()` / `onStructureInvalid()` 这对回调，成型/失效的挂载点改成
 * `formStructure(name)` / `invalidateStructure(name)`（`MasterTowerMachine` 同款，名字参数用来区分
 * substructure；本机只关心 `main`）。
 *
 * @author rain fox
 */
abstract class ETModuleHostMachine(info: BlockEntityCreationInfo) : WorkableElectricMultiblockMachine(info) {

    private val modules = LinkedHashSet<ETModuleMachine>()

    /** 主机等级（成型时由能源仓算出）：**单元的配方等级以它为准**。 */
    open fun hostTier(): Int = tier

    /** 主机当前可用 EU —— 单元借电前先看这个值就知道够不够。 */
    fun availableEu(): Long = energyContainer?.energyStored ?: 0

    /**
     * 从主机的能源仓里扣 [amount]；返回**实际扣到**的量（不够就少扣，永远不会扣成负数）。
     *
     * 单元侧的统一入口是 [ETModuleMachine.consumeEu]（`ETPowerSource.HOST` 那条分支走这里）。
     */
    fun consumeHostEu(amount: Long): Long {
        if (amount <= 0) return 0
        val container = energyContainer ?: return 0
        return (-container.changeEnergy(-amount)).coerceAtLeast(0)
    }

    fun attachModule(module: ETModuleMachine) {
        if (modules.add(module)) onModulesChanged()
    }

    fun detachModule(module: ETModuleMachine) {
        if (modules.remove(module)) onModulesChanged()
    }

    /** 当前挂着的单元（只读）。 */
    fun modules(): Set<ETModuleMachine> = modules

    /**
     * 单元表变了：重判配方 + 唤醒 tick。
     *
     * ⚠️ 一定要 `updateTickSubscription()`：GTM 的配方逻辑没活干时**会自己退订 tick**，
     * 新挂上来的单元可能正躺在退订状态里，不唤醒它就永远不会开始跑。
     */
    protected open fun onModulesChanged() {
        recipeLogic.markLastRecipeDirty()
        recipeLogic.updateTickSubscription()
    }

    /** 结构成型：只有 `main` 那份才算这台主机立起来了（具名 substructure 是图案仓库，见 `ETModularMachine`）。 */
    override fun formStructure(substructureName: String) {
        super.formStructure(substructureName)
        if (substructureName == ETModularMachine.MAIN_SUBSTRUCTURE) ETModuleNetwork.addHost(this)
    }

    override fun invalidateStructure(substructureName: String) {
        if (substructureName == ETModularMachine.MAIN_SUBSTRUCTURE) ETModuleNetwork.removeHost(this)
        super.invalidateStructure(substructureName)
    }

    override fun onUnload() {
        ETModuleNetwork.removeHost(this)
        super.onUnload()
    }

    /**
     * 面板里显示「挂了几台单元 / 主机等级 / 现在有多少电」——
     * 单元那边显示的是「接没接上主机」，两边对着看就知道链路的哪一头断了。
     *
     * 三个量都不是同步字段，只能服务端求值：各挂一个同步取值控件（写法同 `MasterTowerMachine`），
     * 文案在客户端由这些同步值拼出来。
     */
    override fun getWidgetsForDisplay(syncManager: PanelSyncManager): List<IWidget> {
        val widgets = super.getWidgetsForDisplay(syncManager)

        val attached = syncManager.getOrCreateSyncHandler(
            KEY_MODULE_COUNT, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { modules.size }) },
        )
        val formed = syncManager.getOrCreateSyncHandler(
            KEY_FORMED_COUNT, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { modules.count { it.isFormed } }) },
        )
        val hostTierSync = syncManager.getOrCreateSyncHandler(
            KEY_HOST_TIER, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { hostTier() }) },
        )
        val available = syncManager.getOrCreateSyncHandler(
            KEY_AVAILABLE_EU, LongSyncValue::class.java,
            Supplier { LongSyncValue(LongSupplier { availableEu() }) },
        )

        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(ModuleLang.HOST_MODULES, attached.intValue, formed.intValue)
            }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(ModuleLang.HOST_TIER, ETModularMachine.tierName(hostTierSync.intValue))
            }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(
                    ModuleLang.HOST_AVAILABLE_EU,
                    FormattingUtil.formatNumbers(available.longValue),
                )
            }).asWidget()
        )
        return widgets
    }

    companion object {

        private const val KEY_MODULE_COUNT: String = "gtetHostModuleCount"
        private const val KEY_FORMED_COUNT: String = "gtetHostFormedCount"
        private const val KEY_HOST_TIER: String = "gtetHostTier"
        private const val KEY_AVAILABLE_EU: String = "gtetHostAvailableEu"
    }
}
