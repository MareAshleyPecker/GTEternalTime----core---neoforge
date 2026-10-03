package rain.fox.gtetcore.common.machine.multiblock.modular

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widgets.ButtonWidget
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleMachine.Companion.HOST_RANGE
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleMachine.Companion.RECHECK_INTERVAL
import rain.fox.gtetcore.data.lang.ModuleLang
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier
import java.util.function.Supplier
import kotlin.math.sqrt

/**
 * 模块化多方块的**单元（子机）**：认一台主机，可以从自己的或主机的能源仓取电。
 *
 * 一句话机器逻辑：**附近 [HOST_RANGE] 格内有成型的主机 + 配方等级不超过主机 + 付得起每 tick 的电 → 才跑这条配方。**
 *
 * 等级分工（按需求定死）：
 * - **配方等级以主机算** —— [recipeTier] 决定「这条配方配不配在本单元跑」；
 * - **处理等级以自己算** —— [processingTier] 是本单元自己结构的电压档位（超频 / 并行按它走）。
 *
 * 供电预留了两个入口：`consumeEu(...)` 走 [defaultPowerSource]，也可以显式指定
 * `ETPowerSource.SELF` / `ETPowerSource.HOST` 单次切换。**扣电请在 `onWorking()`（每工作 tick 一次）里调**，
 * 不要放在 [recipeRequirement] / `matchRecipe` 里 —— 那一层是**模拟**匹配，一 tick 会被问好几次。
 *
 * ⚠️ 8.0.0 删了 `onStructureFormed()` / `onStructureInvalid()`：成型与失效的挂载点改成
 * `formStructure(name)` / `invalidateStructure(name)`（只认 `main`），卸载仍是 `onUnload()`。
 *
 * @author rain fox
 */
abstract class ETModuleMachine(info: BlockEntityCreationInfo) :
    WorkableElectricMultiblockMachine(info, ETModuleRecipeLogic()) {
    /** 单元从哪取电。 */
    enum class ETPowerSource {
        /** 自己的能源仓。 */
        SELF,

        /** 主机的能源仓（GTO 净化水单元的写法：单元不自己耗电，主机统一供电）。 */
        HOST,
    }

    /** 对接上的主机（服务端瞬态；重进世界后由 [onModuleTick] 的周期重查重新接上）。 */
    var host: ETModuleHostMachine? = null
        private set

    /** 默认从哪取电；自己带能源仓的单元可以覆写成 [ETPowerSource.SELF]。 */
    protected open val defaultPowerSource: ETPowerSource = ETPowerSource.HOST

    private var tickSub: TickableSubscription? = null
    private var tickCounter = 0

    /** 处理等级：**以自己算**。 */
    open fun processingTier(): Int = tier

    /** 配方等级：**以主机算**（没主机时退化成自己，方便单独调试）。 */
    open fun recipeTier(): Int = host?.hostTier() ?: processingTier()

    /** 本单元每 tick 要的电（EU/t）；子类按自己的配方与倍率折算。 */
    protected open fun euPerTick(): Long = 0

    /**
     * **一句话机器逻辑**：跑这条配方需要什么条件。
     *
     * 返回 `null` = 条件都满足；返回原因 = 不开工，并把这句话显示给玩家（`failureReasons` → Jade / 面板）。
     * 子类覆写时**先调 `super`**（主机那一条是所有单元共有的），再追加自己的条件。
     */
    open fun recipeRequirement(): Component? = when {
        host == null -> Component.translatable(ModuleLang.NO_HOST, HOST_RANGE)
        else -> null
    }

    /**
     * 扣电：返回**实际扣到**的量（不够就是不够，调用方按返回值决定开工 / 停机）。
     *
     * @param amount 要扣多少（EU）；默认取 [euPerTick]
     * @param source 从哪扣；默认 [defaultPowerSource]
     */
    fun consumeEu(amount: Long = euPerTick(), source: ETPowerSource = defaultPowerSource): Long {
        if (amount <= 0) return 0
        return when (source) {
            ETPowerSource.SELF -> (-(energyContainer?.changeEnergy(-amount) ?: 0L)).coerceAtLeast(0)
            ETPowerSource.HOST -> host?.consumeHostEu(amount) ?: 0
        }
    }

    /** 主机现在有多少电（借电前先看一眼，避免「扣不到再回滚」）。 */
    fun hostAvailableEu(): Long = host?.availableEu() ?: 0

    override fun formStructure(substructureName: String) {
        super.formStructure(substructureName)
        if (substructureName != ETModularMachine.MAIN_SUBSTRUCTURE) return
        recheckHost()
        subscribeTick()
    }

    override fun invalidateStructure(substructureName: String) {
        if (substructureName == ETModularMachine.MAIN_SUBSTRUCTURE) {
            unsubscribeTick()
            unbindHost()
        }
        super.invalidateStructure(substructureName)
    }

    /** ⚠️ 卸载同样要解绑，否则主机的单元表里会留悬空引用。 */
    override fun onUnload() {
        unsubscribeTick()
        unbindHost()
        super.onUnload()
    }

    private fun subscribeTick() {
        if (isRemote) return
        tickSub = subscribeServerTick { onModuleTick() }
    }

    private fun unsubscribeTick() {
        tickSub?.let { unsubscribe(it) }
        tickSub = null
    }

    /** 每 [RECHECK_INTERVAL] tick 重找一次主机：主机后成型、被拆、区块重载都能自己接回来。 */
    private fun onModuleTick() {
        if (isRemote) return
        if (++tickCounter < RECHECK_INTERVAL) return
        tickCounter = 0
        recheckHost()
    }

    /** 找一台主机接上；已经接着且主机还在成型状态就不动。 */
    fun recheckHost() {
        if (isRemote) return
        if (host?.isFormed == true) return
        bindHost(ETModuleNetwork.findHost(level ?: return, blockPos, HOST_RANGE))
    }

    private fun bindHost(newHost: ETModuleHostMachine?) {
        if (host === newHost) return
        host?.detachModule(this)
        host = newHost
        newHost?.attachModule(this)
        if (newHost != null) {
            recipeLogic.markLastRecipeDirty()
            recipeLogic.updateTickSubscription()
        }
    }

    private fun unbindHost() {
        if (host === null) return
        host?.detachModule(this)
        host = null
        recipeLogic.markLastRecipeDirty()
    }

    /**
     * 面板：对接状态 + 等级分工，末尾一个**可点的「重新对接」**（排查时不用等 [RECHECK_INTERVAL] tick）。
     *
     * 三个量都是服务端真值（`host` 根本不过客户端），所以走 `IntSyncValue` 同步；
     * 文案在客户端用同步值拼。
     *
     * ⚠️ 按钮**不走** `registerSyncedAction`：那条路在本项目实机上点不动（项目笔记 S1.8）。
     * 改用「getter 恒 false + `.allowC2S()`」的瞬时动作同步值 —— 与 `StructureExportPanel` 同款：
     * 客户端把值翻一下发一次 C2S，setter 在服务端收到后再真办事。
     */
    override fun getWidgetsForDisplay(syncManager: PanelSyncManager): List<IWidget> {
        val widgets = super.getWidgetsForDisplay(syncManager)

        val distance = syncManager.getOrCreateSyncHandler(
            KEY_HOST_DISTANCE, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { hostDistance() }) },
        )
        val processTier = syncManager.getOrCreateSyncHandler(
            KEY_PROCESS_TIER, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { processingTier() }) },
        )
        val recipeTierSync = syncManager.getOrCreateSyncHandler(
            KEY_RECIPE_TIER, IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { recipeTier() }) },
        )
        val recheck = syncManager.getOrCreateSyncHandler(
            KEY_RECHECK, BooleanSyncValue::class.java,
            Supplier {
                BooleanSyncValue(BooleanSupplier { false }, BooleanConsumer { pressed ->
                    // setter 两端各跑一次；只有服务端那次能改机器状态
                    if (pressed && level?.isClientSide == false) recheckHost()
                }).allowC2S()
            },
        )

        widgets.add(
            Text.dynamic(Supplier {
                val d = distance.intValue
                if (d < 0) Component.translatable(ModuleLang.NO_HOST, HOST_RANGE)
                else Component.translatable(ModuleLang.HOST_LINKED, d)
            }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier {
                Component.translatable(
                    ModuleLang.TIER_SPLIT,
                    ETModularMachine.tierName(processTier.intValue),
                    ETModularMachine.tierName(recipeTierSync.intValue),
                )
            }).asWidget()
        )

        val button: ModuleButton = ModuleButton()
        button.overlay(Text.lang(ModuleLang.RECHECK))
        button.onMousePressed { _, _ ->
            // 翻转 = 每次都无条件发一次 C2S（值本身没有含义）
            recheck.boolValue = !recheck.boolValue
            true
        }
        widgets.add(button)
        return widgets
    }

    /** 与主机的距离（格）；没接上返回 -1（面板据此显示两条不同的文案）。 */
    private fun hostDistance(): Int {
        val linked = host ?: return NO_HOST_DISTANCE
        val lvl = level ?: return NO_HOST_DISTANCE
        if (linked.level !== lvl) return NO_HOST_DISTANCE
        return sqrt(linked.blockPos.distSqr(blockPos)).toInt()
    }

    companion object {

        /** 主机的最远对接距离（格）。 */
        const val HOST_RANGE: Int = 32

        /** 重找主机的间隔（tick）。 */
        private const val RECHECK_INTERVAL: Int = 80

        /** 面板里「没接上主机」用的哨兵距离。 */
        private const val NO_HOST_DISTANCE: Int = -1

        private const val KEY_HOST_DISTANCE: String = "gtetModuleHostDistance"
        private const val KEY_PROCESS_TIER: String = "gtetModuleProcessTier"
        private const val KEY_RECIPE_TIER: String = "gtetModuleRecipeTier"
        private const val KEY_RECHECK: String = "gtetModuleRecheck"
    }

    /**
     * 面板上的按钮。
     *
     * ⚠️ MUI 这几只控件都是「自引用泛型」（`Foo<W extends Foo<W>>`），Kotlin 里没法用菱形推断，
     * 必须写一个写死了类型参数的私有子类，链式调用才拿得回自己的类型（同 `StructureExportPanel`）。
     */
    private class ModuleButton : ButtonWidget<ModuleButton>()
}

/**
 * 单元的配方逻辑：把 [ETModuleMachine.recipeRequirement]（一句话机器逻辑）与主机等级当作**内容匹配**的一部分。
 *
 * 在 `matchRecipe` 这一层拦下，失败会带原因文本，GTM 会写进 `failureReasons` ——
 * **Jade 与机器面板都能看到「为什么这条配方不跑」**。
 */
class ETModuleRecipeLogic : RecipeLogic() {

    private val module: ETModuleMachine? get() = getRLMachine() as? ETModuleMachine

    override fun matchRecipe(recipe: GTRecipe): ActionResult {
        val machine = module ?: return super.matchRecipe(recipe)
        machine.recipeRequirement()?.let { return ActionResult.fail(it, null, null) }
        val cap = machine.recipeTier()
        if (RecipeHelper.getRecipeEUtTier(recipe) > cap) {
            val tierName = GTValues.VN[cap.coerceIn(0, GTValues.VN.size - 1)]
            return ActionResult.fail(Component.translatable(ModuleLang.MODULE_TIER_TOO_LOW, tierName), null, null)
        }
        return super.matchRecipe(recipe)
    }
}
