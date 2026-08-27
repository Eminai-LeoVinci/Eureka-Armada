package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.Util

/**
 * The one scrolling-text clock for every helm widget that has more label than room.
 *
 * How far a label is currently wound back, for an overflow of hidden pixels: rests at each end before
 * setting off again, and travels at a fixed speed rather than over a fixed time, so a very long line
 * scrolls at the same readable pace as a slightly long one. Driven by the wall clock, which every caller
 * reads at once -- a screenful of scrolling lines moves together rather than each in its own rhythm.
 *
 * Extracted from [ShipHelmButton] the day the helm's info boxes needed the same treatment for the damage
 * readouts ("Top Speed: 24m/s~ | -50% from Damage"), so a button label and a readout line breathe in step.
 */
object Marquee {

    /** How far the text is currently slid left, 0..[overflow]. */
    fun offset(overflow: Int): Int {
        val travel = (overflow * MS_PER_PIXEL).toLong().coerceAtLeast(1L)
        val period = 2 * (PAUSE_MS + travel)
        val t = Util.getMillis() % period
        return when {
            t < PAUSE_MS -> 0
            t < PAUSE_MS + travel -> (overflow * (t - PAUSE_MS) / travel).toInt()
            t < 2 * PAUSE_MS + travel -> overflow
            else -> overflow - (overflow * (t - 2 * PAUSE_MS - travel) / travel).toInt()
        }
    }

    /** How long a scrolling line rests at each end, and how long it spends crossing one pixel. */
    private const val PAUSE_MS = 1200L
    private const val MS_PER_PIXEL = 30.0
}
