package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.CompoundContainer
import net.minecraft.world.Container
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Teaching a box what it is for, by watching what a captain puts in it.
 *
 * There is no registration step and no special block: fill a barrel with cannonballs, shut the lid, and it
 * is a shot locker from then on. Take them all out again and it stops being one. That is the whole
 * interface, and it is deliberately the one a player was going to perform anyway.
 *
 * ## Only aboard
 * A box on land is just a box. Tags describe a SHIP's stores, so nothing here touches a container that is
 * not part of an assembled hull -- which also keeps the world's chests from carrying a field they will never
 * read.
 *
 * ## Two entrances
 * Closing the lid is one. The other is ASSEMBLY: a captain who built their magazine before raising the ship
 * would otherwise have to open and shut every box to teach it what it already obviously holds, so
 * [tagAllAboard] reads the lot at the moment the hull becomes a ship.
 */
object HoldRetag {

    /**
     * The boxes a chest menu is looking at: one for a barrel or a single chest, two for a double.
     *
     * Each half of a double chest is tagged separately, which is the same way [ShipStores] already counts
     * them -- and correct rather than merely convenient, since a captain can perfectly well keep powder in
     * the left half and shot in the right.
     */
    fun holdsOf(container: Container): List<BaseContainerBlockEntity> = when (container) {
        is CompoundContainer -> listOfNotNull(
            container.container1 as? BaseContainerBlockEntity,
            container.container2 as? BaseContainerBlockEntity
        ).filter { HoldTags.isHold(it) }

        is BaseContainerBlockEntity -> if (HoldTags.isHold(container)) listOf(container) else emptyList()
        else -> emptyList()
    }

    /** Which categories are actually in [hold] right now, shulkers included. */
    fun categoriesIn(level: ServerLevel, hold: BaseContainerBlockEntity): Set<HoldTag> {
        val found = HashSet<HoldTag>(3)
        for (slot in 0 until hold.containerSize) {
            val stack = hold.getItem(slot)
            if (stack.isEmpty) continue
            HoldTags.categoriesIn(stack, found)
            if (found.size == HoldTag.entries.size) break
        }
        return found
    }

    /**
     * What each box held when the menu opened, for [applyOnClose] to compare against. Empty for a container
     * that is not aboard a ship.
     */
    fun snapshot(level: ServerLevel, container: Container): Map<BlockPos, Int> {
        val holds = holdsOf(container).filter { level.getLoadedShipManagingPos(it.blockPos) != null }
        if (holds.isEmpty()) return emptyMap()
        return holds.associate { it.blockPos to HoldTags.toMask(categoriesIn(level, it)) }
    }

    /**
     * Retag every box of [container] now the lid is shut.
     *
     * Per category: present now means TAGGED, absent now means cleared **only if it was present at open**.
     * That asymmetry is the entire point -- see [HoldOpenSnapshot]. A box a restock drained keeps its tag,
     * because the restock never came through here and the captain who later looks inside finds it already
     * empty, so there is nothing for their visit to have removed.
     *
     * A container with no snapshot (opened before the ship existed, or not aboard one) is left alone
     * entirely rather than guessed at.
     */
    fun applyOnClose(level: ServerLevel, container: Container, opened: Map<BlockPos, Int>?) {
        if (opened.isNullOrEmpty()) return
        for (hold in holdsOf(container)) {
            if (!opened.containsKey(hold.blockPos)) continue

            // A captain who opens a box and shuts it again has just LOOKED at what is in there, so what is in
            // there is the answer: the tags become exactly the categories present. Nothing else clears them,
            // which is the point -- a crew that empties a magazine to feed the guns leaves it tagged as a
            // magazine, because that is still what the room is for and nobody said otherwise.
            //
            // The earlier rule only dropped what the captain personally took out DURING the visit, so a box
            // the crew had already emptied could never lose its tag by looking at it: it was empty when the
            // lid came up, so there was nothing to take out, and the stale tag stuck for good.
            HoldTags.setTags(hold, categoriesIn(level, hold))
        }
    }

    /**
     * Read every hold aboard a freshly assembled ship and tag it from its contents.
     *
     * A captain who stocked their magazine before raising the ship has already said what each room is for;
     * making them open and shut forty barrels to repeat it would be a chore invented by the implementation.
     * Additive only -- a box that comes aboard empty keeps whatever it was taught before, because an empty
     * shot locker between voyages is still a shot locker.
     */
    fun tagAllAboard(level: ServerLevel, holds: List<BaseContainerBlockEntity>) {
        for (hold in holds) {
            val found = categoriesIn(level, hold)
            if (found.isEmpty()) continue
            HoldTags.setTags(hold, HoldTags.tagsOf(hold) + found)
        }
    }
    /**
     * Turn one tag on or off for the box (or boxes) behind an open screen.
     *
     * The captain's answer, and the only thing that moves a tag now. What a box is FOR is a decision, not an
     * observation: it used to be re-read from the contents every time a screen closed, so emptying a barrel
     * to load a gun un-marked it as the powder barrel, and a crew restocking it marked it as whatever they
     * happened to put in. A hold is now marked until somebody says otherwise.
     *
     * A double chest is two boxes under one window. The screen shows the UNION of their tags, so a click sets
     * BOTH to the same answer -- flipping each independently would leave the two halves disagreeing about a
     * box the player sees as one.
     */
    fun toggle(container: Container, tag: HoldTag) {
        val holds = holdsOf(container)
        if (holds.isEmpty()) return
        val on = holds.any { HoldTags.has(it, tag) }
        for (hold in holds) {
            val tags = HoldTags.tagsOf(hold)
            HoldTags.setTags(hold, if (on) tags - tag else tags + tag)
        }
    }

    /** The union mask across the box or boxes behind one screen -- what the checkboxes show. */
    fun maskOf(container: Container): Int {
        var mask = 0
        for (hold in holdsOf(container)) mask = mask or HoldTags.toMask(HoldTags.tagsOf(hold))
        return mask
    }

}
