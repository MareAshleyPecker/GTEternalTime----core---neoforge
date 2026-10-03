package rain.fox.gtetcore.api.timeflow

import com.gregtechceu.gtceu.api.recipe.content.IContentSerializer
import com.mojang.serialization.Codec

/**
 * 时间流（TF）的**配方内容**：一个 long 数量，单位 TF。
 *
 * ## 存量与流量共用同一个 long
 * 放进 `tickInputs` 就是「每 tick 消耗多少 TF」，放进 `inputs` 就是「开工时一次性消耗多少 TF」，
 * 由 GTM 的 tick 输入机制负责按 tick 扣（见 [ETTimeFlowHandler] 的注释）。
 * 换算基准：`1 TF = 1A IV × 1 tick = 8192 EU`（见 [ETTimeFlow.EU_PER_TF]）。
 *
 * ## 为什么不用裸 `Long`
 * GTM 自带 `SerializerLong`，直接写 `RecipeCapability<Long>` 也能跑
 * （8.0.0 的 `CWURecipeCapability` 就是 `RecipeCapability<Integer>`）。但 TF 是**独立于 EU 的第二种
 * 能量记账**，专属类型让「TF 内容」在代码里一眼可辨，也为以后换精度（设定 §4 提到的 mTF）留了位置；
 * 代价只是一个 4 行的序列化器。
 *
 * @author rain fox
 */
data class ETTimeFlowStack(
    /** 数量，单位 TF。负数没有意义，由使用方保证非负。 */
    val amount: Long,
) {
    companion object {
        /** 空内容，用作 [IContentSerializer.defaultValue]。 */
        @JvmField
        val EMPTY: ETTimeFlowStack = ETTimeFlowStack(0L)
    }
}

/**
 * [ETTimeFlowStack] 的内容序列化器。
 *
 * ## 8.0.0 的抽象成员仍然是 4 个（已逐个 javap 核实）
 * `of` / `defaultValue` / `contentClass` / `codec`（`IContentSerializer.java:30,32,49,51`）；
 * `toNetwork` / `fromNetwork` / `toJson` / `fromJson` / `toNbt` / `fromNbt`
 * **全部是基于 [codec] 的 default 方法**（`IContentSerializer.java:14-28,53-59`），所以这里不重写它们。
 *
 * ⚠️ 与 7.5.3 的差别只有一处：`fromNbt` / `toJson` 这些 default 方法现在多了一个
 * `HolderLookup.Provider` 参数（1.21 的注册表访问），但**签名变化只落在 default 方法上**，
 * 我们一个都不覆写 ⇒ 本类源码与老工程逐字一致。
 *
 * ## 序列化格式
 * 与 GTM 的 `SerializerLong` 同构：JSON / NBT 里就是一个普通数字（codec 决定），网络里是
 * `buf.writeJsonWithCodec` 包的 JSON（default 方法）。
 *
 * ⚠️ 与老工程的唯一改写：老代码用 `org.apache.commons.lang3.math.NumberUtils.toLong(str, 0L)`
 * 解析字符串，这里换成 Kotlin 的 `toLongOrNull() ?: 0L` —— 语义完全一致（解析不出来就是 0），
 * 少一个对 commons-lang3 的直接依赖。
 *
 * @author rain fox
 */
object ETTimeFlowSerializer : IContentSerializer<ETTimeFlowStack> {

    private val CODEC: Codec<ETTimeFlowStack> = Codec.LONG.xmap({ ETTimeFlowStack(it) }, { it.amount })

    override fun of(o: Any?): ETTimeFlowStack = when (o) {
        is ETTimeFlowStack -> o
        is Number -> ETTimeFlowStack(o.toLong())
        is CharSequence -> ETTimeFlowStack(o.toString().toLongOrNull() ?: 0L)
        else -> ETTimeFlowStack.EMPTY
    }

    override fun defaultValue(): ETTimeFlowStack = ETTimeFlowStack.EMPTY

    override fun contentClass(): Class<ETTimeFlowStack> = ETTimeFlowStack::class.java

    override fun codec(): Codec<ETTimeFlowStack> = CODEC
}
