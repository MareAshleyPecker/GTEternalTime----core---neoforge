@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.IPacketWriter
import brachy.modularui.api.ISyncedAction
import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IGuiAction
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.Rectangle
import brachy.modularui.drawable.schema.BlockHighlight
import brachy.modularui.drawable.schema.ISchema
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
import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.renderer.MultiBufferSource
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

/** 3D 配置页的同步动作名（`PanelSyncManager.registerSyncedAction` 的键，加命名空间避免与 GTM 撞）。 */
private const val ACTION_ITEM = "gtetscore:io_config_item"
private const val ACTION_FLUID = "gtetscore:io_config_fluid"

/** 假 schema 里单格方块的坐标。 */
private val SCHEMA_ORIGIN: BlockPos = BlockPos.ZERO

/** 方块的几何中心；`SchemaWidget.draw` 每帧拿它当相机 lookAt（见 [MachineSchema]）。 */
private val SCHEMA_FOCUS: Vector3f = Vector3f(0.5f, 0.5f, 0.5f)

/** `SchemaWidget.scale` 就是 `Camera.setLookAtAndAngle` 的第 4 个参数 dist（单位：格）。 */
private const val SCHEMA_DISTANCE = 2.0f
private const val SCHEMA_YAW = 0.7853982f
private const val HIGHLIGHT_THICKNESS = 1f / 32f

/**
 * 输出面标记的边框宽度（格）。
 *
 * ⚠️ 必须配 `BlockHighlight(color, allSides, thickness)` 这个三参构造器用：`BlockHighlight(color, thickness)`
 * 那个重载的字节码是 `this(color, true, thickness)` —— `allSides` 被写死成 **true**，doRender 会把 direction
 * 置 null 从而把**六个面**框一圈，既看不出是哪个面、1/32 的细边框在整流机器模型上也几乎看不见。
 */
private const val FACE_MARKER_THICKNESS = 1f / 8f

private val BACKDROP_UNDERLAY: Int = Color.argb(255, 24, 26, 30)
private val ITEM_FACE_COLOR: Int = Color.argb(255, 60, 220, 90)
private val FLUID_FACE_COLOR: Int = Color.argb(255, 70, 170, 255)
private val HOVER_FACE_COLOR: Int = Color.argb(160, 255, 255, 255)

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
 * 单格机器的 3D 输入输出配置页（覆盖整个机器面板，默认隐藏）。
 *
 * 3D 积木：GTMultiblockSchemaRenderer（GTMultiblockSchemaRenderer.java:10）+ MapSchema
 * （brachy.modularui.drawable.schema.MapSchema）+ SchemaWidget；面点击读数走
 * `BaseSchemaRenderer#lastRayTrace`，先例 MultiblockPreviewWidget.java:111-123。
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
        val renderer = IoFaceSchemaRenderer(MachineSchema(level.getBlockState(machine.blockPos))) {
            currentFaceMarkers()
        }
        renderer.highlightRenderer(BlockHighlight(HOVER_FACE_COLOR, HIGHLIGHT_THICKNESS))

        // BaseSchemaRenderer.schema() 返回的就是构造时传进去的那个实例（javap：schema 字段只在构造器赋值、
        // getter 直接返回字段），所以 SchemaWidget.draw 每帧读到的 getFocus() 就是 MachineSchema 的覆盖值。
        val schemaFocus = renderer.schema().getFocus()

        return renderer.asWidget()
            .name("io_config_schema")
            .sizeRel(1f)
            .scale(SCHEMA_DISTANCE)
            .yaw(SCHEMA_YAW)
            .enableDragRotation(true)
            .enableScrollScaling(true)
            // SchemaWidget.draw 每帧拿 `schema.getFocus() + offset` 当相机 lookAt；把差值补掉，
            // 保证 lookAt 恒为方块几何中心 (0.5, 0.5, 0.5)
            .offset(
                SCHEMA_FOCUS.x - schemaFocus.x(),
                SCHEMA_FOCUS.y - schemaFocus.y(),
                SCHEMA_FOCUS.z - schemaFocus.z()
            )
            // 中键拖动改的就是上面这个 offset（SchemaWidget.onMouseDrag 的 button == 2 分支），必须关掉
            .enableDragTranslation(false)
            .listenGuiAction(IGuiAction.MouseReleased { _, button -> onFaceClicked(renderer, button) })
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r ->
                r.addLine(Text.lang(MachineIoConfigLang.HINT_ITEM))
                r.addLine(Text.lang(MachineIoConfigLang.HINT_FLUID))
            }
    }

    /** 该高亮的两个面；客户端读同步下来的 trait 字段，服务端不建 3D 也不会走到这里。 */
    private fun currentFaceMarkers(): List<Pair<Direction, Int>> {
        val markers = ArrayList<Pair<Direction, Int>>(2)
        if (itemSupported) trait.itemOutputDirection?.let { markers.add(it to ITEM_FACE_COLOR) }
        if (fluidSupported) trait.fluidOutputDirection?.let { markers.add(it to FLUID_FACE_COLOR) }
        return markers
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
 * 在 3D 场景里画当前物品 / 流体输出面的面高亮。
 *
 * 钩子点选 `renderWorld`：此刻 `setupCamera` 已设好投影、`resetCamera` 还没跑
 * （`BaseSchemaRenderer.draw` 的顺序是 setupCamera → renderWorld → raytrace → resetCamera），
 * hover 高亮走的也是同一套 `createWorldRenderPose()` + `camera().pos()`（SchemaRenderer.onSuccessfulRayTrace）。
 */
private class IoFaceSchemaRenderer(
    schema: ISchema,
    private val faceMarkers: () -> List<Pair<Direction, Int>>
) : GTMultiblockSchemaRenderer(schema) {

    override fun renderWorld(bufferSource: MultiBufferSource.BufferSource, partialTick: Float) {
        super.renderWorld(bufferSource, partialTick)

        val markers = faceMarkers()
        if (markers.isEmpty()) return

        val pose = createWorldRenderPose()
        val cameraPos = camera().pos()
        markers.forEach { (face, color) ->
            // allSides = false：只在指定的那个面画框（true 会六个面都框一圈，看不出是哪个面）
            BlockHighlight(color, false, FACE_MARKER_THICKNESS)
                .renderHighlight(pose, SCHEMA_ORIGIN, face, cameraPos)
        }

        // BlockHighlight 只开了 blend、关了深度测试，画完还原，别污染后续 UI
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.enableDepthTest()
    }
}
