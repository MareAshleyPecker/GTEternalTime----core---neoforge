package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.world.item.CreativeModeTab
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 创造标签页。
 *
 * 照老工程 `GTETCreativeModeTabs` 的做法自建标签页（老工程一个内容域一个页，这里目前只有高级终端一件、
 * 先只开一个总页）。8.0.0 的 API 与老工程**完全一致**，没有改写法：
 * - `GTRegistrate.defaultCreativeTab(name, config)`（`com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate`）；
 * - 内容表用 GTM 自己的 `GTCreativeModeTabs.RegistrateDisplayItemsGenerator(name, registrate)`：
 *   它遍历本 registrate 注册的全部物品、用 `isInCreativeTab` 过滤出属于本页的那些（一个物品只会出现一次）；
 * - 标题走 registrate 的 `addLang("itemGroup", ...)`，中文另走 [LangUtil.TAB_LANG] → `ZhCnLangProvider`。
 *
 * ⚠️ 物品**归页**不在这里做：`GTRegistrate.creativeModeTab(tab)` 设的是「当前页」，
 * 只对**之后**注册的物品生效（GTM 自己也这么用，见 `GTItems` 里的 `REGISTRATE.creativeModeTab(() -> ITEM)`）。
 * 所以由 `ETItems` 在注册高级终端之前设一次，见那边的注释。
 *
 * @author rain fox
 */
object ETCreativeModeTabs {

    /** 标签页注册名；语言键是 `itemGroup.gtetcore.<这个名字>`。 */
    const val TAB_NAME: String = "gtet"

    /** GTET 总页：把 GTET 自己注册的物品收在一处，免得只能 `/give`。 */
    @JvmField
    val gtet: RegistryEntry<CreativeModeTab, CreativeModeTab> = run {
        // 双语分工：en 走下面 registrate 的 addLang（写 en_us），cn 走 LangUtil.TAB_LANG（写 zh_cn）。
        // GTEternalTime 是专有名词，中英同形。
        LangUtil.TAB_LANG[TAB_NAME] = "GTEternalTime"

        ETRegistrate.REGISTRATE
            .defaultCreativeTab(TAB_NAME) { builder ->
                builder
                    .displayItems(
                        GTCreativeModeTabs.RegistrateDisplayItemsGenerator(TAB_NAME, ETRegistrate.REGISTRATE)
                    )
                    // 图标用高级终端自己（懒取值，注册表建好之后才会被调到，不会与 ETItems 的初始化打架）
                    .icon { ETItems.ADVANCED_TERMINAL.asStack() }
                    .title(ETRegistrate.REGISTRATE.addLang("itemGroup", GTETCore.id(TAB_NAME), "GTEternalTime"))
                    .build()
            }
            .register()
    }
}
