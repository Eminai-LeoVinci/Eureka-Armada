package org.valkyrienskies.eureka.util

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.EurekaConfig

/**
 * How long each contained fire has left before it burns through what it caught on.
 *
 * ## Why a timer at all
 * Vanilla has no notion of "this block burns through in N seconds" -- it rolls dice against a flammability
 * table on every fire tick, so the time to consume a block is an open-ended geometric distribution measured
 * in minutes for planks. That is fine for a forest, where the point is that the fire keeps moving, and
 * useless for a ship, where the fire cannot move and the only thing left for it to express is *how long the
 * crew has to reach it*. So a contained fire draws one duration when it is first seen and burns through when
 * that expires.
 *
 * ## Why it is kept here and not on the block
 * A fire block has no room to store a deadline -- its only property is `age`, which vanilla drives itself,
 * stochastically, and reads back for rain and spread. Writing a deadline into the world would mean a new
 * block entity for every flame. A side table costs nothing by comparison: ship fires are counted in tens,
 * the entries are two longs, and losing them on restart merely restarts the clock on a fire nobody was
 * watching.
 */
object ShipFireBurn {

    /** Deadlines as absolute game time, per dimension, keyed by packed fire position. */
    private val byDimension = HashMap<ResourceKey<Level>, Long2LongOpenHashMap>()

    /** Above this many live entries, take the opportunity to drop the ones that have gone cold. */
    private const val SWEEP_THRESHOLD = 256

    private const val NO_DEADLINE = Long.MIN_VALUE

    /**
     * Whether this fire has burned long enough to take the block, starting its clock if this is the first
     * time we have seen it.
     */
    fun burnedThrough(level: ServerLevel, firePos: BlockPos): Boolean {
        val shortest = EurekaConfig.SERVER.shipFireBurnSecondsMin.coerceAtLeast(0.0)
        val longest = EurekaConfig.SERVER.shipFireBurnSecondsMax.coerceAtLeast(shortest)

        val deadlines = byDimension.getOrPut(level.dimension()) {
            Long2LongOpenHashMap().also { it.defaultReturnValue(NO_DEADLINE) }
        }
        val key = firePos.asLong()
        val now = level.gameTime
        val deadline = deadlines.get(key)

        // A deadline further out than the longest possible burn belongs to a world whose clock has moved
        // (a restored backup, most likely), not to this fire -- redraw rather than wait out the difference.
        val ceiling = now + (longest * 20.0).toLong() + 40L
        if (deadline == NO_DEADLINE || deadline > ceiling) {
            val seconds = shortest + level.random.nextDouble() * (longest - shortest)
            deadlines.put(key, now + (seconds * 20.0).toLong().coerceAtLeast(1L))
            if (deadlines.size > SWEEP_THRESHOLD) {
                sweep(deadlines, now, longest)
            }
            return false
        }
        return now >= deadline
    }

    /**
     * Ticks until this fire burns through, or -1 if it has not drawn a deadline yet.
     *
     * Used only to pull the fire's next tick forward so the deadline is actually honoured: fire ticks
     * every 30-40 ticks, so a three-second burn would otherwise land wherever the next tick happened to
     * fall and "3 seconds" would quietly mean five.
     */
    fun ticksRemaining(level: ServerLevel, firePos: BlockPos): Long {
        val deadline = byDimension[level.dimension()]?.get(firePos.asLong()) ?: return -1L
        if (deadline == NO_DEADLINE) return -1L
        return deadline - level.gameTime
    }

    /** The fire is done with this spot; let the next one that catches here start fresh. */
    fun forget(level: ServerLevel, firePos: BlockPos) {
        byDimension[level.dimension()]?.remove(firePos.asLong())
    }

    /**
     * Drop entries whose deadline is long past. Anything that old was either consumed, or belonged to a
     * fire that was put out or broken -- neither leaves us anything to do, and neither calls [forget].
     */
    private fun sweep(deadlines: Long2LongOpenHashMap, now: Long, longestSeconds: Double) {
        val cutoff = now - ((longestSeconds * 20.0).toLong() + 200L)
        val entries = deadlines.long2LongEntrySet().iterator()
        while (entries.hasNext()) {
            if (entries.next().longValue < cutoff) {
                entries.remove()
            }
        }
    }
}
