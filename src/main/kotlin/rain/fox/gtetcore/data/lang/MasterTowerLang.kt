package rain.fox.gtetcore.data.lang

import rain.fox.gtetcore.common.data.machine.multiblock.ETMasterTower
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 主控塔的双语条目。
 *
 * - **静态行** [TOOLTIP_0] ~ [TOOLTIP_2]：注册时挂到多方块定义上；
 * - **运行时行**（储量 / 容量 / 汇率 / 所有者）：这里只定义键名，使用侧必须 `Component.translatable(键, 参数...)`。
 *
 * 登记时机：`CommonProxy.initLang` 调一次，必须早于数据生成（与 [TimeFlowHatchLang] 同一套约定）。
 *
 * @author rain fox
 */
object MasterTowerLang {

    /** 运行时行的键前缀。 */
    private const val PREFIX = "gtetscore.master_tower."

    /** 部件 / 物品提示的键前缀（与 `ETTimeFlowHatches` 的 `gtetscore.machine.<id>.tooltip.<n>` 同形）。 */
    private const val MACHINE_PREFIX = "gtetscore.machine.master_tower.tooltip."

    /** 储备（TF）与折算 EU。 */
    const val STORED: String = PREFIX + "stored"

    /** 容量（TF）：段数 × 每段容量。 */
    const val CAPACITY: String = PREFIX + "capacity"

    /** 当前潮汐汇率与相位。 */
    const val RATE: String = PREFIX + "rate"

    /** 所有者行。 */
    const val OWNER: String = PREFIX + "owner"

    /** 无所有者（还没被右键认领）时的所有者行。 */
    const val OWNER_NONE: String = PREFIX + "owner_none"

    /** 因「全服唯一」而没能成型时，结构错误里显示的红字。 */
    const val DUPLICATE: String = PREFIX + "duplicate"

    /** 掉落物（塔芯）上显示的封存储备。 */
    const val SEALED: String = PREFIX + "sealed"

    /** tooltip 第 0 行：它是唯一把 EU 换成 TF 的闸口。 */
    const val TOOLTIP_0: String = MACHINE_PREFIX + "0"

    /** tooltip 第 1 行：结构与容量。 */
    const val TOOLTIP_1: String = MACHINE_PREFIX + "1"

    /** tooltip 第 2 行：能源仓与拆塔封存。 */
    const val TOOLTIP_2: String = MACHINE_PREFIX + "2"

    /** 幂等登记（同名键重复登记只是覆盖同一张表）。 */
    @JvmStatic
    fun register() {
        LangUtil.add(STORED, "Stored %s TF (= %s EU)", "储备 %s TF（= %s EU）")
        LangUtil.add(
            CAPACITY,
            "Capacity %s TF (%s segments x %s TF)",
            "容量 %s TF（%s 段 × %s TF/段）"
        )
        LangUtil.add(RATE, "Tide rate %sx (phase %s)", "潮汐汇率 %s×（相位 %s）")
        LangUtil.add(OWNER, "Owner: %s", "所有者：%s")
        LangUtil.add(OWNER_NONE, "Owner: unclaimed", "所有者：尚未认领")
        LangUtil.add(
            DUPLICATE,
            "Not formed: masterTowerUnique is on and another master tower already exists on this server.",
            "未成型：masterTowerUnique 已开启，本服务器上已经有另一座主控塔了。"
        )
        LangUtil.add(SEALED, "Sealed reserve: %s TF (= %s EU)", "封存储备：%s TF（= %s EU）")

        LangUtil.add(
            TOOLTIP_0,
            "The only gate where EU is exchanged into time flow (TF). 1 TF = 8,192 EU.",
            "全服唯一把 EU 换成时间流（TF）的闸口。1 TF = 8,192 EU。"
        )
        LangUtil.add(
            TOOLTIP_1,
            "3x3 footprint; 1 to ${ETMasterTower.MAX_SEGMENTS} body segments stacked upwards. Capacity = segments x (TF per segment).",
            "3×3 底面积，塔身往上有 1~${ETMasterTower.MAX_SEGMENTS} 段。容量 = 段数 × 每段容量。"
        )
        LangUtil.add(
            TOOLTIP_2,
            "Accepts GT energy hatches. Breaking the controller seals the reserve into the dropped item; placing it back restores it.",
            "可插 GTM 能源仓。敲掉控制器会把储备封存进掉落物，放回去即还回塔里。"
        )
    }
}
