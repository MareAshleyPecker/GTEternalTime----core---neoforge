package rain.fox.gtetcore.data.recipe

import com.gregtechceu.gtceu.api.data.chemical.material.stack.MaterialEntry
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.data.recipe.VanillaRecipeHelper
import net.minecraft.data.recipes.RecipeOutput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.common.Tags
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.registry.ETItems

/**
 * 高级终端的工作台配方（老工程 `TerminalRecipes.kt` 的 3×3 图案照搬）：
 * ```
 * S G S
 * P B P
 * P W P
 * ```
 * S = 钢螺丝、G = 玻璃板标签、B = 书、P = 钢板、W = 锡细线。
 *
 * 写法照 **GTM 8.0.0 自己的范式**：`VanillaRecipeHelper.addShapedRecipe(...)`，
 * GTM 的同款范例见 `data/recipe/misc/MiscRecipeLoader.java` 里那条 `basic_terminal`
 * （图案 `SGS/PBP/PWP` 与本条一模一样，只是材料用的是锻铁 + 红合金单线）—— 8.0.0 里 GT 的工作台配方
 * 就是普通原版合成配方，用 `MaterialEntry` 交给 helper 去解析成材料标签。
 *
 * ⚠️ 配方 id 走 [GTETSCore.id]（= `gtetscore:advanced_terminal`）：helper 的 `String` 重载会把 id
 * 拼到 `gtceu:` 命名空间下（内部写死 `GTCEu.id(regName)`），那是 GTM 本体用的，addon 不能用。
 */
object TerminalRecipes {

    /** 注册高级终端的工作台配方；由 [ETRecipeProvider] 在数据生成时调用。 */
    @JvmStatic
    fun init(provider: RecipeOutput) {
        VanillaRecipeHelper.addShapedRecipe(
            provider,
            GTETSCore.id("advanced_terminal"),
            ETItems.ADVANCED_TERMINAL.asStack(),
            "SGS", "PBP", "PWP",
            'S', MaterialEntry(TagPrefix.screw, GTMaterials.Steel),
            // 1.21 起通用标签从 `forge:` 改到 `c:` 命名空间，老工程的 `forge:glass_panes` 已失效
            'G', Tags.Items.GLASS_PANES,
            'B', ItemStack(Items.BOOK),
            'P', MaterialEntry(TagPrefix.plate, GTMaterials.Steel),
            'W', MaterialEntry(TagPrefix.wireFine, GTMaterials.Tin)
        )
    }
}
