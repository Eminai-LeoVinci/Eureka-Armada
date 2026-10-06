package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.ship.ShipCombat
import org.valkyrienskies.eureka.ship.ShipInfluence
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import java.util.UUID

/**
 * Crewman overboard: a crewman who goes over the side in peacetime is put back on his deck.
 *
 * Crew get thrown off on fast turns, with no rail to stop them. Once a second, for every berthed crewman (crew only:
 * not livestock, not hostiles):
 * - standing on a ship's deck, his spot is noted relative to that ship (three numbers, overwritten each second);
 * - standing on dry land off any ship, the note is dropped: he went ashore, and is left alone;
 * - in the water, or falling, OUTSIDE his last ship's influence border, on a ship that has taken no cannon or fire
 *   hit for [CALM_TICKS], he is put back. In a fight, overboard means overboard.
 *
 * Where he lands: not his noted spot, which is usually the edge he went over and would send him straight back
 * over. Instead, the same point along the hull's LONG axis (whichever of its ship-space X and Z extents is bigger),
 * 1 to 3 blocks either side of the centre line across it, at his deck height (trying up to two blocks up or down if
 * that height is blocked). Failing that, his noted spot if there is room; failing that, beside the nearest helm.
 *
 * Loop breaker: a crewman put back [LOOP_LIMIT] times in a row, each within [LOOP_WINDOW] ticks of the last, is
 * landed beside the helm nearest his noted spot instead.
 *
 * Cost is one entity lookup and a few comparisons per crewman per second, so even two 64-man crews are negligible.
 */
object CrewOverboard {

    private const val INTERVAL = 20L

    /** How long a ship must go without a cannon or fire hit before overboard crew are fetched back: 2 minutes. */
    private const val CALM_TICKS = 2400L

    /** A fall this long, in blocks, counts as going over the side rather than hopping. */
    private const val FALLING = 2.0f

    /** Rescues in a row, each within this many ticks of the last, count toward the loop breaker: 3 seconds. */
    private const val LOOP_WINDOW = 60L

    /** The rescue in a row that goes to the nearest helm instead. */
    private const val LOOP_LIMIT = 5

    private class Spot(val shipId: Long, val rel: Vector3d)

    private class Streak(var count: Int, var lastTick: Long)

    private val lastDeck = HashMap<UUID, Spot>()
    private val streaks = HashMap<UUID, Streak>()

    @JvmStatic
    fun tick(level: ServerLevel) {
        if (level.gameTime % INTERVAL != 0L) return
        val ledger = CrewLedger.get(level.server)
        val berthed = ledger.berthedVillagers()
        if (lastDeck.size > berthed.size) lastDeck.keys.retainAll(berthed)
        if (streaks.size > berthed.size) streaks.keys.retainAll(berthed)
        for (id in berthed) {
            val villager = level.getEntity(id) as? Villager ?: continue
            if (!villager.isAlive || villager.isPassenger) continue
            val info = (villager as IEntityDraggingInformationProvider).draggingInformation
            val carriedBy = info.lastShipStoodOn

            if (villager.onGround() && carriedBy != null && info.isEntityBeingDraggedByAShip()) {
                val ship = level.shipObjectWorld.allShips.getById(carriedBy) ?: continue
                lastDeck[id] = Spot(carriedBy, ship.worldToShip.transformPosition(Vector3d(villager.x, villager.y, villager.z)))
                continue
            }
            if (villager.onGround() && !villager.isInWater) {
                lastDeck.remove(id)
                continue
            }
            val spot = lastDeck[id] ?: continue
            val overboard = villager.isInWater || villager.fallDistance > FALLING
            if (!overboard) continue
            val ship = level.shipObjectWorld.loadedShips.getById(spot.shipId) ?: continue
            if (ShipInfluence.insideBorder(ship, villager.x, villager.y, villager.z)) continue
            if (!ShipCombat.calmFor(spot.shipId, level.gameTime, CALM_TICKS)) continue
            rescue(level, villager, ship, spot)
        }
    }

    private fun rescue(level: ServerLevel, villager: Villager, ship: LoadedServerShip, spot: Spot) {
        val streak = streaks.getOrPut(villager.uuid) { Streak(0, Long.MIN_VALUE / 2) }
        streak.count = if (level.gameTime - streak.lastTick <= LOOP_WINDOW) streak.count + 1 else 1
        streak.lastTick = level.gameTime
        val standAt = if (streak.count >= LOOP_LIMIT) {
            besideNearestHelm(level, ship, spot.rel) ?: onCentreLine(level, ship, spot.rel)
        } else {
            onCentreLine(level, ship, spot.rel)
                ?: spot.rel.takeIf { standable(level, it) }
                ?: besideNearestHelm(level, ship, spot.rel)
        } ?: return
        val world = ship.shipToWorld.transformPosition(Vector3d(standAt))
        villager.teleportTo(world.x, world.y, world.z)
        villager.deltaMovement = Vec3.ZERO
        villager.resetFallDistance()
        // Tell every client about the jump with a plain teleport. Once the ship carries him, VS2 replaces his vanilla
        // position updates with ship-relative ones that only ease a client's copy from where it already is; they
        // never make the jump back from mid-air, so without this every watcher saw him hang where he fell (and
        // could not hit him: the real one was on deck). This packet goes out directly, past that swap.
        level.chunkSource.chunkMap.broadcast(villager, ClientboundTeleportEntityPacket(villager))
        // Carried by the ship from this tick on, rather than waiting to touch the deck.
        (villager as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = ship.id
    }

    /** Is there room to stand at shipyard position [rel]: something solid underfoot and two clear blocks? */
    private fun standable(level: ServerLevel, rel: Vector3d): Boolean {
        val feet = BlockPos.containing(rel.x, rel.y + 0.01, rel.z)
        return solid(level, feet.below()) && clear(level, feet) && clear(level, feet.above())
    }

    private fun solid(level: ServerLevel, pos: BlockPos): Boolean =
        !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty

    private fun clear(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBlockState(pos).getCollisionShape(level, pos).isEmpty

    /**
     * A standing spot on [ship]'s centre line, level with shipyard position [rel]: same point along the hull's long
     * axis, 1 to 3 blocks either side of the centre across it (random side first, so crew don't stack), at [rel]'s
     * height or up to two blocks above or below it.
     */
    private fun onCentreLine(level: ServerLevel, ship: LoadedServerShip, rel: Vector3d): Vector3d? {
        val box = ship.shipAABB ?: return null
        val alongX = box.maxX() - box.minX() >= box.maxZ() - box.minZ()
        val feet = BlockPos.containing(rel.x, rel.y + 0.01, rel.z)
        val along = if (alongX) feet.x.coerceIn(box.minX(), box.maxX()) else feet.z.coerceIn(box.minZ(), box.maxZ())
        val centre = if (alongX) Math.floorDiv(box.minZ() + box.maxZ(), 2) else Math.floorDiv(box.minX() + box.maxX(), 2)
        val first = if (level.random.nextBoolean()) 1 else -1
        for (offset in 1..3) for (side in intArrayOf(first, -first)) {
            val across = centre + side * offset
            for (dy in intArrayOf(0, 1, -1, 2, -2)) {
                val c = if (alongX) BlockPos(along, feet.y + dy, across) else BlockPos(across, feet.y + dy, along)
                if (solid(level, c.below()) && clear(level, c) && clear(level, c.above())) {
                    return Vector3d(c.x + 0.5, c.y.toDouble(), c.z + 0.5)
                }
            }
        }
        return null
    }

    /**
     * An open spot in the 3x3 around the helm on [ship] nearest shipyard position [near], at the helm's own level.
     * Ships can carry more than one helm; each is tried nearest first.
     */
    private fun besideNearestHelm(level: ServerLevel, ship: LoadedServerShip, near: Vector3d): Vector3d? {
        val helms = ArrayList<BlockPos>()
        ship.activeChunksSet.forEach { x, z ->
            for (be in level.getChunk(x, z).blockEntities.values) {
                if (be is ShipHelmBlockEntity) helms.add(be.blockPos)
            }
        }
        helms.sortBy { it.distToCenterSqr(near.x, near.y, near.z) }
        for (helm in helms) {
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val c = helm.offset(dx, 0, dz)
                if (solid(level, c.below()) && clear(level, c) && clear(level, c.above())) {
                    return Vector3d(c.x + 0.5, c.y.toDouble(), c.z + 0.5)
                }
            }
        }
        return null
    }

    /** Server stopping. */
    @JvmStatic
    fun clear() {
        lastDeck.clear()
        streaks.clear()
    }
}
