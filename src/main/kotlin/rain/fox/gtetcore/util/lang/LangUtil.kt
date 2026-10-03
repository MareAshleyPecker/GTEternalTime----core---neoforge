package rain.fox.gtetcore.util.lang

/** 双语翻译缓存，供 zh_cn 数据生成器（[rain.fox.gtetcore.data.lang.ZhCnLangProvider]）消费。 */
object LangUtil {

    /** 创造标签页名字：id → 中文。 */
    val TAB_LANG: MutableMap<String, String> = LinkedHashMap()

    /** 物品名字：id → 中文。 */
    val ITEM_LANG: MutableMap<String, String> = LinkedHashMap()

    /** 方块/机器名字：id → 中文。 */
    val BLOCK_LANG: MutableMap<String, String> = LinkedHashMap()

    /** 通用双语条目：key → (en, cn)。 */
    val CUSTOM_LANG: MutableMap<String, Pair<String, String>> = LinkedHashMap()

    /** 登记一条双语条目；`@JvmStatic` 让 Java 侧（mixin、注册文件）也能直接调用。 */
    @JvmStatic
    fun add(keyWord: String, en: String, cn: String) {
        CUSTOM_LANG[keyWord] = en to cn
    }
}
