package org.valkyrienskies.eureka.shipwright

import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item

/**
 * A captain's changes to a set of plans: what to leave out, and what to build it out of instead.
 *
 * ## A lens, not an edit
 * This never touches the filed bill. `Plans.baseCost` is the census as the page was drafted and stays that
 * way forever; the bill everyone actually reads is this applied over it. That is what lets a captain change
 * their mind twice, clear the alteration entirely, and get the original ship back with nothing lost -- and
 * it is why the template on disk is never rewritten until the moment something is built from it.
 *
 * ## Two ways to swap
 * [Fixed] is "birch planks, but build it in acacia": one item for one item, decided up front. [Any] is
 * "slabs, and I do not care which": the requirement is the FAMILY, and whatever the captain hands in counts
 * toward it. The second is the interesting one, because the ship then has to be built out of a mixture --
 * see `ShipAlterations` for the keel-up rule that decides which delivered slab lands where.
 *
 * ## Why an ANY row is billed under one item
 * The bill is a `Map<Item, Int>` everywhere -- on the wire, in NBT, in the screen's rows. Rather than make
 * it a map of some richer key and ripple that through nine call sites, an ANY row is billed under its
 * family's REPRESENTATIVE: the first member by registry id, which is stable across reloads because the
 * family's member list is sorted. Deliveries of any member pay into that row.
 */
class Alteration(
    /** Whole categories struck out at once -- "no decor", "no furniture". */
    val excludedCategories: Set<MaterialFamilies.Category> = emptySet(),
    /** Individual items struck out, whatever their category. */
    val excludedItems: Set<Item> = emptySet(),
    /** What each original material is to be built from instead. */
    val swaps: Map<Item, Swap> = emptyMap()
) {

    sealed interface Swap

    /** One named item stands in for the original. */
    class Fixed(val item: Item) : Swap

    /** Any member of the family will do, and the captain's delivery order decides which goes where. */
    class Any(val family: TagKey<Item>, val representative: Item) : Swap

    val isEmpty: Boolean
        get() = excludedCategories.isEmpty() && excludedItems.isEmpty() && swaps.isEmpty()

    /** Is this material left out of the build entirely? */
    fun isExcluded(item: Item): Boolean =
        item in excludedItems || MaterialFamilies.categoryOf(item) in excludedCategories

    /**
     * The bill this alteration turns [base] into.
     *
     * Walked in [base]'s own order, because `Plans.outstanding()` promises the list does not reshuffle as it
     * is paid and a captain watching a row move around while they work is a captain who thinks it is broken.
     *
     * Swapped rows MERGE into any row already asking for the same item. Birch slabs swapped to oak, on a
     * hull that already wanted oak slabs, is one row asking for the total -- not two rows asking for the
     * same thing, which would bill the captain twice and show the item twice.
     */
    fun applyTo(base: Map<Item, Int>): Map<Item, Int> {
        if (isEmpty) return base
        val out = LinkedHashMap<Item, Int>()
        for ((item, count) in base) {
            if (isExcluded(item)) continue
            val key = when (val swap = swaps[item]) {
                null -> item
                is Fixed -> swap.item
                is Any -> swap.representative
            }
            out[key] = (out[key] ?: 0) + count
        }
        return out
    }

    /**
     * The row a delivered [item] pays into: itself, or the representative of an ANY row it satisfies.
     *
     * Asked of every stack a captain hands over, so it walks the swaps rather than building an index -- a
     * bill has a few dozen rows and this runs once per inventory slot, not per tick.
     */
    fun requirementFor(item: Item): Item {
        for (swap in swaps.values) {
            when (swap) {
                // A fixed swap is already billed under the item it names, so a delivery of that item pays
                // into it without any translation -- this branch exists so the loop below cannot claim it
                // for a family row first.
                is Fixed -> if (swap.item == item) return item
                is Any -> if (MaterialFamilies.interchangeable(swap.representative, item)) {
                    return swap.representative
                }
            }
        }
        return item
    }

    /** The rows this alteration struck out, so the screen can still show them greyed with a red marker. */
    fun struckOut(base: Map<Item, Int>): Map<Item, Int> =
        if (isEmpty) emptyMap() else base.filterKeys { isExcluded(it) }

    companion object {
        val NONE = Alteration()
    }
}
