package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.GameRules
import net.minecraft.world.phys.AABB
import org.joml.Matrix4d
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.primitives.AABBdc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.util.logger
import org.valkyrienskies.eureka.path.PathMessages
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

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
        crewId: UUID,
        berthLimit: Int,
        station: Long
    ) {
        val ledger = CrewLedger.get(level.server)
        val crew = ledger.crew(crewId) ?: return
        // A crew belongs to somebody. Anyone else holding the wheel assembles the ship and musters nobody --
        // which is the whole answer to a stolen helm, and the reason the captain is checked here rather than
        // trusted to have been checked by whatever set the binding.
        if (crew.captain != captain.uuid) return
        val crewName = crew.name
        val berths = ledger.berths(crewId)
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

        val berthing = Berthing(level, ship, station)
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

            val spot = berthing.claim(berth)

            val live = findAnywhere(level.server, berth.villager)
            if (live != null) {
                live.teleportTo(spot.x, spot.y, spot.z)
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
            val restored = CrewSnapshot.restore(level, snapshot, spot)
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

        // `withPost` against `posted` is the line that matters when this goes wrong: it separates "the
        // articles never recorded where they stood" from "they were recorded and something moved them".
        // `labels` is expected to read 0 here -- the gun census needs shipAABB, which vs-core fills in a
        // beat after the ship exists; GunStations.reconcile is what seats the gunners, on its own clock.
        report(captain, crewName, moved, returned, rebuilt, lost, overflow)
    }

    /** What a stand-down amounted to: how many were taken off, of how many berthed crew found aboard. */
    class StandDownReport(val stood: Int, val berthedAboard: Int)

    /**
     * Where every berthed villager aboard [ship] is standing, as offsets from the wheel at [station] in the
     * ship's own block frame. Measured for a stand-down; kept on the berth as [CrewLedger.Berth.post].
     *
     * Has to be taken while the ship still EXISTS, which is the one awkward thing about it: the disassembly
     * stands its crew down after the unfill (so that a refused unfill cannot strand anybody), and by then
     * there is no ship left to measure against. So the measuring is separated from the standing down, and
     * the caller takes this reading first.
     */
    fun postsOf(level: ServerLevel, ship: LoadedServerShip, station: BlockPos): Map<UUID, Vec3> =
        measurePosts(level, ship, "ship") { pos ->
            val local = ship.transform.worldToShip.transformPosition(Vector3d(pos.x, pos.y, pos.z))
            Vec3(local.x - station.x, local.y - station.y, local.z - station.z)
        }

    /**
     * The same reading for a hull about to be laid back into the WORLD by a disassembly, measured in the
     * lattice the unfill is about to create: [snapped] is the quarter-turn-snapped ship-to-world matrix and
     * [carry] the sub-block offset the unfill will apply to blocks and riders alike (both from
     * `ShipAssembler.unfillPlan`), [anchor] the crew-station wheel in shipyard coordinates.
     *
     * Two frames, because a ship is rebuilt from whichever arrangement of blocks it is next gathered from,
     * and the two ways of packing a ship up leave that arrangement in different places. A BOTTLE writes the
     * shipyard layout into a template and lays it back down unturned, so the shipyard frame survives the
     * round trip and [postsOf] is right. A DISASSEMBLY snaps the hull to the nearest quarter turn as it
     * hands the blocks to the world, and the next assembly gathers them from there by pure translation --
     * so the only frame that survives is the one the unfill itself is about to write. Anything less exact
     * showed up in play at once: a plain world-frame reading missed the snap's half-block terms, and a
     * hull disassembled facing a different quarter than it was built at put its whole crew back one block
     * off -- through walls and over rails.
     */
    fun postsInLattice(
        level: ServerLevel,
        ship: LoadedServerShip,
        snapped: Matrix4d,
        carry: Vector3d,
        anchor: BlockPos
    ): Map<UUID, Vec3> {
        // Where the wheel's BLOCK will stand after the unfill: the same floor(centre) write the blocks get.
        val anchorAfter = snapped.transformPosition(Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5))
            .let { Vec3(floor(it.x), floor(it.y), floor(it.z)) }
        val worldToShip = ship.transform.worldToShip
        return measurePosts(level, ship, "lattice") { pos ->
            // The rider's own journey through the unfill: into the ship frame, out through the snapped
            // matrix, carried by the same sub-block offset the deck under their feet takes.
            val after = snapped.transformPosition(
                worldToShip.transformPosition(Vector3d(pos.x, pos.y, pos.z))
            )
            Vec3(
                after.x + carry.x - anchorAfter.x,
                after.y + carry.y - anchorAfter.y,
                after.z + carry.z - anchorAfter.z
            )
        }
    }

    private fun measurePosts(
        level: ServerLevel,
        ship: LoadedServerShip,
        frame: String,
        offsetOf: (Vec3) -> Vec3
    ): Map<UUID, Vec3> {
        val ledger = CrewLedger.get(level.server)
        val posts = HashMap<UUID, Vec3>()
        val seen = villagersIn(level, ship.worldAABB)
        for (villager in seen) {
            if (ledger.berthOf(villager.uuid) == null) continue
            posts[villager.uuid] = offsetOf(Vec3(villager.x, villager.y, villager.z))
        }
        return posts
    }

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
     * wrong wheel stands down nobody at all. So the search starts from the LEDGER and works outward -- every
     * berth on every crew [crewIds] names (all captains at once, since the wheel is being taken apart for all
     * of them), plus every berthed villager the hull box turns up regardless of whose articles they are on, so
     * an unbound wheel or a guest crew still gets everyone off the deck. A berthed villager who is genuinely
     * somewhere else is left there: being on the books does not mean being aboard.
     *
     * [crewIds] being empty is not an error -- it means no wheel aboard claimed anybody, and the box sweep is
     * then the only source. That is exactly the case the sweep exists for.
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
        crewIds: Collection<UUID>,
        posts: Map<UUID, Vec3> = emptyMap(),
        sweepHull: Boolean = true
    ): StandDownReport {
        val ledger = CrewLedger.get(level.server)

        // Two independent ways onto the list, union'd by villager id. The bindings are the intended path; the
        // box sweep is the safety net that holds when a wheel points at the wrong crew or at none, and the
        // reason a mismatch can never again cost a whole crew.
        val berths = LinkedHashMap<UUID, CrewLedger.Berth>()
        var berthsByCrew = 0
        for (crewId in crewIds) {
            for (berth in ledger.berths(crewId)) {
                berths[berth.villager] = berth
                berthsByCrew++
            }
        }
        val inBox = villagersIn(level, hull).associateBy { it.uuid }
        var berthedInBox = 0
        // The sweep is off for a crew SWAP, and only for that. Everywhere else -- disassembly, the bottle,
        // salvage, foundering -- the hull is going away and everyone on it has to come off, so casting the
        // wider net is the whole safety property. A swap is the opposite: it takes one named crew off a ship
        // that carries on existing, and sweeping would take a guest crew and another captain's hands with it.
        if (sweepHull) {
            for (villager in inBox.values) {
                val berth = ledger.berthOf(villager.uuid) ?: continue
                berthedInBox++
                berths.putIfAbsent(berth.villager, berth)
            }
        }

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
                continue
            }
            if (villager.level() !== level) {
                continue
            }
            // Aboard means inside the hull box -- or seated on one of THIS ship's gun seats, which pins them
            // to the ship more directly than any position test can.
            val seated = (villager.vehicle as? ShipMountingEntity)?.driveShipId == shipId
            if (!seated && !deck.contains(villager.x, villager.y, villager.z)) {
                continue
            }
            berthedAboard++
            val snapshot = CrewSnapshot.capture(villager)
            if (snapshot == null) {
                logger.warn("Crew snapshot failed for ${berth.name}; left aboard")
                continue
            }
            // The seat first, while the villager still exists to be dismounted: this kills it now and clears
            // the runtime seating, rather than leaving an orphan for the reconcile sweep to find.
            GunStations.unseat(level, berth.villager)
            ledger.standDown(berth.villager, snapshot, posts[berth.villager])
            // `discard` rather than `kill`: nobody died. There is nothing to drop, no experience to award,
            // and the same crew member walks back out of the articles when she is rebuilt.
            villager.discard()
            stood++
        }

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

    /**
     * Every living villager inside a world-space box, with the usual slack for standing on the top face.
     * Public because "who is actually aboard" is a question more than the muster asks -- the Restock
     * orders count their Crewmen through it.
     */
    fun villagersIn(level: ServerLevel, hull: AABBdc): List<Villager> {
        val box = AABB(
            hull.minX() - DECK_MARGIN, hull.minY() - DECK_MARGIN, hull.minZ() - DECK_MARGIN,
            hull.maxX() + DECK_MARGIN, hull.maxY() + DECK_MARGIN, hull.maxZ() + DECK_MARGIN
        )
        return level.getEntitiesOfClass(Villager::class.java, box) { it.isAlive }
    }

    /**
     * Hands every mustered crew member a place to stand, and never hands out the same block twice.
     *
     * ## Why this exists at all
     * Every returning crew member used to be put down on one square: the block above the wheel. Sixty-seven
     * villagers in one block is not a crowd, it is a death sentence -- vanilla's entity cramming kills
     * everything past twenty-four of them, and a dead crew member is struck off the articles for good. One
     * reassembly cost a captain forty-three of sixty-seven. Handing out distinct blocks is what makes that
     * impossible rather than unlikely.
     *
     * ## Posts first, then the wheel
     * A berth that remembers where it stood ([CrewLedger.Berth.post]) is put back exactly there, which is
     * the whole point of remembering: a shop keeps its shopkeeper, a gun deck keeps its gunners, a ship
     * stays the place it was built to be. Anyone without a post -- signed on ashore, or berthed before
     * posts existed -- rings out around the wheel instead, one to a block, spiralling until there is room.
     *
     * The search prefers a block with something solid under it, so nobody is handed a spot in mid-air over
     * the deck while a perfectly good plank sits one block over.
     */
    private class Berthing(
        private val level: ServerLevel,
        private val ship: LoadedServerShip,
        station: Long
    ) {
        private val helm = BlockPos.of(station)
        private val taken = HashMap<BlockPos, Int>()

        /**
         * This hull's own bounds, in shipyard blocks, or null while vs-core has yet to fill them in.
         *
         * Null is trusted rather than refused: at ASSEMBLY the bounds arrive a beat after the ship does, and
         * every post in hand there was measured on this same hull moments earlier. There is nothing to catch.
         */
        private val hull = ship.shipAABB

        /** How many were put back exactly where they were standing, and how many had to be found room. */
        var posted = 0
            private set
        var scattered = 0
            private set

        /** Posts thrown away for belonging to another hull. Logged, because it is never zero by accident. */
        var strays = 0
            private set

        /**
         * The per-block ceiling: one under the cramming rule, so even a hull with fewer free blocks than
         * crew cannot crush anybody. A rule of zero means the game has cramming switched off entirely, and
         * then there is nothing to protect against.
         */
        private val cap = level.gameRules.getInt(GameRules.RULE_MAX_ENTITY_CRAMMING).let {
            if (it <= 0) Int.MAX_VALUE else maxOf(1, it - 1)
        }

        /** Where this berth should land, in WORLD coordinates. */
        fun claim(berth: CrewLedger.Berth): Vec3 {
            // A post is an offset from the wheel in the ship's own block frame, so it means something only on
            // the hull it was measured on. A crew CALLED to a different ship carries posts that point into the
            // open air beside the new hull -- and open air passes `roomy` cheerfully, so the post was honoured
            // exactly and the hand was placed off the deck with no search at all. On an airship at altitude
            // that is a crew falling to its death, one by one, which is exactly what it did.
            //
            // This could not happen while a crew only ever came back to the hull they left: assembly, the
            // bottle and salvage all put people back where they were. Summoning is the first thing that moves
            // a crew BETWEEN ships, and it is what made a stale post reachable.
            val post = berth.post?.takeIf { p ->
                aboard(BlockPos.containing(helm.x + p.x, helm.y + p.y + FOOT_LIFT, helm.z + p.z))
                    .also { if (!it) strays++ }
            }
            val wanted = if (post != null) {
                Vec3(helm.x + post.x, helm.y + post.y, helm.z + post.z)
            } else {
                Vec3(helm.x + 0.5, helm.y + BOARDING_CLEARANCE, helm.z + 0.5)
            }

            // The FOOT_LIFT before flooring: a foot standing exactly on a block boundary -- everybody on a
            // flush deck, and a seated gunner within a twentieth of one -- must resolve to the block the
            // BODY occupies, not to the deck plank under it, which is solid and reads as "no room here".
            val first = BlockPos.containing(wanted.x, wanted.y + FOOT_LIFT, wanted.z)

            // A remembered post is not a crowd. Two gunners legitimately share the walkway block between
            // their guns, and scattering the second man off his own post traded exactness for a rule that
            // exists to stop a HEAP -- so a post may be re-used up to the cramming cap, and only the
            // wheel-ring fallback keeps the strict one-to-a-block rule.
            val block =
                if (post != null && roomy(first) && (taken[first] ?: 0) < cap) first
                else findRoom(first)
            taken.merge(block, 1, Int::plus)
            if (post != null && block == first) posted++ else scattered++

            // The exact spot when it was free -- standing in a doorway means standing in THAT part of the
            // doorway -- and the middle of whatever block had room otherwise.
            val local =
                if (block == first) wanted
                else Vec3(block.x + 0.5, block.y.toDouble(), block.z + 0.5)
            val world = ship.shipToWorld.transformPosition(Vector3d(local.x, local.y, local.z))
            return Vec3(world.x, world.y, world.z)
        }

        /**
         * The nearest block to [first] that a villager can be put in: empty at head and foot, not already
         * spoken for, and ideally standing on something. Widening rings, lowest first, so a crowd fills the
         * deck it is on before it spills onto the one above.
         */
        private fun findRoom(first: BlockPos): BlockPos {
            var airborne: BlockPos? = null
            var crowded: BlockPos? = null
            val cursor = BlockPos.MutableBlockPos()
            for (radius in 0..SEARCH_RADIUS) {
                for (dy in VERTICAL_ORDER) {
                    for (dx in -radius..radius) {
                        for (dz in -radius..radius) {
                            // Only the ring's edge: the inside was covered by a smaller radius already.
                            if (radius > 0 && abs(dx) != radius && abs(dz) != radius) continue
                            cursor.set(first.x + dx, first.y + dy, first.z + dz)
                            if (!roomy(cursor)) continue
                            val here = cursor.immutable()
                            // EMPTY is the bar, not "under the cramming limit". Letting a block take a
                            // second body while another stands free is how the first cut managed to put a
                            // whole crew back on one square -- the very thing this class exists to stop.
                            if (!spokenFor(here)) {
                                if (standable(here)) return here
                                if (airborne == null) airborne = here
                            } else if (crowded == null && (taken[here] ?: 0) < cap) {
                                crowded = here
                            }
                        }
                    }
                }
            }
            // In order of preference: a free block with a floor, a free block in the air, and only then
            // somebody else's block -- which the cramming cap keeps survivable. `first` is the last resort
            // for a hull so full that nothing within reach is even standable.
            return airborne ?: crowded ?: first
        }

        /**
         * Whether a shipyard block is on THIS hull -- grown by one, so a post on the gunwale or a step off
         * the stern still counts as aboard rather than being thrown away as somebody else's.
         */
        private fun aboard(pos: BlockPos): Boolean {
            val box = hull ?: return true
            return pos.x >= box.minX() - 1 && pos.x <= box.maxX() + 1 &&
                pos.y >= box.minY() - 1 && pos.y <= box.maxY() + 1 &&
                pos.z >= box.minZ() - 1 && pos.z <= box.maxZ() + 1
        }

        /** Two blocks of clear air here: enough for a villager to be put down without suffocating. */
        private fun roomy(pos: BlockPos): Boolean = clear(pos) && clear(pos.above())

        private fun spokenFor(pos: BlockPos): Boolean = (taken[pos] ?: 0) > 0

        private fun clear(pos: BlockPos): Boolean =
            level.getBlockState(pos).getCollisionShape(level, pos).isEmpty

        private fun standable(pos: BlockPos): Boolean =
            !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty
    }

    /** How far a crowded muster will look for room, in blocks, before it settles for standing in the air. */
    private const val SEARCH_RADIUS = 6

    /** Lifts a measured foot off the block boundary before flooring it -- see the note in `claim`. */
    private const val FOOT_LIFT = 0.1

    /** Heights the search tries, in order: this deck first, then a step up, then down into the hold. */
    private val VERTICAL_ORDER = listOf(0, 1, -1, 2, -2)

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
            if (lost > 0 || overflow > 0) PathMessages.Kind.WARN else PathMessages.Kind.GOOD, PathMessages.Topic.CREW_MUSTER
        )
    }

    /**
     * How far above the wheel an unposted crew member is placed. A step down onto the deck, never inside it.
     *
     * The WHEEL is the anchor, and that was learned the hard way: this used to be the top of the ship's
     * world box, on the reasoning that anywhere above the hull is somewhere you can fall onto the deck
     * from. True of a raft and badly false of anything with masts -- the box reaches the top of the
     * rigging, so a crew called aboard a galleon appeared level with the crow's nest and fell the height of
     * the ship. The wheel is a real block on a real deck, it is by definition somewhere a player stands,
     * and the assembly already knows where it landed.
     */
    private const val BOARDING_CLEARANCE = 1.5

    /** Someone standing on deck is a hair outside the hull box; the same slack `ShipCrew` allows. */
    private const val DECK_MARGIN = 2.0
}
