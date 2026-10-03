@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.DynamicDrawable
import brachy.modularui.drawable.GuiDraw
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.ItemDrawable
import brachy.modularui.drawable.Rectangle
import brachy.modularui.drawable.UITexture
import brachy.modularui.integration.embeddium.SodiumCompat
import brachy.modularui.screen.viewport.GuiContext
import brachy.modularui.theme.WidgetTheme
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
import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
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

/** 小框尺寸、格边长、格间距、开关边长。 */
private const val BOX_WIDTH = 96
private const val BOX_HEIGHT = 142
private const val FACE_CELL_SIZE = 24
private const val CELL_GAP = 2
private const val TOGGLE_SIZE = 16

/** 面名缩写的落点：按 MC 字体里一个全角字约 9px 居中。 */
private const val CELL_LABEL_OFFSET = (FACE_CELL_SIZE - 9) / 2

/**
 * 小框相对**主内容区**（`MachineUIPanel.DEFAULT_CONTENT_WIDTH/HEIGHT`，固定 169×77）的像素偏移。
 *
 * ⚠️ 不用 `center()`：MUI 的 `horizontalCenter()`/`verticalCenter()` 字节码就是 `leftRel(0.5f)`/`topRel(0.5f)`，
 * **不减自身一半尺寸**，等于把框的左上角对到父级中心（整体偏右下半个框）。也不要拿相对定位去对齐
 * `coverChildren` 的父级（会和父级尺寸互相拉扯）。这里用已知尺寸算绝对像素。
 */
private val BOX_OFFSET_X: Int = (MachineUIPanel.DEFAULT_CONTENT_WIDTH - BOX_WIDTH) / 2
private const val BOX_OFFSET_Y = -6

/** 格子里的状态色蒙层 alpha 与描边宽度（贴图当前景太抢眼，状态得压得住）。 */
private const val STATE_TINT_ALPHA = 120
private const val FACE_CELL_BORDER = 3f

/** 取邻居面贴图用的固定随机源种子（模型烘焙要求传 RandomSource）。 */
private const val SPRITE_SEED = 42L

/**
 * 六个面槽在小框里的落位（槽, 列, 行）；左上角 (0,0) 刻意留空。
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

/** 只换 alpha 字节（颜色常量都是 `0xAARRGGBB`）。 */
private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/** 同步值里的 `Direction.ordinal` 还原；[NO_FACE] 或越界都返回 null。 */
private fun faceOf(ordinal: Int): Direction? {
    val faces = Direction.entries.toTypedArray()
    return if (ordinal < 0 || ordinal >= faces.size) null else faces[ordinal]
}

/**
 * 「输入输出配置小框」的装配点。
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

        val page = MachineIoConfigPage(machine, trait, syncManager, panel.mainContents)
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
 * 单格机器的输入输出配置小框（贴在主内容区上的浮动小块，默认隐藏），照 TE / Mek 那种六面图。
 *
 * 只用 GTM 真实支持的语义：物品输出面 / 流体输出面（各可为空，AutoOutputTrait.java:61-80）
 * + 自动输出开关 + 允许从输出面输入开关（:192-206、:184-190），
 * 不做「每面独立输入/输出/禁用」那种 GTM 没有的矩阵。
 *
 * 每格背景画的是**相邻方块朝机器那一面的贴图**（邻居自己的 `face.getOpposite()` 面），
 * 上面再叠状态色蒙层与描边。
 *
 * @author rain fox
 */
class MachineIoConfigPage(
    private val machine: MetaMachine,
    private val trait: AutoOutputTrait,
    private val syncManager: PanelSyncManager,
    /** 锚点：`MachineUIPanel` 的固定尺寸主内容区（169×77），用它算绝对像素位置。 */
    private val anchor: IWidget
) : ParentWidget<MachineIoConfigPage>() {

    private val itemSupported: Boolean = trait.supportsAutoOutputItems()
    private val fluidSupported: Boolean = trait.supportsAutoOutputFluids()

    // 四个开关 + 两条「输出面」各一个显式命名的 C2S 同步值（setter 只在服务端跑）。
    // ⚠️ 输出面**不走** registerSyncedAction/callSyncedAction：那条路实机点不动，见项目笔记 S1.8。
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
        size(BOX_WIDTH, BOX_HEIGHT)
        // 绝对像素定位（见 BOX_OFFSET_X 注释：center() 不减自身一半尺寸）
        relative(anchor).left(BOX_OFFSET_X).top(BOX_OFFSET_Y)
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
                .padding(5)
                .childPadding(3)
                .child(IoLabel(Text.lang(MachineIoConfigLang.TITLE)).widthRel(1f).height(10))
                .child(createFaceDiagram())
                .child(IoLabel(Supplier { statusText() }).widthRel(1f).height(20))
                .child(createToggles())
        )

        child(
            IoButton()
                .size(10)
                .right(3)
                .top(3)
                .overlay(GuiTextures.CLOSE)
                .onMousePressed { _, _ ->
                    setPageOpen(false)
                    true
                }
        )
    }

    /** 只切自己的显示；机器界面照旧在框外围可用。 */
    fun setPageOpen(open: Boolean) {
        isEnabled = open
    }

    // ======================== 六面图 ========================

    private fun createFaceDiagram(): IWidget {
        val box = IoBox().name("io_config_faces").size(GRID_WIDTH, GRID_HEIGHT)
        FACE_LAYOUT.forEach { (slot, col, row) -> box.child(faceCell(slot, col, row)) }
        return box
    }

    private fun faceCell(slot: FaceSlot, col: Int, row: Int): IWidget {
        val face = slotDirection(slot)
        val cell = IoButton()
            .size(FACE_CELL_SIZE)
            .pos(col * (FACE_CELL_SIZE + CELL_GAP), row * (FACE_CELL_SIZE + CELL_GAP))

        // 邻居贴图只在客户端取；服务端只铺状态色，两端 widget 树保持一致
        if (machine.level?.isClientSide == true) {
            cell.background(FaceCellIcon(face))
        } else {
            cell.background(DynamicDrawable(Supplier<IDrawable> { Rectangle().color(faceCellColor(face)).solid() }))
        }

        return cell
            // 面名用像素定位（同样不用 center()），子件一定画在背景那三层之上
            .child(
                IoLabel(Supplier { Text.lang(slot.shortKey) })
                    .pos(CELL_LABEL_OFFSET, CELL_LABEL_OFFSET)
                    .color(CELL_TEXT_COLOR)
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

    /** 底色读同步值（客户端拿到服务端值；自己点完那一刻是本地乐观值），所以点完立刻变色。 */
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
            IntConsumer { ordinal -> faceOf(ordinal)?.let(write) }
        ).allowC2S()
        syncManager.syncValue(key, value)
        return value
    }

    // ======================== 点面设 I/O ========================

    private fun setOutputFace(face: Direction, items: Boolean) {
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

    // ======================== 格子的邻居面贴图 ========================

    /**
     * 格子背景：先画相邻方块**朝机器那一面**（邻居自己的 `face.getOpposite()`）的贴图，
     * 再叠状态色蒙层 + 不透明粗描边，保证贴图与状态都看得清。
     *
     * 只在客户端构造（服务端不建，见 [faceCell]）。邻居方块换了才重建贴图，
     * 每帧只做一次 `getBlockState`（区块缓存，很便宜），不重复烘模型。
     */
    private inner class FaceCellIcon(private val face: Direction) : IDrawable {

        private var cachedBlock: Block? = null
        private var cachedIcon: IDrawable? = null

        override fun draw(context: GuiContext, x: Int, y: Int, w: Int, h: Int, theme: WidgetTheme) {
            neighbourIcon()?.draw(context, x, y, w, h, theme)

            val color = faceCellColor(face)
            Rectangle().color(tint(color, STATE_TINT_ALPHA)).solid().draw(context, x, y, w, h, theme)
            Rectangle().color(color).hollow(FACE_CELL_BORDER).draw(context, x, y, w, h, theme)
        }

        private fun neighbourIcon(): IDrawable? {
            val level = machine.level ?: return null
            val state = level.getBlockState(machine.blockPos.relative(face))
            if (state.isAir) return null
            if (state.block !== cachedBlock) {
                cachedBlock = state.block
                cachedIcon = buildIcon(state, face)
            }
            return cachedIcon
        }

        /**
         * 邻居朝机器的那一面 = 邻居自己的 `face.getOpposite()`；取不到就退回方块物品图标。
         *
         * 优先挑**正方形** sprite：GT 那种机壳模型的同一面会叠好几层 quad，其中 overlay 是
         * 16×96 的六帧竖排贴图，整条画进格子就会糊成一团。
         *
         * 三参 `getQuads` 在 1.21 被标了 Deprecated（NeoForge 另加了带 `ModelData`/`RenderType` 的重载），
         * 这里要的就是「不带 modelData 的默认外观」。
         */
        @Suppress("DEPRECATION")
        private fun buildIcon(state: BlockState, face: Direction): IDrawable? = try {
            val quads = Minecraft.getInstance().blockRenderer.getBlockModel(state)
                .getQuads(state, face.opposite, RandomSource.create(SPRITE_SEED))
            val sprite = quads.firstOrNull { q ->
                q.sprite.contents().let { c -> c.width() == c.height() }
            }?.sprite ?: quads.firstOrNull()?.sprite
            if (sprite != null) SpriteRegionDrawable(sprite) else itemIcon(state)
        } catch (t: Throwable) {
            itemIcon(state)
        }

        private fun itemIcon(state: BlockState): IDrawable? {
            val stack = ItemStack(state.block)
            return if (stack.isEmpty) null else ItemDrawable(stack)
        }
    }

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

/**
 * 按 sprite 的真实像素尺寸**等比、居中**画一格面贴图。
 *
 * MUI 现成的 `SpriteDrawable` → `GuiDraw.drawSprite` 用的是 `getU0/V0/U1/V1`（整张 sprite 的 UV）：
 * 对**真动画贴图**这是对的（MC 把当前帧原地写进该区域，画整张就等于画当前帧，会自己动），
 * 但 GT 那种 16×96 六帧竖排贴图会被整条压进格子里 —— 所以这里按 `contents()` 的高宽比切出**第一帧**。
 */
private class SpriteRegionDrawable(private val sprite: TextureAtlasSprite) : IDrawable {

    /** 竖排多帧时只画第一帧的 V 范围；`width`/`height` 就是单帧尺寸。 */
    private val vFraction: Float
    private val frameWidth: Int
    private val frameHeight: Int

    init {
        val contents = sprite.contents()
        val width = contents.width().coerceAtLeast(1)
        val height = contents.height().coerceAtLeast(1)
        val frames = if (height % width == 0) (height / width).coerceAtLeast(1) else 1
        vFraction = 1f / frames
        frameWidth = width
        frameHeight = height / frames
    }

    override fun draw(context: GuiContext, x: Int, y: Int, w: Int, h: Int, theme: WidgetTheme) {
        // MC/Sodium 只给「活跃」的 sprite 走动画帧（MUI 自己在 schema 渲染里也这么干）
        SodiumCompat.markSpritesAsActive(listOf(sprite))

        // 等比缩放 + 居中，不拉伸
        val scale = minOf(w.toFloat() / frameWidth, h.toFloat() / frameHeight)
        val drawWidth = frameWidth * scale
        val drawHeight = frameHeight * scale
        val x1 = x + (w - drawWidth) / 2f
        val y1 = y + (h - drawHeight) / 2f

        RenderSystem.enableBlend()
        RenderSystem.setShaderTexture(0, sprite.atlasLocation())
        GuiDraw.drawTexture(
            context.lastGraphicsPose,
            x1, y1, x1 + drawWidth, y1 + drawHeight,
            sprite.getU(0f), sprite.getV(0f), sprite.getU(1f), sprite.getV(vFraction)
        )
        RenderSystem.disableBlend()
    }
}
