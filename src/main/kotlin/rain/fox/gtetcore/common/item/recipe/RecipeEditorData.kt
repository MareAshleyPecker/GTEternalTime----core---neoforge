package rain.fox.gtetcore.common.item.recipe

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.fluids.FluidStack
import rain.fox.gtetcore.registry.ETDataComponents

/**
 * 配方编辑器草稿的**数据组件值**（`gtetscore:recipe_editor`）。
 *
 * 老工程这份草稿写的是手持物品 NBT 的 `recipe_editor` 子树（`RecipeDraft.KEY`），
 * 1.21 取消了物品 NBT，所以整体搬成数据组件：字段一一对应，只是槽位从
 * 「`ItemStackHandler` 的 NBT」变成了**定长列表**（空槽也占位，读回来按下标对应）。
 *
 * ⚠️ 必须是不可变 + 值相等的 data class：游戏靠 `equals` 判断组件有没有变（变了才同步/才存盘）。
 *
 * ⚠️ 必须 `networkSynchronized`：面板在客户端与服务端各建一次，客户端那一刻要靠它拿到
 * 「打开界面时草稿长什么样」（配方种类 / 各段槽数 / 代码预览文案），否则会先闪一下空状态。
 * 流编码用 [ByteBufCodecs.fromCodecWithRegistries] —— `ItemStack` 的 codec 需要注册表上下文，
 * 普通的 `fromCodec` 走 `NbtOps` 会在编码组件（附魔之类）时炸。
 */
data class RecipeEditorData(
    /** 配方种类（[RecipeDraft.Kind]）。 */
    val kind: RecipeDraft.Kind = RecipeDraft.Kind.SMELTING,
    /** [RecipeDraft.Kind.GT] 专用：GT 配方类型 id。 */
    val gtType: String = RecipeDraft.DEFAULT_GT_TYPE,
    /** 配方 id（留空由 [RecipeCodeWriter.effectiveId] 自动拼）。 */
    val recipeId: String = "",
    /** 配方时间（tick）。 */
    val duration: Int = 200,
    /** 基础耗电 EU/t。 */
    val eut: Long = 30L,
    /** 电压等级下标（见 [VoltageTiers]）。 */
    val tier: Int = 1,
    /** 幽灵电路配置号；`-1` = 不用电路。 */
    val circuit: Int = -1,
    /** 往空流体槽里放流体时用的默认量（mB）。 */
    val fluidAmount: Int = 1000,
    /** 物品输入槽（定长 [RecipeDraft.MAX_INPUTS]）。 */
    val inputs: List<ItemStack> = emptyList(),
    /** 物品输出槽（定长 [RecipeDraft.MAX_OUTPUTS]）。 */
    val outputs: List<ItemStack> = emptyList(),
    /** 流体输入槽（定长 [RecipeDraft.MAX_FLUID_INPUTS]）。 */
    val fluidInputs: List<FluidStack> = emptyList(),
    /** 流体输出槽（定长 [RecipeDraft.MAX_FLUID_OUTPUTS]）。 */
    val fluidOutputs: List<FluidStack> = emptyList(),
) {

    companion object {

        /** 空草稿（没写过组件时的返回值）。 */
        @JvmField
        val EMPTY: RecipeEditorData = RecipeEditorData()

        val CODEC: Codec<RecipeEditorData> = RecordCodecBuilder.create { instance ->
            instance.group(
                RecipeDraft.Kind.CODEC.optionalFieldOf("kind", RecipeDraft.Kind.SMELTING)
                    .forGetter(RecipeEditorData::kind),
                Codec.STRING.optionalFieldOf("gt_type", RecipeDraft.DEFAULT_GT_TYPE)
                    .forGetter(RecipeEditorData::gtType),
                Codec.STRING.optionalFieldOf("recipe_id", "").forGetter(RecipeEditorData::recipeId),
                Codec.INT.optionalFieldOf("duration", 200).forGetter(RecipeEditorData::duration),
                Codec.LONG.optionalFieldOf("eut", 30L).forGetter(RecipeEditorData::eut),
                Codec.INT.optionalFieldOf("tier", 1).forGetter(RecipeEditorData::tier),
                Codec.INT.optionalFieldOf("circuit", -1).forGetter(RecipeEditorData::circuit),
                Codec.INT.optionalFieldOf("fluid_amount", 1000).forGetter(RecipeEditorData::fluidAmount),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("inputs", emptyList())
                    .forGetter(RecipeEditorData::inputs),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("outputs", emptyList())
                    .forGetter(RecipeEditorData::outputs),
                FluidStack.OPTIONAL_CODEC.listOf().optionalFieldOf("fluid_inputs", emptyList())
                    .forGetter(RecipeEditorData::fluidInputs),
                FluidStack.OPTIONAL_CODEC.listOf().optionalFieldOf("fluid_outputs", emptyList())
                    .forGetter(RecipeEditorData::fluidOutputs),
            ).apply(instance) { kind, gtType, recipeId, duration, eut, tier, circuit, fluidAmount,
                                inputs, outputs, fluidInputs, fluidOutputs ->
                RecipeEditorData(
                    kind, gtType, recipeId, duration, eut, tier, circuit, fluidAmount,
                    inputs, outputs, fluidInputs, fluidOutputs,
                )
            }
        }

        /** 网络同步用（`RegistryFriendlyByteBuf` 才带注册表上下文，`ItemStack` 编码离不开它）。 */
        @JvmField
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RecipeEditorData> =
            ByteBufCodecs.fromCodecWithRegistries(CODEC)

        /** 读草稿；没写过组件就是空草稿。 */
        @JvmStatic
        fun read(stack: ItemStack): RecipeEditorData =
            stack.get(ETDataComponents.RECIPE_EDITOR) ?: EMPTY

        /** 写草稿。 */
        @JvmStatic
        fun write(stack: ItemStack, data: RecipeEditorData) {
            stack.set(ETDataComponents.RECIPE_EDITOR, data)
        }

        /** 读成可编辑草稿；没写过组件就给一份默认草稿。 */
        @JvmStatic
        fun loadDraft(stack: ItemStack): RecipeDraft = RecipeDraft.fromData(read(stack))

        /** 把可编辑草稿写回物品。 */
        @JvmStatic
        fun saveDraft(stack: ItemStack, draft: RecipeDraft) {
            write(stack, draft.toData())
        }
    }
}
