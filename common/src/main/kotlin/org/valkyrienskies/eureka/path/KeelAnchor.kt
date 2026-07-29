package org.valkyrienskies.eureka.path

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import java.util.concurrent.ConcurrentHashMap

/**
 * The point on a ship that a path is measured from: the centre of its lowest occupied block layer -- its keel.
 *
 * Everything visible about this feature hangs off one reference point, so it is worth it being the RIGHT one.
 * The ship's centre of mass would drift as cargo moves and sits somewhere invisible inside the hull; the
 * transform origin is wherever the shipyard happened to put it. The keel is the part of the ship a pilot
 * actually lines up with the world -- it's what clears the seabed, what sits at the waterline, and what you
 * would point at if asked where the ship "is". It is also the natural place to hang the snap sphere, which is
 * why the recording, the sphere and the playback tracking all share this one point.
 *
 * ## Ship-local and cached
 * The anchor is computed ONCE per ship, in ship space, and reused. It only changes if the hull changes, which
 * the [org.joml.primitives.AABBic] bounds check catches. Recomputing per tick would mean a block scan every
 * tick for no benefit -- and worse, a keel that shifted mid-route as blocks were added would silently move the
 * line the ship is trying to follow.
 */
object KeelAnchor {

    /** ship id -> (bounds it was computed for, ship-space anchor). */
    private val cache = ConcurrentHashMap<Long, Entry>()

    private class Entry(val boundsKey: Long, val anchor: Vector3d)

    /**
     * The keel anchor in WORLD space, written into [dest].
     *
     * Returns null only if the ship has no voxel bounds at all (nothing assembled yet).
     */
    fun world(level: ServerLevel, ship: LoadedServerShip, dest: Vector3d): Vector3d? {
        val local = local(level, ship) ?: return null
        dest.set(local)
        ship.transform.shipToWorld.transformPosition(dest)
        return dest
    }

    /** The keel anchor in SHIP space, cached. Null if the ship has no voxel bounds. */
    fun local(level: ServerLevel, ship: LoadedServerShip): Vector3d? {
        val aabb = ship.shipAABB ?: return null
        val key = boundsKey(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ())

        cache[ship.id]?.let { if (it.boundsKey == key) return it.anchor }

        val anchor = compute(level, ship, aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ())
        cache[ship.id] = Entry(key, anchor)
        return anchor
    }

    /** Drop a ship's cached anchor. Called when a ship unloads so the map can't grow forever. */
    fun forget(shipId: Long) {
        cache.remove(shipId)
    }

    fun clear() = cache.clear()

    /**
     * Centroid of the solid blocks in the lowest occupied layer, in ship space.
     *
     * [org.valkyrienskies.core.api.ships.ServerShip.getShipAABB] is already the voxel-occupied box, so the
     * bottom layer is normally `minY` exactly. We still scan a few layers up before giving in, because the
     * bounds can lag a block or two behind edits, and an empty scan would otherwise dump the anchor at the
     * geometric centre of a box the hull doesn't fill.
     *
     * The centroid rather than the box centre matters on any ship that is wider up top than at the waterline
     * -- a galleon with flared sides, or anything with overhanging wings. The box centre of those sits out in
     * open air well off the actual keel.
     */
    private fun compute(
        level: ServerLevel, ship: LoadedServerShip,
        minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int
    ): Vector3d {
        val cursor = BlockPos.MutableBlockPos()
        var y = minY
        val topOfSearch = minOf(maxY, minY + LAYER_SEARCH_DEPTH)
        while (y <= topOfSearch) {
            var count = 0
            var sumX = 0.0
            var sumZ = 0.0
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    cursor.set(x, y, z)
                    if (level.getBlockState(cursor).isAir) continue
                    count++
                    sumX += x + 0.5
                    sumZ += z + 0.5
                }
            }
            if (count > 0) {
                // y (not y + 0.5) so the anchor sits on the BOTTOM face of the keel layer -- the surface that
                // actually touches the ground.
                return Vector3d(sumX / count, y.toDouble(), sumZ / count)
            }
            y++
        }

        // Nothing found: fall back to the bottom centre of the bounds.
        return Vector3d((minX + maxX + 1) * 0.5, minY.toDouble(), (minZ + maxZ + 1) * 0.5)
    }

    /** How many layers up from the bounds floor to look before falling back. */
    private const val LAYER_SEARCH_DEPTH = 3

    /** Cheap change-detector for the voxel bounds; exact equality is all we need. */
    private fun boundsKey(minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int): Long {
        var h = 1125899906842597L
        h = h * 31 + minX; h = h * 31 + minY; h = h * 31 + minZ
        h = h * 31 + maxX; h = h * 31 + maxY; h = h * 31 + maxZ
        return h
    }
}
