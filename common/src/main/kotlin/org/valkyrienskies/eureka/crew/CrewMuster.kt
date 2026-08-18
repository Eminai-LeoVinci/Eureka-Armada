package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.util.logger
import org.valkyrienskies.eureka.path.PathMessages
import java.util.UUID

/**
 * Bringing a crew aboard the ship their wheel just built.
 *
 * A crew belongs to a captain and a name, not to a place, so assembling the ship they are filed under should
 * put them on its deck wherever they happened to be standing. Without this, "the crew follow the helm" would
 * mean only that the LIST follows it, and every move would be followed by walking eight villagers across a
 * dock one at a time.
 *
 * ## Moving somebody who may not be there
 * The real villager is always preferred: moving them keeps their identity, their memories, their gossip and
 * anything another mod hung on them. But a crew member can be genuinely unreachable -- an unloaded chunk,
 * another dimension, or dead -- and a crew that silently shrank whenever somebody wandered off would not be a
 * crew. So a berth that cannot be found is rebuilt from the written copy the articles carry, and the original
 * id is TOMBSTONED. That is the whole defence against duplication: the copy is a new individual, and whatever
 * turns up later under the old id is destroyed as it loads.
 *
 * ## One crew, one ship
 * Two hulls can carry wheels of the same name and wood, and a crew cannot be in both. The first to assemble
 * holds them; the second musters nobody and is told which ship has them. Deciding by "who is holding them
 * right now" rather than by a stored claim means a ship that was deleted, or a world that crashed mid-voyage,
 * never leaves a crew permanently spoken for.
 */
object CrewMuster {

    private val logger by logger()

    /**
     * Bring [captain]'s crew aboard [ship], and say what happened.
     *
     * Called from the assembly once the ship is loaded enough to stand on. Everything it needs about the wheel
     * is passed in rather than read from a block entity, because the wheel that started the assembly has been
     * relocated -- and RESET -- by the time this runs. See `ShipHelmBlockEntity.assemble`.
     */
    fun muster(
        level: ServerLevel,
        captain: ServerPlayer,
        ship: LoadedServerShip,
        crewName: String,
        variant: String,
        berthLimit: Int,
        station: Long
    ) {
        val ledger = CrewLedger.get(level.server)
        val key = CrewLedger.Key(captain.uuid, HelmNames.keyOf(crewName), variant)
        val berths = ledger.crew(key)
        if (berths.isEmpty()) return

        // Anybody already inside the hull is aboard and needs no moving. Deliberately NOT
        // `ShipCrew.villagersAboard`, which decides membership from VS2's dragging information: that is written
        // by the collision that carries a mob along with a deck, and a ship one tick old has never carried
        // anybody, so at assembly it answers "nobody" for a deck that is visibly full. Reading the box directly
        // is the one question that is already true the moment the ship exists -- and getting this wrong is not
        // a missed optimisation, it is every crew member on deck being picked up and dropped again.
        val present = villagersInHull(level, ship).associateBy { it.uuid }

        val holder = heldElsewhere(level, ship, berths, present.keys)
        if (holder != null) {
            PathMessages.send(
                captain,
                "$crewName are already crewing ${ShipCrew.name(holder)}. Bring that ship in first.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val deck = boardingPoint(ship, station)
        var moved = 0
        var returned = 0
        var rebuilt = 0
        var lost = 0
        var overflow = 0

        for ((index, berth) in berths.withIndex()) {
            // The berth limit can only be exceeded by lowering the config cap under an existing crew, so this
            // is rare -- but a ship that mustered more crew than it has berths would be a limit that is not
            // one. The excess are paid off rather than left in limbo, oldest berths keeping their places.
            if (index >= berthLimit) {
                ledger.payOff(berth.villager)
                overflow++
                continue
            }
            val aboard = present[berth.villager]
            if (aboard != null) {
                // Already where they belong: nothing to move, but this is still the moment their name and
                // their written copy are known to be current, so both are brought up to date.
                answerTo(aboard, berth)
                CrewSnapshot.capture(aboard)?.let { ledger.updateSnapshot(berth.villager, it) }
                ledger.markAboard(berth.villager)
                continue
            }

            val live = findAnywhere(level.server, berth.villager)
            if (live != null) {
                live.teleportTo(deck.x, deck.y, deck.z)
                answerTo(live, berth)
                CrewSnapshot.capture(live)?.let { ledger.updateSnapshot(berth.villager, it) }
                ledger.markAboard(berth.villager)
                moved++
                continue
            }

            val snapshot = berth.snapshot
            if (snapshot == null) {
                lost++
                continue
            }
            val restored = CrewSnapshot.restore(level, snapshot, deck)
            if (restored == null) {
                lost++
            } else if (berth.ashore) {
                // Stood down when the ship was taken apart: destroyed on purpose, so their old id cannot turn
                // up again and needs no tombstone. This is the ordinary way a crew comes back, and it is
                // counted apart from the salvage case below so that the routine path does not sound alarming.
                ledger.rekey(berth.villager, restored.uuid)
                returned++
            } else {
                ledger.replaceVillager(berth.villager, restored.uuid)
                rebuilt++
            }
        }

        report(captain, crewName, moved, returned, rebuilt, lost, overflow)
    }

    /** What a stand-down amounted to: how many were taken off, of how many berthed crew found aboard. */
    class StandDownReport(val stood: Int, val berthedAboard: Int)

    /**
     * Take every crew member off a ship that is being put back into the world, into the articles.
     *
     * The other half of [muster], and the reason it can be the other half: a crew member is a berth on some
     * articles first and an entity second. Mustering builds the entity from the berth, so standing down can
     * safely destroy the entity as long as the berth is written up to date first -- and they walk back out onto
     * the deck the next time the ship is assembled.
     *
     * Without this, taking a ship apart leaves its whole crew standing wherever the deck used to be: milling
     * about the yard, wandering off, falling to their deaths if she was packed up in the air.
     *
     * ## Found through the articles, not through one wheel's name
     * The first version swept the hull box for villagers and matched them against ONE crew name -- the name of
     * whichever wheel the caller happened to be holding. An entire eighty-gunner crew went over the side that
     * way: on a hull with several wheels the held one need not be the named one, and a gate that reads the
     * wrong wheel stands down nobody at all. So the search now starts from the LEDGER and works outward --
     * every berth under the crew's name (all captains at once, the wheel is being taken apart for all of
     * them), plus every berthed villager the hull box turns up regardless of whose articles they are on, so a
     * renamed wheel or a guest crew still gets everyone off the deck. A berthed villager who is genuinely
     * somewhere else is left there: being on the books does not mean being aboard.
     *
     * **Nobody is destroyed without a written copy in hand.** A snapshot that cannot be taken means the crew
     * member is left exactly where they are, still on the articles and still in the world, because a crew
     * member who cannot be written down cannot be rebuilt -- and a villager left standing in a shipyard is a
     * nuisance, whereas one destroyed without a copy is gone.
     *
     * Deliberately chatty in the log. This path has failed silently once, at the cost of a whole crew, and the
     * counts it prints are what tell the next investigation which lookup came up empty.
     */
    fun standDownShip(
        level: ServerLevel,
        shipId: Long,
        hull: AABBdc,
        crewName: String?,
        variant: String?
    ): StandDownReport {
        val ledger = CrewLedger.get(level.server)
        logger.info("[crew] stand-down ship=$shipId crew='${crewName ?: "(unnamed)"}' variant=${variant ?: "?"}")

        // Two independent ways onto the list, union'd by villager id. The name lookup is the intended path;
        // the box sweep is the safety net that holds when the name is wrong, and the reason a mismatch can
        // never again cost a whole crew.
        val berths = LinkedHashMap<UUID, CrewLedger.Berth>()
        var berthsByName = 0
        if (crewName != null && variant != null) {
            for (key in ledger.keysUnder(crewName, variant)) {
                for (berth in ledger.crew(key)) {
                    berths[berth.villager] = berth
                    berthsByName++
                }
            }
        }
        val inBox = villagersIn(level, hull).associateBy { it.uuid }
        var berthedInBox = 0
        for (villager in inBox.values) {
            val berth = ledger.berthOf(villager.uuid) ?: continue
            berthedInBox++
            berths.putIfAbsent(berth.villager, berth)
        }
        logger.info(
            "[crew] sources: berthsByName=$berthsByName villagersInBox=${inBox.size} " +
                "berthedInBox=$berthedInBox candidates=${berths.size}"
        )

        // The acceptance box, with the same slack the sweep itself allows for somebody standing on the top face.
        val deck = AABB(
            hull.minX() - DECK_MARGIN, hull.minY() - DECK_MARGIN, hull.minZ() - DECK_MARGIN,
            hull.maxX() + DECK_MARGIN, hull.maxY() + DECK_MARGIN, hull.maxZ() + DECK_MARGIN
        )

        var stood = 0
        var berthedAboard = 0
        for (berth in berths.values) {
            val villager = inBox[berth.villager] ?: findAnywhere(level.server, berth.villager)
            if (villager == null) {
                logger.info("[crew]   ${berth.name}: not-found")
                continue
            }
            if (villager.level() !== level) {
                logger.info("[crew]   ${berth.name}: found-elsewhere-skipped (other dimension)")
                continue
            }
            // Aboard means inside the hull box -- or seated on one of THIS ship's gun seats, which pins them
            // to the ship more directly than any position test can.
            val seated = (villager.vehicle as? ShipMountingEntity)?.driveShipId == shipId
            if (!seated && !deck.contains(villager.x, villager.y, villager.z)) {
                logger.info("[crew]   ${berth.name}: found-elsewhere-skipped (ashore)")
                continue
            }
            berthedAboard++
            val snapshot = CrewSnapshot.capture(villager)
            if (snapshot == null) {
                logger.warn("[crew]   ${berth.name}: snapshot-failed -- left aboard")
                continue
            }
            // The seat first, while the villager still exists to be dismounted: this kills it now and clears
            // the runtime seating, rather than leaving an orphan for the reconcile sweep to find.
            GunStations.unseat(level, berth.villager)
            ledger.standDown(berth.villager, snapshot)
            // `discard` rather than `kill`: nobody died. There is nothing to drop, no experience to award,
            // and the same crew member walks back out of the articles when she is rebuilt.
            villager.discard()
            logger.info("[crew]   ${berth.name}: ${if (seated) "stood-down-seated" else "stood-down-standing"}")
            stood++
        }

        logger.info("[crew] stand-down complete stood=$stood berthedAboard=$berthedAboard")
        return StandDownReport(stood, berthedAboard)
    }

    /**
     * The ship currently holding any of [berths], if it is not [ship].
     *
     * Answered by looking for the crew rather than by reading a claim: a stored "which ship has them" would
     * outlive a deleted ship and a crashed session, and would need clearing from more places than it is set.
     * Only LOADED ships can be checked, which is the honest limit of the rule -- a crew aboard a ship nobody
     * has near them will be mustered away, and the tombstone keeps that from duplicating anybody.
     */
    private fun heldElsewhere(
        level: ServerLevel,
        ship: LoadedServerShip,
        berths: List<CrewLedger.Berth>,
        aboardHere: Set<UUID>
    ): LoadedServerShip? {
        // Asked of each crew member rather than by sweeping every loaded ship: `standingOn` already answers
        // "which deck is this one on", and one lookup per berth beats a villager scan per hull in the world.
        for (berth in berths) {
            if (berth.villager in aboardHere) continue
            val live = findAnywhere(level.server, berth.villager) ?: continue
            val deck = ShipCrew.standingOn(live) ?: continue
            if (deck == ship.id) continue
            level.shipObjectWorld.loadedShips.getById(deck)?.let { return it }
        }
        return null
    }

    /**
     * Every living villager inside [ship]'s hull, however they got there.
     *
     * A box test and nothing else. `ShipCrew.villagersAboard` asks VS2 which deck each one is being dragged by,
     * which is the better question everywhere except here: that information is a side effect of the collision
     * that carries a mob along with a moving ship, and a ship that has existed for one tick has never carried
     * anybody. At assembly it reports an empty deck for a deck that is plainly not empty.
     */
    private fun villagersInHull(level: ServerLevel, ship: LoadedServerShip): List<Villager> =
        villagersIn(level, ship.worldAABB)

    /** Every living villager inside a world-space box, with the usual slack for standing on the top face. */
    private fun villagersIn(level: ServerLevel, hull: AABBdc): List<Villager> {
        val box = AABB(
            hull.minX() - DECK_MARGIN, hull.minY() - DECK_MARGIN, hull.minZ() - DECK_MARGIN,
            hull.maxX() + DECK_MARGIN, hull.maxY() + DECK_MARGIN, hull.maxZ() + DECK_MARGIN
        )
        return level.getEntitiesOfClass(Villager::class.java, box) { it.isAlive }
    }

    /**
     * Where a mustered crew member appears: standing at the wheel, in WORLD coordinates.
     *
     * This used to be the top of the ship's world AABB, on the reasoning that anywhere above the hull is
     * somewhere they can fall onto the deck from. That is true of a raft and badly false of a ship with masts:
     * the box reaches the top of the rigging, so a crew called aboard a galleon appeared level with the
     * crow's nest and fell the whole height of the ship -- far enough to hurt them, and on a tall one far
     * enough to kill.
     *
     * The wheel is the right anchor instead. It is a real block on a real deck, it is by definition somewhere a
     * player stands, and its shipyard position is already in hand from the assembly. One block up so nobody
     * appears inside the wheel itself; the drop from there is a step.
     */
    private fun boardingPoint(ship: LoadedServerShip, station: Long): Vec3 {
        val helm = BlockPos.of(station)
        val world = ship.shipToWorld.transformPosition(
            Vector3d(helm.x + 0.5, helm.y + BOARDING_CLEARANCE, helm.z + 0.5)
        )
        return Vec3(world.x, world.y, world.z)
    }

    /**
     * Call [villager] what the articles call them.
     *
     * The ledger is renamed even when the villager is nowhere to be found -- that is what lets the manifest
     * name somebody who is ashore -- so the two can be out of step by the time they come back aboard. Mustering
     * is when they are next in hand, so it is where the entity is brought into line with the paperwork.
     */
    private fun answerTo(villager: Villager, berth: CrewLedger.Berth) {
        if (berth.name.isEmpty() || CrewNames.displayName(villager) == berth.name) return
        villager.customName = Component.literal(berth.name)
    }

    /** The living villager with this id anywhere on the server, or null if they cannot be reached. */
    fun findAnywhere(server: MinecraftServer, id: UUID): Villager? {
        for (candidate in server.allLevels) {
            val found = candidate.getEntity(id) as? Villager ?: continue
            if (found.isAlive) return found
        }
        return null
    }

    private fun report(
        captain: ServerPlayer, crewName: String,
        moved: Int, returned: Int, rebuilt: Int, lost: Int, overflow: Int
    ) {
        if (moved == 0 && returned == 0 && rebuilt == 0 && lost == 0 && overflow == 0) return

        val parts = mutableListOf<String>()
        if (moved > 0) parts.add("$moved aboard")
        if (returned > 0) parts.add("$returned back aboard")
        // Called out separately rather than folded into the total, because a rebuilt crew member is a NEW
        // entity -- anything another mod hung on the original did not come with them, and a player who has
        // been watching one closely deserves to know which. A crew member who was STOOD DOWN is a new entity
        // too, but that is the ordinary path and saying so every voyage would be noise.
        if (rebuilt > 0) parts.add("$rebuilt re-signed from the articles")
        if (overflow > 0) parts.add("$overflow paid off, no berth")
        if (lost > 0) parts.add("$lost could not be found")

        PathMessages.send(
            captain,
            "$crewName mustered: ${parts.joinToString(", ")}.",
            if (lost > 0 || overflow > 0) PathMessages.Kind.WARN else PathMessages.Kind.GOOD
        )
    }

    /** How far above the wheel a mustered crew member is placed. A step down onto the deck, never inside it. */
    private const val BOARDING_CLEARANCE = 1.5

    /** Someone standing on deck is a hair outside the hull box; the same slack `ShipCrew` allows. */
    private const val DECK_MARGIN = 2.0
}
