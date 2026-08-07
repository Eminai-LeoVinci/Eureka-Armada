package org.valkyrienskies.eureka.util

import org.valkyrienskies.eureka.EurekaConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How many floaters and balloons a hull of a given mass actually wants.
 *
 * Three places ask this question and they must never disagree: the Auto-Shipwright, which fits the hull at
 * assembly; `/vs get-ship-weight`, which reports what a ship needs; and the helm's "now +N%" readout, which
 * says how far the assembled ship already sits over the recommendation. The first two carried duplicate
 * copies of these constants and formulas, so a change to one silently made the command lie about what the
 * fill would do. They share this now.
 *
 * Everything here reads [EurekaConfig.SERVER], never a ship-category preset. Buoyancy and lift are global on
 * purpose -- a hybrid changes category in mid-air the moment its keel touches water, and lift that moved with
 * it would sink or launch the ship at the waterline. The numbers are also needed at ASSEMBLY time, before any
 * ship (and so any category) exists.
 */
object BuoyancyMath {

    // Calibration for the floater targets. "Afloat" is the keel at the waterline; "dry" additionally keeps
    // the interior above it, which is the target worth having and always the larger of the two.
    private const val AFLOAT_ADDED_FACTOR = 1.0
    private const val DRY_ADDED_FACTOR = 7.5
    private const val SAFETY_MARGIN = 1

    // |EurekaShipControl.GRAVITY| (10.0). Balloon lift only cancels weight at the neutral-hover count; a ship
    // sized to exactly hover holds itself up but never climbs, because physTick caps the vertical force at the
    // balloons' lift, so the usable CLIMB force is the SURPLUS over weight. Terminal climb velocity when
    // balloon-limited is balloonForce/mass - g, so the count for a target climb speed V is the neutral-hover
    // count times (1 + V/g). Sizing by this factor rather than a flat margin makes the surplus scale WITH
    // mass, so light and heavy ships alike ascend at about the configured rate. Keep in step with
    // EurekaShipControl.GRAVITY.
    private const val GRAVITY_MAGNITUDE = 10.0

    /** Buoyant factor one full-strength floater contributes to a hull of [mass] kg. */
    fun perFloater(mass: Double): Double {
        val cfg = EurekaConfig.SERVER
        if (mass <= 0.0) return 0.0
        return min(cfg.floaterBuoyantFactorPerKg / mass, 15.0 * cfg.maxFloaterBuoyantFactor)
    }

    /** Floaters that put a hull of [mass] kg at the waterline, keel wet and interior dry. */
    fun recommendedFloaters(mass: Double): Int {
        val per = perFloater(mass)
        if (per <= 0.0) return 0
        return max(
            ceil(DRY_ADDED_FACTOR / per).toInt(),
            ceil(AFLOAT_ADDED_FACTOR / per).toInt() + SAFETY_MARGIN
        )
    }

    /** Floaters that merely keep a hull of [mass] kg afloat, without the dry interior. */
    fun afloatFloaters(mass: Double): Int {
        val per = perFloater(mass)
        if (per <= 0.0) return 0
        return ceil(AFLOAT_ADDED_FACTOR / per).toInt() + SAFETY_MARGIN
    }

    /** Kilograms of ship one balloon can lift, at the configured lift multiplier. */
    fun liftPerBalloon(): Double = EurekaConfig.SERVER.massPerBalloon * EurekaConfig.SERVER.balloonLiftMultiplier

    /** Balloons that let a hull of [mass] kg climb at the configured ascend rate, not merely hover. */
    fun recommendedBalloons(mass: Double): Int {
        val lift = liftPerBalloon()
        if (lift <= 0.0 || mass <= 0.0) return 0
        return ceil(mass * ascendFactor() / lift).toInt()
    }

    /** The climb-headroom multiplier over a neutral hover. 1.0 = hover only. */
    fun ascendFactor(): Double =
        1.0 + max(0.0, EurekaConfig.SERVER.assemblerBalloonAscendRate) / GRAVITY_MAGNITUDE

    /**
     * How far [placed] sits over [recommended], as a percentage: 0 is exactly the recommendation, +30 is
     * thirty percent more than the hull needed, and a negative figure is a hull that is under-fitted.
     *
     * This is the number the helm shows beside each Fit row, and it is the whole point of showing it: a ship
     * that flies well at +30% tells you what to type into the box the next time you build one like it.
     *
     * Returns null when there is nothing meaningful to compare -- no ship, or a recommendation of zero.
     */
    fun fitPercent(placed: Int, recommended: Int): Int? {
        if (recommended <= 0) return null
        return ((placed.toDouble() / recommended - 1.0) * 100.0).roundToInt()
    }
}
