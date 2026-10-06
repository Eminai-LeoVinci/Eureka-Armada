package org.valkyrienskies.eureka.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.eureka.recipe.RecipeOverrides;

/**
 * Armada's recipes, read from {@code config/vs_eureka_armada_recipes.json} (see {@link RecipeOverrides}).
 *
 * At recipe (re)load, 1.20.1's {@code apply()} receives the raw id to JSON map, so the config's entries are
 * swapped in before vanilla parses anything: tags, ingredient lists, shaped and shapeless parsing and per-recipe
 * error logging all stay vanilla's own. Runs server-side; clients get the result through normal recipe sync.
 * Any error leaves the built-in recipes untouched.
 */
@Mixin(RecipeManager.class)
public class MixinRecipeManager {

    @ModifyVariable(
        method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("HEAD"),
        argsOnly = true,
        require = 1
    )
    private Map<ResourceLocation, JsonElement> vs_eureka$applyConfigRecipes(
        final Map<ResourceLocation, JsonElement> original) {
        try {
            RecipeOverrides.INSTANCE.reload();
            final Map<String, JsonObject> overrides = RecipeOverrides.INSTANCE.getOverrides();
            final Set<String> removals = RecipeOverrides.INSTANCE.getRemovals();
            if (overrides.isEmpty() && removals.isEmpty()) {
                return original;
            }

            final Map<ResourceLocation, JsonElement> patched = new LinkedHashMap<>(original);
            for (final String rid : removals) {
                try {
                    patched.remove(new ResourceLocation(rid));
                } catch (final Exception ignored) {
                    // bad id in config; skip
                }
            }
            int applied = 0;
            for (final Map.Entry<String, JsonObject> e : overrides.entrySet()) {
                try {
                    patched.put(new ResourceLocation(e.getKey()), e.getValue());
                    applied++;
                } catch (final Exception ex) {
                    RecipeOverrides.INSTANCE.logError("Config recipe '" + e.getKey() + "' has a bad id", ex);
                }
            }

            RecipeOverrides.INSTANCE.logInfo(
                "[vs_eureka recipes] applied " + applied + " override(s), " + removals.size() + " removal(s)."
            );
            return patched;
        } catch (final Exception e) {
            RecipeOverrides.INSTANCE.logError("Config recipe override pass failed; using built-in recipes", e);
            return original;
        }
    }
}
