@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.IPacketWriter
import brachy.modularui.api.ISyncedAction
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IGuiAction
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.schema.BaseSchemaRenderer
import brachy.modularui.drawable.schema.BlockHighlight
import brachy.modularui.drawable.schema.MapSchema
import brachy.modularui.utils.Color
import brachy.modularui.value.BoolValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.ToggleButton
import brachy.modularui.widgets.layout.Flow
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanel
import com.gregtechceu.gtceu.common.machine.trait.AutoOutputTrait
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.GTMultiblockSchemaRenderer
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import org.apache.logging.log4j.Level as LogLevel
import org.joml.Vector3f
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MachineIoConfigLang
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/** 3D 配置页的同步动作名（`PanelSyncManager.registerSyncedAction` 的键，加命名空间避免与 GTM 撞）。 */
private const val ACTION_ITEM = "gtetscore:io_config_item"
private const val ACTION_FLUID = "gtetscore:io_config_fluid"

/** 假 schema 里单格方块的坐标；相机看它的几何中心 (0.5, 0.5, 0.5)。 */
private val SCHEMA_ORIGIN: BlockPos = BlockPos.ZERO

/**
 * 「3D 输入输出配置页」的装配点。
 *
 * 由 [rain.fox.gtetcore.mixin.gtm.MachineUIPanelBuilderMixin] 在
 * `MachineUIPanelBuilder#build` 的 RETURN 处调用（MachineUIPanelBuilder.java:78）。
 * 往右侧配置列加按钮的先例：LargeMinerMachine.java:207-222。
 * I/O 落点与开关：AutoOutputTrait（AutoOutputTrait.java:208-228）。
 *
 * @author rain fox
 */
object MachineIoConfig {

    /** 机器没有 [AutoOutputTrait]、或物品 / 流体都不支持时直接不挂载。 */
    @JvmStatic
    fun attach(panel: MachineUIPanel, machine: MetaMachine, syncManager: PanelSyncManager) {
        val trait = machine.getTrait(AutoOutputTrait::class.java) ?: return
        if (!trait.supportsAutoOutputItems() && !trait.supportsAutoOutputFluids()) return

        val page = MachineIoConfigPage(machine, trait, syncManager)
        val toggle = ToggleButton()
            .size(16)
            .overlay(GTGuiTextures.TOOL_IO_FACING_ROTATION)
            .value(
                BoolValue.Dynamic(
                    { page.isEnabled },
                    { enabled -> page.isEnabled = enabled }
                )
            )
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r -> r.addLine(Text.lang(MachineIoConfigLang.BUTTON)) }

        panel.rightConfiguratorPanel.child(toggle)
        panel.child(page)
    }
}

/**
 * 单格机器的 3D 输入输出配置页（覆盖整个机器面板，默认隐藏）。
 *
 * 3D 积木：GTMultiblockSchemaRenderer（GTMultiblockSchemaRenderer.java:10）+ MapSchema
 * （brachy.modularui.drawable.schema.MapSchema）+ SchemaWidget；面点击读数走
 * `BaseSchemaRenderer#lastRayTrace`，先例 MultiblockPreviewWidget.java:111-123。
 * 相机默认是 (0,0,0) 看 (0,0,0)（Camera 构造器），必须自己设，否则什么都看不见。
 *
 * @author rain fox
 */
class MachineIoConfigPage(
    private val machine: MetaMachine,
    private val trait: AutoOutputTrait,
    private val syncManager: PanelSyncManager
) : ParentWidget<MachineIoConfigPage>() {

    private val itemSupported: Boolean = trait.supportsAutoOutputItems()
    private val fluidSupported: Boolean = trait.supportsAutoOutputFluids()

    init {
        name("gtetscore_io_config")
        pos(0, 0)
        sizeRel(1f)
        background(GTGuiTextures.BACKGROUND)
        excludeAreaInRecipeViewer()
        isEnabled = false

        // C2S：executeClient=false / executeServer=true，与 GTMuiWidgets.java:212 同一组参数。
        if (itemSupported) {
            syncManager.registerSyncedAction(ACTION_ITEM, false, true,
                ISyncedAction { buf -> applyDirection(buf, true) })
        }
        if (fluidSupported) {
            syncManager.registerSyncedAction(ACTION_FLUID, false, true,
                ISyncedAction { buf -> applyDirection(buf, false) })
        }

        child(
            Flow.col()
                .name("io_config_body")
                .sizeRel(1f)
                .padding(6)
                .childPadding(3)
                .child(IoLabel(Text.lang(MachineIoConfigLang.TITLE)).widthRel(1f).height(12))
                .child(createPreview().expanded().widthRel(1f))
                .child(IoLabel(Supplier { statusText() }).widthRel(1f).height(20))
        )

        child(
            IoButton()
                .size(12)
                .right(4)
                .top(4)
                .overlay(GuiTextures.CLOSE)
                .onMousePressed { _, _ ->
                    isEnabled = false
                    true
                }
        )
    }

    // ======================== 3D 视图 ========================

    private fun createPreview(): IoBox {
        val box = IoBox().name("io_config_preview").sizeRel(1f)
        val level = machine.level ?: return box
        if (!level.isClientSide) return box
        try {
            box.child(buildSchemaWidget(level))
        } catch (t: Throwable) {
            // 3D 搭不出来不能连带整台机器的界面打不开
            GTETSCore.LOGGER.log(LogLevel.WARN, "[GTET-TEST] 3D 输入输出页渲染器构建失败", t)
        }
        return box
    }

    private fun buildSchemaWidget(level: Level): IWidget {
        val blocks = HashMap<BlockPos, BlockState>()
        blocks[SCHEMA_ORIGIN] = level.getBlockState(machine.blockPos)

        val renderer = GTMultiblockSchemaRenderer(MapSchema(blocks))
            .highlightRenderer(BlockHighlight(Color.withAlpha(Color.GREEN.brighter(1), 0.9f), 1f / 32f))
        renderer.camera().setPosAndLookAt(0.5f, 0.5f, -3.2f, Vector3f(0.5f, 0.5f, 0.5f))

        return renderer.asWidget()
            .name("io_config_schema")
            .sizeRel(1f)
            .listenGuiAction(IGuiAction.MouseReleased { _, button -> onFaceClicked(renderer, button) })
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r ->
                r.addLine(Text.lang(MachineIoConfigLang.HINT_ITEM))
                r.addLine(Text.lang(MachineIoConfigLang.HINT_FLUID))
            }
    }

    // ======================== 面点击 → I/O ========================

    /** 左键 = 物品输出面，右键 = 流体输出面（对齐 1.20.1 的手感）。 */
    private fun onFaceClicked(renderer: BaseSchemaRenderer, button: Int): Boolean {
        val hit = renderer.lastRayTrace() ?: return false
        if (hit.type != HitResult.Type.BLOCK) return false
        return when (button) {
            0 -> sendDirection(hit.direction, true)
            1 -> sendDirection(hit.direction, false)
            else -> false
        }
    }

    private fun sendDirection(face: Direction, items: Boolean): Boolean {
        if (items) {
            if (!itemSupported) return false
            syncManager.callSyncedAction(ACTION_ITEM,
                IPacketWriter<RegistryFriendlyByteBuf> { buf -> buf.writeVarInt(face.ordinal) })
        } else {
            if (!fluidSupported) return false
            syncManager.callSyncedAction(ACTION_FLUID,
                IPacketWriter<RegistryFriendlyByteBuf> { buf -> buf.writeVarInt(face.ordinal) })
        }
        return true
    }

    /** 服务端执行体；方向与开关都落在 trait 自己的同步字段上（AutoOutputTrait.java:61-74）。 */
    private fun applyDirection(buf: RegistryFriendlyByteBuf, items: Boolean) {
        val ordinal = buf.readVarInt()
        val faces = Direction.entries.toTypedArray()
        if (ordinal < 0 || ordinal >= faces.size) return
        val face = faces[ordinal]
        if (items) trait.setItemOutputDirection(face) else trait.setFluidOutputDirection(face)
    }

    // ======================== 状态行 ========================

    private fun statusText(): Component {
        val lines = ArrayList<Component>(2)
        if (itemSupported) {
            lines.add(
                Component.translatable(
                    MachineIoConfigLang.FACE_ITEM,
                    directionName(trait.itemOutputDirection)
                )
            )
        }
        if (fluidSupported) {
            lines.add(
                Component.translatable(
                    MachineIoConfigLang.FACE_FLUID,
                    directionName(trait.fluidOutputDirection)
                )
            )
        }
        val out = Component.empty()
        lines.forEachIndexed { index, line ->
            if (index > 0) out.append("\n")
            out.append(line)
        }
        return out
    }

    private fun directionName(direction: Direction?): Component =
        if (direction == null) Text.lang(MachineIoConfigLang.FACE_NONE)
        else Text.lang(MachineIoConfigLang.faceKey(direction))

    // ======================== 自引用泛型的控件壳 ========================
    // MUI 这几只控件是 `Foo<W extends Foo<W>>`，Kotlin 里没法用菱形推断，链条会退回父类型。

    private class IoBox : ParentWidget<IoBox>()

    private class IoButton : ButtonWidget<IoButton>()

    private class IoLabel : TextWidget<IoLabel> {

        constructor(text: Component) : super(text)
        constructor(text: Supplier<Component>) : super(text)
    }
}
