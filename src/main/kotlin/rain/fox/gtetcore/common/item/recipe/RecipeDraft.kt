package rain.fox.gtetcore.common.item.recipe

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.transfer.item.CustomItemStackHandler
import com.mojang.serialization.Codec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.fluids.FluidStack

/**
 * 配方编辑器的工作区（草稿）—— 记录「当前这个配方填了什么」。
 *
 * 整份草稿存在手持物品的**数据组件**里（`gtetscore:recipe_editor`，见 [RecipeEditorData]）：
 * 关掉界面再打开、乃至退出游戏都还在。
 *
 * ⚠️ 与老工程（1.20.1）的差别只有「存哪儿」：老工程写物品 NBT 的 `recipe_editor` 子树，
 * 1.21 取消了物品 NBT，所以整体搬到数据组件；草稿的**字段、槽位数量、按配方类型决定用几个槽**
 * 这套逻辑一字不改。老工程那段 `ItemStackHandler#deserializeNBT` 会拿存档里的旧容量覆盖当前容量、
 * 导致「Slot N not in valid range」把整个界面炸掉的历史教训，在新结构下天然不存在 ——
 * 数据组件存的是**定长列表**，读的时候按当前容量逐槽取，多出来的忽略、缺的补空。
 *
 * 槽位用 [CustomItemStackHandler] + [DraftFluidTanks]（多槽幽灵流体罐）。
 * 界面固定只建 [MAX_INPUTS] + [MAX_OUTPUTS] + [MAX_FLUID_INPUTS] + [MAX_FLUID_OUTPUTS] 这一片网格，
 * **这次实际用几个由配方类型的真实能力决定**（见 [inputSlots] 等四个方法），代码生成时只读非空槽。
 *
 * @author rain fox
 */
class RecipeDraft {

    /**
     * 支持的配方种类：原版五类（工作台 / 熔炉 + 两种变种 / 切石机 / 锻造台），
     * 外加一个 [GT]（具体用哪个 GT 配方类型看 [gtType]）。
     *
     * ⚠️ 老工程这里写的是中英文硬编码字符串；新工程文案一律走语言键（见 `RecipeEditorLang`），
     * 所以字段换成 [langKey]。
     */
    enum class Kind(val langKey: String, val inputs: Int, val outputs: Int) {
        CRAFTING_SHAPED("gtetscore.recipe_editor.kind.crafting_shaped", 9, 1),
        CRAFTING_SHAPELESS("gtetscore.recipe_editor.kind.crafting_shapeless", 9, 1),
        SMELTING("gtetscore.recipe_editor.kind.smelting", 1, 1),
        BLASTING("gtetscore.recipe_editor.kind.blasting", 1, 1),
        SMOKING("gtetscore.recipe_editor.kind.smoking", 1, 1),
        STONECUTTING("gtetscore.recipe_editor.kind.stonecutting", 1, 1),
        SMITHING("gtetscore.recipe_editor.kind.smithing", 3, 1),
        GT("gtetscore.recipe_editor.kind.gt", MAX_INPUTS, MAX_OUTPUTS);

        /** 显示名（语言键 → 文本）。 */
        fun displayName(): Component = Component.translatable(langKey)

        companion object {

            /** 存档用的编解码：认不出来的名字退回 [SMELTING]（老工程 `Kind#valueOf` 的 try-catch 同义）。 */
            @JvmField
            val CODEC: Codec<Kind> = Codec.STRING.xmap(
                { name -> entries.firstOrNull { it.name == name } ?: SMELTING },
                { it.name },
            )
        }
    }

    // ======================== 字段 ========================

    /** 配方种类。 */
    var kind: Kind = Kind.SMELTING

    /** [Kind.GT] 专用：GT 配方类型 id，例如 `gtceu:assembler`。 */
    var gtType: String = DEFAULT_GT_TYPE

    /** 配方 id（命名空间后面的那一段；留空会自动拼一个）。 */
    var recipeId: String = ""

    /** 配方时间（tick）。 */
    var duration: Int = 200

    /** 基础耗电 EU/t。 */
    var eut: Long = 30L

    /**
     * 要求电压等级：`0..MAX` 是 GTM 档（下标含义同 `GTValues.VN`，0=ULV、1=LV … 14=MAX），
     * `MAX+1` 之后是 [VoltageTiers] 那 16 个特殊档（15=MAX+1 … 30=MAX+16）。
     *
     * 合法范围由 setter 统一夹紧（[VoltageTiers.coerce]）：存档里的脏值、界面上的越界点击
     * 都进不来，[RecipeCodeWriter] 那边就不会去索引 `VA[MAX+1]` 这种不存在的常量。
     */
    var tier: Int = 1
        set(value) {
            field = VoltageTiers.coerce(value)
        }

    /** 幽灵电路配置号；`-1` 表示这个配方不用电路。 */
    var circuit: Int = -1

    /**
     * 流体槽的「放进来的量」（mB）。
     *
     * 拖进来的流体自带多少就是多少（通常是一桶 1000，JEI 里也多是 1000），
     * 而 GT 配方里的量经常是 144 / 576 / 2000 这种，所以界面给一个字段：
     * 往**空**槽里放流体时按这个值覆盖（已经放好的槽不跟着变，要改就中键点它重新填）。
     */
    var fluidAmount: Int = 1000

    val inputs = CustomItemStackHandler(MAX_INPUTS)
    val outputs = CustomItemStackHandler(MAX_OUTPUTS)

    /** 幽灵流体输入罐（多槽，界面的 `FluidSlot` 直接绑它）。 */
    val fluidInputs = DraftFluidTanks(MAX_FLUID_INPUTS)

    /** 幽灵流体输出罐。 */
    val fluidOutputs = DraftFluidTanks(MAX_FLUID_OUTPUTS)

    /** 幽灵电路的显示槽（界面里显示当前电路，并在配置号变化时同步）。 */
    val circuitSlot = CustomItemStackHandler(1)

    // ======================== 便捷读取 ========================

    /** 第 [i] 个输入（越界或空槽返回空栈）。 */
    fun input(i: Int): ItemStack =
        if (i in 0 until minOf(MAX_INPUTS, inputs.slots)) inputs.getStackInSlot(i) else ItemStack.EMPTY

    /** 第 [i] 个输出（越界或空槽返回空栈）。 */
    fun output(i: Int): ItemStack =
        if (i in 0 until minOf(MAX_OUTPUTS, outputs.slots)) outputs.getStackInSlot(i) else ItemStack.EMPTY

    /** 第一个非空输入（没有就返回空栈）。 */
    fun firstInput(): ItemStack =
        (0 until MAX_INPUTS).firstNotNullOfOrNull { input(it).takeIf { s -> !s.isEmpty } } ?: ItemStack.EMPTY

    /** 第一个非空输出（没有就返回空栈）。 */
    fun firstOutput(): ItemStack =
        (0 until MAX_OUTPUTS).firstNotNullOfOrNull { output(it).takeIf { s -> !s.isEmpty } } ?: ItemStack.EMPTY

    /** 第 [i] 个流体输入（越界返回空栈）。 */
    fun fluidInput(i: Int): FluidStack =
        if (i in 0 until MAX_FLUID_INPUTS) fluidInputs.getFluid(i) else FluidStack.EMPTY

    /** 第 [i] 个流体输出（越界返回空栈）。 */
    fun fluidOutput(i: Int): FluidStack =
        if (i in 0 until MAX_FLUID_OUTPUTS) fluidOutputs.getFluid(i) else FluidStack.EMPTY

    // ======================== 按配方类型决定「实际用几个槽」 ========================

    /**
     * 当前配方类型对应的 GT 配方类型（不是 GT 种类、或者 id 写错了就返回 null）。
     *
     * ⚠️ GTM 8.0.0 删掉了 `GTRegistries.RECIPE_TYPES`（配方类型的注册表整体并进原版的
     * `Registries.RECIPE_TYPE`，条目类型 `GTRecipeTypeEntry extends RegistryEntry<RecipeType<?>, GTRecipeType>`），
     * 所以查表与枚举都改走 `BuiltInRegistries.RECIPE_TYPE` 再按 `GTRecipeType` 过滤。
     */
    fun gtRecipeType(): GTRecipeType? =
        if (kind != Kind.GT) null
        else ResourceLocation.tryParse(gtType)?.let { BuiltInRegistries.RECIPE_TYPE.get(it) as? GTRecipeType }

    /**
     * 实际使用的输入槽数量。
     *
     * GT 类型按它自己的能力上限算（`GTRecipeType.getMaxInputs(ItemRecipeCapability.CAP)`，
     * 例如组装机 9、装配线 16、燃烧发电机 0），原版种类用 [Kind] 里写死的数量。
     */
    fun inputSlots(): Int =
        if (gtDeclaresNothing()) 1
        else slotCount(MAX_INPUTS, kind.inputs) { it.getMaxInputs(ItemRecipeCapability.CAP) }

    /** 实际使用的输出槽数量。 */
    fun outputSlots(): Int =
        if (gtDeclaresNothing()) 1
        else slotCount(MAX_OUTPUTS, kind.outputs) { it.getMaxOutputs(ItemRecipeCapability.CAP) }

    /**
     * 实际使用的流体输入槽数量。
     *
     * 流体是**原版种类没有**的能力，所以原版一律 0（界面一个流体槽都不画）；
     * GT 类型同样按真实能力来（装配线 4、大型化学反应釜 5、研磨机之类没有就是 0）。
     */
    fun fluidInputSlots(): Int = fluidSlotCount(MAX_FLUID_INPUTS) { it.getMaxInputs(FluidRecipeCapability.CAP) }

    /** 实际使用的流体输出槽数量（离心机 / 电解机 6，蒸馏塔 12 会被夹到 [MAX_FLUID_OUTPUTS]）。 */
    fun fluidOutputSlots(): Int = fluidSlotCount(MAX_FLUID_OUTPUTS) { it.getMaxOutputs(FluidRecipeCapability.CAP) }

    /** 是不是 GT 种类、但类型一个槽都没声明（GT 自己注册的 `gtceu:dummy` 占位类型就是这样）。 */
    private fun gtDeclaresNothing(): Boolean = kind == Kind.GT && gtRecipeType()?.let { type ->
        type.getMaxInputs(ItemRecipeCapability.CAP) <= 0 && type.getMaxOutputs(ItemRecipeCapability.CAP) <= 0 &&
                type.getMaxInputs(FluidRecipeCapability.CAP) <= 0 &&
                type.getMaxOutputs(FluidRecipeCapability.CAP) <= 0
    } == true

    /**
     * 把「这个类型要用几个槽」夹到 `[0, cap]`。
     *
     * `cap` 必须是**对应的**那个上限：物品输入夹到 [MAX_INPUTS]、物品输出夹到 [MAX_OUTPUTS]、
     * 流体各自夹到 [MAX_FLUID_INPUTS] / [MAX_FLUID_OUTPUTS]。两边都夹到 [MAX_INPUTS] 的话，
     * 万一某个 GT 类型声明的物品输出超过 [MAX_OUTPUTS]，界面就会给输出表建第 N+1 个幽灵槽。
     *
     * 下界是 0 而不是 1：GT 里真有「零物品输入」的类型（燃烧发电机 / 燃气轮机只有流体输入），
     * 硬给 1 个空物品槽属于凭空捏造能力。原版种类不受影响 —— 它们的数量写死在 [Kind] 里，都 ≥ 1。
     */
    private inline fun slotCount(cap: Int, vanilla: Int, gt: (GTRecipeType) -> Int): Int =
        (if (kind == Kind.GT) gtRecipeType()?.let(gt) ?: vanilla else vanilla).coerceIn(0, cap)

    /**
     * 流体槽数量：原版种类恒为 0，GT 类型按能力算，`cap` 同样是各自的上限。
     *
     * 和 [slotCount] 分开写是因为「没有流体能力」（0）和「类型查不到」（也返回 0）**都是合法结果**，
     * 不像物品那样有「类型 id 写错了就退回满容量」的兜底 —— 那会凭空给研磨机之类的机器画一排用不上的流体槽。
     */
    private inline fun fluidSlotCount(cap: Int, gt: (GTRecipeType) -> Int): Int =
        (if (kind == Kind.GT) gtRecipeType()?.let(gt) ?: 0 else 0).coerceIn(0, cap)

    // ======================== 与数据组件互转 ========================

    /** 草稿 → 数据组件值（定长快照，空槽也占位，读回来按同一套下标对应）。 */
    fun toData(): RecipeEditorData = RecipeEditorData(
        kind = kind,
        gtType = gtType,
        recipeId = recipeId,
        duration = duration,
        eut = eut,
        tier = tier,
        circuit = circuit,
        fluidAmount = fluidAmount,
        inputs = (0 until MAX_INPUTS).map { input(it) },
        outputs = (0 until MAX_OUTPUTS).map { output(it) },
        fluidInputs = fluidInputs.snapshot(),
        fluidOutputs = fluidOutputs.snapshot(),
    )

    companion object {

        const val MAX_INPUTS: Int = 64
        const val MAX_OUTPUTS: Int = 32

        /** 默认的 GT 配方类型（老工程同值）。 */
        const val DEFAULT_GT_TYPE: String = "gtceu:assembler"

        /**
         * 幽灵流体槽的容量上限。
         *
         * 为什么是 8（与老工程同值，不改）：
         *  - GTM 全部配方类型里，流体输入最多的是大型化学反应釜 5 个、
         *    流体输出最多的是离心机 / 电解机 6 个（蒸馏塔 12 个，见下），装配线是 4 个 ——
         *    8 个足够放下「装配线 4 个」这类需求，还留了余量给附属 mod 的类型；
         *  - 界面高度有限：流体槽按 8 个一行、行距 20 排，一行 8 个正好卡在
         *    「最坏情况（16 物品输入 + 8 流体输入 + 9 物品输出 + 8 流体输出）也不会压到
         *    底部按钮」的上限（详细数字见 `RecipeEditorPanel` 的布局注释）。
         *    再往上加大（比如按蒸馏塔的 12 个给两行）就会顶到按钮那一行。
         *
         * ⚠️ 所以蒸馏塔那 12 个流体输出在后 4 个上是被**夹掉**的 —— 和物品这边
         * （上限 16 / 9）一样的处理方式：界面放不下就不画，草稿里多出来的槽也不会被写进代码。
         * 真要做 12 输出的蒸馏塔配方时，在导出代码里手补两行 `.outputFluids(...)` 即可。
         */
        const val MAX_FLUID_INPUTS: Int = 8
        const val MAX_FLUID_OUTPUTS: Int = 8

        /** GT 幽灵电路的最大编号（与 GTCEu `IntCircuitBehaviour.CIRCUIT_MAX` 一致）。 */
        const val CIRCUIT_MAX: Int = 32

        /** 数据组件值 → 草稿（缺的槽补空、多的忽略，任何脏数据都退化成空）。 */
        @JvmStatic
        fun fromData(data: RecipeEditorData): RecipeDraft {
            val draft = RecipeDraft()
            draft.kind = data.kind
            draft.gtType = data.gtType.ifEmpty { DEFAULT_GT_TYPE }
            draft.recipeId = data.recipeId
            draft.duration = data.duration
            draft.eut = data.eut
            draft.tier = data.tier
            draft.circuit = data.circuit
            // 脏值（0 / 负数）夹回至少 1 mB，免得「流体量」填了 0 之后怎么拖都是空罐子
            draft.fluidAmount = maxOf(1, data.fluidAmount)

            for (i in 0 until MAX_INPUTS) {
                draft.inputs.setStackInSlot(i, data.inputs.getOrElse(i) { ItemStack.EMPTY })
            }
            for (i in 0 until MAX_OUTPUTS) {
                draft.outputs.setStackInSlot(i, data.outputs.getOrElse(i) { ItemStack.EMPTY })
            }
            val fluidsIn = draft.fluidInputs
            fluidsIn.clear()
            for (i in 0 until MAX_FLUID_INPUTS) {
                fluidsIn.setFluid(i, data.fluidInputs.getOrElse(i) { FluidStack.EMPTY })
            }
            val fluidsOut = draft.fluidOutputs
            fluidsOut.clear()
            for (i in 0 until MAX_FLUID_OUTPUTS) {
                fluidsOut.setFluid(i, data.fluidOutputs.getOrElse(i) { FluidStack.EMPTY })
            }
            return draft
        }
    }
}
