package org.valkyrienskies.eureka.blueprint

import net.minecraft.core.NonNullList
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.EurekaRecipes

/**
 * Copying a blueprint, exactly the way a written book is copied.
 *
 * One drafted page plus any number of blanks gives that many copies, and the original comes back out of the
 * grid untouched -- `BookCloningRecipe` to the letter, because that is the interaction players already know and
 * there is no reason for a blueprint to have its own.
 *
 * ## Copies share a template, and that is the point
 * Every copy carries the same tag, which means the same template name, which means they all read the
 * same `.nbt`. Nothing is duplicated on disk however many pages exist. This is only safe because a template is
 * immutable once written -- nothing in the mod ever edits one in place -- so shared and copied are the same
 * thing here. A shipwright consuming one page cannot alter what the others describe.
 *
 * ## Why this is a special recipe rather than a config one
 * Every other recipe in the mod lives in `config/vs_eureka_armada_recipes.json`, which speaks in fixed ingredients and
 * a fixed result. This one has neither: its output is whatever page went in, and it takes as many blanks as you
 * care to give it. That cannot be expressed as nine slots and a result id, so it is a datapack recipe of a
 * custom type -- the same shape vanilla uses for book cloning. (On 1.20.1 the serializer is vanilla's own
 * SimpleCraftingRecipeSerializer; the 1.21.1 codec pair collapses back into it.)
 */
class BlueprintCopyRecipe(id: ResourceLocation, category: CraftingBookCategory) : CustomRecipe(id, category) {

    // 1.20.1 still asks; the drafted page and at least one blank need two slots.
    override fun canCraftInDimensions(width: Int, height: Int): Boolean = width * height >= 2

    override fun matches(input: CraftingContainer, level: Level): Boolean {
        var drafted = 0
        var blanks = 0

        for (i in 0 until input.containerSize) {
            val stack = input.getItem(i)
            if (stack.isEmpty) continue
            if (!stack.`is`(EurekaItems.BLUEPRINT.get())) return false

            // A stack of pages in one slot would make "how many copies" ambiguous and lets a player lose the
            // rest of the stack to a single craft. One page per slot, as with books.
            if (stack.count != 1) return false

            if (Blueprint.read(stack) != null) drafted++ else blanks++
        }

        return drafted == 1 && blanks >= 1
    }

    override fun assemble(input: CraftingContainer, registries: RegistryAccess): ItemStack {
        var page = ItemStack.EMPTY
        var blanks = 0

        for (i in 0 until input.containerSize) {
            val stack = input.getItem(i)
            if (stack.isEmpty) continue
            if (Blueprint.read(stack) != null) page = stack else blanks++
        }
        if (page.isEmpty || blanks < 1) return ItemStack.EMPTY

        // Copied through the tag rather than rebuilt, so a copy is byte-for-byte the page it came from
        // -- including the manifest snapshot, which must not be recomputed against a ship that has since sailed.
        val copy = page.copyWithCount(blanks)
        page.tag?.let { copy.tag = it.copy() }
        return copy
    }

    /** The page you put in is the page you get back. Only the blanks are spent. */
    override fun getRemainingItems(input: CraftingContainer): NonNullList<ItemStack> {
        val remaining = NonNullList.withSize(input.containerSize, ItemStack.EMPTY)
        for (i in 0 until input.containerSize) {
            val stack = input.getItem(i)
            if (!stack.isEmpty && Blueprint.read(stack) != null) remaining[i] = stack.copy()
        }
        return remaining
    }

    override fun getSerializer(): RecipeSerializer<BlueprintCopyRecipe> = EurekaRecipes.BLUEPRINT_COPY.get()

    companion object {
        /** The serializer carries the book category and nothing else -- there is nothing else to author. */
        val Serializer: RecipeSerializer<BlueprintCopyRecipe> =
            SimpleCraftingRecipeSerializer { id, category -> BlueprintCopyRecipe(id, category) }
    }
}
