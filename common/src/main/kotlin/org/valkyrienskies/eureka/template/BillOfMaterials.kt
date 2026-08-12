package org.valkyrienskies.eureka.template

import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

/**
 * What a ship costs, counted in the items a player would actually have to carry.
 *
 * This is the single most important thing a blueprint shows: the list you take to the shipwright. It is also
 * what the shipwright checks off as materials arrive in instalments, so "what does this hull need" and "what is
 * still outstanding" are the same question asked twice.
 *
 * ## Counted from the template, not the ship
 * A blueprint is read long after the hull it describes has sailed away -- possibly after it has sunk. Counting
 * from the saved [StructureTemplate] means the answer keeps working with nothing but the `.nbt`, and it means a
 * template that arrived in the mod jar (a pirate hull, say) can be priced the same way as one a player captured.
 *
 * ## Why this is not just "count the blocks"
 * A bed is two blocks and one item. So is a door, a tall flower, and every other thing built from a matched
 * pair -- and a naive count charges double for all of them. Rather than special-case each block class, both
 * halves are recognised through the shared state properties every double block uses, and only the half that
 * carries the item is counted. Getting this wrong is not a rounding error: it is the same shape as the bed
 * duplication bug that once let 1.20.1 players print beds by assembling a ship.
 */
object BillOfMaterials {

    /**
     * Every item needed to build [template], and how many of each.
     *
     * Blocks with no item form -- the helm's render-only wheel, fire, technical blocks -- are skipped rather
     * than reported as air, because a shopping list containing "12 air" helps nobody.
     */
    fun census(template: StructureTemplate): Map<Item, Int> {
        val tally = LinkedHashMap<Item, Int>()
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                val item = itemFor(info.state) ?: continue
                tally[item] = (tally[item] ?: 0) + 1
            }
        }
        return tally
    }

    /** Total items, i.e. the size of the pile rather than the number of distinct kinds. */
    fun total(census: Map<Item, Int>): Int = census.values.sum()

    /**
     * The item [state] would cost to place, or null if it costs nothing.
     *
     * Null covers three cases that all look different and behave the same: air, a block whose item is air
     * because it has none, and the second half of a double block whose first half was already charged for.
     */
    private fun itemFor(state: BlockState): Item? {
        if (state.isAir) return null

        // The upper half of a door or tall plant, and the head of a bed, are the same purchase as their
        // partner. Count one end only; which end does not matter as long as it is consistent.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) &&
            state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
        ) {
            return null
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) &&
            state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
        ) {
            return null
        }

        val item = state.block.asItem()
        return if (item == Items.AIR) null else item
    }
}
