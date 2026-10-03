package rain.fox.gtetcore.data.lang

import net.minecraft.core.Direction
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 机器 3D 输入输出配置页的语言键。
 *
 * 登记进 [LangUtil] 后：en 由 registrate 的语言钩子写进 en_us，cn 由 [ZhCnLangProvider] 写进 zh_cn。
 *
 * @author rain fox
 */
object MachineIoConfigLang {

    /** 语言键前缀。 */
    const val PREFIX: String = "gtetscore.machine.io_config"

    const val TITLE: String = "$PREFIX.title"
    const val BUTTON: String = "$PREFIX.button"
    const val HINT_ITEM: String = "$PREFIX.hint.item"
    const val HINT_FLUID: String = "$PREFIX.hint.fluid"
    const val FACE_ITEM: String = "$PREFIX.face.item"
    const val FACE_FLUID: String = "$PREFIX.face.fluid"
    const val FACE_NONE: String = "$PREFIX.face.none"

    /** 六个面的朝向名。1.21 的 `Direction.getName()` 返回 up/down/north/... 且没有翻译键，只能自备。 */
    const val FACE_UP: String = "$PREFIX.face.up"
    const val FACE_DOWN: String = "$PREFIX.face.down"
    const val FACE_NORTH: String = "$PREFIX.face.north"
    const val FACE_SOUTH: String = "$PREFIX.face.south"
    const val FACE_EAST: String = "$PREFIX.face.east"
    const val FACE_WEST: String = "$PREFIX.face.west"

    private val FACE_KEYS: Map<Direction, String> = mapOf(
        Direction.UP to FACE_UP,
        Direction.DOWN to FACE_DOWN,
        Direction.NORTH to FACE_NORTH,
        Direction.SOUTH to FACE_SOUTH,
        Direction.EAST to FACE_EAST,
        Direction.WEST to FACE_WEST
    )

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.kotlinInit` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        LangUtil.add(TITLE, "I/O Configuration", "输入输出配置")
        LangUtil.add(BUTTON, "Open the 3D I/O configuration page", "打开 3D 输入输出配置页")
        LangUtil.add(HINT_ITEM, "Left-click a face: set it as the item output side", "左键点面：设为物品输出面")
        LangUtil.add(HINT_FLUID, "Right-click a face: set it as the fluid output side", "右键点面：设为流体输出面")
        LangUtil.add(FACE_ITEM, "Item output: %s", "物品输出面：%s")
        LangUtil.add(FACE_FLUID, "Fluid output: %s", "流体输出面：%s")
        LangUtil.add(FACE_NONE, "none", "未设置")

        LangUtil.add(FACE_UP, "Up", "顶面")
        LangUtil.add(FACE_DOWN, "Down", "底面")
        LangUtil.add(FACE_NORTH, "North", "北面")
        LangUtil.add(FACE_SOUTH, "South", "南面")
        LangUtil.add(FACE_EAST, "East", "东面")
        LangUtil.add(FACE_WEST, "West", "西面")
    }

    /** 绝对朝向 → 语言键。 */
    @JvmStatic
    fun faceKey(direction: Direction): String = FACE_KEYS.getOrDefault(direction, FACE_UP)
}
