package org.valkyrienskies.eureka.ship

import org.valkyrienskies.eureka.EurekaConfig
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The ocean swell ships ride: a CPU port of the Gerstner-style `physics_waveHeight` that shaderpacks implement in
 * `oceans.glsl` (the public-domain Physics Mod ocean function).
 *
 * The same math drives the swell our modified shader draws, so a ship bobbing to this field rises and falls with the
 * waves the player sees. The constants below MUST stay numerically identical to the shader's (see the
 * feature_vs2_ocean_waves notes); the height, scale and speed are the server config's `wave*` keys, which have to
 * match the shader's sliders.
 *
 * [height] is the wave's deviation from mean sea level (about +/- waveHeight/2) at a world XZ, now.
 *
 * Our VS2 build carried this; it lives in Armada now so Armada runs on the official release.
 */
object OceanWaveField {

    // --- canonical Physics Mod ocean constants (oceans.glsl) ---
    private const val DRAG_MULT = 0.048
    private const val XZ_SCALE = 0.035
    private const val TIME_MULT = 0.45
    private const val FREQUENCY = 6.0
    private const val SPEED = 2.0
    private const val WEIGHT = 0.8
    private const val FREQUENCY_MULT = 1.18
    private const val SPEED_MULT = 1.07
    private const val ITER_INC = 12.0
    private const val MAX_ITERATIONS = 40

    /** A physics dimension that goes this long without ticking loses ownership of the wave clock. */
    private const val DRIVER_TIMEOUT_NANOS = 1_000_000_000L

    // Each iteration's direction and frequency/speed/weight depend only on the index, so they are tabulated once.
    private val DIR_X = DoubleArray(MAX_ITERATIONS)
    private val DIR_Z = DoubleArray(MAX_ITERATIONS)
    private val FREQ = DoubleArray(MAX_ITERATIONS)
    private val SPD = DoubleArray(MAX_ITERATIONS)
    private val WGT = DoubleArray(MAX_ITERATIONS)

    /** WAVE_SUM[n] = sum of the first n weights: the normalisation for n iterations. */
    private val WAVE_SUM = DoubleArray(MAX_ITERATIONS + 1)

    init {
        var iter = 0.0
        var frequency = FREQUENCY
        var speed = SPEED
        var weight = 1.0
        for (i in 0 until MAX_ITERATIONS) {
            DIR_X[i] = sin(iter)
            DIR_Z[i] = cos(iter)
            FREQ[i] = frequency
            SPD[i] = speed
            WGT[i] = weight
            WAVE_SUM[i + 1] = WAVE_SUM[i] + weight
            iter += ITER_INC
            weight *= WEIGHT
            frequency *= FREQUENCY_MULT
            speed *= SPEED_MULT
        }
    }

    /**
     * The config and wave time for one physics frame, so the samples of a frame agree with each other and a config
     * edit in the middle of a frame can't tear them.
     */
    private class Params(
        @JvmField val waveHeight: Double,
        @JvmField val xzScale: Double,
        @JvmField val offsetX: Double,
        @JvmField val offsetZ: Double,
        @JvmField val iterations: Int,
        @JvmField val modifiedTime: Double,
    )

    /** Monotonic wave clock, advanced once per physics frame. */
    private var time = 0.0

    @Volatile
    private var params = snapshot(0.0)

    @Volatile
    private var driverDimension: String? = null

    @Volatile
    private var lastAdvanceNanos = 0L

    /**
     * Advance the wave clock by one physics frame. VS2's physics tick fires once per physics dimension per frame, so
     * the first dimension to tick owns the clock (otherwise it would run N times too fast with N dimensions loaded);
     * if it stops ticking, another takes over after [DRIVER_TIMEOUT_NANOS].
     */
    fun advanceTime(dimensionId: String, deltaSeconds: Double) {
        val now = System.nanoTime()
        val driver = driverDimension
        if (driver != dimensionId) {
            if (driver != null && now - lastAdvanceNanos < DRIVER_TIMEOUT_NANOS) return
            driverDimension = dimensionId
        }
        lastAdvanceNanos = now
        // guard against pause/teleport spikes
        if (deltaSeconds in 0.0..1.0) time += deltaSeconds
        params = snapshot(time)
    }

    private fun snapshot(time: Double): Params {
        val cfg = EurekaConfig.SERVER
        return Params(
            waveHeight = cfg.waveHeight,
            xzScale = XZ_SCALE * cfg.waveHorizontalScale,
            offsetX = cfg.waveOffsetX,
            offsetZ = cfg.waveOffsetZ,
            iterations = cfg.waveIterations.coerceIn(1, MAX_ITERATIONS),
            modifiedTime = (time + cfg.wavePhaseOffset) * cfg.waveSpeed * TIME_MULT,
        )
    }

    /** Vertical wave deviation (blocks) from mean sea level at world ([worldX], [worldZ]); positive is a crest. */
    fun height(worldX: Double, worldZ: Double): Double {
        val p = params
        val oceanHeight = p.waveHeight
        if (oceanHeight <= 0.0) return 0.0

        var px = (worldX - p.offsetX) * p.xzScale
        var pz = (worldZ - p.offsetZ) * p.xzScale

        val modifiedTime = p.modifiedTime
        val iterations = p.iterations
        var heightSum = 0.0
        for (i in 0 until iterations) {
            val dirX = DIR_X[i]
            val dirZ = DIR_Z[i]
            val weight = WGT[i]
            val x = (dirX * px + dirZ * pz) * FREQ[i] + modifiedTime * SPD[i]
            val wave = exp(sin(x) - 1.0)
            val forceMag = wave * cos(x) * weight
            px -= forceMag * dirX * DRAG_MULT
            pz -= forceMag * dirZ * DRAG_MULT
            heightSum += wave * weight
        }

        // Centred on 0 like the shader (it subtracts oceanHeight * 0.5); WAVE_SUM[n] >= 1 for n >= 1.
        return heightSum / WAVE_SUM[iterations] * oceanHeight - oceanHeight * 0.5
    }
}
