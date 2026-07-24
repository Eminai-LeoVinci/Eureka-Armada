package org.valkyrienskies.eureka.armada

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import org.valkyrienskies.core.api.ships.ServerShip
import kotlin.math.floor

/**
 * Reads blocks without ever loading a chunk, caching the last chunk it touched.
 *
 * Both users below walk long runs of nearby positions, so the one-entry chunk cache turns almost every lookup into
 * a section index instead of a chunk-map probe. The no-load part matters more: [net.minecraft.world.level.Level.getBlockState]
 * generates the chunk if it's missing, which would have the armada synchronously loading terrain along its flight
 * path every tick. Unloaded terrain simply reads as empty -- nothing to collide with out there anyway.
 */
internal class ArmadaBlockReader(private val level: ServerLevel) {

    private val cursor = BlockPos.MutableBlockPos()
    private var cacheX = Int.MIN_VALUE
    private var cacheZ = Int.MIN_VALUE
    private var cacheChunk: LevelChunk? = null

    fun blocksMotionAt(x: Int, y: Int, z: Int): Boolean {
        if (level.isOutsideBuildHeight(y)) return false
        val chunkX = x shr 4
        val chunkZ = z shr 4
        if (chunkX != cacheX || chunkZ != cacheZ) {
            cacheX = chunkX
            cacheZ = chunkZ
            cacheChunk = level.chunkSource.getChunkNow(chunkX, chunkZ)
        }
        val chunk = cacheChunk ?: return false
        val state = chunk.getBlockState(cursor.set(x, y, z))
        return !state.isAir && state.blocksMotion()
    }

    fun blocksMotionAt(x: Double, y: Double, z: Double): Boolean =
        blocksMotionAt(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
}

/**
 * A cached, coarse point cloud of a ship's solid blocks in SHIPYARD (model) coordinates -- the stand-in hull used to
 * test a pose-slaved child against the world (see [ArmadaCollision]).
 *
 * A child ship's pose is forced by [ArmadaFollowProvider], so the physics engine can't collide it with terrain: a
 * forced transform has nothing to yield with. To collide the armada as one body we test the children's shapes
 * ourselves, which means walking their blocks every tick -- and a large hull has far too many blocks for that.
 *
 * So the ship's block AABB is divided into cubic cells of [stride] blocks, and every cell holding at least one solid
 * block contributes ONE point: the centroid of that cell's solid blocks. The stride is chosen per ship so the cell
 * lattice fits inside a sample budget, which bounds the per-tick cost by the budget rather than by ship size. The
 * price is resolution: contact is detected to within about half a stride, so a big hull may clip a block or two of a
 * mountain's corner before the armada stops. That is a fair trade against flying straight through it.
 *
 * Point clouds are cached per ship and rebuilt every [REBUILD_INTERVAL_TICKS] so that building on (or blowing holes
 * in) a bound child eventually shows up in its collision shape.
 */
object ArmadaHullProbe {

    /** Blocks change rarely relative to a tick; 10s of staleness in a collision proxy is not noticeable. */
    private const val REBUILD_INTERVAL_TICKS = 200L

    /** Drop the cloud of a ship nothing has asked about for a minute (unbound, unloaded, disassembled). */
    private const val IDLE_EVICT_TICKS = 1200L

    /** How soon to try again after a build that read no blocks at all -- see [pointsFor]. */
    private const val FAILED_REBUILD_RETRY_TICKS = 20L

    /**
     * Above this AABB volume the per-cell scan steps 2 blocks at a time instead of 1. It only shifts a cell's
     * centroid slightly -- at that size the stride has already blurred the hull far more -- and it keeps the
     * rebuild of a huge ship from being a visible hitch.
     */
    private const val COARSE_SCAN_VOLUME = 500_000L

    private val EMPTY = DoubleArray(0)

    private class Entry(var points: DoubleArray, var builtAt: Long, var usedAt: Long)

    private val cache = HashMap<Long, Entry>()

    /**
     * The cloud for [ship], rebuilt if stale. Returns a flat xyz triple array (shipyard coordinates) -- flat so the
     * per-tick transform loop can run without touching a single object per point.
     */
    fun pointsFor(level: ServerLevel, ship: ServerShip, budget: Int): DoubleArray {
        val now = level.gameTime
        val entry = cache[ship.id]
        if (entry != null && now - entry.builtAt < REBUILD_INTERVAL_TICKS) {
            entry.usedAt = now
            return entry.points
        }

        // A ship that HAS blocks but samples to nothing didn't have its shipyard readable this pass. Caching that
        // blank would read as "no hull" and quietly switch the child's collision off until the next rebuild, so
        // keep whatever cloud we had and come back shortly instead.
        val built = build(level, ship, budget)
        val failed = built.isEmpty() && ship.shipAABB != null
        val points = if (failed && entry != null) entry.points else built
        val builtAt = if (failed) now - REBUILD_INTERVAL_TICKS + FAILED_REBUILD_RETRY_TICKS else now

        if (entry == null) {
            cache[ship.id] = Entry(points, builtAt, now)
        } else {
            entry.points = points
            entry.builtAt = builtAt
            entry.usedAt = now
        }
        return points
    }

    /** Number of sample points currently cached for [shipId], for the /armada debug readout. */
    fun sampleCount(shipId: Long): Int = (cache[shipId]?.points?.size ?: 0) / 3

    /** Forget [shipId]'s cloud -- called when a child leaves its armada and stops being probed. */
    fun forget(shipId: Long) {
        cache.remove(shipId)
    }

    /** Drop clouds nothing has asked for lately, so ships that unload don't sit in the cache forever. */
    fun pruneIdle(now: Long) {
        cache.values.removeIf { now - it.usedAt > IDLE_EVICT_TICKS }
    }

    private fun build(level: ServerLevel, ship: ServerShip, budget: Int): DoubleArray {
        val aabb = ship.shipAABB ?: return EMPTY // blockless ship
        val minX = aabb.minX()
        val minY = aabb.minY()
        val minZ = aabb.minZ()
        val maxX = aabb.maxX()
        val maxY = aabb.maxY()
        val maxZ = aabb.maxZ()
        val spanX = (maxX - minX + 1).toLong()
        val spanY = (maxY - minY + 1).toLong()
        val spanZ = (maxZ - minZ + 1).toLong()
        if (spanX <= 0L || spanY <= 0L || spanZ <= 0L) return EMPTY

        // Budget is a ceiling on cells in the AABB; occupied cells (roughly the hull's SURFACE, since most of a
        // ship is hollow) become points, so the point count lands well under this. A big enough ceiling lets a
        // moderate hull reach stride 1 -- a sample on every surface block -- which is what catches terrain thin
        // enough to slip between coarser samples (a tree trunk, a statue's arm). Bounded so a cranked budget can't
        // make one rebuild allocate absurdly.
        val cap = budget.coerceIn(64, 262144).toLong()
        var stride = 1
        while (cells(spanX, stride) * cells(spanY, stride) * cells(spanZ, stride) > cap) stride++
        val step = if (spanX * spanY * spanZ > COARSE_SCAN_VOLUME) 2 else 1

        val reader = ArmadaBlockReader(level)
        val centre = ship.transform.positionInShip
        val cellCount = (cells(spanX, stride) * cells(spanY, stride) * cells(spanZ, stride)).toInt()
        val buf = DoubleArray(cellCount * 3)
        var n = 0

        var cellX = minX
        while (cellX <= maxX) {
            var cellY = minY
            while (cellY <= maxY) {
                var cellZ = minZ
                while (cellZ <= maxZ) {
                    // The cell's OUTERMOST solid block -- the one furthest from the hull's centre -- stands in for the
                    // cell, rather than its centre or the average of its blocks. Averaging pulls the sample inward,
                    // and it does so worst exactly where it hurts: a pointed prow puts only a few blocks in its
                    // leading cell, so the average sits well behind the tip and the nose buries itself by most of a
                    // cell before anything registers. Biasing outward errs the other way, making the proxy hull a
                    // touch larger than the ship, so contact reads slightly early instead of slightly late.
                    var bestX = 0
                    var bestY = 0
                    var bestZ = 0
                    var bestDistance = -1.0

                    var x = cellX
                    val lastX = minOf(cellX + stride - 1, maxX)
                    val lastY = minOf(cellY + stride - 1, maxY)
                    val lastZ = minOf(cellZ + stride - 1, maxZ)
                    while (x <= lastX) {
                        var y = cellY
                        while (y <= lastY) {
                            var z = cellZ
                            while (z <= lastZ) {
                                if (reader.blocksMotionAt(x, y, z)) {
                                    val dx = x + 0.5 - centre.x()
                                    val dy = y + 0.5 - centre.y()
                                    val dz = z + 0.5 - centre.z()
                                    val distance = dx * dx + dy * dy + dz * dz
                                    if (distance > bestDistance) {
                                        bestDistance = distance
                                        bestX = x
                                        bestY = y
                                        bestZ = z
                                    }
                                }
                                z += step
                            }
                            y += step
                        }
                        x += step
                    }

                    if (bestDistance >= 0.0) {
                        buf[n * 3] = bestX + 0.5
                        buf[n * 3 + 1] = bestY + 0.5
                        buf[n * 3 + 2] = bestZ + 0.5
                        n++
                    }
                    cellZ += stride
                }
                cellY += stride
            }
            cellX += stride
        }
        return if (n * 3 == buf.size) buf else buf.copyOf(n * 3)
    }

    private fun cells(span: Long, stride: Int): Long = (span + stride - 1) / stride
}
