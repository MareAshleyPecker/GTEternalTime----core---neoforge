package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.common.item.behavior.TooltipBehavior
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalBehavior
import rain.fox.gtetcore.common.item.timeflow.TimeClockBehavior
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 物品注册入口。
 *
 * 目前登记两件：高级终端（潜行右键控制器 = 自动搭建；右键空气 = 开 MUI 设置面板。AE 链接留给后续切片）
 * 与时序钟（时间流 TF 的显示 / 搬运道具，见 `common/item/timeflow/`）。
 * Registrate 是在 builder 被创建的那一刻就登记的，所以**必须在 mod 构造期取一次**
 * [ADVANCED_TERMINAL] 的值（见 `CommonProxy.kotlinInit`），否则物品进不了注册表。
 */
object ETItems {

    /** 「一次搭建的方块数超限」提示键；值必须与 `AdvancedTerminalBuilder` 里引用的字面量一致。 */
    @Suppress("ConstPropertyName")
    private const val build_too_many: String = "item.gtetscore.advanced_terminal.build.too_many"

    /**
     * 物品 tooltip 键：老工程三行 tooltip 里**本阶段唯一成立**的那行（右键空气开面板）。
     *
     * 另两行（潜行右键控制器自动搭建 / 潜行右键绑定 AE）分别属于搭建手势与未移植的 AE 切片，这里不登记。
     * 键名用描述式而不是老工程的 `tooltip.<序号>`，免得将来照序号补行时撞键。
     */
    @Suppress("ConstPropertyName")
    private const val tip_open_panel: String = "item.gtetscore.advanced_terminal.tooltip.open_panel"

    /** 时序钟的两行静态说明（带数字的那些行在 `TimeClockLang` 里）。 */
    @Suppress("ConstPropertyName")
    private const val clock_tip_carry: String = "item.gtetscore.clock_of_time_sequence.tooltip.carry"

    @Suppress("ConstPropertyName")
    private const val clock_tip_upgrade: String = "item.gtetscore.clock_of_time_sequence.tooltip.upgrade"

    init {
        // 双语条目：英文进 registrate 的 en_us，中文由 ZhCnLangProvider 写 zh_cn（各写各的文件）。
        // ⚠️ 物品名那条不要在这里写：`.lang(...)` 负责 en、LangUtil.ITEM_LANG 负责 cn，
        //    再用 LangUtil.add 写一遍会因为「重复的翻译键」让数据生成直接失败。

        LangUtil.add(
            build_too_many,
            "Structure is too large for one build: %s blocks (limit %s)",
            "结构过大，一次搭建的方块上限：%s 格（上限 %s）"
        )
        // 老代码「潜行右键非控制器方块」走的是 AE 绑定手势、什么都不提示；AE 切片未做，这里改成明确反馈
        LangUtil.add(
            AdvancedTerminalBehavior.msg_not_controller,
            "This block is not a multiblock controller",
            "该方块不是多方块控制器"
        )
        // 物品 tooltip（老工程原文照搬）
        LangUtil.add(
            tip_open_panel,
            "Right-click air: open the terminal settings",
            "右键空气：打开终端设置"
        )

        // 时序钟的静态 tooltip 两行（老工程原文照搬）：
        // 「绑定主控塔」那一条手势的运行时提示在 TimeClockLang 里，走的是带数字的 translatable。
        LangUtil.add(
            clock_tip_carry,
            "Stores time flow (TF); bind it to a master tower to carry TF across dimensions",
            "存储时间流（TF）；绑定主控塔后可跨维度搬运"
        )
        LangUtil.add(
            clock_tip_upgrade,
            "Upgrade items raise the capacity tier (L1 -> L3); stored TF is kept",
            "用升级件提升容量档位（L1 → L3），钟内 TF 不丢"
        )
    }

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
                        TooltipBehavior { lines -> lines.add(Component.translatable(tip_open_panel)) }
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
                            lines.add(Component.translatable(clock_tip_carry))
                            lines.add(Component.translatable(clock_tip_upgrade))
                        }
                    )
                }
                .register()
        }
    }
}
