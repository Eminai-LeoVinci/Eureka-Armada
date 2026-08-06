package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One recorded route: a CLOSED loop of positions in world space that a ship can fly.
 *
 * The geometry is the whole of this class. Heading comes from the loop's own tangent, the way a
 * rollercoaster's direction comes from its track rather than from a steering input. TIMING does NOT live
 * here -- it is an optional [MotionTrack] hanging off [motion], recorded alongside the line and read only by
 * the replay half of [PathFollower]. Keeping them apart is what lets one route be flown either way: fly the
 * line and drive it yourself, or replay it exactly as recorded.
 *
 * ## Two representations
 * STORED ([control]): the RDP-decimated control points from [PathSmoothing.finish], as a flat x,y,z DoubleArray.
 * Flat primitives rather than a `List<Waypoint>` for the same reason [org.valkyrienskies.eureka.ship
 * .EurekaShipControl] flattened its cruise course into `cruiseSeatDir`/`cruiseFwd`/...: it keeps Jackson off
 * nested types entirely. Decimation is what makes a multi-kilometre loop a few KB of world save rather than
 * tens of KB.
 *
 * FOLLOWED ([dense]): the control points re-expanded through a centripetal Catmull-Rom spline and then
 * resampled to UNIFORM arc length. Uniform spacing is the whole trick behind this class being cheap -- arc
 * length `s` maps to an index by a single divide, so [sampleAt] and [tangentAt] are O(1) with no search and no
 * cumulative-length table to binary-search. The spline (rather than joining the control points with straight
 * lines) is what keeps the tangent continuous: a piecewise-linear rebuild would step the tangent at every
 * control vertex, and the follower turns that step straight into a steering twitch.
 *
 * ## Everything wraps
 * A path is always a loop (see [PathRecorder] -- an unclosed recording is discarded, never saved), so every
 * index and arc length wraps modulo the loop. That is deliberate: it means NOTHING downstream -- follower,
 * smoother, renderer -- needs a seam special case, and a ship can lap forever without a branch that fires once
 * per lap and is therefore never tested.
 */
class ShipPath(
    /** Stable id, also the colour seed so a route keeps its colour across sessions. */
    val id: Long,
    var name: String,
    /** Dimension this route was recorded in; routes are only offered in their own dimension. */
    val dimension: String,
    /** RDP-decimated control points, flat x,y,z. This is what persists. */
    val control: DoubleArray,
    /**
     * Decimated `(arc, seconds)` breakpoints, or empty for a route with no recorded timing.
     *
     * Defaulted so every existing call site is untouched, and so a route saved before timing was recorded --
     * or one recorded with `pathRecordTiming` off -- loads as geometry-only rather than needing a migration.
     */
    timeSamples: DoubleArray = EMPTY,
    /** Decimated `(arc, seconds)` pairs for the recorded pauses. Empty when there are none. */
    dwellSamples: DoubleArray = EMPTY
) {

    /** Uniform-arc-length polyline, flat x,y,z. Rebuilt from [control]; never persisted. */
    private val dense: DoubleArray

    /** Number of dense points. Point `i` sits at arc length `i * spacing`; index wraps mod this. */
    val pointCount: Int

    /** Exact spacing between dense points (close to [DENSE_SPACING], adjusted so the loop closes evenly). */
    private val spacing: Double

    /** Total loop length in blocks. */
    val length: Double

    /**
     * When this route was at each point of itself, and where it was made to wait -- or null if that was
     * never recorded.
     *
     * Null is the ordinary state for anything saved before timing was recorded, and the one thing
     * CTRL+SHIFT+P checks before it will engage.
     */
    val motion: MotionTrack?

    init {
        require(control.size >= 3 * MIN_CONTROL_POINTS) {
            "A path needs at least $MIN_CONTROL_POINTS control points, got ${control.size / 3}"
        }
        dense = buildDense(control)
        pointCount = dense.size / 3
        // Uniform by construction: buildDense chose a point count that divides the loop evenly.
        spacing = totalPolylineLength(dense, closed = true) / pointCount
        length = spacing * pointCount
        motion = if (timeSamples.size < 4) null
        else MotionTrack(timeSamples, dwellSamples, length).takeUnless { it.isEmpty }
    }

    fun x(i: Int) = dense[wrapIndex(i) * 3]
    fun y(i: Int) = dense[wrapIndex(i) * 3 + 1]
    fun z(i: Int) = dense[wrapIndex(i) * 3 + 2]

    private fun wrapIndex(i: Int): Int {
        val m = i % pointCount
        return if (m < 0) m + pointCount else m
    }

    /** Wrap an arc length into `[0, length)`. */
    fun wrapArc(s: Double): Double {
        val m = s % length
        return if (m < 0.0) m + length else m
    }

    /**
     * The point at arc length [s] (wrapped), written into [dest].
     *
     * O(1): uniform spacing turns the lookup into a divide plus a lerp between two neighbours.
     */
    fun sampleAt(s: Double, dest: Vector3d): Vector3d {
        val t = wrapArc(s) / spacing
        val i = floor(t).toInt()
        val f = t - i
        val a = wrapIndex(i)
        val b = wrapIndex(i + 1)
        dest.set(
            dense[a * 3] + (dense[b * 3] - dense[a * 3]) * f,
            dense[a * 3 + 1] + (dense[b * 3 + 1] - dense[a * 3 + 1]) * f,
            dense[a * 3 + 2] + (dense[b * 3 + 2] - dense[a * 3 + 2]) * f
        )
        return dest
    }

    /**
     * Unit direction of travel at arc length [s], written into [dest].
     *
     * Uses a CENTRED difference over [TANGENT_SPAN] blocks rather than the one segment under `s`. A single
     * 1-block segment's direction is dominated by whatever residual noise survived smoothing; averaging over a
     * few blocks gives the follower the heading a pilot would actually read off the line. Degenerate (zero
     * length) results fall back to +X so callers never normalise a zero vector.
     */
    fun tangentAt(s: Double, dest: Vector3d): Vector3d {
        val half = TANGENT_SPAN * 0.5
        // Locals, not shared scratch: this is off the hot path, and a shared buffer here would be a silent
        // trap the first time the render thread asked for a tangent.
        val ax = sampleAt(s - half, Vector3d())
        val bx = sampleAt(s + half, Vector3d())
        dest.set(bx).sub(ax)
        val len = dest.length()
        if (len < 1.0e-9) dest.set(1.0, 0.0, 0.0) else dest.div(len)
        return dest
    }

    /**
     * The tightest horizontal turn radius, in blocks, anywhere in the [span] blocks of route ahead of [s].
     *
     * Only the horizontal component counts, because this exists to bound YAW rate and a climb costs no yaw.
     * Sampled in [CURVATURE_STEP] chunks and minimised rather than averaged over the whole window: a short
     * hairpin inside an otherwise gentle sweep is exactly the case worth slowing for, and an average would
     * dissolve it.
     *
     * Returns [Double.MAX_VALUE] for a straight, which callers read as "no limit".
     */
    fun minTurnRadius(s: Double, span: Double): Double {
        if (span <= CURVATURE_STEP) return Double.MAX_VALUE

        var tightest = Double.MAX_VALUE
        val here = Vector3d()
        val next = Vector3d()
        var travelled = 0.0

        while (travelled < span) {
            val step = CURVATURE_STEP.coerceAtMost(span - travelled)
            if (step < 1.0e-6) break

            tangentAt(s + travelled, here)
            tangentAt(s + travelled + step, next)

            // Signed horizontal heading change across the step. atan2 of cross and dot, so it is already
            // wrapped to +/-pi and needs no unwrapping case at due north.
            val cross = here.x * next.z - here.z * next.x
            val dot = here.x * next.x + here.z * next.z
            val turn = abs(atan2(cross, dot))

            // radius = arc / angle. A turn too small to measure is a straight, and dividing by it would
            // manufacture a radius from floating-point noise.
            if (turn > MIN_MEASURABLE_TURN) tightest = min(tightest, step / turn)

            travelled += step
        }

        return tightest
    }

    /**
     * The steepest slope anywhere in the [span] blocks of route ahead of [s], as the vertical component of a
     * unit tangent -- so 0 is level, +1 straight up and -1 straight down.
     *
     * The vertical twin of [minTurnRadius], and it exists for the same reason: a hull has a maximum climb and
     * descent rate, so a slope steeper than it can hold at its current speed is not a steering error but a
     * request for something the ship cannot do. Signed rather than absolute, because ships climb and descend
     * at different rates and the caller has to know which limit applies.
     *
     * Takes the largest MAGNITUDE in the window rather than an average, exactly as [minTurnRadius] takes the
     * tightest radius: a short sharp dive inside an otherwise level leg is precisely the case worth slowing
     * for, and an average would dissolve it.
     */
    fun steepestGrade(s: Double, span: Double): Double {
        var worst = 0.0
        val probe = Vector3d()
        var travelled = 0.0

        while (travelled <= span) {
            val grade = tangentAt(s + travelled, probe).y
            if (abs(grade) > abs(worst)) worst = grade
            travelled += CURVATURE_STEP
        }

        return worst
    }

    /**
     * Arc length of the point on the loop closest to [pos].
     *
     * [hint] is the previous tick's answer; the search only covers [SEARCH_WINDOW] blocks either side of it,
     * which does two jobs. It keeps per-tick cost independent of route length, and -- the reason it matters --
     * it stops a route that crosses itself (a figure-eight, or a loop that doubles back through a harbour)
     * from snapping the ship onto the wrong strand and skipping half a lap. Pass a negative [hint] to search
     * the whole loop, which is what engaging does since there is no previous answer to trust.
     */
    fun nearestArcLength(pos: Vector3dc, hint: Double): Double {
        val fullSearch = hint < 0.0
        val centreIdx = if (fullSearch) 0 else (wrapArc(hint) / spacing).toInt()
        val half = if (fullSearch) pointCount / 2 + 1 else ceil(SEARCH_WINDOW / spacing).toInt()

        var bestDistSq = Double.MAX_VALUE
        var bestArc = 0.0

        // Walk the candidate segments; project onto each and keep the closest projection.
        var i = centreIdx - half
        val end = centreIdx + half
        while (i <= end) {
            val a = wrapIndex(i)
            val b = wrapIndex(i + 1)
            val ax = dense[a * 3]; val ay = dense[a * 3 + 1]; val az = dense[a * 3 + 2]
            val abx = dense[b * 3] - ax; val aby = dense[b * 3 + 1] - ay; val abz = dense[b * 3 + 2] - az

            val abLenSq = abx * abx + aby * aby + abz * abz
            val t = if (abLenSq < 1.0e-12) 0.0 else
                (((pos.x() - ax) * abx + (pos.y() - ay) * aby + (pos.z() - az) * abz) / abLenSq)
                    .coerceIn(0.0, 1.0)

            val dx = pos.x() - (ax + abx * t)
            val dy = pos.y() - (ay + aby * t)
            val dz = pos.z() - (az + abz * t)
            val distSq = dx * dx + dy * dy + dz * dz

            if (distSq < bestDistSq) {
                bestDistSq = distSq
                // `a` is the wrapped index, so its arc length is a*spacing regardless of how far i ran.
                bestArc = (a + t) * spacing
            }
            i++
        }
        return wrapArc(bestArc)
    }

    /** Straight-line distance from [pos] to the closest point on the loop. Full search; engage-time only. */
    fun distanceTo(pos: Vector3dc): Double {
        val s = nearestArcLength(pos, -1.0)
        val p = sampleAt(s, Vector3d())
        return p.distance(pos.x(), pos.y(), pos.z())
    }

    /** Flat x,y,z copy of the dense polyline, for the renderer's line strip. */
    fun denseCopy(): DoubleArray = dense.copyOf()

    companion object {
        /** Target spacing of the followed polyline. 1 block is far finer than any ship can track. */
        const val DENSE_SPACING = 1.0

        /** Arc length the centred tangent difference spans. */
        private const val TANGENT_SPAN = 4.0

        /** How far either side of the hint [nearestArcLength] looks. */
        private const val SEARCH_WINDOW = 32.0

        /** Arc length each curvature probe spans in [minTurnRadius]. */
        private const val CURVATURE_STEP = 4.0

        /** Heading change (radians) below which a probe counts as straight rather than a very wide arc. */
        private const val MIN_MEASURABLE_TURN = 1.0e-4

        /** Below this a "loop" is a degenerate blob that no smoothing or follower can make sense of. */
        const val MIN_CONTROL_POINTS = 4

        /** Shared "no speed was recorded", so the default argument allocates nothing per route. */
        val EMPTY = DoubleArray(0)

        /**
         * Expand [control] into a uniform-arc-length closed polyline.
         *
         * Two passes: evaluate the centripetal Catmull-Rom spline finely, then resample that fine polyline to
         * uniform spacing. Doing it in two passes rather than trying to march the spline at constant arc
         * length keeps both halves simple -- the spline evaluation does not need to know about arc length, and
         * the resampler does not need to know about splines.
         */
        private fun buildDense(control: DoubleArray): DoubleArray {
            val n = control.size / 3

            // --- Pass 1: fine spline evaluation ---
            val fine = ArrayList<Double>(n * 3 * 8)
            val p0 = Vector3d(); val p1 = Vector3d(); val p2 = Vector3d(); val p3 = Vector3d()
            val out = Vector3d()
            for (i in 0 until n) {
                // Closed loop, so the neighbours wrap.
                readInto(control, (i - 1 + n) % n, p0)
                readInto(control, i, p1)
                readInto(control, (i + 1) % n, p2)
                readInto(control, (i + 2) % n, p3)

                // Subdivide proportionally to segment length so long straights don't get needlessly dense and
                // tight corners still get enough samples to resolve the curve.
                val segLen = p1.distance(p2)
                val steps = max(2, ceil(segLen / DENSE_SPACING).toInt() * 2)
                for (step in 0 until steps) {
                    catmullRom(p0, p1, p2, p3, step.toDouble() / steps, out)
                    fine.add(out.x); fine.add(out.y); fine.add(out.z)
                }
            }
            val fineArr = DoubleArray(fine.size) { fine[it] }

            // --- Pass 2: uniform arc-length resample ---
            val total = totalPolylineLength(fineArr, closed = true)
            // Round to a whole number of points so the last point's step back to the first is the same length
            // as every other step. Without this the seam segment is a different length and the follower sees a
            // one-tick speed/tangent glitch every lap.
            val count = max(MIN_CONTROL_POINTS, (total / DENSE_SPACING).roundToInt())
            return resampleUniform(fineArr, count)
        }

        private fun readInto(arr: DoubleArray, i: Int, dest: Vector3d) {
            dest.set(arr[i * 3], arr[i * 3 + 1], arr[i * 3 + 2])
        }

        /**
         * Centripetal Catmull-Rom (alpha = 0.5) through [p1]..[p2], with [p0]/[p3] as the neighbouring
         * control points that set the tangents. [t] runs 0..1 across the segment.
         *
         * Centripetal rather than uniform parameterisation because RDP leaves control points unevenly spaced
         * -- a short segment next to a long one -- and uniform Catmull-Rom overshoots into a loop or cusp on
         * exactly that pattern. Centripetal is the standard guarantee against it.
         */
        private fun catmullRom(p0: Vector3dc, p1: Vector3dc, p2: Vector3dc, p3: Vector3dc, t: Double, dest: Vector3d) {
            val t0 = 0.0
            val t1 = t0 + knotDelta(p0, p1)
            val t2 = t1 + knotDelta(p1, p2)
            val t3 = t2 + knotDelta(p2, p3)
            val tt = t1 + (t2 - t1) * t

            // Barry-Goldman pyramid. Every denominator is >= KNOT_EPS by knotDelta's floor.
            val a1x = lerp(p0.x(), p1.x(), (tt - t0) / (t1 - t0))
            val a1y = lerp(p0.y(), p1.y(), (tt - t0) / (t1 - t0))
            val a1z = lerp(p0.z(), p1.z(), (tt - t0) / (t1 - t0))

            val a2x = lerp(p1.x(), p2.x(), (tt - t1) / (t2 - t1))
            val a2y = lerp(p1.y(), p2.y(), (tt - t1) / (t2 - t1))
            val a2z = lerp(p1.z(), p2.z(), (tt - t1) / (t2 - t1))

            val a3x = lerp(p2.x(), p3.x(), (tt - t2) / (t3 - t2))
            val a3y = lerp(p2.y(), p3.y(), (tt - t2) / (t3 - t2))
            val a3z = lerp(p2.z(), p3.z(), (tt - t2) / (t3 - t2))

            val b1x = lerp(a1x, a2x, (tt - t0) / (t2 - t0))
            val b1y = lerp(a1y, a2y, (tt - t0) / (t2 - t0))
            val b1z = lerp(a1z, a2z, (tt - t0) / (t2 - t0))

            val b2x = lerp(a2x, a3x, (tt - t1) / (t3 - t1))
            val b2y = lerp(a2y, a3y, (tt - t1) / (t3 - t1))
            val b2z = lerp(a2z, a3z, (tt - t1) / (t3 - t1))

            val u = (tt - t1) / (t2 - t1)
            dest.set(lerp(b1x, b2x, u), lerp(b1y, b2y, u), lerp(b1z, b2z, u))
        }

        /** Knot spacing for centripetal parameterisation, floored so coincident points can't divide by zero. */
        private fun knotDelta(a: Vector3dc, b: Vector3dc): Double {
            val dx = b.x() - a.x(); val dy = b.y() - a.y(); val dz = b.z() - a.z()
            return max(KNOT_EPS, sqrt(sqrt(dx * dx + dy * dy + dz * dz)))
        }

        private const val KNOT_EPS = 1.0e-4

        private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

        /** Total length of a flat x,y,z polyline, including the closing segment when [closed]. */
        fun totalPolylineLength(arr: DoubleArray, closed: Boolean): Double {
            val n = arr.size / 3
            if (n < 2) return 0.0
            var total = 0.0
            val last = if (closed) n - 1 else n - 2
            for (i in 0..last) {
                val a = i; val b = (i + 1) % n
                val dx = arr[b * 3] - arr[a * 3]
                val dy = arr[b * 3 + 1] - arr[a * 3 + 1]
                val dz = arr[b * 3 + 2] - arr[a * 3 + 2]
                total += sqrt(dx * dx + dy * dy + dz * dz)
            }
            return total
        }

        /**
         * Resample a CLOSED flat polyline to exactly [count] evenly-spaced points.
         *
         * Shared with [PathSmoothing], which resamples before filtering so its window is a real distance
         * rather than a point count.
         */
        fun resampleUniform(arr: DoubleArray, count: Int): DoubleArray {
            val n = arr.size / 3
            val total = totalPolylineLength(arr, closed = true)
            val step = total / count
            val out = DoubleArray(count * 3)

            var seg = 0
            var segStart = 0.0
            var segLen = segmentLength(arr, 0, n)
            for (k in 0 until count) {
                val target = k * step
                // Advance to the segment containing `target`. Guarded against running off the end by float
                // drift on the final point.
                while (seg < n - 1 && target > segStart + segLen) {
                    segStart += segLen
                    seg++
                    segLen = segmentLength(arr, seg, n)
                }
                val f = if (segLen < 1.0e-12) 0.0 else ((target - segStart) / segLen).coerceIn(0.0, 1.0)
                val a = seg
                val b = (seg + 1) % n
                out[k * 3] = arr[a * 3] + (arr[b * 3] - arr[a * 3]) * f
                out[k * 3 + 1] = arr[a * 3 + 1] + (arr[b * 3 + 1] - arr[a * 3 + 1]) * f
                out[k * 3 + 2] = arr[a * 3 + 2] + (arr[b * 3 + 2] - arr[a * 3 + 2]) * f
            }
            return out
        }

        private fun segmentLength(arr: DoubleArray, i: Int, n: Int): Double {
            val a = i; val b = (i + 1) % n
            val dx = arr[b * 3] - arr[a * 3]
            val dy = arr[b * 3 + 1] - arr[a * 3 + 1]
            val dz = arr[b * 3 + 2] - arr[a * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
