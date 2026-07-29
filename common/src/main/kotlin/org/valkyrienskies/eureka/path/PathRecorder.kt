package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaConfig
import java.util.UUID
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
class PathRecorder(val shipId: Long, val playerId: UUID, start: Vector3dc) {

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

        // Same chunk column as the start, and far enough round to be a loop. The length gate matters because
        // the ship BEGINS inside the snap radius -- without it every recording would close instantly.
        armed = length >= cfg.pathMinLoopLength &&
            floorChunk(keel.x()) == floorChunk(start.x) &&
            floorChunk(keel.z()) == floorChunk(start.z)

        if (armed && gap <= cfg.pathSnapRadius + SHIP_MARKER_RADIUS) {
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
        /** Radius of the sphere drawn at the ship's keel; half of what makes the snap gap. */
        const val SHIP_MARKER_RADIUS = 1.5

        /** Don't call a jitter a corner: a sample must be at least this far out to count. */
        private const val CORNER_MIN_STEP = 0.5

        /** cos(5 deg) -- below this the heading has changed enough to deserve its own sample. */
        private const val CORNER_COS = 0.9961947

        private fun floorChunk(v: Double): Int = Math.floorDiv(Math.floor(v).toInt(), 16)
    }
}
