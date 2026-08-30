package org.valkyrienskies.eureka.follow

import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import org.valkyrienskies.eureka.ship.ControlProfile
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Holds one ship on station beside another -- or, when there is no station worth holding, in orbit around it.
 *
 * ## What "on station" means
 * A point off the leader's beam, level with it, [slot] ships astern of abeam. The follower steers at that point
 * and throttles to hold it. There is no joint and no constraint anywhere -- if the follower is shoved, or the
 * leader turns inside it, nothing snaps it back. It simply finds itself off station and closes again, which is
 * why the whole thing degrades gracefully instead of having a failure mode.
 *
 * ## Circling
 * Station-keeping assumes the leader is going somewhere. Against one that isn't -- parked, or drifting slower
 * than [EurekaConfig.Server.followCircleBelow] -- "alongside and matching speed" collapses to "parked next to
 * it", so a follower that has actually arrived swaps to a slow lap of the leader instead ([circling]). The same
 * mode is what a MUTUAL pair runs: two ships ordered after each other each orbit the other's centre, and with
 * the same handedness that is a stable circle about their shared midpoint -- the koi-pond manoeuvre. Mutual
 * pairs skip the speed gate entirely, since each is being kept moving by the other.
 *
 * The orbit's radius is sized from both hulls' horizontal diagonals plus [EurekaConfig.Server.followCircleGap]
 * -- deliberately NOT the alongside gap, whose cap would put a galleon's corner through its partner -- and the
 * guidance is the ordinary pursuit steering aimed at a point a little way round the circle, so the radial error
 * corrects itself through the same wheel everything else uses.
 *
 * ## Manual driving never ends the pursuit
 * The physics already yields every axis to a live input, so a pilot can grab the wheel mid-follow, joyride, and
 * let go -- and the station simply re-acquires. The one thing that had to be built for that is the adrift clock:
 * left running it would call "lost contact" on a pilot who flew off for the fun of it, so manual input holds
 * [secondsAdrift] at zero, and releasing re-arms [bestError] from wherever the joyride ended so the trip home
 * counts as making ground rather than as being outrun. A leader that genuinely escapes still breaks the follow;
 * the pilot cannot, except by the same Sneak+F that started it.
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
 * ## Throttle
 * A recorded route never commanded speed, because the pilot's cruise setting owned it and a route had no
 * opinion worth having. Station-keeping is a speed problem before it is a steering one: the leader's speed is
 * most of the answer, and the rest is a correction for being ahead of or behind where you should be. See
 * [EurekaShipControl.pathTargetSpeed]. What the command may NOT do is jump: it rises through a slew limiter at
 * [EurekaConfig.Server.followAcceleration], seeded from the hull's real speed when the pursuit starts, which is
 * what turns the old instant zoom into a spool-up. Falling is never limited -- braking beside another hull must
 * not wait on a ramp.
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

    /** Live distance from the ship to its station -- or, while [circling], to the circle itself. */
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
     * test below cannot possibly fire before the ship has been steered once. Re-armed to the current error
     * when a pilot releases the controls, for the same reason it starts loose -- see the class doc.
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
     * out there. Held at zero outright while the pilot is driving.
     */
    var secondsAdrift = 0.0
        private set

    /** True while orbiting rather than keeping station. Read by [ShipFollows] to skip the side bookkeeping. */
    var circling = false
        private set

    /** Which way round the orbit runs, +1 or -1. Chosen at entry; forced +1 for a mutual pair (see [tick]). */
    private var orbit = 1

    /** +1 the tick an orbit begins, -1 the tick one ends, 0 otherwise. One-shot, for the orchestrator. */
    private var circleTransition = 0

    /** Commanded-speed slew state, m/s. NaN until the first tick seeds it from the hull's real speed. */
    private var commandedSpeed = Double.NaN

    /** Whether a manual input was held last tick, for re-arming [bestError] on release. */
    private var wasManual = false

    private val station = Vector3d()
    private val heading = Vector3d()
    private val ownBeam = Vector3d()
    private val centre = Vector3d()

    /** The orbit transition this tick, if any: +1 began, -1 ended, 0 neither. Clears itself when read. */
    fun consumeCircleTransition(): Int {
        val t = circleTransition
        circleTransition = 0
        return t
    }

    /**
     * One guidance step. Returns false if the ship can no longer be steered, which the caller treats as a
     * reason to break off.
     *
     * [leaderFrame] is passed in rather than derived here because the caller has already built it to decide
     * whether the side should flip, and it is the same frame either way. [mutual] is true when the leader is
     * itself following this ship -- the caller owns the followers map, so it answers that question.
     */
    fun tick(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        leader: LoadedServerShip,
        leaderFrame: FollowGeometry.Frame,
        mutual: Boolean
    ): Boolean {
        val cfg = EurekaConfig.SERVER

        FollowGeometry.centreOf(ship, centre) ?: return false
        val ownHeading = control.pathForward(heading) ?: return false
        FollowGeometry.beamOf(ownHeading, ownBeam)

        // How fast the leader is actually moving over the ground, direction-blind: a leader drifting sideways
        // is exactly as parked, for circling purposes, as one holding still.
        val leaderPace = sqrt(
            leader.velocity.x() * leader.velocity.x() + leader.velocity.z() * leader.velocity.z()
        )

        // The orbit's size, and where this ship sits about the leader, both needed to decide the mode.
        val radius = FollowGeometry.orbitRadius(leader, ship, ownBeam, cfg)
        val offX = centre.x - leaderFrame.centre.x
        val offZ = centre.z - leaderFrame.centre.z
        val range = sqrt(offX * offX + offZ * offZ)

        // Mode. A mutual pair always orbits. Otherwise enter on "leader slow AND we have actually arrived" --
        // the plain approach law handles the whole trip in -- and leave on the leader making way again, with
        // hysteresis so a nudged hull doesn't flap between modes at the threshold.
        val wasCircling = circling
        circling = when {
            mutual -> true
            wasCircling -> leaderPace <= cfg.followCircleBelow + CIRCLE_EXIT_HYSTERESIS
            else -> leaderPace < cfg.followCircleBelow && range < radius + CIRCLE_ENTRY_MARGIN
        }
        if (circling != wasCircling) {
            circleTransition = if (circling) 1 else -1
            if (circling) {
                // Whichever way round the bow already leans, so entry is a merge rather than a U-turn. A
                // mutual pair must AGREE instead: opposite hands cancel into the pair sliding sideways across
                // the map together, same hands is the stable orbit -- so both take +1 by decree.
                orbit = if (mutual) 1 else {
                    val cross = ownHeading.x * offZ - ownHeading.z * offX
                    if (cross >= 0.0) 1 else -1
                }
            }
        }

        // Level KEELS rather than level centres, unless the config says otherwise -- what stops a deep hull
        // stationing itself half-submerged beside a launch, and what makes a formation in the air read as
        // ships on one deck instead of ships at one middle. The same height serves the orbit: a lap flown at
        // the leader's own level is the whole spectacle.
        val keelLift = if (cfg.followMatchKeel) FollowGeometry.keelLiftFor(leader, ship) else 0.0

        val turnOmega: Double
        val desiredSpeed: Double

        if (circling) {
            // The circle IS the station, so the error the break-off machinery watches is distance from the
            // ring, not from any point on it -- a ship anywhere on its lap is exactly where it should be.
            stationError = abs(range - radius)

            // Aim at a point a little way round the ring. On the ring the aim leads the lap; inside or
            // outside it, the same point pulls the ship back toward the ring, so one target does both jobs.
            // The lead arc is floored by the ordinary lookahead so a tiny orbit still steers smoothly, and
            // capped well under a quarter-turn so a big one never aims at the far side.
            val leadArc = (max(cfg.followLookahead, radius * LEAD_FRACTION) / max(radius, 1.0))
                .coerceIn(MIN_LEAD_RAD, MAX_LEAD_RAD)
            // Advancing along the +1 tangent (up x radial) DECREASES the atan2 angle, hence the minus.
            val aimAngle = atan2(offZ, offX) - orbit * leadArc
            val aimX = leaderFrame.centre.x + cos(aimAngle) * radius - centre.x
            val aimZ = leaderFrame.centre.z + sin(aimAngle) * radius - centre.z
            val error = signedYaw(ownHeading.x, ownHeading.z, aimX, aimZ)
            turnOmega = cfg.followTurnGain * error - cfg.followTurnDamping * ship.angularVelocity.y()

            desiredSpeed = cfg.followCircleSpeed
        } else {
            // Both hull measurements are taken about the FOLLOWER'S OWN axes, so the station stops moving
            // whenever this ship turns. See FollowGeometry.standoff for why that mattered enough to pass them in.
            val gap = FollowGeometry.gapBetween(leader, ship)
            standoff = FollowGeometry.standoff(
                leader, leaderFrame, FollowGeometry.halfExtentAlong(ship, ownBeam), gap
            )
            // Queue spacing uses THIS hull's length, so a long ship taking slot 2 doesn't overlap the one in
            // slot 1, and the same size-scaled gap the beam gets.
            val astern = slot * (FollowGeometry.halfExtentAlong(ship, ownHeading) * 2.0 + gap)

            FollowGeometry.stationPoint(leaderFrame, side, standoff, astern, keelLift, station)

            stationError = station.distance(centre)

            // region heading
            // The station error, split into "ahead of / behind station" and "inside / outside it". Read in the
            // LEADER's frame, so the two stay told apart while the leader manoeuvres. Only when the leader's
            // forward is an untrusted keel line -- a guess whose sign is as likely backwards as not -- is our
            // own frame used instead, which is the same decomposition against the only heading we can trust.
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

            // Aim at a point one lookahead ahead along the reference heading, shifted sideways by however far
            // off the station is. `along` never enters the steering: it is floored at the lookahead, so a
            // station ten blocks astern and a station a hundred blocks ahead both steer dead level with the
            // leader, and closing the distance is left to the throttle, which is the only thing that can do
            // it. What steers is `lateral` alone -- the aim leans over by atan(lateral / lookahead), which
            // saturates near a right angle however far out we are and unwinds to zero on station.
            val aimAhead = max(along, max(cfg.followLookahead, MIN_LOOKAHEAD))
            val aimX = refFx * aimAhead + refBx * lateral
            val aimZ = refFz * aimAhead + refBz * lateral
            val error = signedYaw(ownHeading.x, ownHeading.z, aimX, aimZ)

            // Rate feedback: take the yaw the hull has already got back out of the demand. Heading error alone
            // is a spring with no damper -- the ship is swinging hardest at the instant the error hits zero, so
            // it sails through and comes back, which is the wallowing the leader's every course change set off.
            turnOmega = cfg.followTurnGain * error - cfg.followTurnDamping * ship.angularVelocity.y()
            // endregion

            // region throttle
            // The leader's speed along its own heading is the baseline -- match that and the gap stops
            // growing. The correction is how far ahead of or behind station we are, measured along OUR heading
            // because that is the axis the throttle actually pushes on. A ship crabbing in hard toward a
            // station off to one side is pointing most of the way at it, and this reads that as ground to make
            // up; the leader-frame reading would see barely any and leave it dawdling across.
            val alongError = dx * ownHeading.x + dz * ownHeading.z
            val leaderSpeed = leader.velocity.x() * leaderFrame.forward.x +
                leader.velocity.z() * leaderFrame.forward.z

            // Capping the CLOSING rate rather than the absolute speed is what lets this work at any leader
            // speed. An absolute cap would quietly forbid ever catching a leader travelling faster than the
            // cap, however much engine the follower had.
            val closing = (cfg.followClosingGain * alongError)
                .coerceIn(-cfg.followReverseSpeed, cfg.followClosingSpeed)
            desiredSpeed = (leaderSpeed + closing).coerceAtLeast(-cfg.followReverseSpeed)
            // endregion
        }

        // The adrift clock, with the pilot written out of it: manual input holds the clock at zero, and the
        // moment it is released the ratchet re-arms from HERE -- so the way home after a joyride counts as
        // making ground under the followBreakSlack rule, exactly like the opening leg of a long-range order.
        val manual = abs(control.turnHold) > 0.0 || abs(control.fwdHold) > 0.0 || abs(control.vertHold) > 0.0
        if (manual) {
            wasManual = true
            secondsAdrift = 0.0
        } else {
            if (wasManual) {
                wasManual = false
                bestError = stationError
            }
            if (stationError < bestError) bestError = stationError
            // Flat limit once the ship has ever been within reach of its station; the ratchet plus slack only
            // while it never has, which is where a long-range order starts.
            val adriftLimit = max(cfg.followBreakRange, bestError + cfg.followBreakSlack)
            secondsAdrift = if (stationError > adriftLimit) secondsAdrift + TICK_SECONDS else 0.0
        }

        // region elevation
        // Exactly zero inside the deadband, which is load-bearing rather than tidy: the water altitude hold
        // latches its depth only while the commanded vertical is zero, so a permanently jittering near-zero
        // command would knock it out of hold every tick and the ship would slowly sink. The orbit holds the
        // same height the station would -- the leader's level, keel-matched.
        // A follower will wet its keel to keep station, but it will not go under.
        //
        // The station's height is the leader's, keel-matched, which is right up until the leader is sinking
        // -- and then "match her keel" reads as "follow her to the seabed". A pirate would ride a
        // foundering prize all the way down, still circling, still firing, both hulls under water.
        //
        // The line is drawn at the follower's own hull rather than at the leader's behaviour, which is what
        // keeps a sinking ship a legitimate target instead of a way to shake a pursuit: a leader may swamp
        // herself to the gunwales and still be chased and shot at, which is exactly the desperate escape
        // that ought to be available to her. What she cannot do is take the pursuer down with her.
        //
        // [MAX_KEEL_SUBMERSION] blocks of keel may go under. Below that the descent command is dropped --
        // not reversed, and nothing else is touched: the ship is not shoved back up, it simply stops being
        // driven down, and buoyancy does the rest at its own pace. Climbing is never clamped, so a follower
        // still rises with a leader that rises.
        //
        // The surface is sampled under the hull rather than assumed to be at sea level, so the same rule
        // covers a lake on a mountain and a flooded quarry. Gated on keelInWater, which is a real block
        // sample at the keel -- VS2's own liquidOverlap measures against the dimension's flat sea-level
        // plane and reads zero for every body of water that is not at it.
        var climbError = (leaderFrame.centre.y + keelLift) - centre.y
        if (climbError < 0.0 && control.keelInWater && control.activeProfile != ControlProfile.SUBMARINE) {
            val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, centre.x.toInt(), centre.z.toInt()).toDouble()
            // Centre height at which the keel sits exactly MAX_KEEL_SUBMERSION under the surface. Measured
            // about world UP so a heeled hull is judged on how it is actually lying, the same way
            // FollowGeometry sizes everything else.
            val floor = surface - MAX_KEEL_SUBMERSION + FollowGeometry.halfHeightOf(ship)
            if (centre.y <= floor) climbError = 0.0
        }
        val verticalMps = if (abs(climbError) < cfg.followVerticalDeadband) 0.0
        else cfg.followVerticalGain * climbError
        // endregion

        // The slew limiter -- see the class doc. Seeded from the hull's REAL forward speed so a pursuit begun
        // under way starts from the speed it has, not from zero. A non-positive acceleration means the limiter
        // is switched off, which is also what keeps a config of 0 from silently forbidding motion entirely.
        val actualForward = ship.velocity.x() * ownHeading.x + ship.velocity.z() * ownHeading.z
        if (commandedSpeed.isNaN()) commandedSpeed = actualForward
        commandedSpeed = when {
            cfg.followAcceleration <= 0.0 -> desiredSpeed
            desiredSpeed > commandedSpeed ->
                min(commandedSpeed + cfg.followAcceleration * TICK_SECONDS, desiredSpeed)
            else -> desiredSpeed
        }

        control.pathCommand(
            turnOmega = turnOmega,
            verticalMps = verticalMps,
            // No corner ceiling: unlike a route, there is no known curve ahead to be too fast for. The closing
            // cap above is what keeps the approach from being a charge.
            speedCap = null,
            targetSpeed = commandedSpeed
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

        /** Blocks beyond the orbit radius inside which a slow leader's follower counts as having arrived. */
        const val CIRCLE_ENTRY_MARGIN = 24.0

        /** m/s above followCircleBelow the leader must reach before an orbit is abandoned -- mode hysteresis. */
        const val CIRCLE_EXIT_HYSTERESIS = 0.5

        /** The lead arc as a fraction of the radius, before the lookahead floor and the angular clamps. */
        const val LEAD_FRACTION = 0.3

        /** Angular clamps on the orbit's aim lead, radians: enough to steer by, never near the far side. */
        /** How many blocks of keel a follower may put under water to hold station. See the climb clamp. */
        const val MAX_KEEL_SUBMERSION = 1.5

        const val MIN_LEAD_RAD = 0.2
        const val MAX_LEAD_RAD = 0.8
    }
}
