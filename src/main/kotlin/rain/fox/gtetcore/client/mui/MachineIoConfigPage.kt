@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.IPacketWriter
import brachy.modularui.api.ISyncedAction
import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.DynamicDrawable
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.Rectangle
import brachy.modularui.drawable.UITexture
import brachy.modularui.utils.Color
import brachy.modularui.value.BoolValue
import brachy.modularui.value.sync.BooleanSyncValue
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
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.data.lang.MachineIoConfigLang
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/** 配置页的同步动作名（`PanelSyncManager.registerSyncedAction` 的键，加命名空间避免与 GTM 撞）。 */
private const val ACTION_ITEM = "gtetscore:io_config_item"
private const val ACTION_FLUID = "gtetscore:io_config_fluid"

/** 四个开关的同步值键；显式命名，免得跟 GTM 塞在右侧配置列里的那几个匿名同名值撞键。 */
private const val SYNC_AUTO_ITEM = "gtetscore_io_auto_item"
private const val SYNC_AUTO_FLUID = "gtetscore_io_auto_fluid"
private const val SYNC_ALLOW_IN_ITEM = "gtetscore_io_allow_in_item"
private const val SYNC_ALLOW_IN_FLUID = "gtetscore_io_allow_in_fluid"

/** 六面图：格边长、格间距、开关边长。 */
private const val FACE_CELL_SIZE = 26
private const val CELL_GAP = 2
private const val TOGGLE_SIZE = 18

/**
 * 六个面在展开图里的落位（面, 列, 行）。
 *
 * ⚠️ 显式写 `Direction` 常量，**绝不**拿列表下标当协议：`Direction` 的 ordinal 顺序是
 * DOWN, UP, NORTH, SOUTH, WEST, EAST，跟这里的显示顺序毫无关系。
 */
private val FACE_LAYOUT: List<Triple<Direction, Int, Int>> = listOf(
    Triple(Direction.UP, 1, 0),
    Triple(Direction.WEST, 0, 1),
    Triple(Direction.NORTH, 1, 1),
    Triple(Direction.EAST, 2, 1),
    Triple(Direction.SOUTH, 3, 1),
    Triple(Direction.DOWN, 1, 2)
)

private const val GRID_WIDTH = 4 * FACE_CELL_SIZE + 3 * CELL_GAP
private const val GRID_HEIGHT = 3 * FACE_CELL_SIZE + 2 * CELL_GAP

private val BACKDROP_UNDERLAY: Int = Color.argb(255, 24, 26, 30)
private val ITEM_FACE_COLOR: Int = Color.argb(255, 60, 220, 90)
private val FLUID_FACE_COLOR: Int = Color.argb(255, 70, 170, 255)
private val BOTH_FACE_COLOR: Int = Color.argb(255, 60, 200, 170)
private val IDLE_FACE_COLOR: Int = Color.argb(255, 58, 62, 70)
private val CELL_TEXT_COLOR: Int = Color.argb(255, 255, 255, 255)

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
 * 单格机器的输入输出配置页（覆盖整个机器面板，默认隐藏），照 TE / Mek 那种六面图。
 *
 * 只用 GTM 真实支持的语义：物品输出面 / 流体输出面（各可为空，AutoOutputTrait.java:61-80）
 * + 自动输出开关 + 允许从输出面输入开关（:192-206、:184-190），
 * 不做「每面独立输入/输出/禁用」那种 GTM 没有的矩阵。
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

    // 四个开关各一个显式命名的 C2S 同步值（setter 只在服务端跑）
    private val autoItemSync = boolSync(SYNC_AUTO_ITEM, { trait.isAutoOutputItems() },
        { trait.setAllowAutoOutputItems(it) })
    private val autoFluidSync = boolSync(SYNC_AUTO_FLUID, { trait.isAutoOutputFluids() },
        { trait.setAllowAutoOutputFluids(it) })
    private val allowInItemSync = boolSync(SYNC_ALLOW_IN_ITEM, { trait.allowsItemInputFromOutputSide() },
        { trait.setAllowItemInputFromOutputSide(it) })
    private val allowInFluidSync = boolSync(SYNC_ALLOW_IN_FLUID, { trait.allowsFluidInputFromOutputSide() },
        { trait.setAllowFluidInputFromOutputSide(it) })

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
                .child(createFaceDiagram())
                .child(IoLabel(Supplier { statusText() }).widthRel(1f).height(20))
                .child(createToggles())
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

    // ======================== 六面图 ========================

    private fun createFaceDiagram(): IWidget {
        val box = IoBox().name("io_config_faces").size(GRID_WIDTH, GRID_HEIGHT)
        FACE_LAYOUT.forEach { (face, col, row) -> box.child(faceCell(face, col, row)) }
        return box
    }

    private fun faceCell(face: Direction, col: Int, row: Int): IWidget =
        IoButton()
            .size(FACE_CELL_SIZE)
            .pos(col * (FACE_CELL_SIZE + CELL_GAP), row * (FACE_CELL_SIZE + CELL_GAP))
            .background(DynamicDrawable(Supplier<IDrawable> { Rectangle().color(faceCellColor(face)).solid() }))
            .child(
                IoLabel(Supplier { Text.lang(MachineIoConfigLang.shortFaceKey(face)) })
                    .center().color(CELL_TEXT_COLOR)
            )
            .tooltipAutoUpdate(true)
            .tooltipBuilder { tip ->
                tip.addLine(Text.lang(MachineIoConfigLang.faceKey(face)))
                if (isFrontFace(face)) tip.addLine(Text.lang(MachineIoConfigLang.FACE_CELL_FRONT))
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

    /** 正面不能设成输出面：`setItemOutputDirection` 会直接 return（AutoOutputTrait.java:221-222）。 */
    private fun isFrontFace(face: Direction): Boolean = machine.hasFrontFacing() && machine.frontFacing == face

    // ======================== 四个开关 ========================

    private fun createToggles(): IWidget {
        val toggles = ArrayList<IWidget>(4)
        if (itemSupported) {
            toggles.add(
                toggleButton(autoItemSync, GTGuiTextures.BUTTON_ITEM_OUTPUT,
                    Supplier { onOffLine(MachineIoConfigLang.TOGGLE_AUTO_ITEM, autoItemSync.boolValue) })
            )
            toggles.add(
                toggleButton(allowInItemSync, GTGuiTextures.BUTTON_ITEM_ALLOW_INPUT_OUTPUT,
                    Supplier { onOffLine(MachineIoConfigLang.TOGGLE_ALLOW_IN_ITEM, allowInItemSync.boolValue) })
            )
        }
        if (fluidSupported) {
            toggles.add(
                toggleButton(autoFluidSync, GTGuiTextures.BUTTON_FLUID_OUTPUT,
                    Supplier { onOffLine(MachineIoConfigLang.TOGGLE_AUTO_FLUID, autoFluidSync.boolValue) })
            )
            toggles.add(
                toggleButton(allowInFluidSync, GTGuiTextures.BUTTON_FLUID_ALLOW_INPUT_OUTPUT,
                    Supplier { onOffLine(MachineIoConfigLang.TOGGLE_ALLOW_IN_FLUID, allowInFluidSync.boolValue) })
            )
        }

        val width = toggles.size * TOGGLE_SIZE + (toggles.size - 1).coerceAtLeast(0) * CELL_GAP
        val row = Flow.row().name("io_config_toggles").size(width, TOGGLE_SIZE).childPadding(CELL_GAP)
        toggles.forEach { row.child(it) }
        return row
    }

    private fun toggleButton(value: BooleanSyncValue, texture: UITexture, tip: Supplier<Component>): IWidget =
        ToggleButton()
            .size(TOGGLE_SIZE)
            .value(value)
            .overlay(texture)
            .tooltipAutoUpdate(true)
            .tooltipDynamic { r -> r.addLine(tip.get()) }

    private fun onOffLine(key: String, on: Boolean): Component = Component.translatable(
        key, Text.lang(if (on) MachineIoConfigLang.STATE_ON else MachineIoConfigLang.STATE_OFF)
    )

    private fun boolSync(key: String, read: () -> Boolean, write: (Boolean) -> Unit): BooleanSyncValue {
        // ⚠️ Kotlin 直接写 lambda 会撞上 (BooleanSupplier, BooleanConsumer) 的重载歧义，要显式写 SAM 类型
        val value = BooleanSyncValue(BooleanSupplier { read() }, BooleanConsumer { updated -> write(updated) })
            .allowC2S()
        syncManager.syncValue(key, value)
        return value
    }

    // ======================== 点面设 I/O ========================

    private fun sendDirection(face: Direction, items: Boolean): Boolean {
        if (items) {
            if (!itemSupported) return false
            // 传的是 `Direction.ordinal`（枚举协议序），不是六面图的显示下标
            syncManager.callSyncedAction(ACTION_ITEM,
                IPacketWriter<RegistryFriendlyByteBuf> { buf -> buf.writeVarInt(face.ordinal) })
        } else {
            if (!fluidSupported) return false
            syncManager.callSyncedAction(ACTION_FLUID,
                IPacketWriter<RegistryFriendlyByteBuf> { buf -> buf.writeVarInt(face.ordinal) })
        }
        return true
    }

    /** 服务端执行体；方向与开关都落在 trait 自己的同步字段上（AutoOutputTrait.java:61-91）。 */
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
