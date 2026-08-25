package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.ship.ShipBearing
import org.valkyrienskies.eureka.ship.ShipBearing.along

/**
 * The deck-and-bow-relative names of a ship's guns: `L1 - D1`, `L2 - D1`, ... down the port side of the
 * lowest gun deck, `R1 - D1` ... down starboard, then `L1 - D2` ... a deck up; `F` across the bow and `B`
 * across the stern, both read port to starboard, the way you would count them standing behind the gun line
 * and facing forward.
 *
 * ## The group is the gun's FACING, not its position
 * A gun bolted on the centreline firing to port is a port gun -- it fires with the port broadside, and
 * calling it anything else would name it after where the carpenter stood rather than what the gunner does.
 * So: muzzle pointing where the bow points is F, astern is B, and the two beams are L and R.
 *
 * ## Decks are Y-ranks, and numbering restarts per deck
 * Every distinct height that holds a gun is a deck, counted keel-up: the lowest is Deck 1, the next height
 * with guns is Deck 2 however far above it sits -- what matters is that guns are THERE, not how far apart
 * the decks are. Every ship deals decks, even a raft with one: `R5 - D1` on a sloop and on a first-rate
 * alike, so the format never changes shape under a player who adds a second deck. The deck is a rank, not a
 * height, because the shipyard re-deals absolute coordinates on every assembly -- but it only ever
 * TRANSLATES Y (the disassembly rotation snap is about the vertical axis alone), so a gun's deck survives a
 * bottle cycle even though its Y does not.
 *
 * ## Where "forward" comes from
 * The crew-station helm's facing, read in SHIPYARD space -- the same space every cannon's own facing lives
 * in, so the comparison is one enum equality with no transform. A helm block faces its wheel, so the bow is
 * its `HORIZONTAL_FACING.opposite`, exactly the seat-direction rule the cruise course uses. No crew-station
 * helm means no bow, and a ship with no bow has unnamed guns: the manifest shows nothing to station anyone
 * at until a wheel claims the articles.
 *
 * ## Labels are derived, never stored per gun
 * Deterministic from geometry, so the same hull always deals the same names -- across relogs, across
 * disassemble/reassemble, across a bottle cycle -- which is what lets a label serve as the durable half of a
 * gunner's station binding while shipyard addresses get re-dealt underneath it. The trade is visible and
 * accepted: adding or removing a gun renumbers everything behind it on its side of its deck.
 *
 * Welded armada children are labeled against the flagship's forward DIRECTION applied in their own shipyard
 * axes -- correct whenever the hulls' shipyard spaces are aligned, possibly rotated names otherwise. Decks
 * carry the same caveat one axis over: each hull's shipyard has its own Y origin, so ranking heights across
 * a weld is best-effort -- right whenever the hulls sit level with each other, and a mislabel is cosmetic
 * for the same reason a rotated one is: the station binding itself is by position.
 */
object GunLabels {

    class Labeled(val gun: CannonBlockEntity, val label: String, val layer: Int)

    /**
     * The bow, as a shipyard-space direction, or null for a ship with no crew-station helm.
     *
     * Kept as the guns' own entry point -- callers throughout the cannon code ask GunLabels where forward is
     * -- but the definition itself now lives in [ShipBearing], because holds, wheels and engines are numbered
     * from the same bow and two definitions of forward would eventually disagree.
     */
    fun forwardOf(level: ServerLevel, ship: LoadedServerShip): Direction? =
        ShipBearing.forwardOf(level, ship)

    /**
     * Every gun aboard [ship] (armada included), named, in reading order: Deck 1's port battery bow to
     * stern, then its starboard, its bow chasers, its stern chasers, then Deck 2's the same, keel to
     * masthead. Empty when there is no bow to name from.
     *
     * The order is deliberately the rolling-broadside order too: `CrewDuties` fires volleys in list order,
     * so a two-decker rolls its lower deck before its upper rather than zig-zagging between them.
     */
    fun labeled(level: ServerLevel, ship: LoadedServerShip): List<Labeled> {
        val forward = forwardOf(level, ship) ?: return emptyList()
        val starboard = forward.clockWise
        val guns = ShipGuns.aboard(level, ship)
        if (guns.isEmpty()) return emptyList()

        val port = ArrayList<CannonBlockEntity>()
        val stbd = ArrayList<CannonBlockEntity>()
        val bow = ArrayList<CannonBlockEntity>()
        val stern = ArrayList<CannonBlockEntity>()
        for (gun in guns) {
            when (gun.blockState.getValue(HORIZONTAL_FACING)) {
                forward -> bow
                forward.opposite -> stern
                forward.counterClockWise -> port
                else -> stbd
            }.add(gun)
        }

        // Deal the decks first: every distinct gun-bearing height, ranked keel-up. The breech block's Y is
        // the whole cannon's -- both halves stand on one deck.
        val deckOf = ShipBearing.decksOf(guns.map { it.blockPos })

        // Bow-most first for the broadsides; port-most first for the chasers. The cross axis breaks ties so
        // the order is total within a deck and the names can never shuffle between two calls on the same
        // hull. (Height needs no tiebreak here: a deck is one height by construction.)
        val toBow = compareByDescending<CannonBlockEntity> { it.blockPos.along(forward) }
            .thenBy { it.blockPos.along(starboard) }
        val toStarboard = compareBy<CannonBlockEntity> { it.blockPos.along(starboard) }
            .thenByDescending { it.blockPos.along(forward) }

        val out = ArrayList<Labeled>(guns.size)
        fun emit(group: List<CannonBlockEntity>, comparator: Comparator<CannonBlockEntity>, letter: Char, deck: Int) {
            val onDeck = group.filter { deckOf.getValue(it.blockPos.y) == deck }.sortedWith(comparator)
            for ((index, gun) in onDeck.withIndex()) out.add(Labeled(gun, format(letter, index + 1, deck), deck))
        }
        for (deck in 1..deckOf.size) {
            emit(port, toBow, 'L', deck)
            emit(stbd, toBow, 'R', deck)
            emit(bow, toStarboard, 'F', deck)
            emit(stern, toStarboard, 'B', deck)
        }
        return out
    }

    /** Guns per deck, index 0 = Deck 1 -- what the Operations screen's deck dropdown lists. */
    fun layerCounts(labeled: List<Labeled>): List<Int> {
        val decks = labeled.maxOfOrNull { it.layer } ?: return emptyList()
        val counts = IntArray(decks)
        for (named in labeled) counts[named.layer - 1]++
        return counts.toList()
    }

    /** The gun answering to [label] on [ship], or null. */
    fun byLabel(level: ServerLevel, ship: LoadedServerShip, label: String): CannonBlockEntity? =
        labeled(level, ship).firstOrNull { it.label == label }?.gun

    /** The label of the gun whose breech sits at [pos], or null when it has no name (or no longer exists). */
    fun labelAt(level: ServerLevel, ship: LoadedServerShip, pos: BlockPos): String? =
        labeled(level, ship).firstOrNull { it.gun.blockPos == pos }?.label

    /**
     * The one place the label's shape lives: `L3 - D2` is the third port gun, bow-counted, on Deck 2.
     *
     * The group letter MUST stay at index 0 -- side filters throughout the crew code read it with a
     * `startsWith` -- and both emission and [decode] build through here so the two can never disagree.
     */
    fun format(group: Char, number: Int, layer: Int): String = "$group$number - D$layer"

    // region label <-> int packing, for ContainerData slots (the cannon menu's synced ints)

    private const val GROUPS = "LRFB"

    // Bit-packed, because THE SLOT THIS TRAVELS IN IS A 16-BIT SHORT.
    //
    // It used to be packed decimally -- `layer * 10_000 + (group + 1) * 100 + number` -- which reads nicely
    // and overflows a short the moment a ship has FOUR gun decks: `L1 - D4` is 40101, and a signed short
    // stops at 32767. Past that the value arrives as noise, decodes to null, and the gun draws no name at
    // all. Nobody hit it because nobody had built a four-decker yet; it was found while fixing the identical
    // fault in the hold numbering, where the ceiling was low enough to hit immediately (see
    // [org.valkyrienskies.eureka.ship.ShipBearing.packNumber], which fell over at `Engine: 14/35`).
    //
    // 5 + 2 + 7 bits = 14, so the largest value is 16383 and there is headroom to spare. The caps are 31
    // decks, four groups and 127 guns per group per deck -- a first-rate carries perhaps sixty a side, so
    // the gun cap is generous, and a hull outside any of these shows no name rather than a wrong one.
    private const val NUMBER_BITS = 7
    private const val NUMBER_MAX = (1 shl NUMBER_BITS) - 1
    private const val GROUP_BITS = 2
    private const val GROUP_MASK = (1 shl GROUP_BITS) - 1
    private const val LAYER_SHIFT = NUMBER_BITS + GROUP_BITS
    private const val LAYER_MAX = 31

    /** "L1 - D1" -> a packed int; 0 for null/none/unparseable. Inverse of [decode]. */
    fun encode(label: String?): Int {
        if (label.isNullOrEmpty()) return 0
        val group = GROUPS.indexOf(label[0])
        if (group < 0) return 0
        val number = label.drop(1).takeWhile { it.isDigit() }.toIntOrNull() ?: return 0
        val layer = label.substringAfterLast('D', "").toIntOrNull() ?: return 0
        if (number < 1 || number > NUMBER_MAX || layer < 1 || layer > LAYER_MAX) return 0
        return (layer shl LAYER_SHIFT) or (group shl NUMBER_BITS) or number
    }

    /** The packed int back to "L1 - D1"; null for 0 or anything [encode] cannot have produced. */
    fun decode(code: Int): String? {
        if (code <= 0) return null
        val layer = code ushr LAYER_SHIFT
        val group = (code ushr NUMBER_BITS) and GROUP_MASK
        val number = code and NUMBER_MAX
        if (layer < 1 || layer > LAYER_MAX || group !in GROUPS.indices || number < 1) return null
        return format(GROUPS[group], number, layer)
    }

    // endregion
}
