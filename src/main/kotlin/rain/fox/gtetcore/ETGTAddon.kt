package rain.fox.gtetcore

import com.gregtechceu.gtceu.api.addon.GTAddon
import com.gregtechceu.gtceu.api.addon.IGTAddon
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import net.minecraft.data.recipes.RecipeOutput
import rain.fox.gtetcore.registry.ETRegistrate

@GTAddon("")
class ETGTAddon : IGTAddon{
    override fun getRegistrate(): GTRegistrate {
        return ETRegistrate.REGISTRATE
    }

    override fun addRecipes(provider: RecipeOutput?) {
        super.addRecipes(provider)
    }
}