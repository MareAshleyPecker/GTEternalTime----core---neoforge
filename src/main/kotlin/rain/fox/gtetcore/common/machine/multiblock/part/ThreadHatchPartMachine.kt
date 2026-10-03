package rain.fox.gtetcore.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import rain.fox.gtetcore.api.capability.IThreadHatch
import rain.fox.gtetcore.config.GtetConfig

import java.util.function.IntConsumer
import java.util.function.IntSupplier

import brachy.modularui.api.drawable.Text
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.utils.Alignment
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.layout.Flow
import brachy.modularui.widgets.textfield.TextFieldWidget

/**
 * 「线程仓」多方块部件：往控制器上声明「这台机器最多能同时跑几条线程」。
 *
 * 真正开线程 / 每线程计时 / 每线程结算的是 [rain.fox.gtetcore.common.machine.multiblock.thread.ThreadedRecipeLogic]。
 *
 * - 上限 [maxThreads] 由变体表显式传入（`ETThreadHatches.VARIANTS`），构造后不再变化；
 * - [currentThread] 是玩家可下调的**当前生效值**，`@SaveField` 进存档（面板的值由 MUI 的
 *   `IntSyncValue#allowC2S` 自己双向同步，所以不标 `@SyncToClient`，与 GTM 自己的并行仓同款）；
 * - `canShared()` 返回全局开关，默认禁止共享。
 *
 * ⚠️ 下调线程数**不会**杀掉已在跑的线程（否则扣掉的料会凭空消失），见内核的 `ensureSlots`。
 *
 * @param tier    电压等级（决定外壳与正面覆盖层，见变体表）
 * @param threads 该档的线程数上限，由变体表原样传入，本类不做任何推导
 */
class ThreadHatchPartMachine(
    info: BlockEntityCreationInfo,
    tier: Int,
    threads: Int,
) : TieredPartMachine(info, tier), IMuiMachine, IThreadHatch {

    /** 该档的线程数上限；兜底到 [MIN_THREAD]（变体表误写 0 / 负数时线程逻辑会一条也开不出来）。 */
    override val maxThreads: Int = threads.coerceAtLeast(MIN_THREAD)

    /** 玩家在面板里设定的线程数（1 ~ [maxThreads]），默认全开。 */
    @field:SaveField
    var currentThread: Int = maxThreads
        private set

    /** 配方逻辑读的是**当前生效**的线程数，不是上限。 */
    override val threadCount: Int get() = currentThread

    /**
     * 玩家改线程数：夹到合法区间，值真的变了才敲控制器让配方逻辑下一轮重新取数。
     */
    fun setThreadAmount(amount: Int) {
        val clamped = Mth.clamp(amount, MIN_THREAD, maxThreads)
        if (clamped == currentThread) return
        currentThread = clamped
        for (controller in controllers) {
            if (controller is IRecipeLogicMachine) controller.recipeLogic.markLastRecipeDirty()
        }
    }

    /**
     * 读档后把 [currentThread] 夹回 `[MIN_THREAD] .. [maxThreads]`（旧存档换了低一档的仓、手改 NBT、
     * 0 / 负数都会在这里被拦住，见内核 `ensureSlots`）。
     *
     * ⚠️ `@SaveField` 的反序列化在 `super.loadAdditional(...)` 里完成，所以夹取只能写在它**之后**
     * （`ManagedSyncBlockEntity.java:70-73`）。
     */
    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        currentThread = currentThread.coerceIn(MIN_THREAD, maxThreads)
    }

    /**
     * 部件共享闸门：返回全局开关 [GtetConfig.partsShareable]（默认 `false` = 禁止共享）。
     *
     * 本件持有自己的线程池与「已经吃掉的料」，被两个已成型结构共享时两个控制器会同时往同一份线程账上派活。
     * ⚠️ 8.0.0 的签名比老工程多了两个参数（`MultiblockPartMachine.java:158`）。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()

    /** 面板：一个数字输入框绑到 [currentThread]（值经 MUI 的 C2S 同步回服务端）+ 方块名。 */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        // ⚠️ 必须用显式 SAM：IntSyncValue 同时有 (IntSupplier, IntConsumer) 与 (IntSupplier, IntSupplier)
        // 两个重载，直接写 lambda 会「Overload resolution ambiguity」。
        val threads = IntSyncValue(
            IntSupplier { currentThread },
            IntConsumer { setThreadAmount(it) },
        ).allowC2S()
        mainWidget.child(
            Flow.row()
                .coverChildren()
                .child(
                    TextFieldWidget()
                        .width(50)
                        .setTextAlignment(Alignment.CENTER)
                        .setNumbers(MIN_THREAD, maxThreads)
                        .value(threads)
                        .setDefaultNumber(maxThreads.toDouble())
                        .margin(4)
                )
                .child(Text.lang("block.gtetscore.${definition.name}").asWidget().margin(4).verticalCenter())
        )
    }

    companion object {

        /** 线程数下限：至少 1 条线程（= 退化成 GTM 原版的单配方机器）。 */
        const val MIN_THREAD: Int = 1
    }
}
