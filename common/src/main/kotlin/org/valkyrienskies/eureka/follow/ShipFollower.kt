package org.valkyrienskies.eureka.follow

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

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
 * The separation is sharper than it first was, and had to be. The station error is split in the leader's own
 * frame into how far ahead or behind station this ship is and how far inside or outside it, and **only the
 * sideways half ever reaches the wheel**. Steering at the station point itself -- which is what the first
 * version did -- gives a controller that answers "you are eight blocks too far forward" with a hard turn, and
 * eight blocks too far forward is the normal state of affairs every time a leader slows down.
 *
 * ## Everything the follower measures about itself, it measures in its own frame
 * Its half-beam, its half-length, its heading error. Nothing about the station may depend on the follower's
 * instantaneous attitude, or the act of turning toward the station moves the station -- and a hull measured
 * across the leader's beam is exactly that, since a long ship swung thirty degrees reads twice as wide. The
 * leader's contribution is measured in the leader's frame for the same reason.
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
    var slot: Int
) {

    /** Live distance from the ship to its station. Drives [bestError] and [secondsAdrift]. */
    var stationError = 0.0
        private set

    /** Last computed centre-to-centre lateral station distance. Read by `ShipFollows.updateSide`. */
    var standoff = 0.0
        private set

    /**
     * The closest to station this pursuit has ever got. Ratchets downward only, from wherever it started.
     *
     * This is what lets a target be picked up from further away than the break-off distance. Without it, an
     * order given at the far end of the crosshair's reach would break off on the very next tick, having never
     * been given a chance to close -- the player would get "in pursuit" and "lost contact" in the same breath.
     * With it, a distant pursuit runs while it is still making ground, and once it has closed to inside the
     * configured break range the flat rule takes over again for good.
     *
     * Starts at infinity so the first tick sets it to whatever the truth is, which also means the break-off
     * test below cannot possibly fire before the ship has been steered once.
     */
    var bestError = Double.MAX_VALUE
        private set

    /**
     * Seconds spent CONTINUOUSLY beyond the break-off limit, zeroed the moment the ship is back inside it.
     *
     * The pursuit is called off on this rather than on a bare distance because a single tick over the line
     * means nothing. A leader putting the wheel over swings the station point sideways at a speed no hull can
     * match -- the wider the berth, the faster it swings -- so the follower is briefly a long way adrift every
     * time the leader turns, and recovers on its own. Only a leader that is genuinely outrunning it holds it
     * out there.
     */
    var secondsAdrift = 0.0
        private set

    private val station = Vector3d()
    private val heading = Vector3d()
    private val ownBeam = Vector3d()
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
        FollowGeometry.beamOf(ownHeading, ownBeam)

        // Both hull measurements are taken about the FOLLOWER'S OWN axes, so the station stops moving whenever
        // this ship turns. See FollowGeometry.standoff for why that mattered enough to pass them in.
        val gap = FollowGeometry.gapBetween(leader, ship)
        standoff = FollowGeometry.standoff(leader, leaderFrame, FollowGeometry.halfExtentAlong(ship, ownBeam), gap)
        // Queue spacing uses THIS hull's length, so a long ship taking slot 2 doesn't overlap the one in slot 1,
        // and the same size-scaled gap the beam gets -- a line of galleons wants the room a line of rafts doesn't.
        val astern = slot * (FollowGeometry.halfExtentAlong(ship, ownHeading) * 2.0 + gap)

        FollowGeometry.stationPoint(leaderFrame, side, standoff, astern, station)

        stationError = station.distance(centre)

        if (stationError < bestError) bestError = stationError
        // Flat limit once the ship has ever been within reach of its station; the ratchet plus slack only while
        // it never has, which is where a long-range order starts.
        val adriftLimit = max(cfg.followBreakRange, bestError + cfg.followBreakSlack)
        secondsAdrift = if (stationError > adriftLimit) secondsAdrift + TICK_SECONDS else 0.0

        // region heading
        // The station error, split into "ahead of / behind station" and "inside / outside it". Read in the
        // LEADER's frame, so the two stay told apart while the leader manoeuvres. Only when the leader's forward
        // is an untrusted keel line -- a guess whose sign is as likely backwards as not -- is our own frame used
        // instead, which is the same decomposition relative to the only heading we can trust.
        val dx = station.x - centre.x
        val dz = station.z - centre.z

        val refFx: Double
        val refFz: Double
        val refBx: Double
        val refBz: Double
        if (leaderFrame.alignTrusted) {
            refFx = leaderFrame.forward.x; refFz = leaderFrame.forward.z
            refBx = leaderFrame.beam.x; refBz = leaderFrame.beam.z
        } else {
            refFx = ownHeading.x; refFz = ownHeading.z
            refBx = ownBeam.x; refBz = ownBeam.z
        }

        val along = dx * refFx + dz * refFz
        val lateral = dx * refBx + dz * refBz

        // Aim at a point one lookahead ahead along the reference heading, shifted sideways by however far off
        // the station is. That single line replaces the old chase/align blend and fixes what it got wrong.
        //
        // The blend steered at the station point itself, which meant being AHEAD of station demanded a turn --
        // and at a hundred and eighty degrees, at that. Overshoot the leader by a few blocks (which is exactly
        // what happens when it slows) and the ship would try to spin round to face a point behind it, throttle
        // fighting wheel, the stern coming in. Here `along` never enters the steering at all: it is floored at
        // the lookahead, so a station ten blocks astern of us and a station a hundred blocks ahead both steer
        // dead level with the leader, and closing the distance is left to the throttle, which is the only thing
        // that can do it. What steers is `lateral` alone -- the aim leans over by atan(lateral / lookahead),
        // which saturates near a right angle however far out we are and unwinds to zero on station.
        val aimAhead = max(along, max(cfg.followLookahead, MIN_LOOKAHEAD))
        val aimX = refFx * aimAhead + refBx * lateral
        val aimZ = refFz * aimAhead + refBz * lateral
        val error = signedYaw(ownHeading.x, ownHeading.z, aimX, aimZ)

        // Rate feedback: take the yaw the hull has already got back out of the demand. Heading error alone is a
        // spring with no damper -- the ship is swinging hardest at the instant the error hits zero, so it sails
        // through and comes back, which is the wallowing the leader's every course change used to set off.
        val yawRate = ship.angularVelocity.y()
        val turnOmega = cfg.followTurnGain * error - cfg.followTurnDamping * yawRate
        // endregion

        // region throttle
        // The leader's speed along its own heading is the baseline -- match that and the gap stops growing. The
        // correction is how far ahead of or behind station we are, measured along OUR heading because that is
        // the axis the throttle actually pushes on -- deliberately NOT the `along` the steering works in. A ship
        // crabbing in hard toward a station off to one side is pointing most of the way at it, and this reads
        // that as ground to make up and gives it the power to; the leader-frame reading would see barely any
        // and leave it dawdling across.
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
            turnOmega = turnOmega,
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
        /** Seconds per server tick. [ShipFollows.tick] runs once per world tick, at Minecraft's fixed 20 TPS. */
        const val TICK_SECONDS = 0.05

        /** Floor under the configured lookahead, so an aim vector always has a direction to point in. */
        const val MIN_LOOKAHEAD = 1.0
    }
}
