@file:Suppress("unused")

package rain.fox.gtetcore.client.terminal

import brachy.modularui.api.drawable.IDrawable
import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.drawable.DynamicDrawable
import brachy.modularui.drawable.GuiTextures
import brachy.modularui.drawable.ItemDrawable
import brachy.modularui.factory.PlayerInventoryGuiData
import brachy.modularui.screen.ModularPanel
import brachy.modularui.screen.UISettings
import brachy.modularui.utils.Alignment
import brachy.modularui.value.BoolValue
import brachy.modularui.value.sync.BooleanSyncValue
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.value.sync.StringSyncValue
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.ButtonWidget
import brachy.modularui.widgets.ListWidget
import brachy.modularui.widgets.TextWidget
import brachy.modularui.widgets.ToggleButton
import brachy.modularui.widgets.textfield.TextFieldWidget
import com.gregtechceu.gtceu.api.GTCEuAPI
import com.gregtechceu.gtceu.api.block.ICoilType
import com.gregtechceu.gtceu.common.block.CoilBlock
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalSettings
import rain.fox.gtetcore.common.item.terminal.TerminalItems
import rain.fox.gtetcore.common.item.terminal.TerminalSettings
import rain.fox.gtetcore.data.lang.AdvancedTerminalLang
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.IntSupplier
import java.util.function.Supplier

/**
 * 高级终端的设置面板（MUI 版）。
 *
 * 老工程这块是 LDLib 的 `AdvancedTerminalUI`（372×274：左侧 8 项设置 + 右侧「组列表 / 该组候选」两块分级面板），
 * 这里按同一套布局重写。GTM 8.0.0 删掉了 `IItemUIFactory`，物品界面改走 MUI 的
 * [com.gregtechceu.gtceu.api.mui.IItemUIHolder]（挂接见 [rain.fox.gtetcore.common.item.terminal.AdvancedTerminalBehavior]）。
 *
 * ⚠️ **面板树在服务端与客户端各建一次**（服务端那次是为了登记同步值，见 MUI 的 `GuiManager.open`）。
 * 所以树结构只能由「两端都一样」的数据决定（8 项设置来自物品数据组件、分级组来自 `cachedGroups`），
 * 而任何会变的量（8 项设置、当前显示哪一组、各组的候选偏好）**一律走同步值**：
 * 服务端读物品 → 同步给客户端。客户端绝不自己去读物品的组件 —— 老 LDLib 版就是栽在
 * 「界面开着时服务端写的 NBT 传不到客户端」上的（老代码注释里写得很清楚）。
 *
 * @author rain fox
 */
object AdvancedTerminalPanel {

    /** 面板尺寸（与老界面一致）。 */
    const val WIDTH: Int = 372
    const val HEIGHT: Int = 274

    /** 面板名：MUI 拿它当同步命名空间。 */
    private const val PANEL_NAME = "advanced_terminal"

    /** 左侧设置面板：(4, 4) 160×266。 */
    private const val SETTINGS_X = 4
    private const val SETTINGS_Y = 4
    private const val SETTINGS_W = 160
    private const val SETTINGS_H = 266

    /** 右侧两块分级面板：x = 170、宽 198、高 114；上块 y = 20，下块 y = 154（与老界面一致）。 */
    private const val LIST_X = 170
    private const val LIST_W = 198
    private const val LIST_H = 114
    private const val CYCLE_Y = 20
    private const val CHOOSE_Y = 154

    /** 列表行高。 */
    private const val ROW_HEIGHT = 16

    /** 列表行内：图标 16×16 @ (4, 0)、名字 @ (24, 4)、行尾控件 @ 174。 */
    private const val ICON_X = 4
    private const val NAME_X = 24
    private const val NAME_W = 144
    private const val TAIL_X = 174

    /** 设置行控件的 y（老界面的行位，逐行 26 像素左右）。 */
    private val ROW_CONTROL_Y = intArrayOf(28, 55, 88, 114, 140, 166, 193, 218)

    /** 设置行标签的 y（比控件低 1~2 像素，文字与控件才对得齐）。 */
    private val ROW_LABEL_Y = intArrayOf(30, 56, 90, 116, 142, 168, 194, 220)

    /** 标签列：x = 10、宽 100（控件从 x = 110 起）。 */
    private const val LABEL_X = 10
    private const val LABEL_W = 100

    /** 数字输入框 36×14，右边缘对齐面板内 x = 152。 */
    private const val FIELD_X = 116
    private const val FIELD_W = 36
    private const val FIELD_H = 14

    /** 勾选框 14×14，右边缘同样对齐 x = 152。 */
    private const val CHECK_X = 138
    private const val CHECK_SIZE = 14

    /** 线圈步进：[<] 12×12 @ 110、数值 @ 126 宽 24、[>] 12×12 @ 140。 */
    private const val STEP_DOWN_X = 110
    private const val STEP_UP_X = 140
    private const val STEP_SIZE = 12
    private const val STEP_VALUE_X = 126
    private const val STEP_VALUE_W = 24

    /** 建面板。两端各调一次，树结构必须完全一致。 */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun build(data: PlayerInventoryGuiData<*>, syncManager: PanelSyncManager, uiSettings: UISettings): ModularPanel<*> =
        TerminalUi(data, syncManager).build()

    // ======================== 面板树 ========================

    /**
     * 一次建树用的全部状态。
     *
     * 单独成类，是为了让「8 项设置」和「两块分级面板」共用同一批同步值，不至于把 `build` 写成几百行。
     */
    private class TerminalUi(
        private val data: PlayerInventoryGuiData<*>,
        private val syncManager: PanelSyncManager,
    ) {

        /** 终端物品栈：每次现取（`GuiData` 拿着玩家与槽位下标，不是一个快照）。 */
        private val terminal: () -> ItemStack = { data.usedItemStack }

        /** 物品 id → 物品栈的小缓存（分级组在界面存活期间不变，省得每帧都去查注册表）。 */
        private val stacks = HashMap<String, ItemStack>()

        /** 终端里记着的分级组（组键 → 候选物品 id）；候选只有一个的组不会出现在这里。 */
        private val groups: List<TerminalSettings.GroupView> = TerminalSettings.cachedGroups(terminal())

        // ---------- 8 项设置 ----------
        // 每项一个 C2S 同步值：getter 读物品的数据组件，setter 写回同一个组件。
        // setter 只在服务端执行（`.allowC2S()` 的含义），客户端只认同步下来的缓存值。

        private val coilTier = intSync(
            "coil_tier",
            { AdvancedTerminalSettings.read(terminal()).coilTier },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(coilTier = value) } }
        )

        private val repeatCount = intSync(
            "repeat_count",
            { AdvancedTerminalSettings.read(terminal()).repeatCount },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(repeatCount = value) } }
        )

        private val noHatch = boolSync(
            "no_hatch",
            { AdvancedTerminalSettings.read(terminal()).noHatch },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(noHatch = value) } }
        )

        private val replaceCoil = boolSync(
            "replace_coil",
            { AdvancedTerminalSettings.read(terminal()).replaceCoil },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(replaceCoil = value) } }
        )

        private val useAe = boolSync(
            "use_ae",
            { AdvancedTerminalSettings.read(terminal()).useAe },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(useAe = value) } }
        )

        private val flip = boolSync(
            "flip",
            { AdvancedTerminalSettings.read(terminal()).flip },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(flip = value) } }
        )

        private val module = intSync(
            "module",
            { AdvancedTerminalSettings.read(terminal()).module },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(module = value) } }
        )

        private val demolition = boolSync(
            "demolition",
            { AdvancedTerminalSettings.read(terminal()).demolition },
            { value -> AdvancedTerminalSettings.modify(terminal()) { it.copy(demolition = value) } }
        )

        // ---------- 两块分级面板 ----------

        /** 「右下正在显示哪一组」；空串 = 没设过，面板退回第 1 组。 */
        private val uiGroup = stringSync(
            "ui_group",
            { TerminalSettings.getUiGroup(terminal()) ?: "" },
            { value -> TerminalSettings.setUiGroup(terminal(), value.ifEmpty { null }) }
        )

        /** 每组的候选偏好各占一个同步值；同步键用**下标**（组顺序两端一致，键也必须一致）。 */
        private val prefs: List<StringSyncValue> = groups.mapIndexed { index, group ->
            stringSync(
                "pref_$index",
                { TerminalSettings.getPreferences(terminal())[group.key] ?: "" },
                { value -> TerminalSettings.setPreference(terminal(), group.key, value.ifEmpty { null }) }
            )
        }

        /** 建树那一刻「当前是哪一组」：只用于给候选块定初值，之后由 [uiGroup] 的同步值驱动。 */
        private val initialKey: String? = effectiveKey(TerminalSettings.getUiGroup(terminal()))

        fun build(): ModularPanel<*> {
            val panel = TerminalPanel().size(WIDTH, HEIGHT)

            // 标题（老界面居中在设置面板上）+ 关闭按钮（MUI 主题自带，自己定位在面板右上角）
            panel.child(
                Label(Text.lang(AdvancedTerminalLang.TITLE))
                    .pos(SETTINGS_X, 6).size(SETTINGS_W, 10).textAlign(Alignment.Center)
            )
            panel.child(ButtonWidget.panelCloseButton())

            panel.child(settingsPanel())
            panel.child(panelTitle(Text.lang(AdvancedTerminalLang.PANEL_CYCLE), 8))
            panel.child(panelTitle(Text.lang(AdvancedTerminalLang.PANEL_CHOOSE), 142))

            val cycleList = list().pos(LIST_X, CYCLE_Y).size(LIST_W, LIST_H)
            val chooseList = list().pos(LIST_X, CHOOSE_Y).size(LIST_W, LIST_H)
            panel.child(cycleList)
            panel.child(chooseList)

            if (groups.isEmpty()) {
                // 还没扫描过结构 / 一个分级组都没有：两块面板都给同一句空状态提示
                cycleList.child(emptyHint())
                chooseList.child(emptyHint())
            } else {
                groups.forEachIndexed { index, group -> cycleList.child(groupRow(group, prefs[index])) }
                groups.forEachIndexed { index, group -> chooseList.child(candidateBlock(group, prefs[index])) }
            }
            return panel
        }

        // ======================== 左侧设置面板 ========================

        /** 8 行设置：线圈 / 重复次数 / 无仓室 / 线圈替换 / 使用 AE / 镜像 / 模块 / 拆除。 */
        private fun settingsPanel(): IWidget {
            val settings = Box().pos(SETTINGS_X, SETTINGS_Y).size(SETTINGS_W, SETTINGS_H)
                .background(GTGuiTextures.DISPLAY)

            // 1 线圈等级：上限 = 全部加热线圈的档位数（0 = 不指定），带 −/+ 步进
            val coilMax = coilTypes().size
            val coilTip = coilTips()
            settings.child(label(ROW_LABEL_Y[0], AdvancedTerminalLang.SETTING_1, coilTip))
            settings.child(
                stepButton(STEP_DOWN_X, ROW_CONTROL_Y[0], "<") {
                    coilTier.intValue = (coilTier.intValue - 1).coerceIn(0, coilMax)
                }
            )
            settings.child(
                Label { Component.literal(coilTier.intValue.toString()) }
                    .pos(STEP_VALUE_X, ROW_LABEL_Y[0]).size(STEP_VALUE_W, 9).textAlign(Alignment.Center)
            )
            settings.child(
                stepButton(STEP_UP_X, ROW_CONTROL_Y[0], ">") {
                    coilTier.intValue = (coilTier.intValue + 1).coerceIn(0, coilMax)
                }
            )

            // 2 重复结构次数（0..1000）
            settings.child(label(ROW_LABEL_Y[1], AdvancedTerminalLang.SETTING_2, tips(AdvancedTerminalLang.SETTING_2_TIP)))
            settings.child(
                numberField(
                    ROW_CONTROL_Y[1], repeatCount,
                    AdvancedTerminalSettings.REPEAT_MIN, AdvancedTerminalSettings.REPEAT_MAX,
                    tips(AdvancedTerminalLang.SETTING_2_TIP)
                )
            )

            // 3 无仓室模式（默认关：老项目「默认不铺仓室导致机器不成型」的 bug 已在新数据类里修掉）
            settings.child(label(ROW_LABEL_Y[2], AdvancedTerminalLang.SETTING_3, tips(AdvancedTerminalLang.SETTING_3_TIP)))
            settings.child(checkbox(ROW_CONTROL_Y[2], noHatch, tips(AdvancedTerminalLang.SETTING_3_TIP)))

            // 4 线圈替换模式
            settings.child(label(ROW_LABEL_Y[3], AdvancedTerminalLang.SETTING_4, tips(AdvancedTerminalLang.SETTING_4_TIP)))
            settings.child(checkbox(ROW_CONTROL_Y[3], replaceCoil, tips(AdvancedTerminalLang.SETTING_4_TIP)))

            // 5 使用 AE 物品 —— AE 链接未移植：开关**显示但点不动**，tooltip 里写清楚
            settings.child(label(ROW_LABEL_Y[4], AdvancedTerminalLang.SETTING_5, aeTips()))
            settings.child(aeCheckbox(ROW_CONTROL_Y[4], aeTips()))

            // 6 镜像搭建
            settings.child(label(ROW_LABEL_Y[5], AdvancedTerminalLang.SETTING_6, tips(AdvancedTerminalLang.SETTING_6_TIP)))
            settings.child(checkbox(ROW_CONTROL_Y[5], flip, tips(AdvancedTerminalLang.SETTING_6_TIP)))

            // 7 模块搭建档位 —— 模块化机器未移植：能调，但当前不生效，tooltip 里写清楚
            settings.child(label(ROW_LABEL_Y[6], AdvancedTerminalLang.SETTING_7, moduleTips()))
            settings.child(
                numberField(
                    ROW_CONTROL_Y[6], module,
                    AdvancedTerminalSettings.MODULE_MIN, AdvancedTerminalSettings.MODULE_MAX,
                    moduleTips()
                )
            )

            // 8 拆除模式
            settings.child(label(ROW_LABEL_Y[7], AdvancedTerminalLang.SETTING_8, tips(AdvancedTerminalLang.SETTING_8_TIP)))
            settings.child(checkbox(ROW_CONTROL_Y[7], demolition, tips(AdvancedTerminalLang.SETTING_8_TIP)))

            return settings
        }

        // ======================== 右上：组列表 ========================

        /**
         * 组列表的一行：点整行 = 把这一组设成「右下正在显示的那一组」；行尾 [▶] = 循环到下一个候选。
         *
         * 整行是一个按钮，图标 / 名字 / [▶] 都是它的子控件：MUI 里叶子控件默认 `canClickThrough()` 为 true，
         * 而子容器 [Box] 既没背景也没 tooltip（[ParentWidget] 的 `canClickThrough()` = `!canHover()`），
         * 所以点图标、点名字都会落到整行按钮上。
         *
         * ⚠️ 子容器**不要**加 tooltip / 背景，否则它会吃掉整行的点击。
         */
        private fun groupRow(group: TerminalSettings.GroupView, pref: StringSyncValue): IWidget {
            val inner = Box().size(LIST_W, ROW_HEIGHT)
                .child(itemIcon({ stackOf(chosenId(group, pref)) }, ICON_X, 0))
                .child(Label { rowName(group, pref) }.pos(NAME_X, 4).size(NAME_W, 9))
                .child(cycleButton(group, pref))

            val row = TerminalButton().size(LIST_W, ROW_HEIGHT)
            row.tooltip { it.addLine(Text.lang(AdvancedTerminalLang.PANEL_PICK_TIP)) }
            row.onMousePressed { _, _ ->
                uiGroup.stringValue = group.key
                true
            }
            return row.child(inner)
        }

        /** 行尾 [▶]：先把这一组设成当前组，再把它循环到下一档（与老界面一致）。 */
        private fun cycleButton(group: TerminalSettings.GroupView, pref: StringSyncValue): IWidget {
            val button = TerminalButton().pos(TAIL_X, 1).size(18, ROW_HEIGHT - 2)
                .overlay(Text.str("\u25b6"))
            button.tooltip { it.addLine(Text.lang(AdvancedTerminalLang.PANEL_CYCLE_TIP)) }
            button.onMousePressed { _, _ ->
                uiGroup.stringValue = group.key
                val candidates = group.candidates
                if (candidates.isNotEmpty()) {
                    val index = candidates.indexOf(chosenId(group, pref))
                    pref.stringValue = candidates[(index + 1) % candidates.size]
                }
                true
            }
            return button
        }

        /** 行首标记（当前组是金色的 ▶）+ 该组当前选中方块的名字。 */
        private fun rowName(group: TerminalSettings.GroupView, pref: StringSyncValue): Component {
            val current = currentKey() == group.key
            val marker: MutableComponent = Component.literal(if (current) "\u25b6 " else "  ")
            if (current) marker.withStyle(ChatFormatting.GOLD)
            val stack = stackOf(chosenId(group, pref))
            return marker.append(if (stack.isEmpty) Component.empty() else stack.hoverName)
        }

        // ======================== 右下：该组的候选 ========================

        /**
         * 一组候选（整块一个容器，行在块内按行高绝对定位）。
         *
         * ⚠️ 不能「按当前组去建树」：树要两端一致。所以**每组的候选块全都建出来**，
         * 用 `setEnabledIf` 只让当前那块生效 —— MUI 的 `InternalWidgetTree.drawTree` 碰到
         * 未启用的控件会整棵子树直接返回（连子控件都不画），而 `ListWidget` 默认开着
         * `collapseDisabledChildren`，会把未启用的子控件从布局里挤掉，所以是「既不显示也不占位」。
         */
        private fun candidateBlock(group: TerminalSettings.GroupView, pref: StringSyncValue): IWidget {
            val block = Box().size(LIST_W, group.candidates.size * ROW_HEIGHT)
            // 初值按建树那一刻的本地判断给（客户端的同步值要等第一个 tick 才下来，不然会闪一下）
            block.isEnabled = initialKey == group.key
            block.setEnabledIf { currentKey() == group.key }

            group.candidates.forEachIndexed { index, itemId ->
                block.child(candidateRow(itemId, pref, index * ROW_HEIGHT))
            }
            return block
        }

        /** 候选列表的一行：点整行 = 把这一档记成该组的偏好（点已选中的那行不会取消选择）。 */
        private fun candidateRow(itemId: String, pref: StringSyncValue, y: Int): IWidget {
            val stack = stackOf(itemId)
            val inner = Box().size(LIST_W, ROW_HEIGHT)
                .child(itemIcon({ stack }, ICON_X, 0))
                .child(Label(if (stack.isEmpty) Component.empty() else stack.hoverName).pos(NAME_X, 4).size(NAME_W, 9))
                .child(checkMark(pref, itemId))

            val row = TerminalButton().pos(0, y).size(LIST_W, ROW_HEIGHT)
            row.onMousePressed { _, _ ->
                pref.stringValue = itemId
                true
            }
            return row.child(inner)
        }

        /**
         * 行尾的勾选标记：这一档是该组当前偏好时打勾，否则是空框。
         *
         * ⚠️ 它必须是**不吃点击**的纯显示控件。MUI 的 `ToggleButton` 按下会返回
         * `Interactable.Result.SUCCESS`（accepts + stops），而点击派发是「从最上层往下、遇到 stops 就停」——
         * 勾选标记一旦做成按钮，就会把整行的点击吃掉，点行尾那一格等于没反应。
         * 所以这里用 `DynamicDrawable` + `DrawableWidget`，让点击穿过去落到整行按钮上。
         */
        private fun checkMark(pref: StringSyncValue, itemId: String): IWidget {
            val drawable: Supplier<IDrawable> = Supplier {
                if (pref.stringValue == itemId) GuiTextures.CHECK_BOX_FULL else GuiTextures.CHECK_BOX_EMPTY
            }
            return IDrawable.DrawableWidget(DynamicDrawable(drawable)).pos(TAIL_X, 1).size(CHECK_SIZE, CHECK_SIZE)
        }

        // ======================== 小工具 ========================

        /** 一块分级面板用的滚动列表。 */
        private fun list(): TerminalList = TerminalList().background(GTGuiTextures.DISPLAY)

        /** 列表上方的小标题。 */
        private fun panelTitle(text: Component, y: Int): IWidget =
            Label(text).pos(LIST_X + 4, y).size(LIST_W - 8, 9)

        /** 空状态提示：还没扫描过结构 / 没有分级组。 */
        private fun emptyHint(): IWidget =
            Label(Text.lang(AdvancedTerminalLang.PANEL_EMPTY)).pos(4, 4).size(LIST_W - 8, 9)

        /** 设置项标签（tooltip 同时挂在标签和控件上，鼠标悬哪儿都能看到）。 */
        private fun label(y: Int, key: String, tips: List<Component>): Label =
            Label(Text.lang(key)).pos(LABEL_X, y).size(LABEL_W, 9)
                .tooltip { rich -> tips.forEach { rich.addLine(it) } }

        /** 勾选框 14×14（MUI 自带的复选框贴图正好 14×14 一格）。 */
        private fun checkbox(y: Int, value: BooleanSyncValue, tips: List<Component>): ToggleButton {
            val button = ToggleButton().pos(CHECK_X, y).size(CHECK_SIZE, CHECK_SIZE)
                .stateOverlay(GuiTextures.CHECK_BOX)
                .value(value)
            button.tooltip { rich -> tips.forEach { rich.addLine(it) } }
            return button
        }

        /**
         * 「使用 AE 物品」的勾选框：**显示出来但点不动**。
         *
         * ⚠️ 这里不能用 `setEnabled(false)`：MUI 的 `InternalWidgetTree.drawTree` 碰到未启用的控件
         * 会整棵子树直接返回（那套机制是给「隐藏」用的），开关会**整个消失**，
         * 跟「显示但禁用」根本不是一个意思。所以做成「值只读」的勾选框：
         * 点下去照走 MUI 的 `next()`，但 setter 是空的，一个字节都不会写进物品。
         */
        private fun aeCheckbox(y: Int, tips: List<Component>): ToggleButton {
            val button = ToggleButton().pos(CHECK_X, y).size(CHECK_SIZE, CHECK_SIZE)
                .stateOverlay(GuiTextures.CHECK_BOX)
                .value(BoolValue.Dynamic({ useAe.boolValue }, { _ -> }))
            button.tooltip { rich -> tips.forEach { rich.addLine(it) } }
            return button
        }

        /** 数字输入框 36×14；输入范围由 `setNumbers` 兜住，越界值再由数据类夹一次。 */
        private fun numberField(y: Int, value: IntSyncValue, min: Int, max: Int,
                                tips: List<Component>): TextFieldWidget {
            val field = TextFieldWidget()
                .pos(FIELD_X, y).size(FIELD_W, FIELD_H)
                .setNumbers(min, max)
                .setDefaultNumber(min.toDouble())
                .value(value)
                .autoUpdateOnChange(true)
            field.tooltip { rich -> tips.forEach { rich.addLine(it) } }
            return field
        }

        /** 12×12 的步进按钮（只有线圈等级用）。 */
        private fun stepButton(x: Int, y: Int, glyph: String, action: () -> Unit): IWidget {
            val button = TerminalButton().pos(x, y).size(STEP_SIZE, STEP_SIZE).overlay(Text.str(glyph))
            button.onMousePressed { _, _ ->
                action()
                true
            }
            return button
        }

        /** 物品图标；走 [DynamicDrawable] 是为了「偏好一变图标跟着换」。 */
        private fun itemIcon(item: () -> ItemStack, x: Int, y: Int): IWidget {
            val drawable: Supplier<IDrawable> = Supplier { ItemDrawable(item()) }
            return IDrawable.DrawableWidget(DynamicDrawable(drawable)).pos(x, y).size(16, 16)
        }

        /** 物品 id → 物品栈（带缓存，查不到的当空气）。 */
        private fun stackOf(itemId: String): ItemStack =
            stacks.getOrPut(itemId) { TerminalItems.itemStackOf(itemId) ?: ItemStack.EMPTY }

        /** 这一组当前选中的候选 id；偏好为空或指向别的组时退回第一档。 */
        private fun chosenId(group: TerminalSettings.GroupView, pref: StringSyncValue): String {
            val wanted = pref.stringValue
            if (wanted.isNotEmpty() && group.candidates.contains(wanted)) return wanted
            return group.candidates.firstOrNull() ?: ""
        }

        /** 「右下正在显示哪一组」的实时判定：同步值指向一个还在列表里的组就用它，否则退回第 1 组。 */
        private fun currentKey(): String? = effectiveKey(uiGroup.stringValue)

        /** 把「终端里记着的组键」翻成实际生效的组键（不在列表里 = 没设过 / 被后来的扫描覆盖掉了）。 */
        private fun effectiveKey(stored: String?): String? {
            if (groups.any { it.key == stored }) return stored
            return groups.firstOrNull()?.key
        }

        // ---------- 同步值登记 ----------

        private fun intSync(key: String, read: () -> Int, write: (Int) -> Unit): IntSyncValue {
            // ⚠️ Kotlin 直接写 lambda 会撞上 `(IntSupplier, IntConsumer)` 与 `(IntSupplier, IntSupplier)`
            //    两个重载的歧义，必须显式写出 SAM 类型
            val value = IntSyncValue({ read() }, { updated -> write(updated) }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }

        private fun boolSync(key: String, read: () -> Boolean, write: (Boolean) -> Unit): BooleanSyncValue {
            val value = BooleanSyncValue({ read() }, { updated -> write(updated) })
                .allowC2S()
            syncManager.syncValue(key, value)
            return value
        }

        private fun stringSync(key: String, read: () -> String, write: (String) -> Unit): StringSyncValue {
            val value = StringSyncValue({ read() }, { updated -> write(updated) }).allowC2S()
            syncManager.syncValue(key, value)
            return value
        }
    }

    // ======================== 控件类型 ========================
    // MUI 这几只控件都是「自引用泛型」（`Foo<W extends Foo<W>>`），Kotlin 里没法用菱形推断，
    // 所以各写一个写死了类型参数的私有子类 —— 只有这样链式调用才拿得回自己的类型。

    /** 面板本体。 */
    private class TerminalPanel : ModularPanel<TerminalPanel>(PANEL_NAME)

    /** 一块绝对定位的子容器（不参与自动布局，坐标全靠 `pos`）。 */
    private class Box : ParentWidget<Box>()

    /** 会滚动的竖向列表。 */
    private class TerminalList : ListWidget<IWidget, TerminalList>()

    /** 可点的整行按钮。 */
    private class TerminalButton : ButtonWidget<TerminalButton>()

    /** 文本标签（构造器吃 [Component]，也吃它的 [Supplier] —— 后者每帧重算）。 */
    private class Label : TextWidget<Label> {
        constructor(text: Component) : super(text)
        constructor(text: Supplier<Component>) : super(text)
    }

    // ======================== 线圈 tooltip（照搬老界面） ========================

    /** 「线圈等级」的 tooltip：第 1 行是文案，之后每档一行「档位序号:线圈显示名」。 */
    private fun coilTips(): List<Component> {
        val tips = ArrayList<Component>()
        tips.add(Text.lang(AdvancedTerminalLang.SETTING_1_TIP))
        val coils = coilTypes()
        for (i in coils.indices) {
            tips.add(Component.literal("${i + 1}:").append(coilName(coils[i])))
        }
        return tips
    }

    /** 全部加热线圈，按 `getTier()` 升序（稳定顺序 —— 两端的 tooltip 必须一模一样）。 */
    private fun coilTypes(): List<ICoilType> {
        val types = ArrayList(GTCEuAPI.HEATING_COILS.keys)
        types.sortWith(compareBy { it.tier })
        return types
    }

    private fun coilName(type: ICoilType): Component {
        return try {
            val supplier: Supplier<CoilBlock> = GTCEuAPI.HEATING_COILS[type] ?: return Component.empty()
            val stack = ItemStack(supplier.get())
            if (stack.isEmpty) Component.empty() else stack.hoverName
        } catch (ignored: Throwable) {
            Component.empty()
        }
    }

    /** AE 那项：先讲它本该干什么，再讲本阶段为什么点不动。 */
    private fun aeTips(): List<Component> =
        tips(AdvancedTerminalLang.SETTING_5_TIP, AdvancedTerminalLang.SETTING_5_TIP_DISABLED)

    /** 模块那项：先讲它本该干什么，再讲当前调了也不生效。 */
    private fun moduleTips(): List<Component> =
        tips(AdvancedTerminalLang.SETTING_7_TIP, AdvancedTerminalLang.SETTING_7_TIP_DISABLED)

    private fun tips(vararg keys: String): List<Component> = keys.map { Text.lang(it) }
}
