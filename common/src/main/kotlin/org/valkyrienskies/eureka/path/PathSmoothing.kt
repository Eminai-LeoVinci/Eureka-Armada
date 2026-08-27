package org.valkyrienskies.eureka.path

import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a raw recording into a finished, followable loop.
 *
 * ## The problem this solves
 * A pilot steering a ship by hand does not draw a clean line. Holding a heading means tapping left, drifting
 * right, tapping back -- so a stretch the pilot experienced as "going straight" arrives here as fifteen small
 * left-right waves. Handed to the follower unfiltered, the ship would dutifully re-fly every one of those
 * corrections, and worse, chase them a lookahead-distance late, which is how a follower turns a wobble into an
 * oscillation that grows.
 *
 * What we want is the line the pilot MEANT: those fifteen waves collapsed into one long strand with maybe a
 * subtle wave left in it, while a deliberate sweeping turn around a headland survives exactly as flown.
 *
 * ## Why a curvature-weighted low-pass
 * The two are separable in the frequency domain, which is the whole idea. Correction jitter is short-wavelength
 * (a few blocks, set by how fast a pilot notices and reacts); intended course changes are long-wavelength (tens
 * to hundreds of blocks, set by the terrain). A Gaussian whose sigma is specified in ARC LENGTH -- not in
 * points -- cuts cleanly between them: at sigma = 8 blocks it all but annihilates anything under ~16 blocks
 * and leaves a 200-block curve untouched.
 *
 * Specifying sigma in arc length is why [resampleUniform] has to run first. Recorded samples cluster where the
 * ship was slow and spread where it was fast, so a fixed point-count window would filter a slow harbour
 * manoeuvre far harder than a fast open-water leg -- the filter's strength would depend on throttle, which is
 * absurd.
 *
 * The curvature WEIGHTING then buys the last thing a plain low-pass would cost us: a plain Gaussian rounds off
 * genuine sharp corners too. Weighting each point's filtering by how jagged its neighbourhood actually is means
 * straights and clean curves are left bit-for-bit alone (w = 0) and only the wobbly stretches get pulled.
 */
object PathSmoothing {

    /**
     * Raw recording -> RDP-decimated control points ready for [ShipPath], or null if the recording is too
     * short or degenerate to make a loop from.
     *
     * [raw] is the open recorded polyline: `raw[0]` is the start point the loop snapped back to, and the last
     * sample is wherever the ship was when it snapped.
     */
    fun finish(
        raw: DoubleArray,
        seamBlend: Double,
        smoothWindow: Double,
        iterations: Int,
        jagThresholdDeg: Double,
        maxDeviation: Double,
        rdpEpsilon: Double
    ): DoubleArray? {
        if (raw.size / 3 < 8) return null

        val closed = blendSeam(raw, seamBlend) ?: return null

        val total = ShipPath.totalPolylineLength(closed, closed = true)
        if (total < MIN_LOOP_LENGTH) return null

        val count = max(ShipPath.MIN_CONTROL_POINTS, (total / ShipPath.DENSE_SPACING).roundToInt())
        val uniform = ShipPath.resampleUniform(closed, count)

        val smoothed = lowPass(uniform, smoothWindow, iterations, jagThresholdDeg, maxDeviation)

        val decimated = rdpClosed(smoothed, rdpEpsilon)
        if (decimated.size / 3 < ShipPath.MIN_CONTROL_POINTS) return null
        return decimated
    }

    // region seam

    /**
     * Replace the tail of an open recording with a curve that runs into the start point along the start's own
     * outgoing direction, producing a closed loop with no kink at the join.
     *
     * Without this the loop closes with whatever heading the ship happened to have when it tripped the snap
     * sphere, which is essentially never the heading it set off with. A follower crossing that join gets a
     * heading step, and since the ship laps forever it gets it once per lap -- the most visible possible defect
     * and the least likely to be noticed while testing a single pass.
     *
     * The blend spans [seamBlend] blocks (about a chunk), so the correction is spread over a distance rather
     * than concentrated at a point.
     */
    private fun blendSeam(raw: DoubleArray, seamBlend: Double): DoubleArray? {
        val n = raw.size / 3
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + dist(raw, i - 1, i)
        val total = cum[n - 1]

        // Need room for the blend plus enough loop left over to still be a loop.
        val blend = min(seamBlend, total * 0.25)
        if (blend < 1.0) return null

        // Truncate at (total - blend).
        val cutTarget = total - blend
        var cut = n - 1
        while (cut > 0 && cum[cut] > cutTarget) cut--
        if (cut < 4) return null

        // Incoming direction at the cut, and the start's outgoing direction -- both averaged over a few
        // samples so a single noisy sample can't set the seam's shape.
        val backIdx = max(0, cut - TANGENT_SAMPLES)
        val m0 = dir(raw, backIdx, cut)
        val fwdIdx = min(n - 1, TANGENT_SAMPLES)
        val m1 = dir(raw, 0, fwdIdx)

        val px = raw[cut * 3]; val py = raw[cut * 3 + 1]; val pz = raw[cut * 3 + 2]
        val qx = raw[0]; val qy = raw[1]; val qz = raw[2]

        // Hermite tangent magnitudes scale with the gap, which is what keeps the blend's curvature sane
        // whether the gap is 4 blocks or 40.
        val chord = sqrt(sq(qx - px) + sq(qy - py) + sq(qz - pz))
        val scale = max(chord, 1.0)

        val out = ArrayList<Double>((cut + 1) * 3 + 64)
        for (i in 0..cut) { out.add(raw[i * 3]); out.add(raw[i * 3 + 1]); out.add(raw[i * 3 + 2]) }

        val steps = max(2, ceil(max(chord, blend) / ShipPath.DENSE_SPACING).toInt())
        // Skip t = 0 (that is the cut point, already added) and t = 1 (that is raw[0], which the closed
        // polyline reaches by wrapping).
        for (step in 1 until steps) {
            val t = step.toDouble() / steps
            val h00 = 2 * t * t * t - 3 * t * t + 1
            val h10 = t * t * t - 2 * t * t + t
            val h01 = -2 * t * t * t + 3 * t * t
            val h11 = t * t * t - t * t
            out.add(h00 * px + h10 * m0[0] * scale + h01 * qx + h11 * m1[0] * scale)
            out.add(h00 * py + h10 * m0[1] * scale + h01 * qy + h11 * m1[1] * scale)
            out.add(h00 * pz + h10 * m0[2] * scale + h01 * qz + h11 * m1[2] * scale)
        }

        return DoubleArray(out.size) { out[it] }
    }

    private const val TANGENT_SAMPLES = 6

    // endregion

    // region low-pass

    /**
     * Curvature-weighted Gaussian low-pass over a CLOSED uniform polyline.
     *
     * Wrapping (rather than pinning endpoints) is what makes the seam invisible: index 0 is not special, so
     * the filter cannot leave a discontinuity there.
     */
    private fun lowPass(
        uniform: DoubleArray,
        smoothWindow: Double,
        iterations: Int,
        jagThresholdDeg: Double,
        maxDeviation: Double
    ): DoubleArray {
        val n = uniform.size / 3
        if (n < 8) return uniform

        val original = uniform.copyOf()
        var cur = uniform.copyOf()

        // Sigma is given in blocks; uniform spacing means blocks and points are interchangeable here.
        val sigma = max(1.0, smoothWindow / ShipPath.DENSE_SPACING)
        val radius = min(n / 2 - 1, ceil(sigma * 3.0).toInt())
        if (radius < 1) return cur

        val kernel = DoubleArray(radius * 2 + 1)
        var kSum = 0.0
        for (k in -radius..radius) {
            val w = exp(-(k * k) / (2.0 * sigma * sigma))
            kernel[k + radius] = w
            kSum += w
        }
        for (k in kernel.indices) kernel[k] /= kSum

        val jagThreshold = Math.toRadians(jagThresholdDeg).coerceAtLeast(1.0e-4)

        repeat(max(1, iterations)) {
            val weights = jaggednessWeights(cur, n, jagThreshold)
            val next = DoubleArray(cur.size)
            for (i in 0 until n) {
                var sx = 0.0; var sy = 0.0; var sz = 0.0
                for (k in -radius..radius) {
                    val j = wrap(i + k, n)
                    val w = kernel[k + radius]
                    sx += cur[j * 3] * w
                    sy += cur[j * 3 + 1] * w
                    sz += cur[j * 3 + 2] * w
                }
                // w = 0 leaves the point EXACTLY as it was -- straights and clean curves are untouched.
                val w = weights[i]
                next[i * 3] = cur[i * 3] + (sx - cur[i * 3]) * w
                next[i * 3 + 1] = cur[i * 3 + 1] + (sy - cur[i * 3 + 1]) * w
                next[i * 3 + 2] = cur[i * 3 + 2] + (sz - cur[i * 3 + 2]) * w
            }
            clampDeviation(next, original, maxDeviation)
            cur = next
        }
        return cur
    }

    /**
     * Per-point filtering weight in 0..1, from how sharply the line zig-zags locally.
     *
     * Jaggedness is the MEAN absolute turn angle between consecutive segments over a small window. Mean rather
     * than max because a single sharp corner is usually intentional (rounding a rock), whereas a run of
     * alternating turns is the signature of hand-correction -- which is exactly what we are trying to catch.
     *
     * The resulting weights are then blurred. An abrupt jump from "filtered" to "unfiltered" would itself put
     * a kink in the line at the boundary, trading one artefact for another.
     */
    private fun jaggednessWeights(arr: DoubleArray, n: Int, jagThreshold: Double): DoubleArray {
        val jag = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            for (k in -JAG_WINDOW until JAG_WINDOW) {
                val a = wrap(i + k, n)
                val b = wrap(i + k + 1, n)
                val c = wrap(i + k + 2, n)
                sum += turnAngle(arr, a, b, c)
            }
            jag[i] = sum / (JAG_WINDOW * 2)
        }

        val raw = DoubleArray(n) { (jag[it] / jagThreshold).coerceIn(0.0, 1.0) }

        // Box-blur the weights so the filtered/unfiltered transition is gradual.
        val smooth = DoubleArray(n)
        val r = JAG_WINDOW * 2
        for (i in 0 until n) {
            var s = 0.0
            for (k in -r..r) s += raw[wrap(i + k, n)]
            smooth[i] = s / (2 * r + 1)
        }
        return smooth
    }

    private const val JAG_WINDOW = 3

    /** Angle between segments a->b and b->c, in radians. 0 for a straight run. */
    private fun turnAngle(arr: DoubleArray, a: Int, b: Int, c: Int): Double {
        val ux = arr[b * 3] - arr[a * 3]
        val uy = arr[b * 3 + 1] - arr[a * 3 + 1]
        val uz = arr[b * 3 + 2] - arr[a * 3 + 2]
        val vx = arr[c * 3] - arr[b * 3]
        val vy = arr[c * 3 + 1] - arr[b * 3 + 1]
        val vz = arr[c * 3 + 2] - arr[b * 3 + 2]
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        if (ul < 1.0e-9 || vl < 1.0e-9) return 0.0
        val cos = ((ux * vx + uy * vy + uz * vz) / (ul * vl)).coerceIn(-1.0, 1.0)
        return acos(cos)
    }

    /**
     * Hard-limit how far smoothing may move any point from where it was actually recorded.
     *
     * The pilot steered around whatever was there. A filter has no idea a cliff exists, and a hairpin is
     * exactly the shape a low-pass most wants to cut the corner off -- straight into the thing being avoided.
     * Clamping against the ORIGINAL positions (not the previous iteration's) bounds total travel no matter how
     * many iterations run.
     */
    private fun clampDeviation(arr: DoubleArray, original: DoubleArray, maxDeviation: Double) {
        if (maxDeviation <= 0.0) return
        val n = arr.size / 3
        val maxSq = maxDeviation * maxDeviation
        for (i in 0 until n) {
            val dx = arr[i * 3] - original[i * 3]
            val dy = arr[i * 3 + 1] - original[i * 3 + 1]
            val dz = arr[i * 3 + 2] - original[i * 3 + 2]
            val dSq = dx * dx + dy * dy + dz * dz
            if (dSq <= maxSq) continue
            val scale = maxDeviation / sqrt(dSq)
            arr[i * 3] = original[i * 3] + dx * scale
            arr[i * 3 + 1] = original[i * 3 + 1] + dy * scale
            arr[i * 3 + 2] = original[i * 3 + 2] + dz * scale
        }
    }

    // endregion

    // region decimation

    /**
     * Ramer-Douglas-Peucker on a closed polyline, for STORAGE only.
     *
     * A closed curve has no endpoints to anchor the recursion, so split it at two far-apart points first --
     * point 0 and the point furthest from it -- and simplify the two open halves. Any split works; this one
     * just avoids a degenerate near-zero-length half.
     *
     * [ShipPath] rebuilds the dense curve through a spline, so the epsilon here is a storage/fidelity knob, not
     * a steering one. At 0.25 blocks the reconstructed curve is far inside the accuracy any ship can track.
     */
    private fun rdpClosed(arr: DoubleArray, epsilon: Double): DoubleArray {
        val n = arr.size / 3
        if (n <= ShipPath.MIN_CONTROL_POINTS) return arr.copyOf()

        var far = 0
        var farDist = -1.0
        for (i in 1 until n) {
            val d = sq(arr[i * 3] - arr[0]) + sq(arr[i * 3 + 1] - arr[1]) + sq(arr[i * 3 + 2] - arr[2])
            if (d > farDist) { farDist = d; far = i }
        }
        if (far < 2 || far > n - 2) return arr.copyOf()

        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        rdp(arr, 0, far, epsilon, keep)
        rdp(arr, far, n - 1, epsilon, keep)
        // The closing segment runs n-1 -> 0; keeping the last point stops RDP from cutting across the seam.
        keep[n - 1] = true

        val out = ArrayList<Double>(n)
        for (i in 0 until n) if (keep[i]) { out.add(arr[i * 3]); out.add(arr[i * 3 + 1]); out.add(arr[i * 3 + 2]) }
        return DoubleArray(out.size) { out[it] }
    }

    private fun rdp(arr: DoubleArray, first: Int, last: Int, epsilon: Double, keep: BooleanArray) {
        if (last <= first + 1) return
        var maxDist = -1.0
        var index = first
        for (i in first + 1 until last) {
            val d = perpendicularDistance(arr, i, first, last)
            if (d > maxDist) { maxDist = d; index = i }
        }
        if (maxDist > epsilon) {
            keep[index] = true
            rdp(arr, first, index, epsilon, keep)
            rdp(arr, index, last, epsilon, keep)
        }
    }

    private fun perpendicularDistance(arr: DoubleArray, i: Int, a: Int, b: Int): Double {
        val ax = arr[a * 3]; val ay = arr[a * 3 + 1]; val az = arr[a * 3 + 2]
        val abx = arr[b * 3] - ax; val aby = arr[b * 3 + 1] - ay; val abz = arr[b * 3 + 2] - az
        val apx = arr[i * 3] - ax; val apy = arr[i * 3 + 1] - ay; val apz = arr[i * 3 + 2] - az
        val abLenSq = abx * abx + aby * aby + abz * abz
        if (abLenSq < 1.0e-12) return sqrt(apx * apx + apy * apy + apz * apz)
        val t = ((apx * abx + apy * aby + apz * abz) / abLenSq).coerceIn(0.0, 1.0)
        val dx = apx - abx * t; val dy = apy - aby * t; val dz = apz - abz * t
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // endregion

    // region helpers

    /** Shortest loop we will accept. Below this the "loop" is a wobble in place, not a route. */
    const val MIN_LOOP_LENGTH = 24.0

    private fun wrap(i: Int, n: Int): Int {
        val m = i % n
        return if (m < 0) m + n else m
    }

    private fun sq(v: Double) = v * v

    private fun dist(arr: DoubleArray, a: Int, b: Int) =
        sqrt(sq(arr[b * 3] - arr[a * 3]) + sq(arr[b * 3 + 1] - arr[a * 3 + 1]) + sq(arr[b * 3 + 2] - arr[a * 3 + 2]))

    /** Unit direction from sample [a] to sample [b]; +X if degenerate. */
    private fun dir(arr: DoubleArray, a: Int, b: Int): DoubleArray {
        val dx = arr[b * 3] - arr[a * 3]
        val dy = arr[b * 3 + 1] - arr[a * 3 + 1]
        val dz = arr[b * 3 + 2] - arr[a * 3 + 2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        return if (len < 1.0e-9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(dx / len, dy / len, dz / len)
    }

    // endregion
}
