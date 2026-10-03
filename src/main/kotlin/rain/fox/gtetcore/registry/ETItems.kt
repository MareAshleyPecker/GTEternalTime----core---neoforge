package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.gregtechceu.gtceu.common.item.behavior.TooltipBehavior
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalBehavior
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 物品注册入口。
 *
 * 目前只登记高级终端这一件（潜行右键控制器 = 自动搭建；右键空气 = 开 MUI 设置面板。AE 链接留给后续切片）。
 * Registrate 是在 builder 被创建的那一刻就登记的，所以**必须在 mod 构造期取一次**
 * [ADVANCED_TERMINAL] 的值（见 `CommonProxy.kotlinInit`），否则物品进不了注册表。
 */
object ETItems {

    /** 「一次搭建的方块数超限」提示键；值必须与 `AdvancedTerminalBuilder` 里引用的字面量一致。 */
    @Suppress("ConstPropertyName")
    private const val build_too_many: String = "item.gtetcore.advanced_terminal.build.too_many"

    /**
     * 物品 tooltip 键：老工程三行 tooltip 里**本阶段唯一成立**的那行（右键空气开面板）。
     *
     * 另两行（潜行右键控制器自动搭建 / 潜行右键绑定 AE）分别属于搭建手势与未移植的 AE 切片，这里不登记。
     * 键名用描述式而不是老工程的 `tooltip.<序号>`，免得将来照序号补行时撞键。
     */
    @Suppress("ConstPropertyName")
    private const val tip_open_panel: String = "item.gtetcore.advanced_terminal.tooltip.open_panel"

    init {
        // 双语条目：英文进 registrate 的 en_us，中文由 ZhCnLangProvider 写 zh_cn（各写各的文件）。
        // ⚠️ 物品名那条不要在这里写：`.lang(...)` 负责 en、LangUtil.ITEM_LANG 负责 cn，
        //    再用 LangUtil.add 写一遍会因为「重复的翻译键」让数据生成直接失败。

        // 把「当前创造页」设成本次要注册的物品所属的页 —— 只对**之后**注册的物品生效，
        // 所以必须在下面创建高级终端之前设（GTCreativeModeTabs 里的页也因此在这一行被登记）。
        ETRegistrate.REGISTRATE.creativeModeTab(ETCreativeModeTabs.gtet)

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
    }

    /** 高级终端 — 潜行右键多方块控制器自动搭建；右键空气开设置面板（MUI，见 `AdvancedTerminalPanel`）。 */
    @JvmField
    val ADVANCED_TERMINAL: ItemEntry<ComponentItem> = run {
        LangUtil.ITEM_LANG["advanced_terminal"] = "§b高级终端"

        ETRegistrate.REGISTRATE
            .item("advanced_terminal", ::ComponentItem)
            .lang("§bAdvanced Terminal")
            .properties { properties -> properties.stacksTo(1) }
            // 贴图自备：assets/gtetcore/textures/item/advanced_terminal.png
            .model { ctx, provider ->
                provider.generated(ctx, GTETCore.id("item/advanced_terminal"))
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
