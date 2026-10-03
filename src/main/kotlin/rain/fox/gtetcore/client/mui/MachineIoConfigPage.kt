@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.IPacketWriter
import brachy.modularui.api.ISyncedAction
import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IGuiAction
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.DynamicDrawable
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.Rectangle
import brachy.modularui.drawable.schema.BaseSchemaRenderer
import brachy.modularui.drawable.schema.BlockHighlight
import brachy.modularui.drawable.schema.MapSchema
import brachy.modularui.screen.viewport.ModularGuiContext
import brachy.modularui.theme.WidgetThemeEntry
import brachy.modularui.utils.Color
import brachy.modularui.value.BoolValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.SchemaWidget
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
import org.joml.Vector3fc
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MachineIoConfigLang
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import kotlin.math.atan2

/** 3D 配置页的同步动作名（`PanelSyncManager.registerSyncedAction` 的键，加命名空间避免与 GTM 撞）。 */
private const val ACTION_ITEM = "gtetscore:io_config_item"
private const val ACTION_FLUID = "gtetscore:io_config_fluid"

/** 假 schema 里单格方块的坐标。 */
private val SCHEMA_ORIGIN: BlockPos = BlockPos.ZERO

/** 方块的几何中心；`SchemaWidget.draw` 每帧拿它当相机 lookAt（见 [MachineSchema] / [IoSchemaWidget]）。 */
private val SCHEMA_FOCUS: Vector3f = Vector3f(0.5f, 0.5f, 0.5f)

/** `SchemaWidget.scale` 就是 `Camera.setLookAtAndAngle` 的第 4 个参数 dist（单位：格）。 */
private const val SCHEMA_DISTANCE = 2.0f

/** 主面之外的额外偏角，露出一个邻面，避免正对着看成一堵墙。 */
private const val SCHEMA_YAW_OFFSET = 0.6f

/** 机器没有朝向（`hasFrontFacing() == false`）时的兜底视角。 */
private const val SCHEMA_YAW_FALLBACK = 0.7853982f
private const val HIGHLIGHT_THICKNESS = 1f / 32f

/** 展开图小格的边长与格间距。 */
private const val FACE_CELL_SIZE = 20
private const val FACE_CELL_GAP = 2

/** 六个面的展示顺序：上、北、东、南、西、下。 */
private val FACE_ROW_ORDER = listOf(
    Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
)

private val BACKDROP_UNDERLAY: Int = Color.argb(255, 24, 26, 30)
private val ITEM_FACE_COLOR: Int = Color.argb(255, 60, 220, 90)
private val FLUID_FACE_COLOR: Int = Color.argb(255, 70, 170, 255)
private val BOTH_FACE_COLOR: Int = Color.argb(255, 60, 200, 170)
private val IDLE_FACE_COLOR: Int = Color.argb(255, 58, 62, 70)
private val CELL_TEXT_COLOR: Int = Color.argb(255, 255, 255, 255)
private val HOVER_FACE_COLOR: Int = Color.argb(160, 255, 255, 255)

/**
 * 「输入输出配置页」的装配点。
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

        // 页面加进 panel 之前先快照同层子件（titleBar / 两个配置列 / panelContents）
        val siblings = panel.children.toList()

        val page = MachineIoConfigPage(machine, trait, syncManager, siblings)
        val toggle = ToggleButton()
            .size(16)
            .overlay(GTGuiTextures.TOOL_IO_FACING_ROTATION)
            .value(
                BoolValue.Dynamic(
                    BooleanSupplier { page.isEnabled },
                    BooleanConsumer { open -> page.setPageOpen(open) }
                )
            )
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r -> r.addLine(Text.lang(MachineIoConfigLang.BUTTON)) }

        panel.rightConfiguratorPanel.child(toggle)
        panel.child(page)
    }
}

/**
 * 单格机器的输入输出配置页（覆盖整个机器面板，默认隐藏）。
 *
 * 3D 视图：GTMultiblockSchemaRenderer（GTMultiblockSchemaRenderer.java:10）+ MapSchema
 * （brachy.modularui.drawable.schema.MapSchema）+ [IoSchemaWidget]；面点击读数走
 * `BaseSchemaRenderer#lastRayTrace`，先例 MultiblockPreviewWidget.java:111-123。
 *
 * 输出面状态用页面下方的**六面展开图**表达（[createFaceRow]）：那条路不依赖 3D 渲染管线，
 * 而且六个小格本身就能设 I/O，比在 3D 里点面稳。
 *
 * @author rain fox
 */
class MachineIoConfigPage(
    private val machine: MetaMachine,
    private val trait: AutoOutputTrait,
    private val syncManager: PanelSyncManager,
    private val siblings: List<IWidget>
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

        // 不透明底：Rectangle 兜底铺满，GT 的背景图叠在上面
        child(IDrawable.DrawableWidget(Rectangle().color(BACKDROP_UNDERLAY).solid()).sizeRel(1f))
        child(IDrawable.DrawableWidget(GTGuiTextures.BACKGROUND).sizeRel(1f))

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
                .child(createFaceRow())
                .child(IoLabel(Supplier { statusText() }).widthRel(1f).height(20))
        )

        child(
            IoButton()
                .size(12)
                .right(4)
                .top(4)
                .overlay(GuiTextures.CLOSE)
                .onMousePressed { _, _ ->
                    setPageOpen(false)
                    true
                }
        )
    }

    /** 开 / 关这一页；同时把同层的机器界面子件一起关掉，免得盖不严时透底。 */
    fun setPageOpen(open: Boolean) {
        isEnabled = open
        siblings.forEach { sibling -> sibling.isEnabled = !open }
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
        val renderer = GTMultiblockSchemaRenderer(MachineSchema(level.getBlockState(machine.blockPos)))
        renderer.highlightRenderer(BlockHighlight(HOVER_FACE_COLOR, HIGHLIGHT_THICKNESS))
        // ⚠️ SchemaRenderer 构造器把 rayTracing 初始化成 false，不打开就没有 lastRayTrace()：
        //    面点击与 hover 高亮两条路全是死的（SchemaRenderer ctor 字节码 iconst_0 → putfield rayTracing）
        renderer.rayTracing(true)

        return IoSchemaWidget(renderer)
            .name("io_config_schema")
            .sizeRel(1f)
            .scale(SCHEMA_DISTANCE)
            .yaw(yawFacingCameraAt(mainFace()))
            .enableDragRotation(true)
            .enableScrollScaling(true)
            // 中键拖动改的是 offset（SchemaWidget.onMouseDrag 的 button == 2 分支），关掉
            .enableDragTranslation(false)
            .listenGuiAction(IGuiAction.MouseReleased { _, button -> onFaceClicked(renderer, button) })
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r ->
                r.addLine(Text.lang(MachineIoConfigLang.HINT_ITEM))
                r.addLine(Text.lang(MachineIoConfigLang.HINT_FLUID))
            }
    }

    /** 机器贴图的主面（furnace 面）。 */
    private fun mainFace(): Direction =
        if (machine.hasFrontFacing()) machine.frontFacing else Direction.NORTH

    /**
     * 让相机落在主面那一侧的 3/4 视角。
     *
     * `SchemaWidget.draw` 每帧按 `pos = lookAt + normalize(cos yaw, tan pitch, sin yaw) * dist` 反推相机位置，
     * 水平方向就是 `(cos yaw, sin yaw)` —— 用主面的 (stepX, stepZ) 反解 yaw，再加 [SCHEMA_YAW_OFFSET] 露一个邻面。
     */
    private fun yawFacingCameraAt(front: Direction): Float {
        val stepX = front.stepX
        val stepZ = front.stepZ
        if (stepX == 0 && stepZ == 0) return SCHEMA_YAW_FALLBACK
        return atan2(stepZ.toFloat(), stepX.toFloat()) + SCHEMA_YAW_OFFSET
    }

    // ======================== 六面展开图 ========================

    /** 一行六个面格：底色 = 当前该面是不是物品 / 流体输出面；左键设物品、右键设流体。 */
    private fun createFaceRow(): IWidget =
        Flow.row()
            .name("io_config_faces")
            .size(FACE_ROW_ORDER.size * FACE_CELL_SIZE + (FACE_ROW_ORDER.size - 1) * FACE_CELL_GAP, FACE_CELL_SIZE)
            .childPadding(FACE_CELL_GAP)
            .apply { FACE_ROW_ORDER.forEach { face -> child(faceCell(face)) } }

    private fun faceCell(face: Direction): IWidget =
        IoButton()
            .size(FACE_CELL_SIZE)
            .background(DynamicDrawable(Supplier<IDrawable> { Rectangle().color(faceCellColor(face)).solid() }))
            .child(IoLabel(Supplier { Text.lang(MachineIoConfigLang.shortFaceKey(face)) }).center().color(CELL_TEXT_COLOR))
            .tooltipAutoUpdate(true)
            .tooltipBuilder { tip ->
                tip.addLine(Text.lang(MachineIoConfigLang.faceKey(face)))
                tip.addLine(Text.lang(MachineIoConfigLang.FACE_CELL_TIP))
            }
            .onMousePressed { _, button ->
                when (button) {
                    0 -> sendDirection(face, true)
                    1 -> sendDirection(face, false)
                }
                true
            }

    private fun faceCellColor(face: Direction): Int {
        val items = itemSupported && trait.itemOutputDirection == face
        val fluids = fluidSupported && trait.fluidOutputDirection == face
        return when {
            items && fluids -> BOTH_FACE_COLOR
            items -> ITEM_FACE_COLOR
            fluids -> FLUID_FACE_COLOR
            else -> IDLE_FACE_COLOR
        }
    }

    // ======================== 面点击 → I/O ========================

    /** 左键 = 物品输出面，右键 = 流体输出面（对齐 1.20.1 的手感）。 */
    private fun onFaceClicked(renderer: GTMultiblockSchemaRenderer, button: Int): Boolean {
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

    /** 服务端执行体；方向与开关都落在 trait 自己的同步字段上（AutoOutputTrait.java:61-80）。 */
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

/**
 * 单格 schema。
 *
 * `MapSchema` 对单格算出的 focus 是方块角点 (0,0,0)（BlockPosUtil.getCenterF），而
 * `SchemaWidget.draw` 每帧用 `schema.getFocus() + offset` 当相机 lookAt —— 不覆盖就会绕着角转。
 */
private class MachineSchema(block: BlockState) : MapSchema(mapOf(SCHEMA_ORIGIN to block)) {

    override fun getFocus(): Vector3fc = SCHEMA_FOCUS
}

/**
 * 每帧把 `offset` 校正回「当前 focus → 方块几何中心」的差值。
 *
 * `SchemaWidget.draw` 每帧拿 `schema.getFocus() + offset` 当相机 lookAt，而 `offset` 是随时可能被
 * relayout / 外部写到的可变字段；在 super.draw 之前重算一次，`focus + offset` 就恒等于方块中心。
 */
private class IoSchemaWidget(renderer: BaseSchemaRenderer) : SchemaWidget(renderer) {

    override fun draw(context: ModularGuiContext, theme: WidgetThemeEntry<*>) {
        val focus = schemaRenderer.schema().getFocus()
        offset(
            SCHEMA_FOCUS.x - focus.x(),
            SCHEMA_FOCUS.y - focus.y(),
            SCHEMA_FOCUS.z - focus.z()
        )
        super.draw(context, theme)
    }
}
