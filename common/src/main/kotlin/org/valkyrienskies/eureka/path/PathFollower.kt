package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

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
 * ## The two modes are two different machines
 * Everything above describes [PathMode.GEOMETRY] -- SHIFT+P, the original feature. The follower steers, the
 * pilot's cruise setting owns the throttle, and the ship flies a translated copy of the line.
 *
 * [PathMode.REPLAY] -- CTRL+SHIFT+P -- does none of that. It runs a CLOCK over the route's own
 * [MotionTrack] and hands the hull a pose to be at, through [EurekaShipControl.PathServo]. No pursuit, no
 * lookahead, no throttle, and no offset: the ship flies the drawn line itself, at the pace it was recorded.
 *
 * That is a change of controller rather than a change of setting, and it is deliberate. Pursuit plus a
 * throttle can approximate a recording but never reproduce one, because every quantity it commands is
 * mediated by something with dynamics of its own -- which is how a lift-off recorded over forty seconds came
 * back as a six-second leap, and how a route diving to the ground was flown as an average of itself.
 *
 * Switching mid-flight is still one field, since the two share their state (route, laps, owner) and differ
 * only in what they do with it each tick.
 *
 * ## Elevation is not steered like heading
 * Heading is aimed a long way ahead ([lookahead], 8-40 blocks) because turning is a curvature problem: a hull
 * cannot turn on the spot, so it has to aim at where it is going to be. Altitude is NOT -- it is a direct
 * up/down velocity command with no such constraint -- and giving the two the same lookahead was a real defect
 * rather than a simplification. On a route that dives to the ground and climbs back out, the aim point
 * crosses to the far side of the V while the ship is still descending, so the controller calls for CLIMB
 * before the bottom is reached and the ship flies a smoothed average of the line instead of touching the
 * ground. Elevation therefore reads its own, much shorter `pathVerticalLookahead`.
 *
 * The vertical command is also fed forward from the route's own slope rather than being left to the
 * proportional term alone. A pure P controller only produces descent in proportion to how far BELOW the line
 * it already is, so holding 5 m/s of descent at the default gain needs a standing 10-block error -- on a
 * short dive the ship never gets there. Feeding the slope in directly leaves the P term correcting error,
 * which is what it is for.
 *
 * ## The offset
 * In [PathMode.GEOMETRY], whatever gap there was between the ship and the line when playback began is
 * REMEMBERED and added to every point the follower aims at. The ship therefore flies a translated copy of the
 * loop -- same shape, same headings, displaced. Park on the line and it flies the line; park 30 blocks up and
 * to starboard and it flies the same route 30 blocks up and to starboard, which is what makes the feature
 * usable for aircraft sharing one recorded circuit at different altitudes.
 *
 * Holding the offset in WORLD space, rather than in a frame that rotates with the route, is deliberate. A
 * rotating "outer lane" offset inverts into a cusp wherever the offset exceeds the local turn radius -- and at
 * the default 32-block engage range, any corner tighter than that would do it. A translation has no such
 * failure mode at any offset.
 *
 * [PathMode.REPLAY] keeps no offset at all. It DECAYS the engage gap to nothing over `pathReplayJoinSeconds`,
 * so the ship eases onto the line and thereafter flies the wireframe itself. A replay of a recording that
 * threaded a gap between buildings is only worth anything if it threads the same gap.
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
    startLaps: Int = 0,
    startMode: PathMode = PathMode.GEOMETRY,
    startClock: Double = -1.0,
    startDwell: Int = -1,
    startDwellRemaining: Double = 0.0,
    startNextDwell: Int = -1
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

    /**
     * Which of the two things this ship is doing with the route. Switchable in place, mid-flight.
     *
     * Owned by [ShipPaths] (it is the side that can refuse a switch onto a route with no timeline) and
     * mirrored onto the ship's [PathBinding], so the mode a player chose survives a world reload. Without
     * that it would not: a replayed ship would come back in geometry mode and simply sit there, since nothing
     * would be driving the hull it had handed over.
     */
    var mode = startMode
        private set

    /**
     * Where the recording is up to, in MOVING seconds -- the whole of replay's state.
     *
     * Not a measurement of the ship. That is the point: the clock advances by exactly one tick per tick and
     * pauses only where the recording paused, so the arc it produces is monotone by construction. The
     * previous design read the hull's own position back to decide where it was in the recording, and a ship
     * held at a stop drifting a centimetre backwards over that stop's arc served the same wait again, and
     * again.
     */
    var clock = 0.0
        private set

    /** Live distance from the ship's keel to where it should be, for the status readout. */
    var trackingError = 0.0
        private set

    /** The recorded pause being served, or -1 for none. Readable so [ShipPaths] can persist it. */
    var dwellIndex = startDwell
        private set

    /** Seconds left of [dwellIndex]'s pause. */
    var dwellRemaining = startDwellRemaining
        private set

    /**
     * The pause the clock is running toward, or [MotionTrack.dwellCount] once the last one this lap is done.
     *
     * A plain index into a list walked in order, which is the whole reason a stop can no longer re-fire: it
     * only ever moves forward, and it is reset exactly once per lap.
     */
    var nextDwell = 0
        private set

    /** True on the tick a pause begins, for the caller to announce. */
    var dwellJustStarted = false
        private set

    /** True on the tick a pause ends, likewise. */
    var dwellJustEnded = false
        private set

    /** Whether the ship is being held at rest by a recorded pause right now. */
    val dwelling: Boolean get() = dwellIndex >= 0

    /**
     * The displacement currently held from the line, for the client to draw the route where it is flown.
     *
     * Always zero in [PathMode.REPLAY], which flies the line itself -- the few seconds of easing onto it are
     * the SHIP's business and move the station, not the route.
     */
    fun copyOffset(dest: Vector3d): Vector3d = dest.set(offset)

    /**
     * Seconds left of the ease onto the line, and the gap being eased away.
     *
     * The gap is taken from where the hull ACTUALLY is on the first replay tick rather than from the offset
     * the binding was carrying, so a ship that drifted while its chunks were unloaded still joins from where
     * it woke up. [joinMeasured] is a flag of its own rather than a `joinLeft < 0` test, because counting a
     * span down by a tick at a time lands a hair either side of zero -- and on the wrong side, the ease
     * re-armed every three seconds for the whole flight, dragging the DRAWN route about with it.
     */
    private val joinOffset = Vector3d()
    private var joinMeasured = false
    private var joinLeft = 0.0
    private var joinSpan = 1.0

    /** Last heading commanded, held through the stretches where the route is too steep to have one. */
    private val lastForward = Vector3d(0.0, 0.0, 1.0)

    private val virtual = Vector3d()
    private val target = Vector3d()
    private val station = Vector3d()
    /** Shared by the elevation reference and its tangent -- neither outlives the other's read. */
    private val vertical = Vector3d()
    private val forward = Vector3d()

    init {
        val motion = path.motion
        if (motion != null) {
            clock =
                if (startClock >= 0.0) startClock
                else motion.timeAtArc(if (startArc >= 0.0) startArc else 0.0)
            nextDwell = if (startNextDwell >= 0) startNextDwell else firstDwellAfter(motion, clock)
        }
    }

    /**
     * One guidance step. Returns false if the ship can no longer be steered (no course), which the caller
     * treats as a reason to stop.
     *
     * [keelLocal] is the same anchor in SHIP space, needed only by [PathMode.REPLAY] -- it is handed to the
     * physics thread so the servo can find the keel against the pose it is about to correct, rather than
     * against one measured a tick earlier over here.
     */
    fun tick(
        keel: Vector3dc,
        keelLocal: Vector3dc,
        control: EurekaShipControl,
        speed: Double
    ): Boolean {
        dwellJustStarted = false
        dwellJustEnded = false
        val motion = if (mode.isReplay) path.motion else null
        return if (motion != null) tickReplay(keel, keelLocal, control, motion)
        else tickGeometry(keel, control, speed)
    }

    /**
     * Pure pursuit with the pilot's throttle -- SHIFT+P, and the original feature. Unchanged.
     */
    private fun tickGeometry(keel: Vector3dc, control: EurekaShipControl, speed: Double): Boolean {
        val cfg = EurekaConfig.SERVER
        // A hull switched out of replay is still carrying the last pose the servo published, and the servo
        // outranks everything this method commands. Clearing it here rather than only on the mode switch also
        // covers a replay that lost its timeline (a route edited out from under it, say).
        control.pathServo = null

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

        // Where the ship should be right now. Used for the readout AND, when the vertical lookahead is zero,
        // as the elevation reference -- so the ordinary case costs one sample rather than two.
        path.sampleAt(arc, station).add(offset)
        trackingError = station.distance(keel.x(), keel.y(), keel.z())

        val heading = control.pathForward(forward) ?: return false

        // Signed yaw angle from the ship's forward to the aim point, about +Y. Taken as atan2 of the cross and
        // dot products rather than a difference of two atan2 headings: it is already wrapped to +/-pi, so there
        // is no wraparound case to get wrong at due north.
        val dx = target.x - keel.x()
        val dz = target.z - keel.z()
        // Sitting on the aim point: nothing to steer toward, so hold the last heading.
        val turnOmega = if (dx * dx + dz * dz < 1.0e-6) 0.0 else {
            val cross = heading.z * dx - heading.x * dz
            val dot = heading.x * dx + heading.z * dz
            cfg.pathTurnGain * atan2(cross, dot)
        }

        control.pathCommand(
            turnOmega = turnOmega,
            // Fed forward from the ship's measured speed, which is all this mode has: the throttle belongs to
            // the pilot, so the follower cannot know what the ship is about to do.
            verticalMps = verticalCommand(cfg, keel, speed),
            speedCap = speedCeiling(control, lookahead)
        )
        return true
    }

    /**
     * Fly the recording: put the hull where the clock says it was, moving how it was moving.
     *
     * There is no pursuit here and no throttle. The route is read at a time, not at a lookahead, and what
     * comes out is a pose and a velocity handed straight to the hull's servo -- which is the only way a
     * recording can be reproduced rather than approximated. Everything the pilot's control law would have
     * added between the two (engine power, drag, buoyancy, the closed loop that trims the throttle) is
     * exactly what made a replay differ from what was flown.
     */
    private fun tickReplay(
        keel: Vector3dc,
        keelLocal: Vector3dc,
        control: EurekaShipControl,
        motion: MotionTrack
    ): Boolean {
        val cfg = EurekaConfig.SERVER
        advanceClock(cfg, motion)

        arc = motion.arcAtTime(clock)
        path.sampleAt(arc, station)

        // Ease onto the line, ONCE. Measured from where the hull actually is the first time through, so a
        // resumed route joins from where the ship woke up rather than from a displacement saved before the
        // reload.
        //
        // The eased gap moves the STATION and nothing else. It is deliberately not written to [offset], which
        // is the displacement the client draws the route AT: a replay flies the line itself, so the line has
        // to be drawn where it is. Publishing the join as an offset made the whole wireframe slide across the
        // sky -- by the full engage range, two chunks, at the moment of engaging -- which says something about
        // the route that simply isn't true. Nothing about the ship's motion changed with it, which is exactly
        // why it read as a purely visual fault.
        if (!joinMeasured) {
            joinMeasured = true
            joinSpan = cfg.pathReplayJoinSeconds.coerceAtLeast(TICK_SECONDS)
            joinLeft = joinSpan
            joinOffset.set(keel).sub(station)
            offset.set(0.0, 0.0, 0.0)
        }
        if (joinLeft > 0.0) {
            // Floored at zero rather than left to run negative. Counting a three-second span down in ticks of
            // 0.05 lands a hair BELOW zero in binary, and the old "not measured yet" test read that as a fresh
            // engage -- so the ease re-armed every three seconds, all flight.
            joinLeft = (joinLeft - TICK_SECONDS).coerceAtLeast(0.0)
            val f = joinLeft / joinSpan
            // Smoothstep, so the ship leaves its starting point and arrives on the line without a corner at
            // either end -- a linear decay would kink the moment it reached zero.
            station.fma(f * f * (3.0 - 2.0 * f), joinOffset)
        }
        trackingError = station.distance(keel.x(), keel.y(), keel.z())

        // The pace along the line, scaled by the clock rate for the same reason the clock is: asking for a
        // route at half speed has to slow the ship as well as the schedule.
        val along = if (dwelling) 0.0 else motion.rateAtTime(clock) * replayRate(cfg)
        path.tangentAt(arc, vertical)

        // The bow follows the route's own direction of travel, flattened -- see the class note. A stretch too
        // steep to have a horizontal direction (a vertical climb) keeps the last one, which is what a ship
        // rising off a pad does anyway.
        val flatSq = vertical.x * vertical.x + vertical.z * vertical.z
        if (flatSq > MIN_FLAT_TANGENT) {
            val inverse = 1.0 / sqrt(flatSq)
            lastForward.set(vertical.x * inverse, 0.0, vertical.z * inverse)
        }

        control.pathServo = EurekaShipControl.PathServo(
            keelTarget = Vector3d(station),
            velocity = Vector3d(vertical).mul(along),
            forward = Vector3d(lastForward),
            keelLocal = Vector3d(keelLocal)
        )
        return true
    }

    /**
     * Move the recording on by one tick, stopping dead on each recorded pause and wrapping at the end of a lap.
     *
     * The clock never runs backwards and the pause index never decreases, so a pause is served once and once
     * only -- which is the whole of the fix for a stop that used to fire three or four times.
     */
    private fun advanceClock(cfg: EurekaConfig.Server, motion: MotionTrack) {
        if (dwelling) {
            dwellRemaining -= TICK_SECONDS
            if (dwellRemaining > 0.0) return
            dwellIndex = -1
            dwellRemaining = 0.0
            dwellJustEnded = true
            nextDwell++
            return
        }

        var next = clock + TICK_SECONDS * replayRate(cfg)

        if (nextDwell < motion.dwellCount) {
            val at = motion.dwellTime(nextDwell)
            if (next >= at) {
                clock = at
                dwellIndex = nextDwell
                dwellRemaining = motion.dwellSeconds(nextDwell)
                dwellJustStarted = true
                return
            }
        }

        if (next >= motion.movingSeconds) {
            next -= motion.movingSeconds
            laps++
            // A new lap re-arms every stop, which is what makes a delivery loop a delivery loop.
            nextDwell = 0
        }
        clock = next
    }

    private fun replayRate(cfg: EurekaConfig.Server): Double =
        cfg.pathReplaySpeedScale.coerceIn(MIN_REPLAY_RATE, MAX_REPLAY_RATE)

    /** The first pause reached after clock reading [t], or the count when there is none left this lap. */
    private fun firstDwellAfter(motion: MotionTrack, t: Double): Int {
        for (i in 0 until motion.dwellCount) if (motion.dwellTime(i) > t) return i
        return motion.dwellCount
    }

    /**
     * Change what this follower does with its route, mid-flight.
     *
     * Switching INTO replay re-measures the join from wherever the hull has got to and puts the clock at the
     * point of the recording the ship is standing in, so the two modes hand over without the ship jumping.
     * Switching out abandons any pause in progress -- [advanceClock] only runs in replay, so a pause left
     * running would hold [dwelling] true with nothing to ever clear it.
     */
    fun switchTo(newMode: PathMode) {
        if (newMode == mode) return
        mode = newMode
        dwellIndex = -1
        dwellRemaining = 0.0
        joinMeasured = false
        if (newMode.isReplay) {
            val motion = path.motion ?: return
            clock = motion.timeAtArc(if (arc >= 0.0) arc else 0.0)
            nextDwell = firstDwellAfter(motion, clock)
        }
    }

    /**
     * The commanded vertical speed in m/s: the route's own slope, plus a correction for being off it.
     *
     * The slope term is the important half and the one that was missing. Without it the proportional term has
     * to produce the entire climb or dive on its own, which it can only do by first falling far enough behind
     * to justify it -- at the default gain, ten blocks of standing error to hold five metres a second. With
     * it, the P term is left doing what a P term should: correcting error.
     *
     * The deadband is applied to the TOTAL, and it is load-bearing rather than tidy. The water altitude hold
     * latches its depth only while the commanded vertical is exactly zero, so a permanently jittering
     * near-zero command would knock it out of hold every tick and the ship would slowly sink. Compare
     * `ShipFollower`, which learned this the same way.
     */
    private fun verticalCommand(
        cfg: EurekaConfig.Server,
        keel: Vector3dc,
        commandedSpeed: Double
    ): Double {
        // Elevation reads its OWN lookahead -- see the class note. `station` already holds the point at `arc`,
        // which is what a zero lookahead wants, so the common case costs no extra sample.
        val ahead = cfg.pathVerticalLookahead
        val targetY =
            if (ahead <= 0.0) station.y
            else path.sampleAt(arc + ahead, vertical).y + offset.y

        // The route's own vertical rate here. Read at the same point the reference is, so the two cannot
        // disagree.
        val feedForward = pitchTangent(path.tangentAt(arc + ahead, vertical).y) * commandedSpeed

        val command = feedForward + cfg.pathVerticalGain * (targetY - keel.y())
        return if (abs(command) < cfg.pathVerticalDeadband) 0.0 else command
    }

    /**
     * Metres climbed per metre of HORIZONTAL travel, from a unit tangent's vertical component -- the tangent
     * of the slope's pitch.
     *
     * The conversion is needed because the two quantities either side of it are measured differently.
     * `pathForward` is flattened to the horizontal before it is normalised, so every forward speed in this
     * class -- commanded, recorded and measured alike -- is a GROUND speed. Multiplying one of those by the
     * tangent's `y` (a sine) instead would answer the question "how fast does altitude change per metre along
     * the slope", which is not the question: it under-reads by a third at 45 degrees and by more than half at
     * 60, precisely on the steep descents this exists to fly properly.
     *
     * Clamped near vertical, where the ratio runs away and no hull could follow it regardless.
     */
    private fun pitchTangent(tangentY: Double): Double {
        val horizontal = sqrt((1.0 - tangentY * tangentY).coerceAtLeast(MIN_HORIZONTAL_FRACTION))
        return tangentY / horizontal
    }

    /** Seconds left of the pause being served, rounded for chat. */
    fun dwellSecondsLeft(): Int = ceil(dwellRemaining).toInt().coerceAtLeast(0)

    /**
     * The fastest this hull can be going and still hold the route that is coming up, or null where nothing
     * about it binds.
     *
     * Two limits, both of the same kind: what the hull can physically do, not what it is being asked for. The
     * horizontal one is the corner, the vertical one is the slope, and the lower of the two wins.
     */
    private fun speedCeiling(control: EurekaShipControl, lookahead: Double): Double? {
        val corner = cornerSpeedCap(control, lookahead)
        val slope = verticalSpeedCap(control, lookahead)
        return when {
            corner == null -> slope
            slope == null -> corner
            else -> min(corner, slope)
        }
    }

    /**
     * The fastest this hull can descend or climb the slope ahead without falling behind the line, or null
     * where the route is level enough not to bind.
     *
     * Exactly the corner cap's argument on the other axis. A hull has a maximum climb and descent rate, and
     * covering a slope `g` at forward speed `v` needs a vertical rate of `v * g` -- so the fastest speed that
     * still fits inside the hull's own limit is `rate / g`. Above it the ship is not tracking badly, it is
     * being asked for something it cannot do, and it sails over the top of the dip. That is why a route that
     * dives to the ground and climbs back out could never reach the bottom at speed however the gains were
     * tuned: the shortfall was never a gain.
     *
     * Climb and descent are capped against their own rates, since the two are rarely the same number.
     */
    private fun verticalSpeedCap(control: EurekaShipControl, lookahead: Double): Double? {
        val cfg = EurekaConfig.SERVER
        if (!cfg.pathVerticalSlowdown) return null

        val grade = path.steepestGrade(arc, lookahead.coerceAtLeast(cfg.pathLookaheadMin))
        if (abs(grade) < MIN_MEASURABLE_GRADE) return null

        // Rise per metre of GROUND covered, matching the units every forward speed here is in -- see
        // [pitchTangent]. The cap is then simply the ground speed at which that rise equals what the hull can
        // actually manage.
        val perMetre = abs(pitchTangent(grade))
        val rate = if (grade >= 0.0) control.pathClimbRateMax else control.pathDescendRateMax
        // The same margin the corner cap keeps, and for the same reason: arriving at a dive needing the hull's
        // absolute maximum descent leaves nothing in hand for the wave or the tracking error that follows.
        return (rate / perMetre * cfg.pathCornerSpeedMargin).coerceAtLeast(cfg.pathCornerMinSpeed)
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

        /** A slope shallower than this is level; dividing by it would manufacture a cap from rounding noise. */
        private const val MIN_MEASURABLE_GRADE = 0.02

        /**
         * Squared horizontal length below which a route tangent has no heading worth taking.
         *
         * 0.01 is about 84 degrees from level. Steeper than that the horizontal component is mostly rounding
         * noise, and swinging the bow to follow it would spin a lifting ship on the spot.
         */
        private const val MIN_FLAT_TANGENT = 0.01

        /** Bounds on the replay clock rate, so a config typo cannot stop time or run a route backwards. */
        private const val MIN_REPLAY_RATE = 0.05
        private const val MAX_REPLAY_RATE = 10.0

        /**
         * Floor on the squared horizontal share of a unit tangent, for [pitchTangent].
         *
         * 0.01 is a slope of about 84 degrees. Past that the rise per metre of ground runs away toward
         * infinity, and no hull is going to fly it whatever number comes out -- the clamp only keeps the
         * arithmetic finite.
         */
        private const val MIN_HORIZONTAL_FRACTION = 0.01
    }
}
