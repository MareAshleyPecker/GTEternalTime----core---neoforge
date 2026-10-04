@file:Suppress("RemoveExplicitTypeArguments", "RedundantSamConstructor")

package rain.fox.gtetcore.client.mui

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IPositioned
import brachy.modularui.api.widget.IWidget
import brachy.modularui.api.widget.Interactable
import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.screen.ModularPanel
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.FluidSlotSyncHandler
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.ItemSlotSyncHandler
import brachy.modularui.value.sync.LongSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.value.sync.PhantomItemSlotSyncHandler
import brachy.modularui.value.sync.StringSyncValue
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.ListWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.slot.FluidSlot
import brachy.modularui.widgets.slot.ItemSlot
import brachy.modularui.widgets.slot.ModularSlot
import brachy.modularui.widgets.slot.PhantomItemSlot
import brachy.modularui.widgets.textfield.TextFieldWidget
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper
import rain.fox.gtetcore.common.item.recipe.RecipeCodeWriter
import rain.fox.gtetcore.common.item.recipe.RecipeDraft
import rain.fox.gtetcore.common.item.recipe.RecipeEditorBehavior
import rain.fox.gtetcore.common.item.recipe.RecipeEditorData
import rain.fox.gtetcore.common.item.recipe.VoltageTiers
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.RecipeEditorLang
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.IntSupplier
import java.util.function.LongConsumer
import java.util.function.LongSupplier
import java.util.function.Supplier

/** 代码预览的文字颜色：不透明黑（代码区坐在 GT 浅色面板底纹上，黑字对比度够）。 */
private const val CODE_TEXT_COLOR: Int = 0xFF000000.toInt()

/** 面板尺寸（与老 LDLib 界面一致）。 */
private const val WIDTH = 462
private const val HEIGHT = 300

/** 面板名：MUI 拿它当同步命名空间。 */
private const val PANEL_NAME = "recipe_editor"

// ── 幽灵槽区布局（页 1）── 起始 y 见 SLOT_TOP；每段的具体坐标由 relayout() 摆。

/** 槽区左边距。 */
private const val SLOT_LEFT = 8

/** 槽区顶边（第一段标题的 y）。 */
private const val SLOT_TOP = 74

/** 每段标题占的高度。 */
private const val LABEL_H = 12

/** 物品槽：一行 9 个、行距 18（槽本身 18×18，正好无缝）。 */
private const val ITEM_COLS = 9
private const val ITEM_PITCH = 18

/** 流体槽：一行 8 个、行距 20（槽 18×18 留 2px 缝，免得相邻两罐的液面贴在一起看不出分界）。 */
private const val FLUID_COLS = 8
private const val FLUID_PITCH = 20

/** 电压快捷弹层：一行 4 个档（横 4 × 46 = 184 ≤ 190），行距 14。 */
private const val TIER_COLS = 4
private const val TIER_PICKER_W = 190
private const val TIER_PICKER_H = 120

/** 玩家物品栏（3×9 + 快捷栏）的落点，避开槽区与按钮行。 */
private const val PLAYER_INV_X = 286
private const val PLAYER_INV_Y = 120
private const val PLAYER_INV_W = 162
private const val PLAYER_INV_H = 76

/**
 * 数量编辑行的 y。
 *
 * 老工程是「中键弹对话框」，MUI 版改成面板内联一行（理由见 [RecipeEditorPanel] 的类注释）。
 * 槽区最坏排到 y=216（16 物品输入 2 行 + 8 流体输入 1 行 + 9 物品输出 1 行 + 8 流体输出 1 行），
 * 底部按钮在 270，所以 244 这一行不会和任何东西重叠。
 */
private const val COUNT_ROW_Y = 244

/** 底部按钮行。 */
private const val BUTTON_ROW_Y = 270
private const val BUTTON_H = 18

/** 代码预览最多画多少行（老界面的 `for (line in 0 until 80)`）。 */
private const val CODE_ROWS = 80

/** 数量上限 2147483647；与物品自己的堆叠上限无关（GT 配方里上千个输入很常见）。 */
private const val MAX_COUNT: Int = Int.MAX_VALUE

/** 四个槽区（数量编辑时用来定位）。 */
private const val SECTION_ITEM_IN = 0
private const val SECTION_FLUID_IN = 1
private const val SECTION_ITEM_OUT = 2
private const val SECTION_FLUID_OUT = 3

/** 没有正在编辑的槽。 */
private const val NO_TARGET: Long = -1L

/** 中键（GLFW 的 `GLFW_MOUSE_BUTTON_MIDDLE`）。 */
private const val MIDDLE_BUTTON: Int = 2

/**
 * 「配方编辑器」面板（MUI 版）。
 *
 * 老工程这块是 LDLib 1.x 的 `IItemUIFactory#createUI`：`ModularUI(462×300)` + 三个
 * `WidgetGroup` 页（顶部按钮切可见性）+ 一片幽灵槽 + `DraggableScrollableWidgetGroup` 的
 * 类型表 / 代码表。MUI 里逐项对应关系：
 *
 * | 老工程（LDLib 1.x） | MUI 3.3.1 | 说明 |
 * |---|---|---|
 * | `ModularUI(w,h,holder,player)` | `ModularPanel` + `IItemUIHolder#buildUI` | 入口从 `IItemUIFactory` 换成 `com.gregtechceu.gtceu.api.mui.IItemUIHolder` |
 * | `WidgetGroup`（页容器）+ `isVisible` | `ParentWidget` + `setEnabledIf` | 未启用的控件 MUI 整棵子树都不画 |
 * | `LabelWidget(Supplier<String>)` | `TextWidget(Supplier<Component>)` | 文案改走 `Component.translatable` |
 * | `ButtonWidget(x,y,w,h,textureGroup)` | `ButtonWidget.pos().size().overlay()` | |
 * | `TextFieldWidget` | `TextFieldWidget.value(sync)` | 值直接绑同步值 |
 * | `IntInputWidget` / `LongInputWidget` | `TextFieldWidget.setNumbers()/.setNumbersLong()` | MUI 没有独立的数字框控件 |
 * | `PhantomSlotWidget` | `PhantomItemSlot` + `PhantomItemSlotSyncHandler` | |
 * | `PhantomFluidWidget` | `FluidSlot` + `FluidSlotSyncHandler.phantom(true)` | |
 * | `PhantomCountSlotWidget`（中键弹对话框） | `CountItemSlot` + 面板内联数量行 | 见下 |
 * | `FluidCountSlotWidget` | `CountFluidSlot` + 同一行 | 见下 |
 * | `DraggableScrollableWidgetGroup` | `ListWidget` | |
 * | `PlayerInventoryWidget` | 手搓 3×9 + 快捷栏的 `ItemSlot` | MUI 没有现成的玩家背包控件 |
 *
 * ## ⚠️ 面板树在服务端与客户端各建一次
 *
 * MUI 会在服务端也跑一遍 `buildUI`（为了登记同步值），所以树结构只能由「两端一样」的数据决定，
 * 任何会变的量（种类 / 配方 id / 时长 / 耗电 / 档位 / 电路 / 各段槽数 / 代码文本）**一律走同步值**：
 * 服务端读草稿 → 推给客户端。客户端不写草稿（所有 setter 都判端）。
 *
 * ## ⚠️ 哪些同步值要手动登记、哪些不要
 *
 * MUI 的 `WidgetTree.collectSyncValues` 会遍历控件树，把**挂在控件上**的同步值
 * （`widget.value(sync)` / `widget.syncHandler(sync)`，即 `ISynced#isSynced` 为真的那些）
 * 自动登记进 `PanelSyncManager`；手动再登记一次会撞键。反过来，没挂到任何控件上的值
 * （本面板的 kind / gt_type / tier / circuit / code / count_target / 四个动作信号）
 * **必须**自己 `syncManager.syncValue(key, value)`，否则永远同步不了。
 *
 * ## ⚠️ 「中键改数量」为什么改成内联一行
 *
 * 老工程中键弹的是 LDLib 的 `DialogWidget.showStringEditorDialog`；MUI 3.3.1 只有一个泛型
 * `brachy.modularui.widgets.Dialog`（`closeWith` + `resultConsumer`），没有「文本框 + 确定」的现成件，
 * 自己拼一个子面板又要额外的 `syncedPanel` 去协调「当前在编辑哪个槽」。面板底部本来就有空位，
 * 所以改成常驻一行：中键点槽 → 那一行变成「编辑槽 #N」，填数字后点确定。
 * 功能等价（数量与 mB 都能精确输入），只是从弹窗变成内联。
 *
 * @author rain fox
 */
object RecipeEditorPanel {

    /** 建面板。两端各调一次，树结构必须完全一致。 */
    @JvmStatic
    fun build(data: PlayerInventoryGuiData<*>, syncManager: PanelSyncManager): ModularPanel<*> =
        RecipeUi(data, syncManager).build()

    // ======================== 面板树 ========================

    private class RecipeUi(
        private val data: PlayerInventoryGuiData<*>,
        private val syncManager: PanelSyncManager,
    ) {

        private val player: Player = data.player

        /** 只有服务端那次可以写草稿 / 写文件；客户端那次只改本地镜像值。 */
        private val serverSide: Boolean = player is ServerPlayer

        /** 手上的配方编辑器：每次现取（`GuiData` 拿着玩家与槽位下标，不是一个快照）。 */
        private val stack: ItemStack get() = data.usedItemStack

        /**
         * 工作草稿。
         *
         * 两端各建一份：服务端这份是**真源**（所有 setter 改它、改完写回物品的数据组件）；
         * 客户端这份是「打开界面那一刻的快照」（组件是 `networkSynchronized` 的，
         * 所以客户端拿到的是正确初值，界面不会先闪一下空状态），之后由同步值驱动刷新。
         */
        private val draft: RecipeDraft = RecipeEditorData.loadDraft(stack)

        /** 面板本体（`refreshIfNeeded` 要拿它催一次重排）。 */
        private var panel: RecipePanel? = null

        // ---------- 本地（非同步）状态：只影响画法，不影响数据 ----------

        /** 当前显示哪一页（老工程的 `showPage`）。纯客户端状态，树结构不受它影响。 */
        private var page: Int = 0

        /** 电压快捷弹层是否展开（老工程的 `tierPicker.isVisible`）。 */
        private var tierPickerOpen: Boolean = false

        /** 数量编辑行当前指向的槽（打包：高 32 位 = 区段、低 32 位 = 槽下标）；[NO_TARGET] = 没在编辑。 */
        private var countTargetValue: Long = NO_TARGET

        /** 服务端这边记着「数量输入框里刚敲进去的原文」（点确定时才写进草稿）。 */
        private var pendingCountText: String = ""

        /** 上一轮重排用的状态键（变了才重排）。 */
        private var layoutKey: String = ""

        // ---------- 代码预览缓存 ----------

        private var codeDirty: Boolean = true
        private var codeCache: String = ""

        // ---------- 四段槽位的「实际用几个」（relayout 会写，标签文案会读） ----------

        private var itemInCount: Int = 0
        private var fluidInCount: Int = 0
        private var itemOutCount: Int = 0
        private var fluidOutCount: Int = 0

        // ======================== 同步值（没挂到控件上的那些，必须手动登记） ========================
        // 全部 `.allowC2S()`：客户端输入 → 服务端 setter 写草稿；服务端每 tick `detectAndSendChanges`
        // 把值推回客户端。⚠️ setter **两端都会被调**（客户端乐观写本地值那次 + 服务端收到包那次），
        // 所以每个 setter 内部都判端。

        /** 配方种类（[RecipeDraft.Kind] 的 ordinal）。 */
        private val kindOrdinal = registerInt("kind", { draft.kind.ordinal }) { value ->
            draft.kind = RecipeDraft.Kind.entries.getOrElse(value) { RecipeDraft.Kind.SMELTING }
            RecipeEditorBehavior.syncCircuit(draft)
            touch()
        }

        /** GT 配方类型 id。 */
        private val gtType = registerString("gt_type", { draft.gtType }) { value ->
            draft.gtType = value
            touch()
        }

        /** 电压档位下标。 */
        private val tier = registerInt("tier", { draft.tier }) { value ->
            // RecipeDraft.tier 的 setter 里还会再夹一次（VoltageTiers.coerce）
            draft.tier = value
            touch()
        }

        /** 幽灵电路配置号（`-1` = 不用电路）。 */
        private val circuit = registerInt("circuit", { draft.circuit }) { value ->
            draft.circuit = value.coerceIn(-1, RecipeDraft.CIRCUIT_MAX)
            RecipeEditorBehavior.syncCircuit(draft)
            touch()
        }

        /** 代码文本（服务端按需重算；客户端只认同步下来的缓存）。 */
        private val code = StringSyncValue(Supplier { currentCode() }).also { syncManager.syncValue("code", it) }

        /** 数量编辑：当前指向哪个槽（打包值，见 [countTargetValue]）。 */
        private val countTarget = registerLong("count_target", { countTargetValue }) { value ->
            countTargetValue = value
        }

        // ======================== 同步值（挂在控件上，交给 MUI 自动登记） ========================

        /** 配方 id。 */
        private val recipeId = boundString({ draft.recipeId }) { value ->
            draft.recipeId = value
            touch()
        }

        /** 配方时间（tick）。 */
        private val duration = boundInt({ draft.duration }) { value ->
            draft.duration = maxOf(0, value)
            touch()
        }

        /** 基础耗电 EU/t。 */
        private val eut = LongSyncValue(LongSupplier { draft.eut }, LongConsumer { value ->
            if (serverSide) {
                draft.eut = maxOf(0L, value)
                touch()
            }
        }).allowC2S()

        /** 往空流体槽里放流体时的默认量（mB）。 */
        private val fluidAmount = boundInt({ draft.fluidAmount }) { value ->
            draft.fluidAmount = maxOf(1, value)
            touch()
        }

        /** 数量编辑：输入框里的原文（服务端点确定时才落进草稿）。 */
        private val countText = StringSyncValue(
            Supplier { pendingCountText },
            Consumer { value -> pendingCountText = value },
        ).allowC2S()

        // ---------- 「点一下干一件事」的信号值 ----------
        // getter 恒 false；每次赋值都无条件发一次 C2S（`ValueSyncHandler#setBoolValue` 里 notify 为真就 sync），
        // 所以连点同一个按钮每次都算一次。见 `StructureExportPanel` 的同款写法。

        private val refreshCode = action("do_refresh") {
            codeDirty = true
            code.notifyUpdate()
        }

        private val exportCode = action("do_export") { RecipeEditorBehavior.export(player, draft) }

        private val applyCountAction = action("do_apply_count") { applyPendingCount() }

        // ======================== 控件 ========================

        private val itemInputSlots: Array<CountItemSlot> = Array(RecipeDraft.MAX_INPUTS) { i ->
            countItemSlot(SECTION_ITEM_IN, draft.inputs, i)
        }
        private val itemOutputSlots: Array<CountItemSlot> = Array(RecipeDraft.MAX_OUTPUTS) { i ->
            countItemSlot(SECTION_ITEM_OUT, draft.outputs, i)
        }
        private val fluidInputSlots: Array<CountFluidSlot> = Array(RecipeDraft.MAX_FLUID_INPUTS) { i ->
            countFluidSlot(SECTION_FLUID_IN, i)
        }
        private val fluidOutputSlots: Array<CountFluidSlot> = Array(RecipeDraft.MAX_FLUID_OUTPUTS) { i ->
            countFluidSlot(SECTION_FLUID_OUT, i)
        }

        private val itemInputLabel = sectionLabel {
            Component.translatable(RecipeEditorLang.SECTION_ITEM_INPUT, itemInCount, Text.lang(RecipeEditorLang.HINT_ITEM))
        }
        private val fluidInputLabel = sectionLabel {
            Component.translatable(RecipeEditorLang.SECTION_FLUID_INPUT, fluidInCount, Text.lang(RecipeEditorLang.HINT_FLUID))
        }
        private val itemOutputLabel = sectionLabel {
            Component.translatable(RecipeEditorLang.SECTION_ITEM_OUTPUT, itemOutCount, Text.lang(RecipeEditorLang.HINT_ITEM))
        }
        private val fluidOutputLabel = sectionLabel {
            Component.translatable(RecipeEditorLang.SECTION_FLUID_OUTPUT, fluidOutCount, Text.lang(RecipeEditorLang.HINT_FLUID))
        }

        /** 电压快捷弹层（最后加进页 1，见 [buildRecipePage]）。 */
        private val tierPicker: Box = Box().pos(64, 42).size(TIER_PICKER_W, TIER_PICKER_H)
            .background(GTGuiTextures.DISPLAY)
            .also { it.setEnabled(false) }

        // ---------- 数量编辑行 ----------

        private val countLabel: Label = Label { countTitle() }.pos(0, 4).size(150, 9)

        private val countField: TextFieldWidget = TextFieldWidget()
            .pos(154, 0).size(56, BUTTON_H)
            .setNumbers(1, MAX_COUNT)
            .setDefaultNumber(1.0)
            .value(countText)
            .autoUpdateOnChange(true)

        /** 「取消」也要让服务端把目标清掉（否则服务端还记着上一个槽）。 */
        private val cancelCountAction = action("do_cancel_count") { countTargetValue = NO_TARGET }

        private val countApplyButton: EditorButton =
            editorButton(214, 0, 56, BUTTON_H, Text.lang(RecipeEditorLang.COUNT_APPLY)) {
                fire(applyCountAction)
                countTargetValue = NO_TARGET
            }

        private val countCancelButton: EditorButton =
            editorButton(272, 0, 56, BUTTON_H, Text.lang(RecipeEditorLang.COUNT_CANCEL)) {
                countTargetValue = NO_TARGET
                fire(cancelCountAction)
            }

        private val countRow: Box = Box().pos(SLOT_LEFT, COUNT_ROW_Y).size(330, BUTTON_H)
            .child(countLabel).child(countField).child(countApplyButton).child(countCancelButton)

        init {
            // 拖入流体时的默认量走草稿里的「流体量 (mB)」字段（老界面同义）
            draft.fluidInputs.defaultFillAmount = { draft.fluidAmount }
            draft.fluidOutputs.defaultFillAmount = { draft.fluidAmount }
            // 幽灵电路的显示槽先按草稿填一次，免得界面刚打开时电路图标是空的
            RecipeEditorBehavior.syncCircuit(draft)

            // 草稿一变就把代码预览标脏（改数量走的是 handler，不经过同步值的 setter）
            draft.inputs.setOnContentsChanged { touch() }
            draft.outputs.setOnContentsChanged { touch() }

            // 类型一变就要重排槽区：客户端的 kind/gtType 由服务端推下来，得自己盯
            syncManager.onClientTick(Runnable { refreshIfNeeded() })
        }

        fun build(): ModularPanel<*> {
            val root = RecipePanel().size(WIDTH, HEIGHT)
            panel = root
            root.child(ButtonWidget.panelCloseButton())

            // ── 顶部导航（放在页容器之外，切页时不动）──
            val navKeys = listOf(RecipeEditorLang.NAV_RECIPE, RecipeEditorLang.NAV_TYPES, RecipeEditorLang.NAV_CODE)
            navKeys.forEachIndexed { index, key ->
                root.child(langButton(key, 8 + index * 64, 4, 60, 14) { page = index })
            }
            root.child(
                Label { Component.translatable(RecipeEditorLang.TITLE).append("  ").append(currentTypeLabel()) }
                    .pos(200, 7).size(WIDTH - 208, 9)
            )

            val pageRecipe = Box().size(WIDTH, HEIGHT)
            val pageTypes = Box().size(WIDTH, HEIGHT).also { it.setEnabledIf { _ -> page == 1 } }
            val pageCode = Box().size(WIDTH, HEIGHT).also { it.setEnabledIf { _ -> page == 2 } }
            pageRecipe.setEnabledIf { _ -> page == 0 }

            buildRecipePage(pageRecipe)
            buildTypesPage(pageTypes)
            buildCodePage(pageCode)

            root.child(pageRecipe)
            root.child(pageTypes)
            root.child(pageCode)

            relayout()
            return root
        }

        // ======================== 页 1：配方 ========================

        private fun buildRecipePage(pageBox: Box) {
            // ── 配方 id ──
            pageBox.child(Label(Component.translatable(RecipeEditorLang.FIELD_RECIPE_ID)).pos(8, 26).size(56, 9))
            pageBox.child(
                TextFieldWidget()
                    .pos(64, 24).size(150, 14)
                    .setMaxLength(128)
                    .value(recipeId)
                    .autoUpdateOnChange(true)
            )

            // ── 电压等级：点一下弹出 ULV~MAX 的快捷选择，后 4 行是 GTET 的特殊档 MAX+1~MAX+16 ──
            // GTM 档与特殊档**各自**按 4 个一行分块（`chunked` 天然断行），所以特殊档一定从新的一行开头，
            // 不会和 OpV / MAX 挤在同一行里。总行数 = 4（LV..MAX 共 14 个）+ 4（特殊档 16 个）= 8，
            // 8 × 14 = 112 ≤ 弹层高度 120，不用加滚动。
            pageBox.child(
                editorButton(218, 24, 60, 14, Text.dynamic(Supplier {
                    Component.translatable(RecipeEditorLang.TIER_BUTTON, VoltageTiers.name(tier.intValue))
                })) {
                    tierPickerOpen = !tierPickerOpen
                    tierPicker.setEnabled(tierPickerOpen)
                    tierPicker.scheduleResize()
                }
            )

            // ── 幽灵电路 ──
            pageBox.child(Label(Component.translatable(RecipeEditorLang.FIELD_CIRCUIT)).pos(340, 32).size(60, 9))
            val circuitSlot = ModularSlot(draft.circuitSlot, 0)
            pageBox.child(
                PhantomItemSlot()
                    .pos(340, 42)
                    .syncHandler(PhantomItemSlotSyncHandler(circuitSlot))
                    .background(GTGuiTextures.SLOT)
            )
            pageBox.child(editorButton(362, 42, 16, 16, Text.str("\u25c0")) {
                circuit.intValue = if (circuit.intValue <= 0) -1 else circuit.intValue - 1
            })
            pageBox.child(editorButton(380, 42, 16, 16, Text.str("\u25b6")) {
                circuit.intValue = if (circuit.intValue < 0) 0
                else minOf(RecipeDraft.CIRCUIT_MAX, circuit.intValue + 1)
            })
            pageBox.child(
                Label {
                    if (circuit.intValue < 0) Text.lang(RecipeEditorLang.CIRCUIT_OFF)
                    else Component.literal(circuit.intValue.toString())
                }.pos(400, 46).size(50, 9)
            )

            // ── 时间 / 耗电 / 流体量 ──
            pageBox.child(Label(Component.translatable(RecipeEditorLang.FIELD_DURATION)).pos(8, 42).size(100, 9))
            pageBox.child(
                TextFieldWidget()
                    .pos(8, 54).size(108, 14)
                    .setNumbers(0, Int.MAX_VALUE)
                    .setDefaultNumber(200.0)
                    .value(duration)
                    .autoUpdateOnChange(true)
            )
            pageBox.child(Label(Component.translatable(RecipeEditorLang.FIELD_EUT)).pos(116, 42).size(108, 9))
            pageBox.child(
                TextFieldWidget()
                    .pos(116, 54).size(108, 14)
                    .setNumbersLong(LongSupplier { 0L }, LongSupplier { Long.MAX_VALUE })
                    .setDefaultNumber(30.0)
                    .value(eut)
                    .autoUpdateOnChange(true)
            )
            pageBox.child(Label(Component.translatable(RecipeEditorLang.FIELD_FLUID_AMOUNT)).pos(232, 42).size(108, 9))
            pageBox.child(
                TextFieldWidget()
                    .pos(232, 54).size(108, 14)
                    .setNumbers(1, MAX_COUNT)
                    .setDefaultNumber(1000.0)
                    .value(fluidAmount)
                    .autoUpdateOnChange(true)
            )

            // ── 幽灵槽 ──
            // 「用几个槽」是配方类型的属性，所以这里一次性把每段都建满（数量见 RecipeDraft 的常量），
            // 之后只改启用状态与坐标（见 relayout），**不增删控件** —— 同步值是按登记顺序路由的，
            // 控件表一变两边就对不上了。
            for (widget in listOf(itemInputLabel, fluidInputLabel, itemOutputLabel, fluidOutputLabel)) {
                pageBox.child(widget)
            }
            for (widget in itemInputSlots) pageBox.child(widget)
            for (widget in fluidInputSlots) pageBox.child(widget)
            for (widget in itemOutputSlots) pageBox.child(widget)
            for (widget in fluidOutputSlots) pageBox.child(widget)

            // ── 玩家物品栏 ──
            pageBox.child(playerInventory())

            // ── 数量编辑行（老工程的中键对话框，见类注释）──
            pageBox.child(countRow)

            // ── 底部按钮 ──
            pageBox.child(langButton(RecipeEditorLang.BTN_REFRESH, 8, BUTTON_ROW_Y, 60, BUTTON_H) { fire(refreshCode) })
            pageBox.child(langButton(RecipeEditorLang.BTN_EXPORT, 72, BUTTON_ROW_Y, 76, BUTTON_H) { fire(exportCode) })
            pageBox.child(langButton(RecipeEditorLang.BTN_COPY, 152, BUTTON_ROW_Y, 76, BUTTON_H) { copyToClipboard() })
            pageBox.child(langButton(RecipeEditorLang.BTN_SHOW_CODE, 232, BUTTON_ROW_Y, 76, BUTTON_H) { page = 2 })

            // 电压快捷选择弹层放在页 1 的**最后**才加入：
            // MUI 的绘制是按下标顺序画的（后加的在上层），鼠标事件则反过来从最后一个开始找，
            // 所以「最后加入」= 画在最上面 + 点击优先。
            // 它的矩形（y 42~162）会和槽区前两行重叠，压在槽位下面的话既看不清、按钮也点不到。
            pageBox.child(buildTierPicker())
        }

        /** 电压弹层：8 行 × 4 个，档名就是 `ULV`…`MAX` 与 `MAX+1`…`MAX+16`（不走翻译键，与 GTM 的 VNF 写法一致）。 */
        private fun buildTierPicker(): Box {
            val rows = ((1 until VoltageTiers.GTM_TIERS).toList() + VoltageTiers.SPECIAL_RANGE.toList())
                .chunked(TIER_COLS)
            rows.forEachIndexed { row, tiersInRow ->
                tiersInRow.forEachIndexed { col, tierIndex ->
                    tierPicker.child(
                        editorButton(col * 46, row * 14, 44, 12, Text.str(VoltageTiers.name(tierIndex))) {
                            tier.intValue = tierIndex
                            // 选档就把耗电摆到该档的默认值：GTM 档是 VA（= V × 30/32，原行为不变），
                            // 特殊档是那档电压本身（VA 装不下 2^33 那种量级）。
                            eut.longValue = VoltageTiers.defaultEut(tierIndex)
                            tierPickerOpen = false
                            tierPicker.setEnabled(false)
                            tierPicker.scheduleResize()
                        }
                    )
                }
            }
            return tierPicker
        }

        /** 玩家物品栏：3×9 主背包 + 1×9 快捷栏（老工程的 `PlayerInventoryWidget` 固有 172×86）。 */
        private fun playerInventory(): IWidget {
            val box = Box().pos(PLAYER_INV_X, PLAYER_INV_Y).size(PLAYER_INV_W, PLAYER_INV_H)
            val inv = PlayerMainInvWrapper(player.inventory)
            for (row in 0 until 3) {
                for (col in 0 until 9) {
                    val index = 9 + row * 9 + col
                    box.child(playerSlot(inv, index, col * 18, row * 18))
                }
            }
            for (col in 0 until 9) {
                box.child(playerSlot(inv, col, col * 18, 58))
            }
            return box
        }

        private fun playerSlot(inv: PlayerMainInvWrapper, index: Int, x: Int, y: Int): IWidget {
            val slot = ModularSlot.playerSlot(inv, index, player)
            return ItemSlot().slot(slot).syncHandler(ItemSlotSyncHandler(slot)).pos(x, y)
        }

        // ======================== 页 2：配方种类 ========================

        private fun buildTypesPage(pageBox: Box) {
            pageBox.child(Label(Component.translatable(RecipeEditorLang.TYPES_TITLE)).pos(8, 26).size(452, 9))

            val list = RecipeList().pos(8, 40).size(452, 218).background(GTGuiTextures.DISPLAY)
            val entries: MutableList<Pair<String, () -> Unit>> = mutableListOf()
            for (kind in RecipeDraft.Kind.entries) {
                entries += kind.displayName().string to {
                    kindOrdinal.intValue = kind.ordinal
                    // 换种类必须重排幽灵槽区：槽位数量跟着类型走
                    relayout()
                    page = 0
                }
            }
            for ((id, _) in RecipeEditorBehavior.gtTypes()) {
                entries += Component.translatable(RecipeEditorLang.TYPES_GT_ENTRY, id.path).string to {
                    // 先写类型 id 再写种类：反过来会让「种类已经是 GT、id 还是旧的」那一瞬间算出错的槽数
                    gtType.stringValue = id.toString()
                    kindOrdinal.intValue = RecipeDraft.Kind.GT.ordinal
                    if (tier.intValue < 1) tier.intValue = 2
                    eut.longValue = VoltageTiers.defaultEut(tier.intValue)
                    relayout()
                    page = 0
                }
            }
            // 三列排；每行一个容器，交给 ListWidget 竖着堆（超出高度自动出滚动条）
            entries.chunked(3).forEach { rowEntries ->
                val row = Box().size(452, 14)
                rowEntries.forEachIndexed { col, (name, select) ->
                    row.child(editorButton(col * 127, 0, 124, 12, Text.str(name)) { select() })
                }
                list.child(row)
            }
            pageBox.child(list)
        }

        // ======================== 页 3：代码 ========================

        private fun buildCodePage(pageBox: Box) {
            pageBox.child(
                Label { Component.translatable(RecipeEditorLang.CODE_TITLE, GtetConfig.recipeExportDirectory()) }
                    .pos(8, 26).size(452, 9)
            )

            val list = RecipeList().pos(8, 40).size(452, 192).background(GTGuiTextures.DISPLAY)
            for (line in 0 until CODE_ROWS) {
                list.child(
                    Label { Component.literal(codeLine(line)) }
                        .pos(0, line * 9).size(444, 9).color(CODE_TEXT_COLOR)
                )
            }
            pageBox.child(list)

            pageBox.child(langButton(RecipeEditorLang.BTN_REFRESH, 8, BUTTON_ROW_Y, 60, BUTTON_H) { fire(refreshCode) })
            pageBox.child(langButton(RecipeEditorLang.BTN_EXPORT, 72, BUTTON_ROW_Y, 76, BUTTON_H) { fire(exportCode) })
            pageBox.child(langButton(RecipeEditorLang.BTN_COPY, 152, BUTTON_ROW_Y, 76, BUTTON_H) { copyToClipboard() })
        }

        /** 代码预览的第 [line] 行（超出内容就是空串）。 */
        private fun codeLine(line: Int): String {
            val lines = code.stringValue.split("\n")
            return if (line < lines.size) lines[line] else ""
        }

        // ======================== 槽位控件的构造 ========================

        /** 可改数量的幽灵物品槽（中键 → 底部那一行）。 */
        private fun countItemSlot(section: Int, handler: CustomItemStackHandler, index: Int): CountItemSlot {
            val widget = CountItemSlot(section, index) { s, i, current -> beginCountEdit(s, i, current) }
            val slot = ModularSlot(handler, index).changeListener { _, _, _, _ -> touch() }
            widget.slot(slot)
            widget.syncHandler(PhantomItemSlotSyncHandler(slot))
            widget.background(GTGuiTextures.SLOT)
            return widget
        }

        /** 可改量的幽灵流体槽（MUI 的 `FluidSlot` 要 `IMultiTankFluidHandler` + 槽下标）。 */
        private fun countFluidSlot(section: Int, index: Int): CountFluidSlot {
            val widget = CountFluidSlot(section, index) { s, i, current -> beginCountEdit(s, i, current) }
            val tanks = if (section == SECTION_FLUID_IN) draft.fluidInputs else draft.fluidOutputs
            // ⚠️ `phantom(true)` 不能省：`FluidSlotSyncHandler` 的 phantom 默认 false，
            // 不置位的话点击走的是「灌桶 / 倒桶」那条真实容器路径，而不是「把手上的流体放进幻影槽」。
            widget.syncHandler(FluidSlotSyncHandler(tanks, index).phantom(true))
            widget.background(GTGuiTextures.FLUID_SLOT)
            return widget
        }

        // ======================== 重排 ========================

        /** 客户端每 tick 看一次「影响布局的状态」有没有变，变了就重排（服务端不画，不用管）。 */
        private fun refreshIfNeeded() {
            val key = "$page|$tierPickerOpen|${kindOrdinal.intValue}|${gtType.stringValue}|$countTargetValue"
            if (key == layoutKey) return
            layoutKey = key
            relayout()
            panel?.scheduleResize()
        }

        /**
         * 按「当前配方类型实际用几个槽」重排幽灵槽区：只改启用状态与坐标，不增删控件。
         *
         * 槽位的 `left/top` 只写值、不触发重算，所以这里每次都用 [move] 显式
         * `scheduleResize()`；MUI 的重排不是逐帧的。
         */
        private fun relayout() {
            val itemIn = countsFor(SECTION_ITEM_IN)
            val itemOut = countsFor(SECTION_ITEM_OUT)
            val fluidIn = countsFor(SECTION_FLUID_IN)
            val fluidOut = countsFor(SECTION_FLUID_OUT)
            itemInCount = itemIn
            fluidInCount = fluidIn
            itemOutCount = itemOut
            fluidOutCount = fluidOut

            // 有序合成按 3×3 摆：导出代码时 `shaped()` 正是按 row * 3 + col 读槽位的，
            // 摆成一排的话用户没法对着生成的图案放东西。其它种类统一 9 个一行。
            val itemCols = if (layoutKind() == RecipeDraft.Kind.CRAFTING_SHAPED) 3 else ITEM_COLS

            var y = SLOT_TOP

            itemInputLabel.setEnabled(itemIn > 0)
            move(itemInputLabel, SLOT_LEFT, y)
            if (itemIn > 0) y += LABEL_H
            itemInputSlots.forEachIndexed { i, slot ->
                slot.setEnabled(i < itemIn)
                if (i < itemIn) move(slot, SLOT_LEFT + (i % itemCols) * ITEM_PITCH, y + (i / itemCols) * ITEM_PITCH)
            }
            y += slotRows(itemIn, itemCols) * ITEM_PITCH

            fluidInputLabel.setEnabled(fluidIn > 0)
            move(fluidInputLabel, SLOT_LEFT, y)
            if (fluidIn > 0) y += LABEL_H
            fluidInputSlots.forEachIndexed { i, slot ->
                slot.setEnabled(i < fluidIn)
                if (i < fluidIn) {
                    move(slot, SLOT_LEFT + (i % FLUID_COLS) * FLUID_PITCH, y + (i / FLUID_COLS) * FLUID_PITCH)
                }
            }
            y += slotRows(fluidIn, FLUID_COLS) * FLUID_PITCH

            itemOutputLabel.setEnabled(itemOut > 0)
            move(itemOutputLabel, SLOT_LEFT, y)
            if (itemOut > 0) y += LABEL_H
            itemOutputSlots.forEachIndexed { i, slot ->
                slot.setEnabled(i < itemOut)
                if (i < itemOut) move(slot, SLOT_LEFT + (i % ITEM_COLS) * ITEM_PITCH, y + (i / ITEM_COLS) * ITEM_PITCH)
            }
            y += slotRows(itemOut, ITEM_COLS) * ITEM_PITCH

            fluidOutputLabel.setEnabled(fluidOut > 0)
            move(fluidOutputLabel, SLOT_LEFT, y)
            if (fluidOut > 0) y += LABEL_H
            fluidOutputSlots.forEachIndexed { i, slot ->
                slot.setEnabled(i < fluidOut)
                if (i < fluidOut) {
                    move(slot, SLOT_LEFT + (i % FLUID_COLS) * FLUID_PITCH, y + (i / FLUID_COLS) * FLUID_PITCH)
                }
            }

            // 数量编辑行只在真的中键点过槽之后才亮起来
            val editing = countTargetValue != NO_TARGET
            countRow.setEnabled(editing)
            countLabel.setEnabled(editing)
            countField.setEnabled(editing)
            countApplyButton.setEnabled(editing)
            countCancelButton.setEnabled(editing)
            countRow.scheduleResize()
        }

        /** 一列 [cols] 个槽要占几行（0 个占 0 行；用于把槽区各段顺次往下排）。 */
        private fun slotRows(count: Int, cols: Int): Int = if (count <= 0) 0 else (count + cols - 1) / cols

        // ======================== 数据 / 状态 ========================

        /** 当前显示的配方种类（以**同步值**为准；客户端的 `draft` 是打开界面时的快照）。 */
        private fun layoutKind(): RecipeDraft.Kind =
            RecipeDraft.Kind.entries.getOrElse(kindOrdinal.intValue) { RecipeDraft.Kind.SMELTING }

        /**
         * 某一段实际用几个槽。
         *
         * ⚠️ 必须用**同步下来的** kind / gtType 算，不能用本地 `draft` ——
         * 客户端那份草稿是打开界面时的快照，在类型页点完不会跟着变。
         */
        private fun countsFor(section: Int): Int {
            val probe = RecipeDraft()
            probe.kind = layoutKind()
            probe.gtType = gtType.stringValue.ifEmpty { draft.gtType }
            return when (section) {
                SECTION_ITEM_IN -> probe.inputSlots()
                SECTION_ITEM_OUT -> probe.outputSlots()
                SECTION_FLUID_IN -> probe.fluidInputSlots()
                else -> probe.fluidOutputSlots()
            }
        }

        /** 当前选中类型的显示名，用来在标题栏里报一句（老工程的 `RecipeCodeWriter.describe`）。 */
        private fun currentTypeLabel(): Component = if (layoutKind() == RecipeDraft.Kind.GT) {
            Component.translatable(
                RecipeEditorLang.TYPES_GT_ENTRY,
                ResourceLocation.tryParse(gtType.stringValue)?.path ?: gtType.stringValue,
            )
        } else {
            layoutKind().displayName()
        }

        /** 数量编辑行的标题。 */
        private fun countTitle(): Component {
            if (countTargetValue == NO_TARGET) return Text.lang(RecipeEditorLang.COUNT_NONE)
            val section = (countTargetValue ushr 32).toInt()
            val index = (countTargetValue and 0xFFFFFFFFL).toInt()
            val key = if (section == SECTION_FLUID_IN || section == SECTION_FLUID_OUT) {
                RecipeEditorLang.COUNT_TITLE_FLUID
            } else {
                RecipeEditorLang.COUNT_TITLE_ITEM
            }
            return Component.translatable(key, index)
        }

        /** 中键点了一个槽：把「编辑哪个槽」+「当前值」推给服务端，并点亮数量行。 */
        private fun beginCountEdit(section: Int, index: Int, current: Int) {
            val packed = (section.toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
            countTarget.longValue = packed
            countText.stringValue = current.toString()
            countTargetValue = packed
            relayout()
        }

        /** 服务端：把输入框里的数字写进指定槽（点「确定」时）。 */
        private fun applyPendingCount() {
            if (!serverSide) return
            val target = countTargetValue
            if (target == NO_TARGET) return
            val value = pendingCountText.trim().toIntOrNull() ?: return
            val count = value.coerceIn(1, MAX_COUNT)
            val section = (target ushr 32).toInt()
            val index = (target and 0xFFFFFFFFL).toInt()
            when (section) {
                SECTION_ITEM_IN -> setItemCount(draft.inputs, index, count)
                SECTION_ITEM_OUT -> setItemCount(draft.outputs, index, count)
                SECTION_FLUID_IN -> {
                    val stack = draft.fluidInputs.getFluid(index)
                    // 槽里没流体就没有量可改（期间被右键清空了）
                    if (!stack.isEmpty) draft.fluidInputs.setFluid(index, stack, count)
                }

                SECTION_FLUID_OUT -> {
                    val stack = draft.fluidOutputs.getFluid(index)
                    if (!stack.isEmpty) draft.fluidOutputs.setFluid(index, stack, count)
                }
            }
            countTargetValue = NO_TARGET
            touch()
        }

        /** 写回槽：⚠️ 必须 copy 后再 `setStackInSlot` —— 就地改数量不标记容器变更，槽位同步看不到。 */
        private fun setItemCount(handler: CustomItemStackHandler, index: Int, count: Int) {
            if (index !in 0 until handler.slots) return
            val stack = handler.getStackInSlot(index)
            if (stack.isEmpty) return // 期间被清空了（例如右键清空），放弃
            val updated = stack.copy()
            updated.count = count
            handler.setStackInSlot(index, updated)
        }

        /**
         * 把草稿写回物品的数据组件（**只在服务端**）。
         *
         * `broadcastChanges()` 是给客户端那份数据组件用的：客户端打开界面时要靠它拿到
         * 「这次打开时草稿长什么样」（组件是 `networkSynchronized` 的，但同步发生在物品栈
         * 被送出去的时候，得主动催一次）。
         */
        private fun touch() {
            if (!serverSide) return
            codeDirty = true
            RecipeEditorData.saveDraft(stack, draft)
            player.containerMenu.broadcastChanges()
        }

        /** 代码文本：服务端按需重算，客户端只认同步下来的缓存。 */
        private fun currentCode(): String {
            if (serverSide && codeDirty) {
                codeCache = RecipeCodeWriter.toCode(draft)
                codeDirty = false
            }
            return codeCache
        }

        /** 剪贴板只在客户端（MUI 的按钮回调就在客户端跑）。 */
        private fun copyToClipboard() {
            val text = if (serverSide) RecipeCodeWriter.toCode(draft) else code.stringValue
            Minecraft.getInstance().keyboardHandler.setClipboard(text)
            player.displayClientMessage(Text.lang(RecipeEditorLang.MSG_COPIED), false)
        }

        // ======================== 小工具 ========================

        /** 一段槽区的标题；文字是 lambda，每帧重算（数量变了立刻跟上）。 */
        private fun sectionLabel(text: () -> Component): Label =
            Label(text).pos(SLOT_LEFT, SLOT_TOP).size(452, 9)

        /** 语言键按钮。 */
        private fun langButton(key: String, x: Int, y: Int, width: Int, height: Int, onClick: () -> Unit): EditorButton =
            editorButton(x, y, width, height, Text.lang(key), onClick)

        /** 文字按钮。 */
        private fun editorButton(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            text: IDrawable,
            onClick: () -> Unit,
        ): EditorButton {
            val button = EditorButton().pos(x, y).size(width, height).overlay(text)
            button.onMousePressed { _, _ ->
                onClick()
                true
            }
            return button
        }

        /** 把信号值翻一下 = 发一次 C2S。值本身没有含义，翻转只是为了让每次点击都产生一次写入。 */
        private fun fire(value: BooleanSyncValue) {
            value.boolValue = !value.boolValue
        }

        /** 改坐标 + 让 MUI 重算布局（`left/top` 只写值，不重算）。 */
        private fun <W : IPositioned<W>> move(widget: W, x: Int, y: Int) {
            widget.left(x)
            widget.top(y)
            (widget as IWidget).scheduleResize()
        }

        // ======================== 同步值登记 ========================

        /**
         * 手动登记一个 Int 同步值（**不挂控件**的那些用这个）。
         *
         * ⚠️ 显式写出 SAM 类型：Kotlin 直接写 lambda 会撞上 `(IntSupplier, IntConsumer)`
         *    与 `(IntSupplier, IntSupplier)` 两个重载的歧义。
         */
        private fun registerInt(key: String, read: () -> Int, write: (Int) -> Unit): IntSyncValue {
            val value = IntSyncValue(IntSupplier { read() }, IntConsumer { updated ->
                if (serverSide) write(updated)
            }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }

        private fun registerString(key: String, read: () -> String, write: (String) -> Unit): StringSyncValue {
            val value = StringSyncValue(Supplier { read() }, Consumer { updated ->
                if (serverSide) write(updated)
            }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }

        private fun registerLong(key: String, read: () -> Long, write: (Long) -> Unit): LongSyncValue {
            val value = LongSyncValue(LongSupplier { read() }, LongConsumer { updated -> write(updated) }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }

        /** 挂到 `TextFieldWidget` 上的值：**不手动登记**（MUI 的 `WidgetTree.collectSyncValues` 会收）。 */
        private fun boundInt(read: () -> Int, write: (Int) -> Unit): IntSyncValue =
            IntSyncValue(IntSupplier { read() }, IntConsumer { updated ->
                if (serverSide) write(updated)
            }).allowC2S()

        private fun boundString(read: () -> String, write: (String) -> Unit): StringSyncValue =
            StringSyncValue(Supplier { read() }, Consumer { updated ->
                if (serverSide) write(updated)
            }).allowC2S()

        /**
         * 瞬时动作值：getter 恒 `false`，setter 只在服务端跑。
         *
         * ⚠️ setter 会被**两端**各调一次，所以这里自己判端；具体动作里另有各自的服务端早退。
         */
        private fun action(key: String, run: () -> Unit): BooleanSyncValue {
            val value = BooleanSyncValue(BooleanSupplier { false }, BooleanConsumer {
                if (serverSide) run()
            }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }
    }

    // ======================== 控件类型 ========================
    // MUI 这几只控件都是「自引用泛型」（`Foo<W extends Foo<W>>`），Kotlin 里没法用菱形推断，
    // 各写一个写死了类型参数的私有子类 —— 只有这样链式调用才拿得回自己的类型。

    private class RecipePanel : ModularPanel<RecipePanel>(PANEL_NAME)

    private class Box : ParentWidget<Box>()

    private class RecipeList : ListWidget<IWidget, RecipeList>()

    private class EditorButton : ButtonWidget<EditorButton>()

    private class Label : TextWidget<Label> {
        constructor(text: Component) : super(text)
        constructor(text: Supplier<Component>) : super(text)
    }

    /**
     * 可改数量的幽灵物品槽：**只在中键上分叉**，其余按键原样交给父类
     * （父类负责「左键放手上那份 / 右键清空 / 拖入 / JEI 拖放」那几条分支）。
     *
     * ⚠️ 空槽不弹编辑行：没有物品就没有数量可设，中键直接吃掉这次点击。
     */
    private class CountItemSlot(
        private val section: Int,
        private val index: Int,
        private val onMiddleClick: (Int, Int, Int) -> Unit,
    ) : PhantomItemSlot() {

        override fun onMousePressed(button: Int): Interactable.Result {
            if (button == MIDDLE_BUTTON) {
                val stack = slot.item
                if (!stack.isEmpty) onMiddleClick(section, index, stack.count)
                return Interactable.Result.SUCCESS
            }
            return super.onMousePressed(button)
        }
    }

    /** 可改量的幽灵流体槽（中键 → 底部那一行，单位 mB）。 */
    private class CountFluidSlot(
        private val section: Int,
        private val index: Int,
        private val onMiddleClick: (Int, Int, Int) -> Unit,
    ) : FluidSlot() {

        override fun onMousePressed(button: Int): Interactable.Result {
            if (button == MIDDLE_BUTTON) {
                val stack = fluidStack
                if (!stack.isEmpty) onMiddleClick(section, index, stack.amount)
                return Interactable.Result.SUCCESS
            }
            return super.onMousePressed(button)
        }
    }
}
