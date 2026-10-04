@file:Suppress("UNCHECKED_CAST", "unused")

package rain.fox.gtetcore.common.item.recipe

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper
import com.gregtechceu.gtceu.api.data.chemical.material.Material
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.fluids.store.FluidStorageKeys
import com.gregtechceu.gtceu.api.recipe.GTRecipeType
import com.gregtechceu.gtceu.api.registry.registrate.entry.GTRecipeTypeEntry
import com.gregtechceu.gtceu.common.data.GTRecipeTypes
import com.tterrag.registrate.util.entry.RegistryEntry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.neoforged.neoforge.fluids.FluidStack
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.config.GtetConfig
import java.io.File
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets

/**
 * 把 [RecipeDraft] 翻译成能直接粘进 datagen 的代码片段，并导出到 `GtetExport/recipes/`
 * （目录见 [GtetConfig.recipeExportDirectory]）。输出**代码**而不是 JSON：整合包里的 GT 配方基本都用代码注册，
 * 生成 `GTRecipeTypes.xxx.recipeBuilder(...)…save(provider);` 这种片段，Java / Kotlin 都能直接改改用。
 * 文件名取配方 id，重复导出直接覆盖。
 *
 * ## 与老工程（1.20.1 / GTM 7.5.3）的差异
 *
 * 1. `GTRegistries.RECIPE_TYPES` 在 8.0.0 已删 —— 配方类型的注册表并进了原版的
 *    `Registries.RECIPE_TYPE`（条目类型是 `GTRecipeTypeEntry extends RegistryEntry<RecipeType<?>, GTRecipeType>`）。
 *    所以「按 id 查类型」和「枚举全部类型」都改走 `BuiltInRegistries.RECIPE_TYPE`；
 *    生成代码里查不到常量时的兜底表达式也随之改成 `BuiltInRegistries.RECIPE_TYPE.get(...)`。
 * 2. `ForgeRegistries.ITEMS/FLUIDS` → `BuiltInRegistries.ITEM/FLUID`。
 * 3. `TagPrefix.NULL_PREFIX` 在 8.0.0 已删 —— `ChemicalHelper.getPrefix` 认不出来时返回 `null`，
 *    改用 null 判断（语义相同）。
 * 4. `ItemStack#hasTag()` 在 1.21 已删（物品 NBT 取消）—— 改用 `!isComponentsPatchEmpty`。
 * 5. 兜底表达式不再引用老工程的 `RegistriesUtil`（新工程没有这个工具类），直接给注册表查询。
 *
 * 生成出来的**片段文本**照旧是老工程那套 vanilla datagen 写法（`ShapedRecipeBuilder.shaped(…)` 等），
 * 1.21 里这些 builder 的名字与参数没变，只有 `new ResourceLocation(ns, path)` 换成了
 * `ResourceLocation.fromNamespaceAndPath(ns, path)`（1.21 起构造函数不再鼓励直接用）。
 *
 * @author rain fox
 */
object RecipeCodeWriter {

    /** 预览最多显示多少行。 */
    const val PREVIEW_LINES: Int = 16

    // ======================== 代码文本 ========================

    /** 生成完整代码文本（含头部注释）。 */
    @JvmStatic
    fun toCode(draft: RecipeDraft): String = buildString {
        append("// ").append(describe2(draft)).append('\n')
        append(
            when (draft.kind) {
                RecipeDraft.Kind.CRAFTING_SHAPED -> shaped(draft)
                RecipeDraft.Kind.CRAFTING_SHAPELESS -> shapeless(draft)
                RecipeDraft.Kind.SMELTING -> cooking(draft, "smelting")
                RecipeDraft.Kind.BLASTING -> cooking(draft, "blasting")
                RecipeDraft.Kind.SMOKING -> cooking(draft, "smoking")
                RecipeDraft.Kind.STONECUTTING -> stonecutting(draft)
                RecipeDraft.Kind.SMITHING -> smithing(draft)
                RecipeDraft.Kind.GT -> gt(draft)
            },
        )
    }

    /** 预览用的前若干行。 */
    @JvmStatic
    fun previewLines(draft: RecipeDraft): Array<String> {
        val all = toCode(draft).split("\n")
        if (all.size <= PREVIEW_LINES) return all.toTypedArray()
        return (all.take(PREVIEW_LINES) + "// …（共 ${all.size} 行）").toTypedArray()
    }

    /** 一句话描述当前草稿，用于界面标题。 */
    @JvmStatic
    fun describe(draft: RecipeDraft): String {
        val id = effectiveId(draft)
        return if (draft.kind == RecipeDraft.Kind.GT) {
            "${draft.gtType} / $id"
        } else {
            "${draft.kind.name} / $id"
        }
    }

    /** 头部注释用的更详细描述（GT 种类带上时长 / 耗电 / 电压档）。 */
    @JvmStatic
    fun describe2(draft: RecipeDraft): String {
        val id = effectiveId(draft)
        return if (draft.kind == RecipeDraft.Kind.GT) {
            "${draft.gtType} / $id / ${draft.duration}t / ${draft.eut} EU/t / ${tierName(draft.tier)}"
        } else {
            "${draft.kind.name} / $id"
        }
    }

    // ======================== 各类型的代码 ========================

    private fun shaped(draft: RecipeDraft): String {
        val out = draft.firstOutput()
        if (out.isEmpty) return "// 还没有填输出槽，先放一个产物再生成。\n"

        // 3x3 输入摊平成图案：先按行拼字符，再裁掉整空的上下行
        val legend = LinkedHashMap<String, Char>()
        var next = 'A'
        val rows = (0 until 3).map { row ->
            buildString {
                for (col in 0 until 3) {
                    val input = draft.input(row * 3 + col)
                    if (input.isEmpty) {
                        append(' ')
                        continue
                    }
                    val key = itemKey(input)
                    val ch = legend.getOrPut(key) { next++ }
                    append(ch)
                }
            }.trimEnd()
        }.let { list -> list.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() } }
        if (rows.isEmpty() || legend.isEmpty()) return shapeless(draft)

        val first = legend.keys.first()
        return buildString {
            append("ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            rows.forEach { append("        .pattern(\"").append(it).append("\")\n") }
            legend.forEach { (key, ch) ->
                append("        .define('").append(ch).append("', ").append(key).append(")\n")
            }
            append("        .unlockedBy(\"has_item\", has(").append(first).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun shapeless(draft: RecipeDraft): String {
        val out = draft.firstOutput()
        if (out.isEmpty) return "// 还没有填输出槽，先放一个产物再生成。\n"

        val used = (0 until RecipeDraft.MAX_INPUTS).map { draft.input(it) }.filter { !it.isEmpty }
        if (used.isEmpty()) return "// 还没有填输入槽，先放点材料再生成。\n"

        return buildString {
            append("ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            used.forEach { stack ->
                append("        .requires(").append(itemExpr(stack))
                if (stack.count > 1) append(", ").append(stack.count)
                append(")\n")
            }
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(used.first())).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun cooking(draft: RecipeDraft, method: String): String {
        val input = draft.firstInput()
        val out = draft.firstOutput()
        if (input.isEmpty || out.isEmpty) return "// 熔炉系配方需要一个输入 + 一个输出。\n"

        return buildString {
            append("SimpleCookingRecipeBuilder.").append(method).append('(')
                .append("Ingredient.of(").append(itemExpr(input)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(", ")
                .append("0.1F, ") // 经验值界面暂未提供，默认 0.1
                .append(draft.duration).append(")\n")
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(input)).append("))\n")
            append("        .save(provider);\n")
        }
    }

    private fun stonecutting(draft: RecipeDraft): String {
        val input = draft.firstInput()
        val out = draft.firstOutput()
        if (input.isEmpty || out.isEmpty) return "// 切石机配方需要一个输入 + 一个输出。\n"

        return buildString {
            append("SingleItemRecipeBuilder.stonecutting(Ingredient.of(").append(itemExpr(input)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(", ")
                .append(maxOf(1, out.count)).append(")\n")
            append("        .unlockedBy(\"has_item\", has(").append(itemExpr(input)).append("))\n")
            append("        .save(provider, ").append(recipeId(draft)).append(");\n")
        }
    }

    private fun smithing(draft: RecipeDraft): String {
        val template = draft.input(0)
        val base = draft.input(1)
        val addition = draft.input(2)
        val out = draft.firstOutput()
        if (template.isEmpty || base.isEmpty || addition.isEmpty || out.isEmpty) {
            return "// 锻造台配方需要模板 + 底材 + 附加材料 + 输出四个槽。\n"
        }

        return buildString {
            append("SmithingTransformRecipeBuilder.smithing(")
                .append("Ingredient.of(").append(itemExpr(template)).append("), ")
                .append("Ingredient.of(").append(itemExpr(base)).append("), ")
                .append("Ingredient.of(").append(itemExpr(addition)).append("), ")
                .append("RecipeCategory.MISC, ").append(itemExpr(out)).append(")\n")
            append("        .unlocks(\"has_item\", has(").append(itemExpr(base)).append("))\n")
            append("        .save(provider, ").append(recipeId(draft)).append(");\n")
        }
    }

    private fun gt(draft: RecipeDraft): String {
        val inputs = (0 until RecipeDraft.MAX_INPUTS).map { draft.input(it) }.filter { !it.isEmpty }
        val outputs = (0 until RecipeDraft.MAX_OUTPUTS).map { draft.output(it) }.filter { !it.isEmpty }
        // 流体槽只有 GT 种类才有（原版配方模型里没有流体这一说），所以原版那些分支完全不用管。
        // 这里遍历到容量上限而不是「当前类型用几个」：换过类型后残留在槽里的东西不该被悄悄丢掉，
        // 用户能在预览里看到多出来的那几行，自己决定删不删。
        val fluidInputs = (0 until RecipeDraft.MAX_FLUID_INPUTS).map { draft.fluidInput(it) }.filter { !it.isEmpty }
        val fluidOutputs = (0 until RecipeDraft.MAX_FLUID_OUTPUTS).map { draft.fluidOutput(it) }.filter { !it.isEmpty }
        if (inputs.isEmpty() && outputs.isEmpty() && fluidInputs.isEmpty() && fluidOutputs.isEmpty()) {
            return "// 至少填一个输入或输出槽。\n"
        }

        return buildString {
            append(typeExpr(draft.gtType)).append(".recipeBuilder(\"").append(effectiveId(draft)).append("\")\n")
            inputs.forEach { stack ->
                append("            .inputItems(").append(gtItemExpr(stack)).append(")\n")
            }
            fluidInputs.forEach { stack ->
                append("            .inputFluids(").append(fluidExpr(stack)).append(")\n")
            }
            outputs.forEach { stack ->
                append("            .outputItems(").append(gtItemExpr(stack)).append(")\n")
            }
            fluidOutputs.forEach { stack ->
                append("            .outputFluids(").append(fluidExpr(stack)).append(")\n")
            }
            // 幽灵电路：设置过才写（0 也是合法配置，所以用 -1 表示「不用」）
            if (draft.circuit >= 0) append("            .circuitMeta(").append(draft.circuit).append(")\n")
            if (draft.duration > 0) append("            .duration(").append(draft.duration).append(")\n")
            append("            .EUt(").append(eutExpr(draft)).append(")\n")
            append("            .save(provider);\n")
        }
    }

    // ======================== 导出到磁盘 ========================

    /** 导出到 `GtetExport/recipes/<配方id>.kt`；返回写出的文件。 */
    @JvmStatic
    fun export(draft: RecipeDraft): File {
        val dir = File(GtetConfig.recipeExportDirectory())
        if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录: ${dir.absolutePath}")

        val file = File(dir, "${sanitize(effectiveId(draft))}.kt")
        file.writeText(toCode(draft), StandardCharsets.UTF_8)
        GTETSCore.LOGGER.info("[gtetscore] 配方代码已导出: {}", file.absolutePath)
        return file
    }

    // ======================== 表达式工具 ========================

    /** 配方 id：没填就用「种类（或 GT 类型）+ 第一个输出」自动拼一个。 */
    @JvmStatic
    fun effectiveId(draft: RecipeDraft): String {
        val raw = draft.recipeId.trim()
        if (raw.isNotEmpty()) return sanitize(raw)

        var base = if (draft.kind == RecipeDraft.Kind.GT) path(draft.gtType) else draft.kind.name.lowercase()
        val out = draft.firstOutput()
        if (!out.isEmpty) {
            BuiltInRegistries.ITEM.getKey(out.item)?.let { base = "${base}_${it.path}" }
        }
        return sanitize(base)
    }

    /** 电压等级名（越界先夹进合法范围；GTM 档 `ULV`…`MAX`、特殊档 `MAX+1`…`MAX+16`，见 [VoltageTiers.name]）。 */
    @JvmStatic
    fun tierName(tier: Int): String = VoltageTiers.name(tier)

    /**
     * 耗电正好等于该档 `GTValues.VA[tier]` 时写 `VA[档名]`，否则写字面量。
     *
     * ⚠️ 特殊档（`MAX+1` 起）没有对应 VA 且数值都在 int 之上，必须带 `L`，否则粘进 datagen 编译不过。
     */
    private fun eutExpr(draft: RecipeDraft): String {
        val tier = VoltageTiers.coerce(draft.tier)
        if (tier in GTValues.VA.indices && GTValues.VA[tier].toLong() == draft.eut) {
            return "VA[${tierName(tier)}]"
        }
        return if (draft.eut > Int.MAX_VALUE) "${draft.eut}L" else draft.eut.toString()
    }

    /** 原版配方用的物品表达式（要 `ItemLike`）：不带数量、不给 `ItemStack`，方块常量本身就是 `ItemLike`。 */
    private fun itemExpr(stack: ItemStack): String {
        val key = BuiltInRegistries.ITEM.getKey(stack.item) ?: return "Items.AIR /* 未注册物品 */"
        ITEM_CONSTANTS[stack.item]?.let { return it.text }
        blockConstant(stack)?.let { return it.text }
        return registryItem(key)
    }

    /**
     * GT 配方用的物品表达式 —— **已经带数量**，按优先级挑写法：
     * Registrate 条目 `GTItems.X.asStack(n)` → 原版 `ItemStack(Items.X[, n])` / `ItemStack(Blocks.X[, n])`
     * → 材料 `ChemicalHelper.get(TagPrefix.dust, GTMaterials.Iron[, n])` → 注册表查询。
     *
     * ⚠️ 方块**必须**包进 `ItemStack`：`inputItems(Object, int)` 不认 `Block`，直接写 `Blocks.GLASS` 会静默丢掉这个输入。
     */
    private fun gtItemExpr(stack: ItemStack): String {
        ITEM_CONSTANTS[stack.item]?.let { return stackExpr(it, stack.count) }
        blockConstant(stack)?.let { return stackExpr(it, stack.count) }
        // 带组件（相当于老工程的带 NBT）的物品不写材料形式（材料形式描述不了组件），直接走注册表
        if (stack.isComponentsPatchEmpty) materialItemExpr(stack)?.let { return it }
        val key = BuiltInRegistries.ITEM.getKey(stack.item) ?: return "ItemStack(Items.AIR) /* 未注册物品 */"
        val ref = registryItem(key)
        return if (stack.count > 1) "ItemStack($ref, ${stack.count})" else ref
    }

    /**
     * 流体表达式：材料流体写 `GTMaterials.X.getFluid(n)`（非主键带 `FluidStorageKeys`），
     * 其余 `FluidIngredient.of(<流体>, n)`。
     *
     * 用 `FluidIngredient.of` 而不是 `inputFluids(new FluidStack(...))`：后者会把流体转成**流体标签**，
     * 对整合包里任意一个流体可能生成根本不存在的标签。拿不到 id 就给个显眼的占位。
     */
    private fun fluidExpr(stack: FluidStack): String {
        if (stack.isComponentsPatchEmpty) {
            FLUID_MATERIAL_EXPRS[stack.fluid]?.let { return String.format(it, stack.amount) }
        }
        val key = BuiltInRegistries.FLUID.getKey(stack.fluid)
            ?: return "FluidIngredient.EMPTY /* 未注册流体 */"
        val ref = FLUID_CONSTANTS[stack.fluid]
            ?: "BuiltInRegistries.FLUID.get(ResourceLocation.parse(\"${key.namespace}:${key.path}\"))"
        return "FluidIngredient.of($ref, ${stack.amount})"
    }

    /** `define(...)` 的第二个参数（既可以是物品也可以是标签，这里统一给物品常量）。 */
    private fun itemKey(stack: ItemStack): String = itemExpr(stack)

    /**
     * 注册表查询 —— 拿不到常量时的兜底写法。
     *
     * 老工程走本 mod 的 `RegistriesUtil.getItem("ns:path")`（id 写错时自己打 error 日志并回 `Items.AIR`）；
     * 新工程没有这个工具类，直接给原版注册表查询。**表达式仍然不带 import**，
     * 粘进目标类时记得 `import net.minecraft.core.registries.BuiltInRegistries;`。
     */
    private fun registryItem(key: ResourceLocation): String =
        "BuiltInRegistries.ITEM.get(ResourceLocation.parse(\"${key.namespace}:${key.path}\"))"

    /** 生成代码里的配方 id 表达式（1.21 起 `ResourceLocation` 的构造函数不再鼓励直接用）。 */
    private fun recipeId(draft: RecipeDraft): String =
        "ResourceLocation.fromNamespaceAndPath(\"${GTETSCore.ID}\", \"${effectiveId(draft)}\")"

    /** 方块物品对应的方块常量（`Blocks.X` / `GTBlocks.X`）。 */
    private fun blockConstant(stack: ItemStack): Ref? =
        (stack.item as? BlockItem)?.block?.let { BLOCK_CONSTANTS[it] }

    /** 常量引用 → 带数量的表达式：Registrate 条目走 `.asStack(n)`，原版 `Items.X` / `Blocks.X` 走 `ItemStack(X[, n])`。 */
    private fun stackExpr(ref: Ref, count: Int): String = if (ref.registrate) {
        if (count > 1) "${ref.text}.asStack($count)" else "${ref.text}.asStack()"
    } else {
        if (count > 1) "ItemStack(${ref.text}, $count)" else "ItemStack(${ref.text})"
    }

    /**
     * 材料物品 → `ChemicalHelper.get(TagPrefix.dust, GTMaterials.Iron[, n])`。
     *
     * ⚠️ 反查之后会用同一组常量正算一遍比对物品，对不上（统一化条目 / 别的 mod 同名物品）就不写这种形式。
     * ⚠️ 8.0.0 删掉了 `TagPrefix.NULL_PREFIX`：`ChemicalHelper.getPrefix` 认不出来时返回 `null`。
     */
    private fun materialItemExpr(stack: ItemStack): String? {
        val prefix = ChemicalHelper.getPrefix(stack.item) ?: return null
        val material = ChemicalHelper.getMaterialStack(stack.item).material()
        val prefixName = PREFIX_FIELDS[prefix] ?: return null
        val materialName = MATERIAL_FIELDS[material] ?: return null
        val check = ChemicalHelper.get(prefix, material, 1)
        if (check.isEmpty || check.item !== stack.item) return null
        val count = if (stack.count > 1) ", ${stack.count}" else ""
        return "$prefixName, GTMaterials.$materialName,$count"
    }

    /**
     * GT 配方类型表达式：优先给 `GTRecipeTypes` 里常量的**裸名**（GTM 的配方类都是 `import static GTRecipeTypes.*`
     * 之后直接写 `ALLOY_SMELTER_RECIPES.recipeBuilder(...)`，这样导出片段贴进去不用改），查不到才退回注册表查询。
     */
    private fun typeExpr(gtTypeRaw: String): String {
        val id = parse(gtTypeRaw) ?: return "GTRecipeTypes.DUMMY_RECIPES /* 配方类型 id 写错了 */"
        GT_TYPE_CONSTANTS[id]?.let { return it }
        return "BuiltInRegistries.RECIPE_TYPE.get(ResourceLocation.parse(\"${id.namespace}:${id.path}\")) " +
                "as GTRecipeType"
    }

    private fun path(id: String): String = parse(id)?.path ?: "recipe"

    private fun parse(raw: String): ResourceLocation? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { ResourceLocation.tryParse(it) }

    /** 文件名 / id 清洗：只留字母数字、下划线、点、斜杠、短横。 */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_./-]"), "_").ifEmpty { "recipe" }

    // ======================== 常量名反射表 ========================
    // 下面用普通 for 循环而不是 buildMap{...}：嵌套 lambda + 带标签的 return 在不同 Kotlin 插件版本下 IDE 会飘红。

    /**
     * 常量引用。[text] 是 `Holder.字段名`；[registrate] 表示是 Registrate 条目（要 `.asStack(n)`，
     * 原版 `Items.X` / `Blocks.X` 则写 `ItemStack(X[, n])`）。
     */
    private data class Ref(val text: String, val registrate: Boolean)

    /** 物品 → 常量（原版 `Items` + GTM `GTItems` + 本 mod `ETItems`）。 */
    private val ITEM_CONSTANTS: Map<Item, Ref> = holderFields(
        Item::class.java, Items::class.java.name,
        "com.gregtechceu.gtceu.common.data.GTItems",
        "rain.fox.gtetcore.registry.ETItems",
    )

    /** 方块 → 常量（原版 `Blocks` + GTM `GTBlocks`）。新工程暂无方块持有类，等有了再加一条。 */
    private val BLOCK_CONSTANTS: Map<Block, Ref> = holderFields(
        Block::class.java, Blocks::class.java.name,
        "com.gregtechceu.gtceu.common.data.GTBlocks",
    )

    /** 流体 → 常量（原版 `Fluids`；GT 材料流体走 [FLUID_MATERIAL_EXPRS]）。 */
    private val FLUID_CONSTANTS: Map<Fluid, String> =
        holderFields(Fluid::class.java, Fluids::class.java.name).mapValues { (_, ref) -> ref.text }

    /** GT 材料 → `GTMaterials` 里的字段名。 */
    private val MATERIAL_FIELDS: Map<Material, String> =
        holderFields(Material::class.java, "com.gregtechceu.gtceu.common.data.GTMaterials")
            .mapValues { (_, ref) -> ref.text.substringAfterLast('.') }

    /** `TagPrefix` → 字段名（`ingot` / `dust` / `plate` …）。 */
    private val PREFIX_FIELDS: Map<TagPrefix, String> =
        holderFields(TagPrefix::class.java, "com.gregtechceu.gtceu.api.data.tag.TagPrefix")
            .mapValues { (_, ref) -> ref.text.substringAfterLast('.') }

    /**
     * 材料流体 → `GTMaterials.X.getFluid(...)` 模板（`%d` 是量）：主键流体短形式，其余带 `FluidStorageKeys.<KEY>`。
     * 惰性构建 —— 材料要到注册阶段之后才齐全。
     */
    private val FLUID_MATERIAL_EXPRS: Map<Fluid, String> by lazy {
        val keys = listOf(
            FluidStorageKeys.LIQUID to "LIQUID",
            FluidStorageKeys.GAS to "GAS",
            FluidStorageKeys.PLASMA to "PLASMA",
            FluidStorageKeys.MOLTEN to "MOLTEN",
        )
        val map = HashMap<Fluid, String>()
        for ((material, name) in MATERIAL_FIELDS) {
            if (!material.hasFluid()) continue
            val primary = try {
                material.getFluid()
            } catch (e: Throwable) {
                null
            }
            if (primary != null) map.putIfAbsent(primary, "GTMaterials.$name.getFluid(%d)")
            for ((key, keyName) in keys) {
                val fluid = try {
                    material.getFluid(key)
                } catch (e: Throwable) { // 该材料没有这一档流体时 getFluid 会抛
                    null
                } ?: continue
                map.putIfAbsent(fluid, "GTMaterials.$name.getFluid(FluidStorageKeys.$keyName, %d)")
            }
        }
        map
    }

    /**
     * 配方类型 id → `GTRecipeTypes` 里的常量名。
     *
     * ⚠️ 8.0.0 里这些字段的类型是 `GTRecipeTypeEntry`（Registrate 条目）而不是 `GTRecipeType`，
     * 所以要 `isAssignableFrom(GTRecipeTypeEntry)` + `entry.get()` 取实例；id 直接读 `GTRecipeType.id`。
     */
    private val GT_TYPE_CONSTANTS: Map<ResourceLocation, String> = HashMap<ResourceLocation, String>().apply {
        for (field in GTRecipeTypes::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            if (!GTRecipeTypeEntry::class.java.isAssignableFrom(field.type)) continue

            val type = try {
                (field.get(null) as? GTRecipeTypeEntry)?.get()
            } catch (e: Throwable) {
                null
            } ?: continue

            putIfAbsent(type.id, field.name)
        }
    }

    /**
     * 扫若干持有类的静态字段，建「注册表对象 → [Ref]」反查表；字段值可以是对象本身，也可以是 Registrate 条目（取 `.get()`）。
     *
     * ⚠️ 一律 `isAccessible = true` 再读：本 mod 的 `ETItems` 是 Kotlin `object`，属性背后是私有静态字段。
     */
    private fun <T : Any> holderFields(type: Class<T>, vararg holderNames: String): Map<T, Ref> {
        val map = HashMap<T, Ref>()
        for (name in holderNames) {
            val owner = try {
                Class.forName(name)
            } catch (e: Throwable) {
                null
            } ?: continue
            for (field in owner.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                val raw = try {
                    field.isAccessible = true
                    field.get(null)
                } catch (e: Throwable) {
                    null
                } ?: continue // 含 IllegalAccessException 与类初始化失败
                val registrate = raw is RegistryEntry<*, *>
                val value: T = when {
                    type.isInstance(raw) -> type.cast(raw)
                    registrate -> try {
                        raw.get().takeIf { type.isInstance(it) }?.let { type.cast(it) }
                    } catch (e: Throwable) {
                        null
                    }

                    else -> null
                } ?: continue
                map.putIfAbsent(value, Ref("${owner.simpleName}.${field.name}", registrate))
            }
        }
        return map
    }
}
