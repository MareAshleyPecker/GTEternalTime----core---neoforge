package rain.fox.gtetcore.data.lang

import net.minecraft.data.PackOutput
import net.neoforged.neoforge.common.data.LanguageProvider
import rain.fox.gtetcore.Gtetcore
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 只写 `zh_cn.json` 的语言数据生成器。
 *
 * en_us 由 registrate 自己的语言生成器负责（`langValue(...)` + 自动生成的 en_ud），
 * 这里只补中文，两边各写各的文件、不抢同一个路径（否则 `processResources` 会因重名直接失败）。
 *
 * 中文条目先登记进 [LangUtil]，再由本生成器统一写盘；材料类前缀翻译等后续功能移植过来时
 * 照老项目那样往 [LangUtil] 里补即可。
 */
class ZhCnLangProvider(output: PackOutput) : LanguageProvider(output, Gtetcore.ID, "zh_cn") {

    override fun addTranslations() {
        LangUtil.BLOCK_LANG.forEach { (id, name) -> add("block.${Gtetcore.ID}.$id", name) }
        LangUtil.ITEM_LANG.forEach { (id, name) -> add("item.${Gtetcore.ID}.$id", name) }
        LangUtil.TAB_LANG.forEach { (id, name) -> add("itemGroup.${Gtetcore.ID}.$id", name) }
        LangUtil.CUSTOM_LANG.forEach { (key, pair) -> add(key, pair.second) }
    }
}
