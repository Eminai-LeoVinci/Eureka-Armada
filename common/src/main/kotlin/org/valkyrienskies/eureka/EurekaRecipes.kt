package org.valkyrienskies.eureka

import net.minecraft.core.registries.Registries
import net.minecraft.world.item.crafting.RecipeSerializer
import org.valkyrienskies.eureka.blueprint.BlueprintCopyRecipe
import org.valkyrienskies.eureka.registry.DeferredRegister
import org.valkyrienskies.eureka.registry.RegistrySupplier

/**
 * Recipe types the mod defines in code.
 *
 * Almost nothing belongs here. Ordinary recipes are authored in `config/vs_eureka_recipes.json` through VS2's
 * `RecipeOverrides`, which is what makes them retunable without a rebuild. A recipe only lands in this file
 * when it cannot be written as fixed ingredients and a fixed result -- so far, only copying a blueprint, whose
 * output is whatever page went into the grid.
 */
object EurekaRecipes {
    private val SERIALIZERS = DeferredRegister.create(EurekaMod.MOD_ID, Registries.RECIPE_SERIALIZER)

    val BLUEPRINT_COPY: RegistrySupplier<RecipeSerializer<BlueprintCopyRecipe>> =
        SERIALIZERS.register("blueprint_copy") { BlueprintCopyRecipe.Serializer }

    fun register() {
        SERIALIZERS.applyAll()
    }
}
