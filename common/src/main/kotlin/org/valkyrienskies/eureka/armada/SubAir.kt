package org.valkyrienskies.eureka.armada

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.isBlockInShipyard

/**
 * The submarine "dry interior": everything that decides whether a world position is shielded from the ocean by a
 * [org.valkyrienskies.eureka.block.SubAirBlock], plus the fill/clear passes that put those blocks into a ship.
 *
 * ## The coordinate problem this solves
 * A ship's blocks live in the SHIPYARD, but a player standing on that ship is at a WORLD position -- and when the
 * ship is underwater, that world position is ocean. Vanilla therefore has them swimming and drowning inside a dry
 * hull. [isShielded] closes the gap: it takes the world position, finds the ships containing it, transforms into
 * each ship's frame and asks what block is really there. Sub air means the hull, not the sea, wins.
 *
 * Cost is kept off the common path by the CALLERS, not by caching here: every caller checks that water is
 * actually in play first (one or two block reads), so the ship lookup only happens for something that is about to
 * be treated as wet. Caching the answer per position would be wrong anyway -- a moving sub changes the answer for
 * a fixed world position every tick.
 */
object SubAir {

    /** Half-extent of the probe box used to find ships around a point. Small: we want the ships containing it. */
    private const val PROBE = 0.001

    /**
     * Ceiling on the flood fill's working volume. A fill walks the ship's block AABB inflated by one, so this is
     * a cap on (w+2)(h+2)(d+2) -- roughly a 200-cube hull. Past that we refuse rather than freeze the server.
     */
    private const val MAX_FILL_CELLS = 8_000_000L

    /** True when [x],[y],[z] (WORLD coordinates) sits inside some ship's sub air. */
    fun isShielded(level: Level, x: Double, y: Double, z: Double): Boolean {
        // Already in ship space (an entity teleported into the shipyard); read straight through.
        if (level.isBlockInShipyard(x, y, z)) {
            return isSubAir(level, BlockPos.containing(x, y, z))
        }
        val probe = AABB(x - PROBE, y - PROBE, z - PROBE, x + PROBE, y + PROBE, z + PROBE)
        for (ship in level.getShipsIntersecting(probe)) {
            val inShip = ship.worldToShip.transformPosition(Vector3d(x, y, z), Vector3d())
            if (isSubAir(level, BlockPos.containing(inShip.x, inShip.y, inShip.z))) return true
        }
        return false
    }

    /**
     * Read a shipyard position without ever loading a chunk. A ship whose chunks are loaded always answers; an
     * unloaded one reads as "not sub air", which is the safe direction -- it just means no shielding, never a
     * chunk generated under a moving hull.
     */
    private fun isSubAir(level: Level, pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        return level.getBlockState(pos).block === EurekaBlocks.SUB_AIR.get()
    }

    /** Outcome of a fill/clear pass: how many blocks changed, or why nothing did. */
    class FillResult(val changed: Int, val error: String?)

    /**
     * Which air a fill claims.
     *
     * [ENCLOSED] is the submarine rule: only air the outside cannot reach. [ALL] is the open-hull rule: every
     * air cell inside the ship's block AABB, sealed or not.
     *
     * The distinction is not academic. Measured on `dolman-texture-nuke` (2026-07-24): [ENCLOSED] claimed 31
     * cells where the ship's real interior is ~174, because the hull is open above -- the flood pours in through
     * the roof gaps and correctly reports that almost nothing is sealed. Ships built to sit keel-under with an
     * open deck are exactly the case this feature exists for, so enclosure alone cannot be the only policy.
     */
    enum class FillMode { ENCLOSED, ALL }

    /**
     * Fill [ship]'s enclosed air with sub air.
     *
     * Interior is found by elimination rather than by seeding inside: flood the air INWARD from a one-block shell
     * around the ship's block AABB, and whatever air the flood never reaches is enclosed. That needs no guess at
     * where "inside" is, handles several separate compartments in one pass, and can't leak through a hull that is
     * genuinely sealed.
     *
     * Writes with [Block.UPDATE_CLIENTS] only: clients need these blocks (the camera check runs client-side), but
     * air replacing air changes no light and needs no neighbour updates, so a fill can't cascade.
     */
    fun fill(level: ServerLevel, ship: LoadedServerShip, mode: FillMode): FillResult {
        val aabb = ship.shipAABB ?: return FillResult(0, "That ship has no blocks.")
        val minX = aabb.minX() - 1
        val minY = aabb.minY() - 1
        val minZ = aabb.minZ() - 1
        val maxX = aabb.maxX() + 1
        val maxY = aabb.maxY() + 1
        val maxZ = aabb.maxZ() + 1
        val spanX = maxX - minX + 1
        val spanY = maxY - minY + 1
        val spanZ = maxZ - minZ + 1

        val cells = spanX.toLong() * spanY.toLong() * spanZ.toLong()
        if (cells > MAX_FILL_CELLS) {
            return FillResult(0, "That ship is too big to fill ($cells cells, limit $MAX_FILL_CELLS).")
        }

        fun index(x: Int, y: Int, z: Int) =
            ((x - minX) * spanY + (y - minY)) * spanZ + (z - minZ)

        val cursor = BlockPos.MutableBlockPos()
        fun isAirAt(x: Int, y: Int, z: Int): Boolean {
            cursor.set(x, y, z)
            if (!level.hasChunkAt(cursor)) return true // never generated = empty shipyard = air
            return level.getBlockState(cursor).isAir
        }

        // Flood the shell inward. Reached air is outside the hull; everything else that is air is enclosed.
        // ALL skips the flood entirely -- nothing is "outside", so every air cell in the AABB is claimed.
        val outside = BooleanArray(cells.toInt())
        val queue = ArrayDeque<Int>()
        if (mode == FillMode.ENCLOSED) {
            fun seed(x: Int, y: Int, z: Int) {
                val i = index(x, y, z)
                if (outside[i] || !isAirAt(x, y, z)) return
                outside[i] = true
                queue.addLast(i)
            }
            for (x in minX..maxX) for (y in minY..maxY) {
                seed(x, y, minZ); seed(x, y, maxZ)
            }
            for (x in minX..maxX) for (z in minZ..maxZ) {
                seed(x, minY, z); seed(x, maxY, z)
            }
            for (y in minY..maxY) for (z in minZ..maxZ) {
                seed(minX, y, z); seed(maxX, y, z)
            }

            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val z = minZ + i % spanZ
                val y = minY + (i / spanZ) % spanY
                val x = minX + i / (spanZ * spanY)
                for (face in FACES) {
                    val nx = x + face[0]
                    val ny = y + face[1]
                    val nz = z + face[2]
                    if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue
                    val ni = index(nx, ny, nz)
                    if (outside[ni] || !isAirAt(nx, ny, nz)) continue
                    outside[ni] = true
                    queue.addLast(ni)
                }
            }
        }

        val subAir = EurekaBlocks.SUB_AIR.get().defaultBlockState()
        var changed = 0
        // Only the ship's own AABB, not the shell -- the shell is by definition outside.
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    if (outside[index(x, y, z)]) continue
                    cursor.set(x, y, z)
                    if (!level.hasChunkAt(cursor)) continue
                    val state = level.getBlockState(cursor)
                    if (!state.isAir || state.block === EurekaBlocks.SUB_AIR.get()) continue
                    level.setBlock(cursor.immutable(), subAir, Block.UPDATE_CLIENTS)
                    changed++
                }
            }
        }
        return FillResult(changed, null)
    }

    /** Put [ship]'s sub air back to plain air. Used by "unmark as sub" and before disassembly. */
    fun clear(level: ServerLevel, ship: LoadedServerShip): FillResult {
        val aabb = ship.shipAABB ?: return FillResult(0, "That ship has no blocks.")
        val air = Blocks.AIR.defaultBlockState()
        val cursor = BlockPos.MutableBlockPos()
        var changed = 0
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    if (!level.hasChunkAt(cursor)) continue
                    if (level.getBlockState(cursor).block !== EurekaBlocks.SUB_AIR.get()) continue
                    level.setBlock(cursor.immutable(), air, Block.UPDATE_CLIENTS)
                    changed++
                }
            }
        }
        return FillResult(changed, null)
    }

    private val FACES = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
    )
}
