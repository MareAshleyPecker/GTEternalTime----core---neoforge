package rain.fox.gtetcore.common.item.recipe

import com.gregtechceu.gtceu.api.GTValues

/**
 * 配方编辑器的电压档表 —— GTM 的 15 档（下标 `0..MAX`）**加上** 16 个特殊档 `MAX+1 … MAX+16`。
 *
 * 为什么要 GTET 自己建表：GTM 的 `VN` / `V` / `VA` 都只到 `MAX`（`MAX` = 14）——
 * `VN`、`V` 长度 15，`VA` 更是 `int[15]`，`MAX+1` 的 2^33 根本装不下。
 * 所以 MAX 之后的档位**只作选项**：界面里能选、代码里能生成，但不注册方块、不动机壳贴图，
 * 也一个字节都不改 GTM 的 `GTValues`。
 *
 * ⚠️ 档数与档名不是拍脑袋定的，而是对齐 GTM 自己的扩展表（这里只**读**它的常量）：
 *  - `GTValues.VEX`（"The Voltage Tiers"）是 `long[31]`，索引 `15..30` 正好是 `MAX+1..MAX+16` 的电压；
 *  - `GTValues.VNF` 同样 31 项，`VNF[15..30]` 就是 GTM 自己写的 "MAX+1"…"MAX+16"（带颜色码）；
 *  - `GTValues.MAX_TRUE` = 30，就是最后那一档的下标。
 *
 * 老工程（1.20.1 / GTM 7.5.3）与 8.0.0 的 `GTValues` 这几个字段签名逐字一致，本文件**一行未改**。
 *
 * @author rain fox
 */
object VoltageTiers {

    /** GTM 自己的档数（`GTValues.VN` 长度 = `MAX` + 1 = 15），下标 `0..MAX` 仍然照 `VN` 解释。 */
    @JvmField
    val GTM_TIERS: Int = GTValues.VN.size

    /** 特殊档数：`MAX+1 … MAX+16`，正好是 GTM `VEX` / `VNF` 比 `VN` 多出来的那 16 项。 */
    const val SPECIAL_TIERS: Int = 16

    /** 合法档位数（合法下标是 `0 .. TOTAL_TIERS - 1`）。 */
    @JvmField
    val TOTAL_TIERS: Int = GTM_TIERS + SPECIAL_TIERS

    /** 特殊档的下标范围（`MAX+1` 起，到 `MAX+16` 止）。 */
    @JvmField
    val SPECIAL_RANGE: IntRange = GTM_TIERS..<TOTAL_TIERS

    /** 是不是特殊档（`MAX` 之后；GTM 的 `VA`/`VN` 里没有这些下标）。 */
    @JvmStatic
    fun isSpecial(tier: Int): Boolean = tier >= GTM_TIERS

    /** 把档位夹进合法范围：负数当 ULV、越界当最后一档（`MAX+16`）。 */
    @JvmStatic
    fun coerce(tier: Int): Int = tier.coerceIn(0, TOTAL_TIERS - 1)

    /**
     * 档位显示名。GTM 档就是 `GTValues.VN[tier]`（ULV…MAX），特殊档按 GTM 的写法拼 `MAX+n`：
     * 下标 15 → `MAX+1`、下标 30 → `MAX+16`（`MAX` 就是下标 14，所以减出来正好是 n）。
     * 越界的档位先 [coerce] 再取名。
     */
    @JvmStatic
    fun name(tier: Int): String {
        val index = coerce(tier)
        return if (index < GTM_TIERS) GTValues.VN[index] else "MAX+${index - GTValues.MAX}"
    }

    /**
     * 该档的电压（EU/t 量级）。GTM 档走 `GTValues.VEX`（与 `V` 同值），特殊档也走它 ——
     * `VEX` 本来就多带了这 16 项，不用自己抄一遍数字。
     *
     * `VEX` 万一变短（GTM 改表）就退回自己按 ×4 推，并且**在溢出前夹到 `Long.MAX_VALUE`**：
     * `MAX+16` 的 4 倍是 2^63，已经超出 long 上限（GTM 在 `VEX` 最后一项也是这么夹的）。
     */
    @JvmStatic
    fun voltage(tier: Int): Long {
        val index = coerce(tier)
        if (index in GTValues.VEX.indices) return GTValues.VEX[index]

        var value = GTValues.V[GTValues.MAX]
        repeat(index - GTValues.MAX) {
            value = if (value > Long.MAX_VALUE / 4) Long.MAX_VALUE else value * 4
        }
        return value
    }

    /**
     * 选中该档时给草稿的默认耗电（[RecipeDraft.eut]）。
     *
     * GTM 档沿用原来那句 `GTValues.VA[tier]`（= `V × 30/32`，GT「避免整安培配方」的约定），
     * 所以 `0..MAX` 的手感和改动前完全一致；特殊档没有对应的 VA（`VA` 是 `int[15]`，装不下 2^33），
     * 就直接给这一档的电压本身 —— 也就是「MAX 之后每档 ×4」的那个值。
     */
    @JvmStatic
    fun defaultEut(tier: Int): Long {
        val index = coerce(tier)
        return if (index in GTValues.VA.indices) GTValues.VA[index].toLong() else voltage(index)
    }
}
