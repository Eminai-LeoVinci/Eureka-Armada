package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.cannon.GunLabels
import java.util.UUID

/**
 * Re-deals a summoned crew's gun stations onto the hull they have just been called to.
 *
 * ## Why this has to happen at the moment of summoning
 * A station is TWO things: a live shipyard address, and the label that outlives it. Everything that returns a
 * crew to the hull they left -- assembly, the bottle, salvage -- can lean on the address, because the guns are
 * the same guns. Summoning is the one thing that moves a crew BETWEEN hulls, and there the address is not
 * merely stale, it is a real gun on a real ship: the one they came from, whose shipyard chunks are still
 * loaded. [GunStations] reconcile finds a cannon at that address, believes it, and seats the gunner back on
 * the OLD ship -- which is why summoned gunners turned up riding nothing in mid-air off the new hull's
 * quarter, why taking the ship apart did not recover them, and why re-assigning them by hand did: that was
 * the only path that rewrote the address.
 *
 * So the address is re-resolved HERE, against the new hull, before reconcile ever runs.
 *
 * ## Matching a gun to a gunner
 * A gunner remembers a NAME, not a place -- "L9 - D2" is the ninth gun down the port side of the second deck.
 * On a hull they have never seen, that name may not exist, so the search widens by the smallest step that
 * still means something to a gun crew:
 *
 *  1. the same side, deck and number -- their own gun, on a ship built to the same plan;
 *  2. the same side and deck, nearest free number -- their own deck, a berth or two along;
 *  3. the same side, the next deck DOWN, nearest number -- a three-decker's crew on a two-decker step down
 *     rather than being thrown to the other broadside, because a gun deck is a side first and a height second;
 *  4. any free gun at all.
 *
 * A gun already claimed by somebody else is never taken, so a crew arriving onto a partly-manned hull fills in
 * around the hands already there.
 *
 * ## When there is no gun
 * A gunner with nowhere to stand stops being one. The duty is cleared and the station with it, which drops
 * them into the ordinary crewman placement [CrewMuster] already does -- on the deck, by the wheel. That is the
 * honest outcome: a gun crew without guns is a crew, and leaving the duty set would leave them bound to a
 * station that does not exist, which is the exact fault this file is here to end.
 */
object GunReberth {

    /**
     * Take every one of [crewId] out of whatever they are riding, before they are placed.
     *
     * A gunner is not standing on the deck, they are RIDING a seat, and a seat is an entity with a position
     * of its own. Move the villager and the seat brings them straight back; clear their station in the
     * ledger and they are still sitting in it. That is the whole of what went wrong the first time this was
     * fixed: the paperwork was re-dealt and the furniture was left where it was, so a summoned crew arrived
     * still mounted -- hanging in the air off the new hull because a seat, not a deck, was holding them, and
     * frozen once they got there because a mounted villager does not walk. Releasing them did not help, and
     * neither did paying them off, because neither of those kills a seat either.
     *
     * So the seats go first, for the WHOLE crew and not merely the gunners: a hand who was a gunner an hour
     * ago and is a deckhand now is still sitting in the chair they were given. After this they are ordinary
     * loose villagers, [CrewMuster] can put them where it likes, and the gunners among them are re-seated by
     * [GunStations] against the stations dealt in [reberth].
     */
    fun unseatAll(level: ServerLevel, crewId: UUID) {
        val ledger = CrewLedger.get(level.server)
        for (berth in ledger.berths(crewId)) {
            GunStations.unseat(level, berth.villager)
        }
    }

    /** One gun's name, taken apart. */
    private class Name(val side: Char, val number: Int, val deck: Int)

    /** "L9 - D2" -> side L, number 9, deck 2. Null for anything that does not read as a gun name. */
    private fun parse(label: String?): Name? {
        if (label.isNullOrBlank()) return null
        val side = label.firstOrNull() ?: return null
        if (side !in "LRFB") return null
        val number = label.drop(1).takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val deck = label.substringAfterLast('D', "").trim().toIntOrNull() ?: return null
        return Name(side, number, deck)
    }

    /**
     * Give every gunner in [crewId] a gun on [ship], or take the duty away.
     *
     * Called on the summon path only. Returns how many were re-berthed and how many were stood down, for the
     * report the captain reads.
     */
    fun reberth(level: ServerLevel, ship: LoadedServerShip, crewId: UUID): Pair<Int, Int> {
        val ledger = CrewLedger.get(level.server)
        val berths = ledger.berths(crewId).filter { it.duty == CrewDuty.GUNNER }
        if (berths.isEmpty()) return 0 to 0

        val labeled = GunLabels.labeled(level, ship)
        // Guns any OTHER crew member already answers for. Their own crew's old addresses are not in the way:
        // those point at the ship they came from, and are about to be overwritten anyway.
        val mine = berths.mapTo(HashSet()) { it.villager }
        val claimed = ledger.stationedBerths()
            .filter { it.villager !in mine }
            .mapNotNullTo(HashSet()) { it.station }

        var seated = 0
        var stoodDown = 0
        for (berth in berths) {
            val free = labeled.filter { it.gun.blockPos.asLong() !in claimed }
            if (free.isEmpty()) {
                ledger.clearStation(berth.villager)
                ledger.setDuty(berth.villager, CrewDuty.NONE)
                GunStations.unseat(level, berth.villager)
                stoodDown++
                continue
            }
            val want = parse(berth.stationLabel)
            val pick = if (want == null) free.first() else choose(free, want)
            ledger.setStation(berth.villager, pick.gun.blockPos.asLong(), pick.label)
            claimed.add(pick.gun.blockPos.asLong())
            seated++
        }
        return seated to stoodDown
    }

    /**
     * The best free gun for a gunner who remembers [want], by the ladder in the class note.
     *
     * Ranked rather than searched in four passes: one walk, and the comparison says outright what "closest"
     * means -- side first, then how far the deck had to move (and only ever DOWNWARD by preference), then how
     * far along the deck.
     */
    private fun choose(free: List<GunLabels.Labeled>, want: Name): GunLabels.Labeled =
        free.minByOrNull { candidate ->
            val name = parse(candidate.label)
            when {
                name == null -> 1_000_000L
                else -> {
                    val sidePenalty = if (name.side == want.side) 0L else 10_000L
                    // A deck below the one they knew is a step down a ladder; a deck above is a longer climb
                    // and is preferred less, so the drop is weighted lighter than the rise.
                    val deckGap = name.deck - want.deck
                    val deckPenalty = if (deckGap <= 0) -deckGap * 100L else deckGap * 300L
                    val numberPenalty = Math.abs(name.number - want.number).toLong()
                    sidePenalty + deckPenalty + numberPenalty
                }
            }
        } ?: free.first()
}
