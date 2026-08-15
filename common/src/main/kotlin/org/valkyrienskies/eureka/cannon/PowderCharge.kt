package org.valkyrienskies.eureka.cannon

import org.valkyrienskies.eureka.EurekaConfig

/**
 * How much powder a gunner packs behind the ball, and therefore how the shot flies.
 *
 * ## Not to be confused with [org.valkyrienskies.eureka.item.CannonCharge]
 * That one is what is packed *inside the shell* -- plain, explosive or incendiary -- and it is a property of
 * the round you load. This is what is packed *behind* it, is a property of the gun rather than of the
 * ammunition, and is set by the gunner at the breech. A gun firing plain iron can do so on any of the three.
 *
 * ## Why each level owns all four numbers rather than scaling one
 * A charge could have been a multiplier on a single base arc, and it would have been less code. But then a
 * long shot and a close one could never be tuned against each other: steepening the 1x lob would flatten the
 * 3x at the same time, because they would be the same curve read at two points. Four independent numbers per
 * level means the short charge can be a mortar and the long one a rifle, which is the whole reason to give a
 * gunner the choice.
 *
 * All twelve live on [EurekaConfig.SERVER] rather than on a ship category. A shot's arc is a fact about
 * powder and iron; it does not become a different fact because the gun is bolted to an airship.
 */
enum class PowderCharge(
    /** Gunpowder consumed per shot, and the number the button shows. */
    val powder: Int
) {
    SINGLE(1),
    DOUBLE(2),
    TRIPLE(3);

    /** Blocks per tick the ball leaves the muzzle at. */
    val speed: Double
        get() = when (this) {
            SINGLE -> EurekaConfig.SERVER.cannonShotSpeed1x
            DOUBLE -> EurekaConfig.SERVER.cannonShotSpeed2x
            TRIPLE -> EurekaConfig.SERVER.cannonShotSpeed3x
        }

    /** Blocks per tick squared the ball falls. */
    val gravity: Double
        get() = when (this) {
            SINGLE -> EurekaConfig.SERVER.cannonShotGravity1x
            DOUBLE -> EurekaConfig.SERVER.cannonShotGravity2x
            TRIPLE -> EurekaConfig.SERVER.cannonShotGravity3x
        }

    /** Fraction of its speed the ball keeps each tick. */
    val drag: Double
        get() = when (this) {
            SINGLE -> EurekaConfig.SERVER.cannonShotDrag1x
            DOUBLE -> EurekaConfig.SERVER.cannonShotDrag2x
            TRIPLE -> EurekaConfig.SERVER.cannonShotDrag3x
        }

    /** Ticks before the gun that fired this can fire again. At least one, so a zeroed config cannot spam. */
    val reloadTicks: Long
        get() = (
            when (this) {
                SINGLE -> EurekaConfig.SERVER.cannonReloadSeconds1x
                DOUBLE -> EurekaConfig.SERVER.cannonReloadSeconds2x
                TRIPLE -> EurekaConfig.SERVER.cannonReloadSeconds3x
            } * 20.0
            ).toLong().coerceAtLeast(1L)

    /** The next setting the breech button cycles to, wrapping back to [SINGLE]. */
    val next: PowderCharge get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * The charge with this ordinal, or [SINGLE].
         *
         * Everything that crosses a boundary -- saved NBT, synced entity data, the menu's container data --
         * carries the ordinal as a bare int, and any of them can arrive holding a value this build does not
         * have (an older save, a newer server). Falling back rather than throwing means the worst case is a
         * gun that fires light, not a crash on world load.
         */
        fun of(ordinal: Int): PowderCharge = entries.getOrElse(ordinal) { SINGLE }
    }
}
