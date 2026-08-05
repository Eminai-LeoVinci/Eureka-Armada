package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID
import kotlin.math.atan2

/**
 * Steers one ship along one route.
 *
 * ## Pure pursuit
 * Each tick the follower finds where the ship is along the route, picks a point some distance further on, and
 * turns toward it. That is the whole algorithm. It is the standard solution for exactly this problem and it
 * earns its place here for two reasons: the aim-ahead distance is a single, physically meaningful tuning knob
 * (how far ahead a helmsman looks), and it degrades gracefully -- a ship knocked off the line by a wave or a
 * collision simply converges back, with no special recovery case to get wrong.
 *
 * The aim-ahead distance scales with speed, and is floored at the hull's own turn radius. Without that floor a
 * large ship at low speed would aim at a point inside its own turning circle, which it physically cannot reach,
 * and it would wind up circling the target instead of following the route.
 *
 * ## What it does NOT control
 * Speed. The pilot's cruise setting owns that, so one recorded route can be flown at any speed without
 * re-recording, and a route recorded while dawdling can be run at full ahead.
 *
 * ## The offset
 * Whatever gap there was between the ship and the line when playback began is REMEMBERED and added to every
 * point the follower aims at. The ship therefore flies a translated copy of the loop -- same shape, same
 * headings, displaced. Park on the line and it flies the line; park 30 blocks up and to starboard and it flies
 * the same route 30 blocks up and to starboard, which is what makes the feature usable for aircraft sharing one
 * recorded circuit at different altitudes.
 *
 * Holding the offset in WORLD space, rather than in a frame that rotates with the route, is deliberate. A
 * rotating "outer lane" offset inverts into a cusp wherever the offset exceeds the local turn radius -- and at
 * the default 32-block engage range, any corner tighter than that would do it. A translation has no such
 * failure mode at any offset.
 *
 * ## Resuming
 * A follower is a runtime object, but the binding behind it is saved on the ship ([PathBinding]), so one gets
 * rebuilt after a world reload with the offset, arc and lap count it had. [playerId] is null on a follower that
 * was rebuilt from a binding with no owner recorded, which only means nobody is told when this ship reports
 * something -- it steers exactly the same either way.
 */
class PathFollower(
    val shipId: Long,
    val playerId: UUID?,
    val path: ShipPath,
    offset: Vector3dc,
    startArc: Double = -1.0,
    startLaps: Int = 0
) {

    private val offset = Vector3d(offset)

    /**
     * Progress along the route, in blocks of arc length. Negative means "not established yet", which makes the
     * next lookup search the whole loop instead of a window around this value.
     *
     * Readable so [ShipPaths] can mirror it onto the ship's persisted [PathBinding]; a re-armed follower is
     * handed it back as `startArc`.
     */
    var arc = startArc
        private set

    /** Completed laps, for the status readout. */
    var laps = startLaps
        private set

    /**
     * Bound to the route but not being steered along it -- SHIFT+P pressed once. Owned by [ShipPaths].
     *
     * Held here rather than by taking the follower out of the map, because everything that makes a pause a
     * pause rather than a stop lives in this object: [arc], [laps] and the offset. A paused ship is still
     * BUSY (it owns the wheel as far as recording and pursuit are concerned) and is still drawn on the client
     * as flying its route, which is exactly what being bound but idle should look like.
     */
    var paused = false

    /** Live distance from the ship's keel to where it should be, for the status readout. */
    var trackingError = 0.0
        private set

    /** The displacement currently held from the line, for the client to draw the route where it is flown. */
    fun copyOffset(dest: Vector3d): Vector3d = dest.set(offset)

    private val virtual = Vector3d()
    private val target = Vector3d()
    private val forward = Vector3d()

    /**
     * One guidance step. Returns false if the ship can no longer be steered (no course), which the caller
     * treats as a reason to stop.
     */
    fun tick(keel: Vector3dc, control: EurekaShipControl, speed: Double): Boolean {
        val cfg = EurekaConfig.SERVER

        // Progress is measured on the UNOFFSET line, so a ship flying a parallel course still advances by the
        // route's own arc length rather than by its displaced copy's.
        virtual.set(keel).sub(offset)
        val previous = arc
        arc = path.nearestArcLength(virtual, previous)
        if (previous >= 0.0 && arc < previous - path.length * 0.5) laps++

        if (cfg.pathOffsetDecay > 0.0) {
            // Optional convergence onto the true line. Off by default -- a predictable parallel course is
            // usually what is wanted, and silent drift toward the line would be surprising.
            offset.mul((1.0 - cfg.pathOffsetDecay * TICK_SECONDS).coerceIn(0.0, 1.0))
        }

        val lookahead = (cfg.pathLookaheadSeconds * speed)
            .coerceIn(cfg.pathLookaheadMin, cfg.pathLookaheadMax)
            .coerceAtLeast(control.pathTurnRadius * TURN_RADIUS_LOOKAHEAD)

        path.sampleAt(arc + lookahead, target).add(offset)

        // Where the ship should be right now, purely for the readout.
        trackingError = path.sampleAt(arc, Vector3d()).add(offset).distance(keel.x(), keel.y(), keel.z())

        val heading = control.pathForward(forward) ?: return false

        // Signed yaw angle from the ship's forward to the aim point, about +Y. Taken as atan2 of the cross and
        // dot products rather than a difference of two atan2 headings: it is already wrapped to +/-pi, so there
        // is no wraparound case to get wrong at due north.
        val dx = target.x - keel.x()
        val dz = target.z - keel.z()
        if (dx * dx + dz * dz < 1.0e-6) return true // sitting on the aim point; nothing to steer toward

        val cross = heading.z * dx - heading.x * dz
        val dot = heading.x * dx + heading.z * dz
        val error = atan2(cross, dot)

        val verticalMps = cfg.pathVerticalGain * (target.y - keel.y())

        control.pathCommand(cfg.pathTurnGain * error, verticalMps, cornerSpeedCap(control, lookahead))
        return true
    }

    /**
     * The fastest this hull can be going and still hold the corner that is coming up, or null on a straight.
     *
     * A ship turns at some maximum yaw rate w, and holding a circle of radius R at speed v needs w = v / R. Run
     * that backwards and the fastest speed that still fits inside the tightest radius ahead is v = w * R. Above
     * it the ship is not being steered badly, it is being asked for something the hull cannot do, and it runs
     * wide -- gently on a sweeping curve, which is why wide turns already looked right, and badly on a hairpin.
     *
     * The window looked at is the aim-ahead distance, so the ship starts shedding speed roughly when it starts
     * aiming into the corner rather than on top of it.
     *
     * This is a CEILING handed to the hull, never a throttle setting: on a straight there is none at all, and
     * where there is one the pilot's own speed still applies if it is lower.
     */
    private fun cornerSpeedCap(control: EurekaShipControl, lookahead: Double): Double? {
        val cfg = EurekaConfig.SERVER
        if (!cfg.pathCornerSlowdown) return null

        val radius = path.minTurnRadius(arc, lookahead.coerceAtLeast(cfg.pathLookaheadMin))
        if (radius >= STRAIGHT_RADIUS) return null

        // pathTurnCap is the hull's own yaw ceiling, so this is its physical cornering speed. The margin backs
        // off from that limit, because arriving at a corner at exactly the speed that needs the ship's absolute
        // maximum yaw rate leaves nothing in hand for the wave, the gust or the tracking error that follows.
        return (control.pathTurnCap * radius * cfg.pathCornerSpeedMargin)
            .coerceAtLeast(cfg.pathCornerMinSpeed)
    }

    companion object {
        private const val TICK_SECONDS = 0.05

        /** Aim at least this many turn radii ahead, so the target is never inside the hull's turning circle. */
        private const val TURN_RADIUS_LOOKAHEAD = 1.5

        /** A "corner" this wide is a straight as far as any ship is concerned; don't cap speed for it. */
        private const val STRAIGHT_RADIUS = 1.0e4
    }
}
