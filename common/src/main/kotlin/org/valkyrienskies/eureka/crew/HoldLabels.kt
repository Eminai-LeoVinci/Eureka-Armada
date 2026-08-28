package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.ship.ShipBearing
import org.valkyrienskies.eureka.ship.ShipBearing.along

/**
 * The deck-and-bow-relative names of a ship's holds: `Chest 1 - D1`, `Chest 2 - D1`, ... bow to stern across
 * the lowest deck that holds a box, then its barrels the same way, then `Chest 1 - D2` a deck up.
 *
 * Built to the same rules as [org.valkyrienskies.eureka.cannon.GunLabels], because a captain reading
 * "3,840 shot taken from Chest 2 - D3" beside "reloaded L4 - D3" should not have to hold two numbering
 * schemes in their head.
 *
 * ## Chests and barrels count separately
 * `Chest 2 - D1` and `Barrel 2 - D1` can both exist, exactly as `L2 - D1` and `R2 - D1` do. The type is the
 * group, and a captain looking for a barrel scans past the chests without counting them.
 *
 * ## Labels are derived, never stored
 * Deterministic from geometry, so the same hull always deals the same names across relogs, reassembly and a
 * bottle cycle. The trade is the same one the guns make and it is worth naming, because here it is louder:
 * **adding a box renumbers every box behind it on its deck.** That is precisely why the TAGS live on the
 * block entity instead of under the label -- see [HoldTagged]. Numbers move; what a box is FOR does not.
 *
 * ## Only on an assembled ship
 * A box in the world is just a box. Numbering exists to talk about a ship's stores, and a ship you are
 * standing next to on land has none.
 */
object HoldLabels {

    class Labeled(
        val hold: BaseContainerBlockEntity,
        val label: String,
        val deck: Int
    ) {
        val tags: Set<HoldTag> get() = HoldTags.tagsOf(hold)
    }

    /**
     * Every hold aboard [ship] (armada included), named, in reading order.
     *
     * Empty when there is no bow to number from -- a ship whose wheel has never claimed the articles has no
     * front, and numbering from an arbitrary end would produce names that changed the moment one was claimed.
     */
    fun labeled(level: ServerLevel, ship: LoadedServerShip): List<Labeled> {
        val forward = ShipBearing.forwardOf(level, ship) ?: return emptyList()
        val holds = ShipStores.containersAboard(level, ship)
        if (holds.isEmpty()) return emptyList()

        val starboard = forward.clockWise
        val deckOf = ShipBearing.decksOf(holds.map { it.blockPos })

        // Bow-most first, port breaking the tie -- the order you would walk the deck reading them off.
        val order = compareByDescending<BaseContainerBlockEntity> { it.blockPos.along(forward) }
            .thenBy { it.blockPos.along(starboard) }

        val chests = holds.filter { it !is BarrelBlockEntity }
        val barrels = holds.filter { it is BarrelBlockEntity }

        val out = ArrayList<Labeled>(holds.size)
        fun emit(group: List<BaseContainerBlockEntity>, noun: String, deck: Int) {
            val onDeck = group.filter { deckOf.getValue(it.blockPos.y) == deck }.sortedWith(order)
            for ((index, hold) in onDeck.withIndex()) out.add(Labeled(hold, format(noun, index + 1, deck), deck))
        }
        for (deck in 1..deckOf.size) {
            emit(chests, CHEST, deck)
            emit(barrels, BARREL, deck)
        }
        return out
    }

    /**
     * The box a restock falls back on when no box aboard has ever been tagged for what it is carrying: the
     * first one on the lowest deck.
     *
     * A ship that has never seen a cannonball still has to put the ones just unloaded from her guns
     * SOMEWHERE, and the alternative -- refusing, or scattering them into whatever had room -- is how
     * ammunition ends up in three rooms. The caller says which box this was, because a silent default is
     * indistinguishable from a bug the first time it happens.
     */
    fun defaultHold(labeled: List<Labeled>): Labeled? = labeled.minByOrNull { it.deck }

    /** The label of the box at [pos], or null when it has no name (not aboard, or the ship has no bow). */
    fun labelAt(level: ServerLevel, ship: LoadedServerShip, pos: BlockPos): String? =
        labeled(level, ship).firstOrNull { it.hold.blockPos == pos }?.label

    /** The one place a hold label's shape lives: `Chest 2 - D3` is the second chest, bow-counted, on Deck 3. */
    fun format(noun: String, number: Int, deck: Int): String = "$noun $number - D$deck"

    const val CHEST = "Chest"
    const val BARREL = "Barrel"
}
