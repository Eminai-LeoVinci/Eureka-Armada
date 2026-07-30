package org.valkyrienskies.eureka.follow

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Holds one ship on station beside another.
 *
 * ## What "on station" means
 * A point off the leader's beam, level with it, [slot] ships astern of abeam. The follower steers at that point
 * and throttles to hold it. There is no joint and no constraint anywhere -- if the follower is shoved, or the
 * leader turns inside it, nothing snaps it back. It simply finds itself off station and closes again, which is
 * why the whole thing degrades gracefully instead of having a failure mode.
 *
 * ## Three loops, deliberately separate
 * Heading, throttle and elevation are computed independently and handed over together. They are not independent
 * in the plant -- turning costs speed -- but coupling them in the controller would mean tuning one changed the
 * others, and the whole point of the config knobs is that each does one thing.
 *
 * ## The one that is new
 * Throttle. A recorded route never commanded speed, because the pilot's cruise setting owned it and a route had
 * no opinion worth having. Station-keeping is a speed problem before it is a steering one: the leader's speed is
 * most of the answer, and the rest is a correction for being ahead of or behind where you should be. See
 * [EurekaShipControl.pathTargetSpeed].
 *
 * ## What it will not do
 * Exceed the hull's own capability, because it cannot -- the throttle it drives is clamped to +/-1. A follower
 * told to keep up with something faster than it simply falls behind, and [ShipFollows] eventually calls the
 * pursuit off. That is the whole of "the ship doesn't magically gain the leader's speed"; there is no code for it.
 */
class ShipFollower(
    val shipId: Long,
    val leaderId: Long,
    val ownerId: UUID?,
    /** Which beam of the leader to sit off: +1 or -1 against the leader frame's own beam vector. */
    var side: Int,
    /** 0 = abeam the leader, 1 = one ship astern of that, and so on. Owned by [ShipFollows]. */
    var slot: Int,
    /** Centre-to-centre range at the moment the order was given -- the starting value of [closestRange]. */
    acquiredAt: Double
) {

    /** Live distance from the ship to its station, for the status readout and the break-off test. */
    var stationError = 0.0
        private set

    /** Centre-to-centre range to the leader, for the break-off test. */
    var range = 0.0
        private set

    /**
     * The closest this pursuit has ever got, never worse than where it started. Ratchets downward only.
     *
     * This is what lets a target be picked up from further away than the break-off distance. Without it, an
     * order given at the far end of the crosshair's reach would break off on the very next tick, having never
     * been given a chance to close -- the player would get "in pursuit" and "lost contact" in the same breath.
     * With it, a distant pursuit runs until it stops making ground, and once it has closed to inside the
     * configured break range the ordinary rule takes over again for good.
     */
    var closestRange = acquiredAt
        private set

    private val station = Vector3d()
    private val heading = Vector3d()
    private val centre = Vector3d()

    /**
     * One guidance step. Returns false if the ship can no longer be steered, which the caller treats as a
     * reason to break off.
     *
     * [leaderFrame] is passed in rather than derived here because the caller has already built it to decide
     * whether the side should flip, and it is the same frame either way.
     */
    fun tick(
        ship: LoadedServerShip,
        control: EurekaShipControl,
        leader: LoadedServerShip,
        leaderFrame: FollowGeometry.Frame
    ): Boolean {
        val cfg = EurekaConfig.SERVER

        FollowGeometry.centreOf(ship, centre) ?: return false
        val ownHeading = control.pathForward(heading) ?: return false

        val standoff = FollowGeometry.standoff(leader, ship, leaderFrame, cfg.followGap)
        // Queue spacing uses THIS hull's length, so a long ship taking slot 2 doesn't overlap the one in slot 1.
        val astern = slot * (FollowGeometry.halfExtentAlong(ship, leaderFrame.forward) * 2.0 + cfg.followGap)

        FollowGeometry.stationPoint(leaderFrame, side, standoff, astern, station)

        stationError = station.distance(centre)
        range = leaderFrame.centre.distance(centre)
        if (range < closestRange) closestRange = range

        // region heading
        // Pure pursuit toward the station point, blended into matching the leader's heading as the station is
        // reached. Both terms are needed: chase alone leaves a ship that has ARRIVED with no heading constraint
        // at all, free to sit alongside pointing across the leader's deck; alignment alone would have it drive
        // parallel to a leader it is nowhere near, never closing.
        val dx = station.x - centre.x
        val dz = station.z - centre.z
        val chaseSq = dx * dx + dz * dz

        val errChase = if (chaseSq < CHASE_EPSILON) 0.0 else signedYaw(ownHeading.x, ownHeading.z, dx, dz)
        // Untrusted means the leader's forward is a keel line whose direction is a guess, so matching it would
        // square the follower up back-to-front half the time. Chase alone, and accept a loose heading on station.
        val errAlign = if (leaderFrame.alignTrusted) {
            signedYaw(ownHeading.x, ownHeading.z, leaderFrame.forward.x, leaderFrame.forward.z)
        } else {
            errChase
        }

        val blend = if (cfg.followBlendRange <= 0.0) 0.0
        else (stationError / cfg.followBlendRange).coerceIn(0.0, 1.0)
        val error = blend * errChase + (1.0 - blend) * errAlign
        // endregion

        // region throttle
        // The leader's speed along its own heading is the baseline -- match that and the gap stops growing. The
        // correction is how far ahead of or behind station we are, measured along OUR heading because that is
        // the axis the throttle actually pushes on.
        val alongError = dx * ownHeading.x + dz * ownHeading.z
        val leaderSpeed = leader.velocity.x() * leaderFrame.forward.x +
            leader.velocity.z() * leaderFrame.forward.z

        // Capping the CLOSING rate rather than the absolute speed is what lets this work at any leader speed. An
        // absolute cap would quietly forbid ever catching a leader travelling faster than the cap, however much
        // engine the follower had.
        val closing = (cfg.followClosingGain * alongError)
            .coerceIn(-cfg.followReverseSpeed, cfg.followClosingSpeed)
        val targetSpeed = (leaderSpeed + closing).coerceAtLeast(-cfg.followReverseSpeed)
        // endregion

        // region elevation
        // Exactly zero inside the deadband, which is load-bearing rather than tidy: the water altitude hold
        // latches its depth only while the commanded vertical is zero, so a permanently jittering near-zero
        // command would knock it out of hold every tick and the ship would slowly sink.
        val climbError = station.y - centre.y
        val verticalMps = if (abs(climbError) < cfg.followVerticalDeadband) 0.0
        else cfg.followVerticalGain * climbError
        // endregion

        control.pathCommand(
            turnOmega = cfg.followTurnGain * error,
            verticalMps = verticalMps,
            // No corner ceiling: unlike a route, there is no known curve ahead to be too fast for. The closing
            // cap above is what keeps the approach from being a charge.
            speedCap = null,
            targetSpeed = targetSpeed
        )
        return true
    }

    /**
     * Signed yaw from heading (hx, hz) to direction (dx, dz), about +Y.
     *
     * Taken as the atan2 of the cross and dot products rather than as the difference of two atan2 headings: it
     * comes out already wrapped to +/-pi, so there is no wraparound case to get wrong at due north.
     */
    private fun signedYaw(hx: Double, hz: Double, dx: Double, dz: Double): Double =
        atan2(hz * dx - hx * dz, hx * dx + hz * dz)

    private companion object {
        /** Closer than this to the station point there is no meaningful bearing to chase. */
        const val CHASE_EPSILON = 1.0e-6
    }
}
