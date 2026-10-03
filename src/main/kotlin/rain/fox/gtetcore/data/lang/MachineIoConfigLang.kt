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

    /** 展开图小格里的**单字**面名（格子只有 20px，放不下上面那种两字名）。 */
    const val FACE_SHORT_UP: String = "$PREFIX.face.short.up"
    const val FACE_SHORT_DOWN: String = "$PREFIX.face.short.down"
    const val FACE_SHORT_NORTH: String = "$PREFIX.face.short.north"
    const val FACE_SHORT_SOUTH: String = "$PREFIX.face.short.south"
    const val FACE_SHORT_EAST: String = "$PREFIX.face.short.east"
    const val FACE_SHORT_WEST: String = "$PREFIX.face.short.west"

    /** 六面图小格的 tooltip：怎么点。 */
    const val FACE_CELL_TIP: String = "$PREFIX.face.tip"

    /** 六面图小格的 tooltip：该面是机器正面，设不上（GTM 会直接拒绝）。 */
    const val FACE_CELL_FRONT: String = "$PREFIX.face.front"

    /** 四个开关的 tooltip（`%s` 填 [STATE_ON] / [STATE_OFF]）。 */
    const val TOGGLE_AUTO_ITEM: String = "$PREFIX.toggle.auto_item"
    const val TOGGLE_AUTO_FLUID: String = "$PREFIX.toggle.auto_fluid"
    const val TOGGLE_ALLOW_IN_ITEM: String = "$PREFIX.toggle.allow_in_item"
    const val TOGGLE_ALLOW_IN_FLUID: String = "$PREFIX.toggle.allow_in_fluid"

    const val STATE_ON: String = "$PREFIX.state.on"
    const val STATE_OFF: String = "$PREFIX.state.off"

    private val FACE_KEYS: Map<Direction, String> = mapOf(
        Direction.UP to FACE_UP,
        Direction.DOWN to FACE_DOWN,
        Direction.NORTH to FACE_NORTH,
        Direction.SOUTH to FACE_SOUTH,
        Direction.EAST to FACE_EAST,
        Direction.WEST to FACE_WEST
    )

    private val FACE_SHORT_KEYS: Map<Direction, String> = mapOf(
        Direction.UP to FACE_SHORT_UP,
        Direction.DOWN to FACE_SHORT_DOWN,
        Direction.NORTH to FACE_SHORT_NORTH,
        Direction.SOUTH to FACE_SHORT_SOUTH,
        Direction.EAST to FACE_SHORT_EAST,
        Direction.WEST to FACE_SHORT_WEST
    )

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.kotlinInit` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        LangUtil.add(TITLE, "I/O Configuration", "输入输出配置")
        LangUtil.add(BUTTON, "Open the I/O configuration page", "打开输入输出配置页")
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

        LangUtil.add(FACE_SHORT_UP, "U", "顶")
        LangUtil.add(FACE_SHORT_DOWN, "D", "底")
        LangUtil.add(FACE_SHORT_NORTH, "N", "北")
        LangUtil.add(FACE_SHORT_SOUTH, "S", "南")
        LangUtil.add(FACE_SHORT_EAST, "E", "东")
        LangUtil.add(FACE_SHORT_WEST, "W", "西")
        LangUtil.add(
            FACE_CELL_TIP, "Left-click: set as the item output side; right-click: set as the fluid output side",
            "左键：设为物品输出面；右键：设为流体输出面"
        )
        LangUtil.add(
            FACE_CELL_FRONT, "This is the machine's front face - it cannot be set as an output side",
            "该面是机器正面，不能设为输出面"
        )

        LangUtil.add(TOGGLE_AUTO_ITEM, "Auto-output items: %s", "自动输出物品：%s")
        LangUtil.add(TOGGLE_AUTO_FLUID, "Auto-output fluids: %s", "自动输出流体：%s")
        LangUtil.add(
            TOGGLE_ALLOW_IN_ITEM, "Allow input from the item output side: %s",
            "允许从物品输出面输入：%s"
        )
        LangUtil.add(
            TOGGLE_ALLOW_IN_FLUID, "Allow input from the fluid output side: %s",
            "允许从流体输出面输入：%s"
        )
        LangUtil.add(STATE_ON, "ON", "开")
        LangUtil.add(STATE_OFF, "OFF", "关")
    }

    /** 绝对朝向 → 语言键。 */
    @JvmStatic
    fun faceKey(direction: Direction): String = FACE_KEYS.getOrDefault(direction, FACE_UP)

    /** 绝对朝向 → 展开图小格的单字面名键。 */
    @JvmStatic
    fun shortFaceKey(direction: Direction): String = FACE_SHORT_KEYS.getOrDefault(direction, FACE_SHORT_UP)
}
