package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * WHEN a route was at each point of itself, and where the ship was made to sit still -- the half of a
 * recording that [ShipPath] deliberately does not hold.
 *
 * ## Why this is a timeline and not a speed profile
 * The obvious model is `v(s)`: how fast the ship was going at each point. It was tried, and it was wrong
 * twice over.
 *
 * A speed is a REQUEST. Handing one to the hull's throttle loop -- the same closed loop the helm's typed
 * cruise speed uses -- means that loop decides the acceleration, and it reaches its target in six to eight
 * seconds where a pilot's hand takes thirty or forty. A recording made to creep off a pad between buildings
 * came back as a leap off it.
 *
 * And a speed has a direction the recording could not see. What was captured was the ship's speed along its
 * own heading, flattened horizontal -- so a vertical lift-off, the one manoeuvre the whole feature exists
 * for, recorded as approximately zero all the way up.
 *
 * A TIME is neither. It is not a request but a statement about where the ship was, it is the same number
 * whichever way the hull was moving, and playback that reads it has nothing left to decide. So the track
 * holds `t -> s`: the route's arc length as a function of the clock.
 *
 * ## The clock excludes the pauses
 * [movingSeconds] counts only the time the ship was under way; a recorded stop PAUSES the clock rather than
 * appearing in it. That is what makes a stop unmissable and unrepeatable at the same time. Arc becomes a
 * monotone function of a clock playback owns, so "have I reached the next stop yet" is a comparison between
 * two numbers that only ever increase -- where the previous design asked whether the hull's MEASURED
 * position had crossed an arc, and a held ship drifting a few centimetres backwards over that arc could
 * trip the same stop again and again.
 *
 * ## Stored versus followed
 * Exactly the split [ShipPath] already makes, for the same reasons.
 *
 * STORED ([times]): flat `(arc, seconds)` breakpoints, RDP-decimated to `pathTimeEpsilon`. A leg flown at a
 * steady pace is a straight line in this plane and collapses to its two ends, while an acceleration keeps
 * every bend of its curve.
 *
 * FOLLOWED ([denseArc]): the inverse, sampled at a uniform time step, so [arcAtTime] is one divide and a
 * lerp with no search. Rebuilt from the stored form even at the moment of recording, so a route flies
 * identically before and after a world reload.
 */
class MotionTrack(
    /** Flat `arc, seconds` breakpoints, sorted and non-decreasing in both. Persisted. */
    val times: DoubleArray,
    /** Flat `arc, seconds` pairs -- where the ship was held, and for how long. Persisted. */
    val dwells: DoubleArray,
    /** Owning route's loop length in blocks. */
    private val length: Double
) {

    /** Arc length at each step of the clock. Rebuilt from [times]; never persisted. */
    private val denseArc: DoubleArray

    /** Seconds between entries of [denseArc]. */
    private val step: Double

    /** Seconds of one lap spent MOVING -- the full range of the clock. Pauses are not in it. */
    val movingSeconds: Double

    /** The clock reading at which each pause is reached, ascending. */
    private val dwellTimes: DoubleArray

    /** How many recorded pauses this route carries. */
    val dwellCount: Int get() = dwells.size / 2

    /** Arc length of pause [i]. */
    fun dwellArc(i: Int): Double = dwells[i * 2]

    /** How long pause [i] lasts, in seconds. */
    fun dwellSeconds(i: Int): Double = dwells[i * 2 + 1]

    /** The clock reading pause [i] is reached at. */
    fun dwellTime(i: Int): Double = dwellTimes[i]

    init {
        val pairs = times.size / 2
        movingSeconds = if (pairs < 2) 0.0 else times[times.size - 1]

        step =
            if (movingSeconds <= 0.0) TIME_STEP
            else max(TIME_STEP, movingSeconds / MAX_DENSE)

        denseArc = invert()
        // Pauses are stored sorted by arc, and the clock is non-decreasing in arc, so these come out
        // ascending without a sort -- which is what lets playback walk them with a single index.
        dwellTimes = DoubleArray(dwellCount) { timeAtArc(dwellArc(it)) }
    }

    /** True for a track that carries no usable timeline, so playback must refuse it. */
    val isEmpty: Boolean get() = movingSeconds <= 0.0 || denseArc.size < 2

    /**
     * Where along the route the recording was at clock reading [t], in blocks of arc length.
     *
     * O(1), and wraps: the clock runs `[0, movingSeconds)` round and round, one lap per pass.
     */
    fun arcAtTime(t: Double): Double {
        if (denseArc.isEmpty()) return 0.0
        val x = wrapTime(t) / step
        val i = floor(x).toInt()
        if (i >= denseArc.size - 1) return denseArc[denseArc.size - 1]
        val f = x - i
        return denseArc[i] + (denseArc[i + 1] - denseArc[i]) * f
    }

    /**
     * How fast the recording was covering the route at clock reading [t], in blocks of arc per second --
     * the speed ALONG the line, which is the quantity the servo's velocity feed-forward needs.
     *
     * A central difference rather than a stored derivative: the timeline is already decimated to the
     * tolerance a player asked for, and differentiating it on demand cannot disagree with [arcAtTime].
     * The seam needs the wrap taken out, or one step either side of arc 0 would read as a whole lap
     * backwards.
     */
    fun rateAtTime(t: Double): Double {
        if (isEmpty) return 0.0
        var d = arcAtTime(t + step) - arcAtTime(t - step)
        if (d < -length * 0.5) d += length else if (d > length * 0.5) d -= length
        return d / (2.0 * step)
    }

    /**
     * The clock reading at arc length [arc] -- the inverse of [arcAtTime], read off the stored breakpoints.
     *
     * A binary search rather than a table, because this is called once when a ship joins a route (to start
     * its clock where the hull already is) and once per pause when the track is built. Nothing per tick.
     */
    fun timeAtArc(arc: Double): Double {
        val pairs = times.size / 2
        if (pairs == 0) return 0.0
        if (pairs == 1) return times[1]

        val a = wrapArc(arc)
        if (a <= times[0]) return times[1]
        if (a >= times[(pairs - 1) * 2]) return times[(pairs - 1) * 2 + 1]

        var lo = 0
        var hi = pairs - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid * 2] <= a) lo = mid else hi = mid
        }

        val aArc = times[lo * 2]
        val aTime = times[lo * 2 + 1]
        val bArc = times[hi * 2]
        val bTime = times[hi * 2 + 1]
        val span = bArc - aArc
        return if (span <= 1.0e-9) aTime else aTime + (bTime - aTime) * ((a - aArc) / span)
    }

    /** Seconds one lap takes as recorded: the time spent moving, plus every pause. */
    val lapSeconds: Double by lazy {
        var total = movingSeconds
        for (i in 0 until dwellCount) total += dwellSeconds(i)
        total
    }

    /** Fastest the route was covered anywhere, in blocks per second. */
    val topSpeed: Double by lazy {
        var top = 0.0
        for (i in 0 until denseArc.size - 1) {
            val v = (denseArc[i + 1] - denseArc[i]) / step
            if (v > top) top = v
        }
        top
    }

    /** Mean pace over the moving part of a lap, blocks per second. */
    val averageSpeed: Double get() = if (movingSeconds <= 0.0) 0.0 else length / movingSeconds

    private fun wrapTime(t: Double): Double {
        if (movingSeconds <= 0.0) return 0.0
        val m = t % movingSeconds
        return if (m < 0.0) m + movingSeconds else m
    }

    private fun wrapArc(s: Double): Double {
        val m = s % length
        return if (m < 0.0) m + length else m
    }

    /**
     * Turn the stored `arc -> time` breakpoints into `time -> arc` at a uniform step.
     *
     * Both sides are non-decreasing (enforced in [build]), so this is one forward walk: for each clock step,
     * advance through the breakpoints until the one that brackets it and interpolate. The last entry is
     * pinned to the loop length so a lap always closes exactly where it started.
     */
    private fun invert(): DoubleArray {
        val pairs = times.size / 2
        if (pairs < 2 || movingSeconds <= 0.0) return DoubleArray(0)

        val count = ceil(movingSeconds / step).toInt() + 1
        val out = DoubleArray(count)
        var seg = 0
        for (k in 0 until count) {
            val t = min(k * step, movingSeconds)
            while (seg < pairs - 2 && times[(seg + 1) * 2 + 1] < t) seg++

            val aArc = times[seg * 2]
            val aTime = times[seg * 2 + 1]
            val bArc = times[(seg + 1) * 2]
            val bTime = times[(seg + 1) * 2 + 1]

            val span = bTime - aTime
            val f = if (span <= 1.0e-9) 0.0 else ((t - aTime) / span).coerceIn(0.0, 1.0)
            out[k] = aArc + (bArc - aArc) * f
        }
        out[count - 1] = length
        return out
    }

    companion object {

        /** Seconds between entries of the followed timeline -- one game tick. */
        private const val TIME_STEP = 0.05

        /** Ceiling on that timeline's length, so a very long route widens its step instead of its array. */
        private const val MAX_DENSE = 8192

        /**
         * Turn a raw recording into a stored timeline, measured against the finished route [bare].
         *
         * ## Why this maps by POSITION
         * A raw sample's index means nothing in the finished route. [PathSmoothing.finish] blends the seam
         * (discarding the tail outright), resamples, low-passes and decimates; then [ShipPath] re-expands
         * through a spline and resamples again. Trying to carry an index through all that would be a running
         * bookkeeping problem with a silent failure mode.
         *
         * Asking the finished route where each raw sample LANDS sidesteps every stage at once. The marching
         * [hint][ShipPath.nearestArcLength] is what makes it safe on a route that crosses itself -- a
         * figure-eight, or a loop doubled back through a harbour -- where a position alone has two answers
         * and only the one near the last sample is right.
         *
         * ## Unwrapping
         * That lookup answers in `[0, length)`, so a recording read straight off it jumps from the end of the
         * loop back to the start. The samples are therefore unwrapped into one rising ramp before anything
         * else touches them -- a timeline that folds at the seam is not a function.
         *
         * @param rawPoints flat x,y,z of the raw recording
         * @param rawTimes one MOVING elapsed second per raw point (pauses already banked out)
         * @param rawDwells flat x,y,z,seconds -- a pause's position and how long it lasted
         */
        fun build(
            bare: ShipPath,
            rawPoints: DoubleArray,
            rawTimes: DoubleArray,
            rawDwells: DoubleArray,
            epsilon: Double
        ): MotionTrack? {
            val n = min(rawPoints.size / 3, rawTimes.size)
            if (n < 2) return null

            val length = bare.length
            if (length <= 0.0) return null

            val arcs = DoubleArray(n)
            val secs = DoubleArray(n)
            var kept = 0

            val pos = Vector3d()
            var hint = -1.0
            var running = 0.0
            var previous = 0.0

            for (i in 0 until n) {
                pos.set(rawPoints[i * 3], rawPoints[i * 3 + 1], rawPoints[i * 3 + 2])
                val here = bare.nearestArcLength(pos, hint)
                hint = here

                if (i == 0) {
                    // The seam blend puts the recording's first sample at roughly arc zero, but "roughly" can
                    // fall either side of it -- so a first reading just under the loop length is a hair BEFORE
                    // the start, not a lap ahead of it.
                    running = if (here > length * 0.5) here - length else here
                } else {
                    var d = here - previous
                    if (d > length * 0.5) d -= length else if (d < -length * 0.5) d += length
                    running += d
                }
                previous = here

                // Everything past the seam is the tail the blend discarded; it maps back onto the start of the
                // loop and would fold the timeline over itself.
                if (kept > 0 && running >= length) break

                // Keep only what moves the story forward. A sample that reads backwards along the route, or
                // no later than the one before it, carries no timing a monotone inverse can use.
                val t = rawTimes[i]
                if (kept == 0) {
                    arcs[0] = max(running, 0.0)
                    secs[0] = t
                    kept = 1
                } else if (running > arcs[kept - 1] && t > secs[kept - 1]) {
                    arcs[kept] = running
                    secs[kept] = t
                    kept++
                }
            }

            if (kept < 2) return null

            // Pin the start, and carry the last measured pace on to the seam. The tail dropped above is real
            // route the ship flew, so ending the clock at the last surviving sample would quietly shorten
            // every lap by the length of the blend.
            val t0 = secs[0]
            for (i in 0 until kept) secs[i] -= t0
            arcs[0] = 0.0
            secs[0] = 0.0

            val lastArc = arcs[kept - 1]
            val remaining = length - lastArc
            if (remaining > 1.0e-6 && kept < n) {
                val spanArc = lastArc - arcs[kept - 2]
                val spanTime = secs[kept - 1] - secs[kept - 2]
                val rate = if (spanTime > 1.0e-9) spanArc / spanTime else 0.0
                arcs[kept] = length
                secs[kept] = secs[kept - 1] + if (rate > 1.0e-6) remaining / rate else 0.0
                kept++
            } else {
                arcs[kept - 1] = length
            }

            if (secs[kept - 1] <= 0.0) return null

            val stored = decimate(arcs, secs, kept, max(epsilon, 1.0e-3))

            // Dwells map the same way, and the hint is deliberately NOT carried between them: there are a
            // handful at most, they can sit anywhere on the loop, and a full search is both cheap and the only
            // answer that cannot be thrown off by whichever one was resolved last.
            val count = rawDwells.size / 4
            val dwells = DoubleArray(count * 2)
            for (i in 0 until count) {
                pos.set(rawDwells[i * 4], rawDwells[i * 4 + 1], rawDwells[i * 4 + 2])
                dwells[i * 2] = bare.nearestArcLength(pos, -1.0)
                dwells[i * 2 + 1] = rawDwells[i * 4 + 3]
            }
            sortPairs(dwells)

            return MotionTrack(stored, dwells, length)
        }

        /**
         * Decimate the timeline into `(arc, seconds)` breakpoints, keeping every point a straight line
         * between its neighbours would misplace in time by more than [epsilon].
         *
         * Ramer-Douglas-Peucker, the same recursion [PathSmoothing] runs on the geometry, but with the error
         * measured as a plain difference in SECONDS rather than a perpendicular distance. Perpendicular
         * distance would need arc length and time to be commensurable, which they are not -- and the quantity
         * worth bounding is "how far out would the replay's clock be here", which is a vertical difference and
         * nothing else. That also makes [epsilon] something a player can reason about.
         */
        private fun decimate(arcs: DoubleArray, secs: DoubleArray, n: Int, epsilon: Double): DoubleArray {
            val keep = BooleanArray(n)
            keep[0] = true
            keep[n - 1] = true
            rdp(arcs, secs, 0, n - 1, epsilon, keep)

            val out = ArrayList<Double>(64)
            for (i in 0 until n) if (keep[i]) { out.add(arcs[i]); out.add(secs[i]) }
            return DoubleArray(out.size) { out[it] }
        }

        private fun rdp(
            x: DoubleArray, y: DoubleArray, first: Int, last: Int, epsilon: Double, keep: BooleanArray
        ) {
            if (last <= first + 1) return
            val x0 = x[first]
            val y0 = y[first]
            val span = x[last] - x0
            val slope = if (span <= 1.0e-9) 0.0 else (y[last] - y0) / span

            var worst = -1.0
            var index = first
            for (i in first + 1 until last) {
                val d = abs(y[i] - (y0 + slope * (x[i] - x0)))
                if (d > worst) { worst = d; index = i }
            }

            if (worst > epsilon) {
                keep[index] = true
                rdp(x, y, first, index, epsilon, keep)
                rdp(x, y, index, last, epsilon, keep)
            }
        }

        /** Insertion sort on flat `(key, value)` pairs. There are never more than a handful. */
        private fun sortPairs(pairs: DoubleArray) {
            val n = pairs.size / 2
            for (i in 1 until n) {
                val key = pairs[i * 2]
                val value = pairs[i * 2 + 1]
                var j = i - 1
                while (j >= 0 && pairs[j * 2] > key) {
                    pairs[(j + 1) * 2] = pairs[j * 2]
                    pairs[(j + 1) * 2 + 1] = pairs[j * 2 + 1]
                    j--
                }
                pairs[(j + 1) * 2] = key
                pairs[(j + 1) * 2 + 1] = value
            }
        }
    }
}
