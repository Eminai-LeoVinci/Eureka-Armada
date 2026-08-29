package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/**
 * Keeps every stationed gunner in his seat, and no one in a seat that has stopped being theirs.
 *
 * The LEDGER is the record ([CrewLedger.Berth.station] / [CrewLedger.Berth.stationLabel]); this object is the
 * muscle. Once a second it reconciles the world against the articles: seats gunners who should be seated,
 * relieves the ones whose gun has stopped existing, and re-seats a crew that came back from a disassembly by
 * looking their remembered LABEL up on the freshly-dealt shipyard addresses.
 *
 * ## The seat is VS2's world-space passenger seat, and that choice is the whole fix
 * The first cut seated gunners on a custom seat at SHIPYARD coordinates, like the helm's -- and learned in
 * order the two lessons its own base class documents. Shipyard chunks never entity-tick, so the seat needed
 * a hand-rolled per-tick glue to place its rider; and then vanilla sends NO movement packets for an entity
 * that is a passenger, so however right the server's picture was, the CLIENT still drew every gunner frozen
 * on the spot where he was standing when the order landed -- unreachable to clicks, because the server's
 * copy stood fourteen blocks away behind his gun. [ShipMountingEntity.spawnPassengerSeat] is the mechanism
 * VS2 built for exactly this (it is what carries a reconnecting player on a moving ship): a WORLD-space seat
 * that snaps itself to `shipToWorld(driveRelPos)` every tick, ticks like any world entity so vanilla
 * positions its rider server-side, and syncs its drive anchor so the client drives it identically. No glue,
 * no packets of our own, both sides agree.
 *
 * "The gunner ignores everything" comes free of the mount itself: a mounted mob's navigation cannot move it,
 * and vanilla mobs never dismount by choice -- zombies, damage and wanderlust are all answered by the seat.
 * Death still works: the ledger strikes the berth, the binding evaporates, and the seat -- which self-kills
 * the tick its rider is gone -- cleans itself up.
 *
 * What it does NOT come free of is the THINKING, which this class was originally written believing. A
 * navigation that cannot move a mob still runs in full, and aboard a ship each path costs many times what
 * it does ashore -- so sixty seated gunners were quietly eating the server thread whole. See
 * [standDownNavigation], which is the profile and the fix.
 */
object GunStations {

    /** One gunner's live seating: which gun, and the world-space seat serving it. */
    private class Seating(val gunPos: BlockPos, val seat: ShipMountingEntity)

    /** villager -> their seating. Runtime only; rebuilt from the ledger by [reconcile]. */
    private val seats = HashMap<UUID, Seating>()

    fun tick(level: ServerLevel) {
        if (seats.isNotEmpty()) standDownNavigation(level)
        if (level.gameTime % RECONCILE_INTERVAL == 0L) reconcile(level)
    }

    /**
     * A gunner at his gun does not walk anywhere, so he must not THINK about walking either.
     *
     * The note above this class says a mounted mob's navigation cannot move it, and that is true -- but it
     * was the wrong thing to have checked. Navigation that cannot move a mob still RUNS: the brain keeps a
     * walk target, `MoveToTargetTask` keeps calling for a path to it, and the path is computed in full
     * every time. A spark profile of a ship with sixty seated gunners put **95.7% of the entire server
     * thread** under `VillagerEntity`, 95.9% of it inside the pathfinder and 86.3% under that one task --
     * for sixty villagers who were bolted to cannons and could not have taken a step if they wanted to.
     *
     * It is worse than ordinary wasted work, for two reasons that compound:
     *
     *  - Two thirds of the cost is not vanilla's at all. Every block the path evaluator reads goes through
     *    VS2's `getBlockState` overlay, which asks which ships intersect that position -- a spatial query,
     *    a map iteration and an allocation, per block, and a path evaluator reads thousands of blocks.
     *    (67% of the thread was inside that mixin.) Pathfinding aboard a ship costs an order of magnitude
     *    more than pathfinding ashore, which is exactly why this never showed up on land.
     *  - Vanilla's own escape hatch is disarmed. `EntityNavigation` gives up on a path when the mob stops
     *    making progress -- but a seated gunner is snapped to a moving seat every tick, so he reads as
     *    moving beautifully while never getting anywhere. The stuck-detector never fires, and the path is
     *    recomputed forever.
     *
     * So the walk target is erased and the navigation stopped every tick a gunner is in his seat. Erasing
     * the memory is the lever rather than stopping the path, because `MoveToTargetTask` requires the memory
     * to be there at all: with it gone the task never starts and no path is ever built. Standing a gunner
     * down removes him from [seats] and his brain resumes untouched the same tick.
     */
    private fun standDownNavigation(level: ServerLevel) {
        val freeze = EurekaConfig.SERVER.crewGunnerFreeze
        for (seating in seats.values) {
            // [seats] is one map for every dimension, while this tick is per level -- so a seat belonging
            // to another world is skipped rather than reached into. (The same rule GunnerMounts' posts
            // learned the hard way: a global map maintained by a per-level tick will be wrong otherwise.)
            if (seating.seat.level() !== level) continue
            val gunner = seating.seat.passengers.firstOrNull() as? Villager ?: continue

            // The whole hammer: no brain, no navigation, no looking about. `isNoAi` is what the pillager
            // gun crews have always run on ([GunnerMounts]), and it makes a seated villager cost about
            // what a dropped item costs. Damage, death and the ledger all still work -- what is given up
            // is that he no longer turns to watch you, and cannot be traded with until he is stood down.
            if (freeze) {
                if (!gunner.isNoAi) gunner.isNoAi = true
                continue
            }

            // The lighter measure, for a captain who would rather keep his crew looking alive: leave the
            // brain running but take away the one thing it does that costs -- the walk target, without
            // which `MoveToTargetTask` never starts and no path is ever built.
            if (gunner.isNoAi) gunner.isNoAi = false
            gunner.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            if (!gunner.navigation.isDone) gunner.navigation.stop()
        }
    }

    /**
     * Wake a gunner as he leaves his seat. Called from every path that ends a seating, because [isNoAi] is
     * PERSISTED on the entity: a crew member stood down while frozen and never woken would be a statue for
     * the rest of the save, and one released by a disassembly would be a statue on the deck of a ship that
     * no longer exists.
     */
    private fun wake(seat: ShipMountingEntity) {
        (seat.passengers.firstOrNull() as? Villager)?.let { if (it.isNoAi) it.isNoAi = false }
    }

    /** Server stopped: entities are gone with it, only the map survives to be wrong. */
    fun reset() = seats.clear()

    /**
     * Put [villagerId] on station at the gun whose breech is [gunPos]: TELEPORT them to the spot behind the
     * cart, then seat them there. Returns whether they are seated.
     *
     * The teleport is not a flourish, it is the guarantee. Round one mounted the villager wherever they
     * happened to be standing and trusted the per-tick glue to reel them in -- and half a test crew ended up
     * frozen nowhere near any gun. Moving the entity FIRST (the same [net.minecraft.world.entity.Entity
     * .teleportTo] crew muster uses to put villagers on decks) means that whatever the mount machinery does
     * afterwards, the gunner is standing at his gun. The lock-in gesture is the manifest's business; by the
     * time this is called, the order is given.
     */
    fun stationNow(level: ServerLevel, villagerId: UUID, gunPos: BlockPos): Boolean {
        val villager = CrewMuster.findAnywhere(level.server, villagerId) ?: return false
        if (villager.level() !== level || !villager.isAlive) return false
        val behind = behindOf(level, gunPos) ?: return false

        // Re-stationing: free the old seat before the move, so its orphan can never hold a stale claim.
        (villager.vehicle as? ShipMountingEntity)?.takeIf { !it.isController }?.let { wake(it); it.kill(level) }

        val ship = level.getLoadedShipManagingPos(gunPos) ?: return false
        val footing = footingOf(level, gunPos) as? Footing.Stand ?: return false
        val world = ship.shipToWorld.transformPosition(
            Vector3d(behind.x + 0.5, footing.surfaceY + 0.05, behind.z + 0.5)
        )
        villager.teleportTo(world.x, world.y, world.z)
        return ensureMounted(level, villager, gunPos)
    }

    /** Is a villager gunner currently holding this gun? Lets the mob gun crews keep off stationed guns. */
    fun manned(gunPos: BlockPos): Boolean =
        seats.values.any { it.gunPos == gunPos && !it.seat.isRemoved }

    /** Take [villagerId] out of any gunner seat now -- unassignment and duty changes call this. */
    fun unseat(level: ServerLevel, villagerId: UUID) {
        seats.remove(villagerId)?.seat?.takeIf { !it.isRemoved }?.let { wake(it); it.kill(level) }
        // Belt and braces for a binding the map lost track of (a reload, a dimension hop): the villager
        // itself knows what it is riding. Never a CONTROLLER seat -- that is the helm's, and no villager
        // legitimately sits one.
        val villager = CrewMuster.findAnywhere(level.server, villagerId)
        (villager?.vehicle as? ShipMountingEntity)?.takeIf { !it.isController }?.kill(level)
        // The freeze lives on the VILLAGER, not on the seat. Seating sets isNoAi so a gunner stands still
        // at his breech, and [wake] can only reach that flag THROUGH a seat that still exists with him
        // still riding it. So every path that had already lost the seat -- a reload, a dimension hop, a
        // summon to another hull -- killed the chair and left the man in it switched off: alive, hurtable,
        // and unable to move again for good. Release and paying off both come through here, which is
        // exactly why neither of them ever freed anybody. Cleared unconditionally now.
        if (villager != null && villager.isNoAi) villager.isNoAi = false
    }

    private fun reconcile(level: ServerLevel) {
        val ledger = CrewLedger.get(level.server)
        val stationed = ledger.stationedBerths()
        // Which villagers END this pass legitimately seated in THIS level; everything else's seat is orphaned.
        val bound = HashSet<UUID>()

        for (berth in stationed) {
            val villager = CrewMuster.findAnywhere(level.server, berth.villager)
            // Nobody to seat: ashore after a stand-down (the label is waiting for the reassembly), or simply
            // in an unloaded chunk. Either way there is nothing to do but let the orphan sweep take the seat.
            if (villager == null || !villager.isAlive) continue
            if (villager.level() !== level) continue

            val station = berth.station
            if (station != null) {
                val pos = BlockPos.of(station)
                // An unloaded chunk answers "no block entity" exactly as a mined gun does, and this pass
                // runs a second after an assembly when the shipyard may still be settling. Silence is not
                // evidence: wait for a chunk we can actually read before relieving anybody.
                if (!level.hasChunkAt(pos)) continue
                if (level.getBlockEntity(pos) !is CannonBlockEntity) {
                    // The gun is gone -- destroyed, mined, or shot away. This is the relieved-of-post rule.
                    relieve(level, ledger, berth.villager, berth.name, berth.stationLabel)
                    continue
                }
                // Guns elsewhere on the ship may have come or gone, which re-deals the names: keep the
                // recorded label the one the manifest and the relieve message will actually say.
                (level.getLoadedShipManagingPos(pos) as? LoadedServerShip)?.let { ship ->
                    val live = GunLabels.labelAt(level, ship, pos)
                    if (live != null && live != berth.stationLabel) ledger.setStation(berth.villager, station, live)
                }
                bound.add(berth.villager)
                ensureMounted(level, villager, pos)
            } else {
                // Label only: the ship was packed up under this gunner and has, evidently, come back --
                // find the gun that answers to their old name on the new addresses and seat them there.
                val label = berth.stationLabel ?: continue
                val shipId = ShipCrew.standingOn(villager) ?: continue
                val ship = level.shipObjectWorld.loadedShips.getById(shipId) ?: continue
                // "This ship has no names for its guns" is not the same statement as "no gun answers to
                // yours". A census that comes up empty means the hull has no crew-station wheel yet, or its
                // chunks are not readable yet -- both true for a beat after every assembly -- and reading
                // that as "your gun is gone" is what quietly stripped a whole gun crew of their stations
                // one second after they mustered, leaving sixty gunners wandering the deck.
                val named = GunLabels.labeled(level, ship)
                if (named.isEmpty()) continue
                val gun = named.firstOrNull { it.label == label }?.gun
                if (gun == null) {
                    // Aboard again, and the ship CAN name its guns -- so this one really is gone.
                    relieve(level, ledger, berth.villager, berth.name, label)
                    continue
                }
                ledger.setStation(berth.villager, gun.blockPos.asLong(), label)
                bound.add(berth.villager)
                ensureMounted(level, villager, gun.blockPos)
            }
        }

        // Orphan sweep: any seating this level holds for somebody no longer bound here -- relieved,
        // re-tasked, dead (the berth is struck), or stood down. A riderless seat kills ITSELF (world-space
        // passenger seats self-kill on their first empty tick), so this is mostly map hygiene.
        val iterator = seats.entries.iterator()
        while (iterator.hasNext()) {
            val (villagerId, seating) = iterator.next()
            if (seating.seat.isRemoved) {
                iterator.remove()
                continue
            }
            if (seating.seat.level() !== level) continue
            if (villagerId !in bound) {
                wake(seating.seat)
                seating.seat.kill(level)
                iterator.remove()
            }
        }
    }

    /**
     * The spot a gunner serves this gun from: the block BEHIND the cart, opposite the muzzle -- where a real
     * crew stands to load and lay a muzzle-loader, and where they are out of the recoil's way. Null only for
     * a position that has stopped being a cannon.
     */
    private fun behindOf(level: ServerLevel, gunPos: BlockPos): BlockPos? {
        val state = level.getBlockState(gunPos)
        if (!state.hasProperty(HORIZONTAL_FACING)) return null
        return gunPos.relative(state.getValue(HORIZONTAL_FACING).opposite)
    }

    /** Where a gunner's feet would land behind this gun, or the plain-words reason they cannot stand there. */
    private sealed class Footing {
        class Stand(val surfaceY: Double) : Footing()
        class Refused(val reason: String) : Footing()
    }

    /**
     * Find the deck behind the gun, at whatever height it actually is.
     *
     * Guns are not always bedded flush: a deck half a slab lower puts the gunner's eye at the breech, a full
     * block higher lays the barrel flush with his floor, and the first cut -- which pinned the stand height
     * to the CANNON's base -- phased him into any deck that didn't happen to match it. So the column behind
     * the cart is scanned from one block above the breech down to one below, and the first collision top is
     * the floor. Tolerance is the user's rule: the surface may sit anywhere from a block below the breech to
     * a block above it (slab steps included by construction, since real shape tops are read); beyond that,
     * or with anything filling the 1x2 a villager needs above the floor, the station is REFUSED with the
     * reason -- a gun you cannot serve is a gun you cannot be assigned.
     */
    private fun footingOf(level: ServerLevel, gunPos: BlockPos): Footing {
        val behind = behindOf(level, gunPos)
            ?: return Footing.Refused("the gun is missing its breech")

        // Down to TWO blocks below the breech, not one: a deck a full block below -- an allowed height --
        // has its surface at behind.y - 1, and the block PROVIDING that surface sits at behind.y - 2. The
        // tolerance test below still rejects anything whose top comes out beyond a block away.
        var surface: Double? = null
        for (y in behind.y + 1 downTo behind.y - 2) {
            val pos = BlockPos(behind.x, y, behind.z)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            if (!shape.isEmpty) {
                surface = y + shape.max(Direction.Axis.Y)
                break
            }
        }
        if (surface == null) {
            return Footing.Refused("there is nothing to stand on behind it")
        }
        if (surface < behind.y - 1.0 || surface > behind.y + 1.0) {
            return Footing.Refused("the footing behind it is more than a block above or below the gun")
        }
        val room = AABB(
            behind.x + 0.2, surface + 0.05, behind.z + 0.2,
            behind.x + 0.8, surface + 1.95, behind.z + 0.8
        )
        if (!level.noCollision(null, room)) {
            return Footing.Refused("there is no room to stand behind it")
        }
        return Footing.Stand(surface)
    }

    /** The reason nobody can be stationed at the gun at [gunPos], or null when someone can. */
    fun footingProblem(level: ServerLevel, gunPos: BlockPos): String? =
        (footingOf(level, gunPos) as? Footing.Refused)?.reason

    private fun ensureMounted(level: ServerLevel, villager: Villager, gunPos: BlockPos): Boolean {
        val behind = behindOf(level, gunPos) ?: return false
        val riding = villager.vehicle
        val seating = seats[villager.uuid]
        if (seating != null && seating.gunPos == gunPos && !seating.seat.isRemoved && riding === seating.seat) {
            return true
        }
        // Seated at the wrong gun (the station changed), or in something else entirely.
        seats.remove(villager.uuid)?.seat?.takeIf { !it.isRemoved }?.let { wake(it); it.kill(level) }
        if (riding is ShipMountingEntity && !riding.isController) riding.kill(level)
        else if (riding != null) villager.stopRiding()

        val ship = level.getLoadedShipManagingPos(gunPos) as? LoadedServerShip ?: return false
        val state = level.getBlockState(gunPos)
        val yaw = if (state.hasProperty(HORIZONTAL_FACING)) state.getValue(HORIZONTAL_FACING).toYRot() else 0.0f
        val footing = footingOf(level, gunPos) as? Footing.Stand ?: return false

        // The drive anchor is the stand point in SHIPYARD coordinates -- on the DETECTED deck surface, not
        // the cannon's base height, which is what phased gunners into any floor that wasn't bedded exactly
        // like the test ship's. The seat spawns at the anchor's current world image and keeps ITSELF there
        // every tick, on both sides; see the class doc.
        //
        // SEAT_SINK is the measured gap between the seat's anchor and where the rider's FEET actually end up
        // (vanilla lowers a rider by its vehicle-attachment, and the render pipeline by a little more).
        // Calibrated against the one arrangement that looked right in play -- deck half a slab below the
        // breech -- and applied relative to the detected surface, so every legal deck height lands the same.
        val ride = villager.getVehicleAttachmentPoint(villager)
        val relX = behind.x + 0.5
        val relY = footing.surfaceY + SEAT_SINK + ride.y
        val relZ = behind.z + 0.5
        val world = ship.shipToWorld.transformPosition(Vector3d(relX, relY, relZ))
        val seat = ShipMountingEntity.spawnPassengerSeat(
            level, world.x, world.y, world.z, yaw, 0.0f, ship.id, relX, relY, relZ
        ) ?: return false
        if (!villager.startRiding(seat, true, true)) {
            seat.kill(level)
            return false
        }
        villager.yRot = yaw
        seats[villager.uuid] = Seating(gunPos, seat)
        return true
    }

    private fun relieve(level: ServerLevel, ledger: CrewLedger, villagerId: UUID, name: String, label: String?) {
        val captain = ledger.crewOf(villagerId)?.let { ledger.crew(it)?.captain }
        ledger.clearStation(villagerId)
        unseat(level, villagerId)
        val gunName = label ?: "their gun"
        captain?.let { level.server.playerList.getPlayer(it) }?.let { player ->
            PathMessages.send(player, "$name stands down -- $gunName is gone.", PathMessages.Kind.WARN, PathMessages.Topic.GUNNERY_GUN_LOST)
        }
    }

    /** Once a second, like the fire watch: bindings change at human speed, only the glue is per-tick. */
    private const val RECONCILE_INTERVAL = 20L

    /**
     * How far above the detected deck surface the seat's anchor sits so the RIDER's feet land ON that
     * surface: vanilla drops a rider below its seat by the vehicle-attachment arithmetic plus a little
     * render-side settling. 0.55 is the value measured against the arrangement that rendered correctly
     * (deck half a slab below the breech) and holds for every height because the anchor tracks the surface.
     */
    private const val SEAT_SINK = 0.55
}
