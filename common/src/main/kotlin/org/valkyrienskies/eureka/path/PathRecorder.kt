package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.ShipFootprint
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One in-progress recording: the raw keel track of a ship being flown round a loop.
 *
 * Lives only while recording. What it produces -- the raw sample list -- is handed to [PathSmoothing] the
 * moment the loop closes, and this object is thrown away.
 *
 * ## Loop closure is automatic, and has to be
 * A ship is recorded by flying it, which means the pilot is sitting at the helm. Holding shift there dismounts
 * you (see `ShipHelmBlockEntity`), so there is no hotkey the pilot can press to end a recording without first
 * standing up. Hence the loop closes on its own: fly back to where you started, and when the ship's keel
 * touches the marker sphere left at the start point, the recording finishes itself.
 */
class PathRecorder(
    val shipId: Long,
    val playerId: UUID,
    start: Vector3dc,
    /**
     * How much bigger this ship's snap spheres are than a raft's -- see [markerScaleFor].
     *
     * Frozen at the start of the recording rather than re-read each tick. It is drawn on the client, and a
     * marker that grew while you were building on deck mid-flight would be worse than one that is a block out.
     */
    val markerScale: Double = 1.0
) {

    /** Where the recording began, and where it has to come back to. Also where the marker sphere is drawn. */
    val start: Vector3d = Vector3d(start)

    /** Raw samples, flat x,y,z. Only appended to. */
    private val points = ArrayList<Double>(3 * 512)

    /** Last EMITTED sample -- distance and turn are measured from here, not from last tick's position. */
    private var lastX = start.x()
    private var lastY = start.y()
    private var lastZ = start.z()

    /** Unit direction of the most recently emitted segment; all zero until there are two samples. */
    private var dirX = 0.0
    private var dirY = 0.0
    private var dirZ = 0.0

    /** Route length so far, in blocks. */
    var length = 0.0
        private set

    /**
     * True when the ship is back in the chunk it started in and the route is long enough to close.
     *
     * This is what the marker spheres key off: they appear only while armed, so they show up exactly when
     * closing the loop becomes possible and stay out of the way the rest of the time.
     */
    var armed = false
        private set

    /** Live distance from the keel to the start point, for the client's snap indicator. */
    var gap = 0.0
        private set

    /** Live keel position, so the client can draw the ship's half of the snap pair without knowing the hull. */
    val keel: Vector3d = Vector3d(start)

    val pointCount: Int get() = points.size / 3

    init {
        emit(start.x(), start.y(), start.z())
    }

    enum class Step {
        /** Still going. */
        RECORDING,

        /** The keel touched the start marker: the loop is closed and the recording is finished. */
        SNAPPED,

        /** Hit the point cap without ever closing. */
        OVERFLOW
    }

    /**
     * Advance the recording with this tick's keel position.
     *
     * Samples are emitted by DISTANCE, not by time, so the route's detail does not depend on how fast the ship
     * was going -- a slow harbour manoeuvre and a fast open-water leg record at the same resolution. Corners
     * get an extra sample regardless of distance, since a corner is exactly the feature a distance-only rule
     * would cut across.
     */
    fun tick(keel: Vector3dc): Step {
        val cfg = EurekaConfig.SERVER
        this.keel.set(keel)

        val dx = keel.x() - lastX
        val dy = keel.y() - lastY
        val dz = keel.z() - lastZ
        val moved = sqrt(dx * dx + dy * dy + dz * dz)

        if (moved >= cfg.pathSampleSpacing || (moved >= CORNER_MIN_STEP && isCorner(dx, dy, dz, moved))) {
            emit(keel.x(), keel.y(), keel.z())
            if (pointCount >= cfg.pathMaxPoints) return Step.OVERFLOW
        }

        gap = sqrt(
            (keel.x() - start.x) * (keel.x() - start.x) +
                (keel.y() - start.y) * (keel.y() - start.y) +
                (keel.z() - start.z) * (keel.z() - start.z)
        )

        // Back over the start, and far enough round to be a loop. The length gate matters because the ship
        // BEGINS inside the snap radius -- without it every recording would close instantly.
        //
        // A horizontal RADIUS, where this used to be "the same chunk column as the start". The chunk test was
        // fine while touching meant 3.5 blocks, since a chunk is comfortably larger than that and the gate
        // never bound. Scaled markers broke it: a 38-block hull touches at nearly 16 blocks, so the chunk
        // became the tighter of the two -- and an asymmetric one, since a start near a chunk edge is 1 block
        // from the boundary on one side and 15 on the other. You could sail right over your own start marker
        // and have nothing happen. A radius is symmetric, and scales with what it is gating.
        val flatGap = sqrt(
            (keel.x() - start.x) * (keel.x() - start.x) + (keel.z() - start.z) * (keel.z() - start.z)
        )
        armed = length >= minLoopLength(markerScale) && flatGap <= armRadius(markerScale)

        if (armed && gap <= touchDistance(markerScale)) {
            // Land a final sample where it actually snapped; the seam blend discards the tail anyway, but this
            // keeps the raw track honest for anything that inspects it.
            if (moved > 0.05) emit(keel.x(), keel.y(), keel.z())
            return Step.SNAPPED
        }

        return Step.RECORDING
    }

    /** The raw track, flat x,y,z, for [PathSmoothing.finish]. */
    fun toArray(): DoubleArray = DoubleArray(points.size) { points[it] }

    /**
     * Samples from index [from] onward, flat x,y,z.
     *
     * The client is sent only what it has not already seen and appends it, so the cost of drawing the live
     * trail is proportional to how far the ship moved this update -- a handful of points -- rather than to how
     * long the recording has been running.
     */
    fun slice(from: Int): DoubleArray {
        val n = pointCount
        val start = from.coerceIn(0, n)
        return DoubleArray((n - start) * 3) { points[start * 3 + it] }
    }

    private fun emit(x: Double, y: Double, z: Double) {
        if (points.isNotEmpty()) {
            val dx = x - lastX; val dy = y - lastY; val dz = z - lastZ
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            length += d
            if (d > 1.0e-9) { dirX = dx / d; dirY = dy / d; dirZ = dz / d }
        }
        points.add(x); points.add(y); points.add(z)
        lastX = x; lastY = y; lastZ = z
    }

    /** True when the ship has turned enough since the last sample that a straight line would miss the corner. */
    private fun isCorner(dx: Double, dy: Double, dz: Double, moved: Double): Boolean {
        if (dirX == 0.0 && dirY == 0.0 && dirZ == 0.0) return false
        val dot = (dx * dirX + dy * dirY + dz * dirZ) / moved
        return dot < CORNER_COS
    }

    companion object {
        /** Radius of the sphere at the ship's keel for the smallest hulls; half of what makes the snap gap. */
        const val SHIP_MARKER_RADIUS = 1.5

        /**
         * How much bigger than a raft's this hull's snap spheres should be drawn. 1.0 for anything up to one
         * size band; +`pathMarkerScaleStep` per band after that.
         *
         * Sized off the ship because the spheres are not decoration -- they ARE the distance at which the loop
         * closes, and a fixed two blocks asks a forty-block hull to park its keel on a spot it cannot see and
         * could not hit if it could. The player experience this fixes is circling the start point over and
         * over waiting for a snap that a big ship can barely reach.
         */
        fun markerScaleFor(footprint: Double): Double =
            1.0 + EurekaConfig.SERVER.pathMarkerScaleStep * ShipFootprint.bands(footprint)

        /** Keel sphere plus start sphere: the gap at which the two touch and the recording closes. */
        fun touchDistance(markerScale: Double): Double =
            (EurekaConfig.SERVER.pathSnapRadius + SHIP_MARKER_RADIUS) * markerScale

        /**
         * Horizontal distance from the start within which the loop may close and the markers are drawn.
         *
         * Never tighter than the old chunk-sized gate, and never so tight that a hull could be touching before
         * it was armed -- which is what [ARM_TOUCH_MULTIPLE] buys: the markers come up with room to line the
         * approach up rather than appearing and snapping in the same breath.
         */
        fun armRadius(markerScale: Double): Double =
            max(ARM_RADIUS_BASE, touchDistance(markerScale) * ARM_TOUCH_MULTIPLE)

        /**
         * Route length before the loop may close, in blocks.
         *
         * Scales for the same reason [armRadius] does. The configured minimum was chosen against a 3.5-block
         * snap; leave it fixed and a big ship, which arms from 45 blocks out, could satisfy a 48-block loop
         * while still sitting almost on top of where it started, and "record a route" would produce a stub.
         */
        fun minLoopLength(markerScale: Double): Double =
            max(EurekaConfig.SERVER.pathMinLoopLength, touchDistance(markerScale) * MIN_LOOP_TOUCH_MULTIPLE)

        /** Don't call a jitter a corner: a sample must be at least this far out to count. */
        private const val CORNER_MIN_STEP = 0.5

        /** cos(5 deg) -- below this the heading has changed enough to deserve its own sample. */
        private const val CORNER_COS = 0.9961947

        /** One chunk: what the arming gate was worth before it was a radius. */
        private const val ARM_RADIUS_BASE = 16.0

        private const val ARM_TOUCH_MULTIPLE = 2.0

        /** A loop has to be several times the size of the thing that closes it to be worth calling a route. */
        private const val MIN_LOOP_TOUCH_MULTIPLE = 6.0
    }
}
