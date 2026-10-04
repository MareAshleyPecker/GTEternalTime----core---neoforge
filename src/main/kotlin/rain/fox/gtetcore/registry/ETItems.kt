package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.common.item.behavior.TooltipBehavior
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalBehavior
import rain.fox.gtetcore.common.item.recipe.RecipeEditorBehavior
import rain.fox.gtetcore.common.item.timeflow.TimeClockBehavior
import rain.fox.gtetcore.common.item.tool.StructureDetectBehavior
import rain.fox.gtetcore.common.item.tool.StructureWriteBehavior
import rain.fox.gtetcore.common.item.tool.TerminalBehavior
import rain.fox.gtetcore.data.lang.AdvancedTerminalLang
import rain.fox.gtetcore.data.lang.RecipeEditorLang
import rain.fox.gtetcore.data.lang.StructureToolLang
import rain.fox.gtetcore.data.lang.TimeClockLang
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 物品注册入口。
 *
 * 目前登记两件：高级终端（潜行右键控制器 = 自动搭建；右键空气 = 开 MUI 设置面板。AE 链接留给后续切片）
 * 与时序钟（时间流 TF 的显示 / 搬运道具，见 `common/item/timeflow/`）。
 * Registrate 是在 builder 被创建的那一刻就登记的，所以**必须在 mod 构造期取一次**
 * [ADVANCED_TERMINAL] 的值（见 `CommonProxy.kotlinInit`），否则物品进不了注册表。
 *
 * 语言键与译文在 `data/lang/`（[AdvancedTerminalLang] / [TimeClockLang]），本文件只管注册。
 */
object ETItems {

    /** 高级终端 — 潜行右键多方块控制器自动搭建；右键空气开设置面板（MUI，见 `AdvancedTerminalPanel`）。 */
    @JvmField
    val ADVANCED_TERMINAL: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["advanced_terminal"] = "§b高级终端"

        // 归页用 inTab 包住整条注册链：GTM 的 creativeModeTab 只对「之后」注册的东西生效
        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                .item("advanced_terminal", ::ComponentItem)
                // Registrate 的「影子归页」会把这件物品再塞进原版搜索页，重建时与聚合结果重复 → 崩游戏
                .noDefaultTab()
                .lang("§bAdvanced Terminal")
                .properties { properties -> properties.stacksTo(1) }
                // 贴图自备：assets/gtetscore/textures/item/advanced_terminal.png
                .model { ctx, provider ->
                    provider.generated(ctx, GTETSCore.id("item/advanced_terminal"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        AdvancedTerminalBehavior,
                        // 8.0.0 删掉了老的 `com.gregtechceu.gtceu.common.item.TooltipBehavior`，
                        // 现址是 `...common.item.behavior.TooltipBehavior`，依旧挂在组件上生效
                        TooltipBehavior { lines -> lines.add(Component.translatable(AdvancedTerminalLang.TOOLTIP_OPEN_PANEL)) }
                    )
                }
                .register()
        }
    }

    /**
     * 时序钟 — 时间流（TF）的显示 / 搬运道具。
     *
     * - **显示**：tooltip 里报当前汇率与相位、`1 TF = 8192 EU`、当前往返损耗、钟内 TF 与其折算 EU、
     *   档位与容量上限；汇率是 `f(gameTime)` 的纯函数，客户端本算，不需要同步包（见
     *   [rain.fox.gtetcore.api.timeflow.ETTimeFlow]）。
     * - **搬运**：TF 用 long 存在物品的数据组件里（1.21 没有物品 NBT），没有亚 TF 精度。
     * - **档位**：一件物品按升级提升 —— L1 = 1A ZPM = 16 TF、L2 = 1A UEV = 1024 TF、L3 = 1A OpV = 65536 TF；
     *   升级件只抬容量上限，钟内 TF 不丢、物品不换（升级件的形态与配方本期不做）。
     * - **绑定主控塔**：数据组件里存一个 `GlobalPos`（维度 + 坐标）+ 交互面，手势沿用 GTM 闪存范式。
     *   ⚠️ 主控塔本期不移植，所以绑定手势现在**打不出来**（`TimeFlowTowers.find` 恒 null，见
     *   [TimeClockBehavior] 的 TODO(主塔切片)）。
     *
     * 贴图：`assets/gtetscore/textures/item/time_clock.png`（从老工程原样搬过来的 16×16 极简占位图）。
     */
    @JvmField
    val CLOCK_OF_TIME_SEQUENCE: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["clock_of_time_sequence"] = "§b时序钟"

        // 归页用 inTab 包住整条注册链：GTM 的 creativeModeTab 只对「之后」注册的东西生效
        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                // ⚠️ 物品 id 按老工程定稿为 clock_of_time_sequence，别改（老工程刚从 time_clock 全量改名过来）
                .item("clock_of_time_sequence", ::ComponentItem)
                // 同上：清掉 Registrate 的影子归页，免得开背包重建创造页时重复入页崩游戏
                .noDefaultTab()
                .lang("§bTime clock")
                .properties { properties -> properties.stacksTo(1) }
                .model { ctx, provider ->
                    provider.generated(ctx, GTETSCore.id("item/time_clock"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        TimeClockBehavior,
                        TooltipBehavior { lines ->
                            lines.add(Component.translatable(TimeClockLang.TOOLTIP_CARRY))
                            lines.add(Component.translatable(TimeClockLang.TOOLTIP_UPGRADE))
                        }
                    )
                }
                .register()
        }
    }

    /**
     * 结构工具 — 右键方块选区（第一下钉住起点，之后每次挪对角终点），潜行右键清除，右键空气开导出面板。
     *
     * 交互与导出在 [StructureWriteBehavior]，面板在 `client/mui/StructureExportPanel`；
     * 贴图沿用原版木棍（老工程三件工具都是 `minecraft:item/stick` 的 generated 模型，不新增素材）。
     */
    @JvmField
    val STRUCTURE_TOOLS: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["structure_tools"] = "结构工具"

        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                .item("structure_tools", ::ComponentItem)
                .noDefaultTab()
                .lang("Structure Tool")
                .properties { properties -> properties.stacksTo(1) }
                .model { ctx, provider ->
                    provider.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        StructureWriteBehavior,
                        TooltipBehavior { lines ->
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_SELECT))
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_CLEAR))
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_OPEN_PANEL))
                        }
                    )
                }
                .register()
        }
    }

    /** 结构刷新工具 — 潜行右键多方块控制器强制重检（[TerminalBehavior]）。 */
    @JvmField
    val STRUCTURE_CHECKER: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["structure_checker"] = "结构刷新工具"

        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                .item("structure_checker", ::ComponentItem)
                .noDefaultTab()
                .lang("Structure Recheck Tool")
                .properties { properties -> properties.stacksTo(1) }
                .model { ctx, provider ->
                    provider.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        TerminalBehavior,
                        TooltipBehavior { lines ->
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_RECHECK))
                        }
                    )
                }
                .register()
        }
    }

    /** 结构检测工具 — 右键多方块控制器当场跑一遍结构检测，错误位置画线框（[StructureDetectBehavior]）。 */
    @JvmField
    val STRUCTURE_DETECT: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["structure_detect"] = "结构检测工具"

        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                .item("structure_detect", ::ComponentItem)
                .noDefaultTab()
                .lang("Structure Detect Tool")
                .properties { properties -> properties.stacksTo(1) }
                .model { ctx, provider ->
                    provider.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        StructureDetectBehavior,
                        TooltipBehavior { lines ->
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_DETECT_1))
                            lines.add(Component.translatable(StructureToolLang.TOOLTIP_DETECT_2))
                        }
                    )
                }
                .register()
        }
    }

    /**
     * 配方编辑器 — 右键空气开面板，草稿存在数据组件 `gtetscore:recipe_editor` 上，
     * 导出成 Kotlin 代码片段（目录走 `GtetConfig.recipeExportDirectory()`）。
     *
     * 交互与导出在 [rain.fox.gtetcore.common.item.recipe.RecipeEditorBehavior]，
     * 面板在 `client/mui/RecipeEditorPanel`；贴图沿用原版木棍（与三件结构工具同款，不新增素材）。
     */
    @JvmField
    val RECIPE_EDITOR: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["recipe_editor"] = "配方编辑器"

        ETRegistrate.REGISTRATE.inTab(ETCreativeModeTabs.ITEM) {
            ETRegistrate.REGISTRATE
                .item("recipe_editor", ::ComponentItem)
                .noDefaultTab()
                .lang("Recipe Editor")
                .properties { properties -> properties.stacksTo(1) }
                .model { ctx, provider ->
                    provider.generated(ctx, ResourceLocation.withDefaultNamespace("item/stick"))
                }
                .onRegister { item ->
                    item.attachComponents(
                        RecipeEditorBehavior,
                        TooltipBehavior { lines ->
                            lines.add(Component.translatable(RecipeEditorLang.TOOLTIP_OPEN))
                            lines.add(Component.translatable(RecipeEditorLang.TOOLTIP_KINDS))
                            lines.add(Component.translatable(RecipeEditorLang.TOOLTIP_EXPORT))
                        }
                    )
                }
                .register()
        }
    }

}
