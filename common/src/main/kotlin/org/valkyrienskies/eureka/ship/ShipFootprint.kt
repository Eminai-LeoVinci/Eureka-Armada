package org.valkyrienskies.eureka.ship

import org.valkyrienskies.core.api.ships.LoadedServerShip
import kotlin.math.floor

/**
 * How big a hull is, as one number, and the size bands three separate features scale themselves by.
 *
 * Lives here rather than in any one feature's package because three of them now ask the same question -- the
 * gap a follower holds alongside its leader, how far SHIFT+F will reach to pick a target up, and how big the
 * spheres that close a recording are drawn. One definition means a 38-block ship is a 38-block ship to all
 * three, and that tuning one never quietly changes another's idea of size.
 */
object ShipFootprint {

    /**
     * The mean of a hull's two horizontal spans, in blocks.
     *
     * Taken from the voxel box rather than by walking the blocks, and the box IS the widest layer as far as
     * this is concerned -- each axis' extent is the furthest any layer reaches along it. The one hull where
     * they differ is one whose parts are horizontally disjoint at different heights, a tower fore and a tower
     * aft with nothing between, where the box reads wider than any real layer. That over-estimates, which is
     * the safe direction for every caller: a bigger gap, a longer reach, a more forgiving snap.
     *
     * The MEAN of both spans rather than the beam alone, which is the user's choice and worth restating: a
     * long hull's bow and stern swing furthest when it turns, and beam-only never accounts for that. The
     * consequence to keep in mind is that a long ship reads as a big one -- a 38x100 galleon comes out at 69.
     *
     * Masts, sails and balloons all count. They are as capable of touching another ship as the hull is.
     */
    fun of(ship: LoadedServerShip): Double {
        val aabb = ship.shipAABB ?: return 0.0
        val spanX = aabb.maxX() + 1 - aabb.minX()
        val spanZ = aabb.maxZ() + 1 - aabb.minZ()
        return (spanX + spanZ) * 0.5
    }

    /**
     * How many whole size bands past the smallest a footprint is. 0 for anything up to [BAND].
     *
     * The bands are `1..5`, `6..10`, `11..15` and so on, hence the `- 1`: the smallest possible hull and a
     * five-block raft are the same size as far as any of this is concerned, and that band is the baseline
     * every scaled quantity is quoted against.
     *
     * Note this is NOT the banding `EurekaConfig.followGapBase`/`followGapStep` uses, which divides straight
     * through (`0..4`, `5..9`, ...). That one came from a worked example of the user's -- a footprint of 38
     * holding an 11-block gap -- and is left alone rather than quietly re-aligned.
     */
    fun bands(footprint: Double): Int = floor((footprint - 1.0) / BAND).toInt().coerceAtLeast(0)

    /** Blocks of footprint per size band. Every size-scaled rule in the mod steps at this granularity. */
    const val BAND = 5.0
}
