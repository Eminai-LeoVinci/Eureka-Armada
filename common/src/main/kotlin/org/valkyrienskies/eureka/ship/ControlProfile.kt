package org.valkyrienskies.eureka.ship

import org.valkyrienskies.eureka.EurekaConfig

/**
 * What kind of vessel a ship is, and therefore which settings block it steers by.
 *
 * A ship is classified from what it is BUILT OUT OF, not from anything the player declares:
 *
 * ```
 *   floaters, no balloons   -> BOAT
 *   balloons, no floaters   -> AIRSHIP
 *   both ("a hybrid")       -> BOAT while touching water, AIRSHIP once clear of it
 *   neither                 -> BOAT (a bare hull has to steer by something)
 * ```
 *
 * The classification runs on the physics thread in [EurekaShipControl.physTick], off the same pooled block
 * counts the rest of the control law uses -- so a welded armada is one vessel with one category, taken from
 * the whole formation's floaters and balloons rather than the flagship's.
 *
 * Only a hybrid ever switches at runtime, and it switches with hysteresis, so a ship skimming the surface
 * cannot flicker between two sets of handling.
 *
 * @see EurekaConfig.BOAT for what is and is not per-category -- in short, categories change how a ship
 * HANDLES and never how much it floats, because a hybrid changes category in mid-air.
 */
enum class ControlProfile {
    BOAT,
    AIRSHIP,

    /**
     * Underwater handling: the whole vessel is under water ([EurekaShipControl.fullySubmerged], pooled) AND it
     * has a pressure hull -- a dry interior of sub air, filled at assembly. The depth hold owns the vertical
     * axis while this is live; see [EurekaShipControl.physTick].
     */
    SUBMARINE;

    /** The settings block a ship of this category reads its handling off. */
    val preset: EurekaConfig.ShipHandling
        get() = when (this) {
            BOAT -> EurekaConfig.BOAT
            AIRSHIP -> EurekaConfig.AIRSHIP
            SUBMARINE -> EurekaConfig.SUBMARINE
        }

    companion object {
        /**
         * Classify a vessel. [wet] only matters for a hybrid, and the caller owns its hysteresis.
         *
         * [floaters] is in FIFTEENTHS (a redstone-weakened floater counts for less) and [balloons] in whole
         * blocks, matching the fields on [EurekaShipControl]; only their sign is read here.
         */
        @JvmStatic
        fun classify(
            balloons: Int,
            floaters: Int,
            wet: Boolean,
            submerged: Boolean = false,
            pressureHull: Boolean = false
        ): ControlProfile = when {
            // Under water with a dry interior to breathe in: a submarine, whatever else it carries. A hull
            // that is under water WITHOUT one is not steering anything -- it is sinking, and stays a boat.
            submerged && pressureHull -> SUBMARINE
            balloons > 0 && floaters <= 0 -> AIRSHIP
            balloons > 0 && !wet -> AIRSHIP
            else -> BOAT
        }
    }
}
