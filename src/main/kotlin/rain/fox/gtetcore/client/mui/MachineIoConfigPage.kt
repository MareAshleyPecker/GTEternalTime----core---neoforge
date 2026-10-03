@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.DynamicDrawable
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.Rectangle
import brachy.modularui.drawable.UITexture
import brachy.modularui.value.BoolValue
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.IntSyncValue
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
import net.minecraft.network.chat.Component
import org.apache.logging.log4j.Level as LogLevel
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.data.lang.MachineIoConfigLang
import java.util.function.BooleanSupplier
import java.util.function.IntConsumer
import java.util.function.IntSupplier
import java.util.function.Supplier

/** 六个同步值键；显式命名，免得跟 GTM 塞在右侧配置列里的那几个匿名同名值撞键。 */
private const val SYNC_ITEM_FACE = "gtetscore_io_item_face"
private const val SYNC_FLUID_FACE = "gtetscore_io_fluid_face"
private const val SYNC_AUTO_ITEM = "gtetscore_io_auto_item"
private const val SYNC_AUTO_FLUID = "gtetscore_io_auto_fluid"
private const val SYNC_ALLOW_IN_ITEM = "gtetscore_io_allow_in_item"
private const val SYNC_ALLOW_IN_FLUID = "gtetscore_io_allow_in_fluid"

/** 「没有输出面」在同步值里的编码（`Direction.ordinal` 用不到它）。 */
private const val NO_FACE = -1

/** 六面图：格边长、格间距、开关边长。 */
private const val FACE_CELL_SIZE = 26
private const val CELL_GAP = 2
private const val TOGGLE_SIZE = 18

/**
 * 六个面槽在六面图里的落位（槽, 列, 行）；左上角 (0,0) 刻意留空。
 *
 * ```
 *   [空]  [顶]
 *   [左]  [正]  [右]
 *   [后]  [底]
 * ```
 *
 * ⚠️ 显式写槽位常量，**绝不**拿列表下标当协议：`Direction` 的 ordinal 顺序是
 * DOWN, UP, NORTH, SOUTH, WEST, EAST，跟这里的显示顺序毫无关系。
 */
private val FACE_LAYOUT: List<Triple<FaceSlot, Int, Int>> = listOf(
    Triple(FaceSlot.TOP, 1, 0),
    Triple(FaceSlot.LEFT, 0, 1),
    Triple(FaceSlot.FRONT, 1, 1),
    Triple(FaceSlot.RIGHT, 2, 1),
    Triple(FaceSlot.BACK, 0, 2),
    Triple(FaceSlot.BOTTOM, 1, 2)
)

private val FACE_SLOT_ORDER: List<FaceSlot> = listOf(
    FaceSlot.FRONT, FaceSlot.BACK, FaceSlot.TOP, FaceSlot.BOTTOM, FaceSlot.LEFT, FaceSlot.RIGHT
)

private const val GRID_WIDTH = 3 * FACE_CELL_SIZE + 2 * CELL_GAP
private const val GRID_HEIGHT = 3 * FACE_CELL_SIZE + 2 * CELL_GAP

/**
 * 颜色一律写成 `0xAARRGGBB` 字面量。
 *
 * ⚠️ MUI 的 `Color.argb(r, g, b, a)` 参数序是 **(r, g, b, a)**（字节码把第 4 个参数左移 24 位当 alpha），
 * 而 `Color.rgba(a, r, g, b)` 才是 alpha 在前 —— 两个名字跟参数序是反的。写成 `Color.argb(255, r, g, b)`
 * 会得到一块 r=255 的亮品红（并且 alpha 取到了 b 的值，半透明），别再碰那两个重载。
 */
private val BACKDROP_UNDERLAY: Int = 0xFF181A1E.toInt()
private val ITEM_FACE_COLOR: Int = 0xFF3CDC5A.toInt()
private val FLUID_FACE_COLOR: Int = 0xFF46AAFF.toInt()
private val BOTH_FACE_COLOR: Int = 0xFF3CC8AA.toInt()
private val IDLE_FACE_COLOR: Int = 0xFF3A3E46.toInt()
private val CELL_TEXT_COLOR: Int = 0xFFFFFFFF.toInt()

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

    // 四个开关 + 两条「输出面」各一个显式命名的 C2S 同步值（setter 只在服务端跑）。
    // ⚠️ 输出面**不再走** registerSyncedAction/callSyncedAction：那条路实机点不动（见 S1.8 报告），
    //    这里改用 MUI 通用的同步值通道，和下面四个开关同一条路。
    private val itemFaceSync = faceSync(SYNC_ITEM_FACE, { trait.itemOutputDirection },
        { trait.setItemOutputDirection(it) })
    private val fluidFaceSync = faceSync(SYNC_FLUID_FACE, { trait.fluidOutputDirection },
        { trait.setFluidOutputDirection(it) })

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
        FACE_LAYOUT.forEach { (slot, col, row) -> box.child(faceCell(slot, col, row)) }
        return box
    }

    private fun faceCell(slot: FaceSlot, col: Int, row: Int): IWidget {
        val face = slotDirection(slot)
        return IoButton()
            .size(FACE_CELL_SIZE)
            .pos(col * (FACE_CELL_SIZE + CELL_GAP), row * (FACE_CELL_SIZE + CELL_GAP))
            .background(DynamicDrawable(Supplier<IDrawable> { Rectangle().color(faceCellColor(face)).solid() }))
            .child(
                IoLabel(Supplier { Text.lang(slot.shortKey) })
                    .center().color(CELL_TEXT_COLOR)
            )
            .tooltipAutoUpdate(true)
            .tooltipBuilder { tip ->
                tip.addLine(Text.lang(slot.langKey))
                if (isFrontFace(face)) tip.addLine(Text.lang(MachineIoConfigLang.FACE_CELL_FRONT))
                tip.addLine(Text.lang(MachineIoConfigLang.FACE_CELL_TIP))
            }
            .onMousePressed { _, button ->
                when (button) {
                    0 -> setOutputFace(face, true)
                    1 -> setOutputFace(face, false)
                }
                true
            }
    }

    /** 底色读的是同步值（客户端拿到的是服务端值；自己点完那一刻是本地乐观值），所以点完立刻变色。 */
    private fun faceCellColor(face: Direction): Int {
        val items = itemSupported && itemFaceSync.intValue == face.ordinal
        val fluids = fluidSupported && fluidFaceSync.intValue == face.ordinal
        return when {
            items && fluids -> BOTH_FACE_COLOR
            items -> ITEM_FACE_COLOR
            fluids -> FLUID_FACE_COLOR
            else -> IDLE_FACE_COLOR
        }
    }

    /** 正面不能设成输出面：`setItemOutputDirection` 会直接 return（AutoOutputTrait.java:221-222）。 */
    private fun isFrontFace(face: Direction): Boolean = machine.hasFrontFacing() && machine.frontFacing == face

    /**
     * 面槽 → 实际 `Direction`。
     *
     * **左 / 右约定：站在机器正前方、面向机器时，玩家的左手边 = 左面。**
     * 水平朝向下即 `左 = front.getClockWise()`、`右 = front.getCounterClockWise()`
     * （MC 的 clockWise 是俯视 +Y 顺时针：NORTH→EAST→SOUTH→WEST）。
     * 万一用户说左右反了，只改这一处即可。
     *
     * 机器没有朝向（`hasFrontFacing() == false`）时，退回以 `Direction.NORTH` 当基准正面；
     * 朝向本身是竖直方向（UP / DOWN）时，左 / 右退回固定的 EAST / WEST。
     */
    private fun slotDirection(slot: FaceSlot): Direction {
        val front = if (machine.hasFrontFacing()) machine.frontFacing else Direction.NORTH
        val horizontal = front.axis != Direction.Axis.Y
        return when (slot) {
            FaceSlot.FRONT -> front
            FaceSlot.BACK -> front.opposite
            FaceSlot.TOP -> Direction.UP
            FaceSlot.BOTTOM -> Direction.DOWN
            FaceSlot.LEFT -> if (horizontal) front.clockWise else Direction.EAST
            FaceSlot.RIGHT -> if (horizontal) front.counterClockWise else Direction.WEST
        }
    }

    /** 绝对方位 → 相对机器朝向的面名；正面先查，避免朝向竖直时正面与顶 / 底重合产生歧义。 */
    private fun relativeName(direction: Direction?): Component {
        if (direction == null) return Text.lang(MachineIoConfigLang.FACE_NONE)
        for (slot in FACE_SLOT_ORDER) {
            if (slotDirection(slot) == direction) return Text.lang(slot.langKey)
        }
        return Text.lang(MachineIoConfigLang.FACE_NONE)
    }

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

    /**
     * 输出面用的 C2S 同步值：`Direction.ordinal` 编进去，没有输出面时用 [NO_FACE]。
     *
     * `.allowC2S()` 的含义是 setter **只在服务端**跑（客户端点一下只是把本地值发过去），
     * 服务端 `setItemOutputDirection / setFluidOutputDirection` 的校验（正面、validator）照旧生效，
     * 被拒时下一次同步会把客户端的乐观值改回来。
     */
    private fun faceSync(key: String, read: () -> Direction?, write: (Direction) -> Unit): IntSyncValue {
        // ⚠️ 同样要显式写 SAM 类型，否则撞上 (IntSupplier, IntSupplier) 的重载
        val value = IntSyncValue(
            IntSupplier { read()?.ordinal ?: NO_FACE },
            IntConsumer { ordinal ->
                // TODO(临时诊断 S1.8)：确认服务端有没有收到这个同步值之后删掉
                GTETSCore.LOGGER.log(LogLevel.INFO, "[GTET-TEST] io page server apply: key={} ordinal={}", key, ordinal)
                faceOf(ordinal)?.let(write)
            }
        ).allowC2S()
        syncManager.syncValue(key, value)
        return value
    }

    // ======================== 点面设 I/O ========================

    private fun setOutputFace(face: Direction, items: Boolean) {
        // TODO(临时诊断 S1.8)：确认「点面」到底有没有送到这里之后删掉
        GTETSCore.LOGGER.log(LogLevel.INFO, "[GTET-TEST] io page click: face={} items={}", face, items)
        if (items) {
            if (itemSupported) itemFaceSync.intValue = face.ordinal
        } else {
            if (fluidSupported) fluidFaceSync.intValue = face.ordinal
        }
    }

    // ======================== 状态行 ========================

    private fun statusText(): Component {
        val lines = ArrayList<Component>(2)
        if (itemSupported) {
            lines.add(
                Component.translatable(
                    MachineIoConfigLang.FACE_ITEM,
                    directionName(faceOf(itemFaceSync.intValue))
                )
            )
        }
        if (fluidSupported) {
            lines.add(
                Component.translatable(
                    MachineIoConfigLang.FACE_FLUID,
                    directionName(faceOf(fluidFaceSync.intValue))
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

    private fun directionName(direction: Direction?): Component = relativeName(direction)

    // ======================== 自引用泛型的控件壳 ========================
    // MUI 这几只控件是 `Foo<W extends Foo<W>>`，Kotlin 里没法用菱形推断，链条会退回父类型。

    private class IoBox : ParentWidget<IoBox>()

    private class IoButton : ButtonWidget<IoButton>()

    private class IoLabel : TextWidget<IoLabel> {

        constructor(text: Component) : super(text)
        constructor(text: Supplier<Component>) : super(text)
    }
}

/** 六面图的面槽（相对机器朝向），自带面名与格内缩写两个语言键。 */
private enum class FaceSlot(val langKey: String, val shortKey: String) {
    FRONT(MachineIoConfigLang.REL_FRONT, MachineIoConfigLang.REL_FRONT_SHORT),
    BACK(MachineIoConfigLang.REL_BACK, MachineIoConfigLang.REL_BACK_SHORT),
    TOP(MachineIoConfigLang.REL_TOP, MachineIoConfigLang.REL_TOP_SHORT),
    BOTTOM(MachineIoConfigLang.REL_BOTTOM, MachineIoConfigLang.REL_BOTTOM_SHORT),
    LEFT(MachineIoConfigLang.REL_LEFT, MachineIoConfigLang.REL_LEFT_SHORT),
    RIGHT(MachineIoConfigLang.REL_RIGHT, MachineIoConfigLang.REL_RIGHT_SHORT)
}

/** 同步值里的 `Direction.ordinal` 还原；[NO_FACE] 或越界都返回 null。 */
private fun faceOf(ordinal: Int): Direction? {
    val faces = Direction.entries.toTypedArray()
    return if (ordinal < 0 || ordinal >= faces.size) null else faces[ordinal]
}
