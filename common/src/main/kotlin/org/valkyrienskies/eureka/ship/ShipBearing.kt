package org.valkyrienskies.eureka.ship

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.crew.CrewStations

/**
 * Which way a ship faces, and the orders that follow from it.
 *
 * Four things aboard are numbered for a captain to read aloud -- guns, holds, wheels and engines -- and all
 * four have to agree about where the bow is, or `Chest 2` would count from one end of the ship and `L2 - D1`
 * from the other. So forward is defined once, here, and every scheme sorts through the comparators below.
 *
 * ## Where "forward" comes from
 * The crew-station helm's facing, read in SHIPYARD space -- the same space every block position aboard lives
 * in, so the comparison is one enum equality with no transform. A helm block faces its wheel, so the bow is
 * its `HORIZONTAL_FACING.opposite`, exactly the seat-direction rule the cruise course uses. No crew-station
 * helm means no bow, and a ship with no bow has nothing numbered: there is no honest way to say which end is
 * the front of a hull that has never been claimed.
 *
 * ## Why shipyard space is safe to number in
 * The shipyard re-deals absolute coordinates on every assembly, so no number may be derived from an absolute
 * position -- but it only ever TRANSLATES the axes (the disassembly rotation snap is about the vertical axis
 * alone), and a translation cannot reorder anything. Every ordering here is therefore stable across a relog,
 * a disassemble/reassemble and a bottle cycle, which is what lets a number be quoted in a message and still
 * mean the same box tomorrow.
 */
object ShipBearing {

    /** The bow, as a shipyard-space direction, or null for a ship with no crew-station helm. */
    fun forwardOf(level: ServerLevel, ship: LoadedServerShip): Direction? {
        val helm = CrewStations.stationOf(level, ship) ?: return null
        return helm.blockState.getValue(HORIZONTAL_FACING).opposite
    }

    /** How far along [direction] this position lies. Negative is behind the origin, which is fine: only the
     * ORDER matters, and every comparison is between two positions in the same space. */
    fun BlockPos.along(direction: Direction): Int {
        val unit = direction.normal
        return x * unit.x + y * unit.y + z * unit.z
    }

    /**
     * Reading order for things numbered as one flat run over the whole ship -- wheels and engines: bow to
     * stern, then port to starboard, then keel upward.
     *
     * Front-to-back leads because that is how a ship is described from the outside; height comes last because
     * a wheel on the quarterdeck and one on the poop below it are the same station to anyone giving orders.
     * The three keys together are a TOTAL order on distinct positions -- no two blocks share all three -- so
     * the numbering can never shuffle between two calls on an unchanged hull.
     */
    fun <T> flatRun(forward: Direction, position: (T) -> BlockPos): Comparator<T> {
        val starboard = forward.clockWise
        return compareByDescending<T> { position(it).along(forward) }
            .thenBy { position(it).along(starboard) }
            .thenBy { position(it).y }
    }

    /**
     * Reading order WITHIN one deck, for things numbered per deck -- holds, and the guns before them: bow to
     * stern, then port to starboard.
     *
     * No height key: a deck is one height by construction, so there is nothing left to break.
     */
    fun <T> alongDeck(forward: Direction, position: (T) -> BlockPos): Comparator<T> {
        val starboard = forward.clockWise
        return compareByDescending<T> { position(it).along(forward) }
            .thenBy { position(it).along(starboard) }
    }

    /**
     * Rank the heights in [positions] into decks, keel-up: the lowest becomes Deck 1, the next height that
     * holds anything becomes Deck 2 however far above it sits.
     *
     * A deck is a RANK, not a height. What matters is that there is something THERE, not how far apart the
     * levels are -- which is what lets `D2` mean the same thing on a sloop and a first-rate, and what lets a
     * deck survive an assembly that moves every Y by the same offset.
     *
     * The consequence is worth stating, because it surprises people with holds and not with guns: anything
     * STACKED reads as separate decks. A shelf of barrels two high is `D1` and `D2`, because by this rule it
     * genuinely is two levels that hold barrels. Guns are naturally one to a level so it never came up; the
     * alternative is a second, different deck rule for holds, which is worse than the surprise.
     */
    fun decksOf(positions: Iterable<BlockPos>): Map<Int, Int> =
        positions.map { it.y }.distinct().sorted()
            .withIndex().associate { (index, y) -> y to index + 1 }

    /**
     * [items] in flat-run order, falling back to a plain positional sort when the ship has no bow.
     *
     * Falling back rather than refusing matters: a wheel has to show its own number on a hull nobody has
     * claimed the articles on yet, which is exactly the hull where a captain is deciding which wheel to
     * claim. The fallback still SORTS -- it must, because the callers hand over chunk-iteration order, and a
     * number that reshuffled itself every relog would be worse than no number at all.
     */
    fun <T> flatRunOrder(forward: Direction?, items: List<T>, position: (T) -> BlockPos): List<T> =
        if (forward != null) {
            items.sortedWith(flatRun(forward, position))
        } else {
            items.sortedWith(compareBy({ position(it).x }, { position(it).z }, { position(it).y }))
        }

    // region "n of total", packed into one synced int
    //
    // Wheels and engines show their number through a ContainerData slot, exactly as a cannon shows its label
    // -- see `GunLabels.encode`. One int is the whole channel, so both halves live in it.

    /**
     * Seven bits each, and that is not arbitrary: **a `DataSlot` transmits a 16-bit SHORT**, which this
     * codebase has already been bitten by twice -- the helm's block count and its mass are both split across
     * two slots for exactly this reason.
     *
     * The first cut packed decimally, `index * 10_000 + total`. `Helm: 2/8` survived that (20008 fits), and
     * `Engine: 14/35` did not: 140035 truncates to noise, which decodes to nothing and draws nothing. The
     * failure was invisible and looked exactly like "the label never worked".
     *
     * 7 + 7 bits tops out at 16383 -- comfortably inside a signed short -- and caps both numbers at 127. A
     * ship with more than 127 wheels or engines shows no number rather than a wrong one.
     */
    private const val NUMBER_BITS = 7
    private const val NUMBER_MAX = (1 shl NUMBER_BITS) - 1

    /** `2 of 8` -> a packed int. 0 for anything unnumbered, which is what an unclaimed hull reads as. */
    fun packNumber(index: Int, total: Int): Int {
        if (index < 1 || total < 1 || index > total) return 0
        if (index > NUMBER_MAX || total > NUMBER_MAX) return 0
        return (index shl NUMBER_BITS) or total
    }

    /** The packed int back to "2/8"; null for 0 or anything [packNumber] could not have produced. */
    fun unpackNumber(code: Int): String? {
        if (code <= 0) return null
        val index = code ushr NUMBER_BITS
        val total = code and NUMBER_MAX
        if (index < 1 || total < 1 || index > total) return null
        return "$index/$total"
    }

    // endregion
}
