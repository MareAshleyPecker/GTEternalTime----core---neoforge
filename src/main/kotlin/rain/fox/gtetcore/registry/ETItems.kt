package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.item.ComponentItem
import com.tterrag.registrate.util.entry.ItemEntry
import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.Gtetcore
import rain.fox.gtetcore.common.item.terminal.AdvancedTerminalBehavior
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 物品注册入口。
 *
 * 目前只登记高级终端这一件（本阶段只做「潜行右键控制器 = 自动搭建」，设置面板与 AE 链接留给后续切片）。
 * Registrate 是在 builder 被创建的那一刻就登记的，所以**必须在 mod 构造期取一次**
 * [ADVANCED_TERMINAL] 的值（见 `Gtetcore.init`），否则物品进不了注册表。
 */
object ETItems {

    /** 「一次搭建的方块数超限」提示键；值必须与 `AdvancedTerminalBuilder` 里引用的字面量一致。 */
    @Suppress("ConstPropertyName")
    private const val build_too_many: String = "item.gtetcore.advanced_terminal.build.too_many"

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
                provider.generated(ctx, ResourceLocation.fromNamespaceAndPath(Gtetcore.ID, "item/advanced_terminal"))
            }
            .onRegister { item -> item.attachComponents(AdvancedTerminalBehavior) }
            .register()
    }
}
