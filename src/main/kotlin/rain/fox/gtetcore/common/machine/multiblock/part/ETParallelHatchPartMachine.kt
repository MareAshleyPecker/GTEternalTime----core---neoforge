package rain.fox.gtetcore.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.common.machine.multiblock.part.ParallelHatchPartMachine
import net.minecraft.util.Mth
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
 * GTET 的「并行仓」多方块部件（IV ~ MAX 共 10 档，并行上限由变体表注入）。
 *
 * ## 1.20.1 → 1.21.1 移植要点
 *
 * - **必须继承 GTM 的 [ParallelHatchPartMachine]**：8.0.0 删掉了 `IParallelHatch` 接口，
 *   控制器认并行仓的方式变成 `part instanceof ParallelHatchPartMachine`（见
 *   `MultiblockControllerMachine` 装配部件那段），自己实现接口已经不会被认。
 * - GTM 那个类的 `maxParallel` 是 private 且由 tier 推公式（4^(tier-EV)），本 mod 的档位不是纯 ×4 数列
 *   （UEV 只有 UHV 的两倍），所以并行值自己存一份 [etParallel]，并覆盖 getter / setter。
 * - UI 从 LDLib 的 `IFancyUIMachine#createUIWidget` 换成 MUI 的 [IMuiMachine#buildMainUI]。
 *
 * ## 同步约定
 *
 * [etParallel] 只标 `@SaveField`（进存档），**不标 `@SyncToClient`**：面板的值由 MUI 的
 * `IntSyncValue#allowC2S` 自己双向同步，不需要方块实体字段同步 —— GTM 自己的并行仓就是这么做的。
 *
 * @param tier          电压等级（决定外壳贴图，见注册处的变体表）
 * @param etMaxParallel 该档并行上限（同时是默认值）
 */
class ETParallelHatchPartMachine(
    info: BlockEntityCreationInfo,
    tier: Int,
    private val etMaxParallel: Int,
) : ParallelHatchPartMachine(info, tier), IMuiMachine {

    /** 当前生效的并行数（1 ~ [etMaxParallel]），进存档。 */
    @field:SaveField
    private var etParallel: Int = etMaxParallel

    /** 覆盖 GTM 那套「由 tier 推上限」的取值，改用本 mod 变体表给的档位值。 */
    override fun getCurrentParallel(): Int = etParallel

    /** 玩家改并行数：夹到合法区间，值真的变了才敲控制器让配方逻辑下一轮重取。 */
    override fun setCurrentParallel(parallelAmount: Int) {
        val clamped = Mth.clamp(parallelAmount, MIN_PARALLEL, etMaxParallel)
        if (clamped == etParallel) return
        etParallel = clamped
        for (controller in controllers) {
            if (controller is IRecipeLogicMachine) {
                controller.recipeLogic.markLastRecipeDirty()
            }
        }
    }

    /**
     * 部件共享闸门：返回全局开关 [GtetConfig.partsShareable]（默认 false = 禁止共享）。
     *
     * 本件是控制器唯一的并行仓（`getParallelHatch()` 只取一个实例），被两个已成型结构共享时
     * 两边会同时读同一个并行数；打开配置就恢复这个串配方风险。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()

    /** MUI 面板：一个数字输入框绑到 [etParallel]，值经 MUI 的 C2S 同步回服务端。 */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        // ⚠️ 必须用显式 SAM：IntSyncValue 同时有 (IntSupplier, IntConsumer) 与 (IntSupplier, IntSupplier)
        // 两个重载，直接写 lambda 会「Overload resolution ambiguity」。
        val parallels = IntSyncValue(
            IntSupplier { getCurrentParallel() },
            IntConsumer { setCurrentParallel(it) },
        ).allowC2S()
        mainWidget.child(
            Flow.row()
                .coverChildren()
                .child(
                    TextFieldWidget()
                        .width(50)
                        .setTextAlignment(Alignment.CENTER)
                        .setNumbers(MIN_PARALLEL, etMaxParallel)
                        .value(parallels)
                        .setDefaultNumber(etMaxParallel.toDouble())
                        .margin(4)
                )
                .child(Text.lang("block.gtetscore.${definition.name}").asWidget().margin(4).verticalCenter())
        )
    }

    companion object {

        /** 并行下限：1 = 退化成 GTCEu 原版不并行的机器。 */
        const val MIN_PARALLEL: Int = 1
    }
}
