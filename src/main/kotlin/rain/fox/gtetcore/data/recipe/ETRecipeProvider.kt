package rain.fox.gtetcore.data.recipe

import net.minecraft.core.HolderLookup
import net.minecraft.data.PackOutput
import net.minecraft.data.recipes.RecipeOutput
import net.minecraft.data.recipes.RecipeProvider
import java.util.concurrent.CompletableFuture

/**
 * GTET 配方的数据生成器：把 [TerminalRecipes] 里的配方写成 `data/gtetcore/recipe/` 下的 json。
 *
 * 自己继承原版 [RecipeProvider]（`buildRecipes(RecipeOutput)` 是 8.0.0 里唯一的配方出口类型；
 * 1.21 已删掉老的 `FinishedRecipe` / `Consumer<FinishedRecipe>` 那套），由 `CommonProxy.onGatherData`
 * 注册进 `GatherDataEvent`，只有 `includeServer()` 时才加。
 */
class ETRecipeProvider(output: PackOutput, registries: CompletableFuture<HolderLookup.Provider>) :
    RecipeProvider(output, registries) {

    override fun buildRecipes(recipeOutput: RecipeOutput) {
        TerminalRecipes.init(recipeOutput)
    }
}
