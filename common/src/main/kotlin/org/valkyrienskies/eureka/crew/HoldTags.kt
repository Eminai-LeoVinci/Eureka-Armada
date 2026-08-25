package org.valkyrienskies.eureka.crew

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.FuelValues
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.registry.FuelRegistry

/**
 * What a chest or barrel aboard is FOR, learned from what a captain puts in it.
 *
 * A ship with a powder room to port and a shot locker to starboard means those rooms differently, and until
 * now nothing in the game knew that: [ShipStores] read every box as one pool, so a few fights of swapping
 * ammunition left coal and cannonballs in the magazine. A tag is how a box says which pool it belongs to.
 *
 * ## Tags are CATEGORIES, never variants
 * Put explosive netherite shot in a barrel and it becomes a barrel for cannonballs -- all of them, every
 * metal and every charge, and the shulkers they come in. Anything finer would be unusable: nobody wants a
 * chest that accepts steel round shot and refuses steel explosive, and a captain reorganising ammunition
 * would have to re-teach every box. Three categories is the whole vocabulary, and they are exactly the three
 * currencies the operations screen spends.
 *
 * ## Fuel is broad on purpose, and that has a sharp edge
 * Anything an engine can burn counts, which is what the refueller already assumed. It does mean a chest of
 * spare BARRELS reads as a fuel chest, because a barrel burns -- and the refueller will happily feed them to
 * the furnace. That is not new behaviour (the old tally counted them too) and it is what "all fuel sources an
 * engine can use" asks for, so it stays; a captain who wants spare boxes kept should keep them ashore or in
 * a box that also holds something else.
 */
enum class HoldTag {
    CANNONBALLS,
    GUNPOWDER,
    FUEL;

    val bit: Int get() = 1 shl ordinal

    /** What a captain reads on the box and in a restock message. */
    val label: String
        get() = when (this) {
            CANNONBALLS -> "Shot"
            GUNPOWDER -> "Powder"
            FUEL -> "Fuel"
        }
}

object HoldTags {

    /** Only chests and barrels are ship storage -- the same allowlist, and the same reasoning, as [ShipStores]. */
    fun isHold(blockEntity: BlockEntity?): Boolean =
        blockEntity is ChestBlockEntity || blockEntity is BarrelBlockEntity

    // region reading and writing the box's own memory

    fun tagsOf(blockEntity: BlockEntity?): Set<HoldTag> {
        if (blockEntity !is HoldTagged) return emptySet()
        return fromMask(blockEntity.`vs_eureka$holdTags`())
    }

    fun has(blockEntity: BlockEntity?, tag: HoldTag): Boolean =
        blockEntity is HoldTagged && (blockEntity.`vs_eureka$holdTags`() and tag.bit) != 0

    /**
     * Replace a box's tags, and mark it changed only when they actually moved.
     *
     * The guard is not micro-optimisation: retagging runs every time any chest on the ship is closed, and an
     * unconditional `setChanged` would re-save and re-sync a block entity for looking at it.
     */
    fun setTags(blockEntity: BlockEntity?, tags: Set<HoldTag>) {
        if (blockEntity !is HoldTagged) return
        val mask = toMask(tags)
        if (blockEntity.`vs_eureka$holdTags`() == mask) return
        blockEntity.`vs_eureka$setHoldTags`(mask)
        blockEntity.setChanged()
    }

    fun toMask(tags: Set<HoldTag>): Int = tags.fold(0) { mask, tag -> mask or tag.bit }

    fun fromMask(mask: Int): Set<HoldTag> {
        if (mask == 0) return emptySet()
        return HoldTag.entries.filterTo(HashSet()) { (mask and it.bit) != 0 }
    }

    // endregion

    /**
     * Which category [stack] belongs to, or null for anything that is not stock.
     *
     * A shulker box is NOT classified by itself here -- see [categoriesIn], which is what callers walking a
     * container should use. A box of cannonballs is a cannonball box, and answering "shulker" for it would
     * make a magazine full of them read as holding nothing at all.
     */
    fun categoryOf(stack: ItemStack, fuelValues: FuelValues): HoldTag? = when {
        stack.isEmpty -> null
        stack.`is`(Items.GUNPOWDER) -> HoldTag.GUNPOWDER
        stack.item is CannonballItem -> HoldTag.CANNONBALLS
        FuelRegistry.INSTANCE.get(stack, fuelValues) > 0 -> HoldTag.FUEL
        else -> null
    }

    /**
     * Every category present in [stack], looking INSIDE it when it is a shulker box.
     *
     * A hold full of shulkers is how anybody actually stores a thousand rounds, so a classifier that could
     * not see inside one would refuse to tag the only chest that mattered.
     */
    fun categoriesIn(stack: ItemStack, fuelValues: FuelValues, into: MutableSet<HoldTag>) {
        if (stack.isEmpty) return
        val own = categoryOf(stack, fuelValues)
        if (own != null) {
            into.add(own)
            return
        }
        val packed = stack.get(DataComponents.CONTAINER) ?: return
        for (inner in packed.nonEmptyItems()) {
            categoryOf(inner, fuelValues)?.let(into::add)
        }
    }

    /** Whether [stack] -- loose or as a shulker holding them -- is stock of [tag]'s kind. */
    fun holds(stack: ItemStack, tag: HoldTag, fuelValues: FuelValues): Boolean {
        val found = HashSet<HoldTag>(3)
        categoriesIn(stack, fuelValues, found)
        return tag in found
    }
}
