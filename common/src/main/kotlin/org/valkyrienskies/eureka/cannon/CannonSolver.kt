package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The firing solution: given where a gun is and where the target is, what pitch and what muzzle speed
 * put the ball there.
 *
 * ## Solved, not simulated
 * The first idea for AI gunnery was the obvious one -- trace test shots down candidate arcs until one
 * lands close. But a [CannonShot]'s whole flight is deterministic arithmetic: each tick the position
 * steps by the current velocity, THEN `v <- v*drag - (0, g, 0)`. Summing that recurrence gives closed
 * forms, so the arc through any point can be computed outright, in microseconds, with no rays cast:
 *
 * with `d` = drag and `S_N = (1 - d^N)/(1 - d)` (the decaying step sum),
 *  - horizontal travel after N ticks:  `X_N = v_h * S_N`
 *  - vertical travel after N ticks:    `Y_N = v_y * S_N - (g/(1-d)) * (N - S_N)`
 *
 * Fix the flight time N and both invert exactly: `v_h = R/S_N`, `v_y = (H + sink_N)/S_N`. There is no
 * closed form over CONTINUOUS N (it lands in Lambert-W territory), but N is an integer at most
 * the flight-tick bound -- so the solver sweeps N ascending and takes the FIRST time whose demanded speed
 * and pitch are within the gun's limits. First accepted = least flight time = flattest arc, which is
 * also the arc least upset by both hulls bobbing during the second the ball is in the air.
 *
 * ## What the pirate cannot exceed
 * The same restraints as a player's gun: pitch within +-45 degrees, speed at most the heaviest powder
 * charge's muzzle velocity. Inside those walls the AI lays continuous decimal values -- the same
 * "simulate past the blockstate" trick the helm's wheel and the barrel renderer already live by.
 *
 * ## Bearing is not solvable
 * A gun's azimuth is its ship's business -- the bore points where the hull points. [solveForGun] only
 * answers when the target sits within a bearing tolerance of the bore line, and the shot then flies
 * along the BORE azimuth, not the target azimuth: the residual lateral spread (at most R*sin(tolerance))
 * is accepted, and wanted -- it is most of the broadside's natural scatter.
 */
object CannonSolver {

    /** One solved arc, in world terms: pitch above the horizon, muzzle speed, and the flight time. */
    class Solution(val pitchDegrees: Double, val speed: Double, val flightTicks: Int)

    /**
     * One gun's mounting, resolved into world space: where the ball leaves at any elevation, and the level
     * azimuth the bore points along.
     *
     * Both are carried out of shipyard space by the SHIP TRANSFORM rather than by rotating a direction by
     * hand -- the same rule [CannonFire] fires by, so the solver, the shot and anything asking where a gun
     * is looking all agree by construction.
     */
    class Bore internal constructor(
        val rear: BlockPos,
        private val facing: Direction,
        private val shipToWorld: Matrix4dc?
    ) {
        /** The muzzle at [pitchRadians] of elevation -- it rides the trunnion arc, so the lay MOVES it. */
        fun muzzleAt(pitchRadians: Double): Vec3 {
            val along = cos(pitchRadians)
            val up = sin(pitchRadians)
            val forward = CannonFire.TRUNNION_FORWARD + CannonFire.BORE_LENGTH * along
            val height = CannonFire.TRUNNION_HEIGHT + CannonFire.BORE_LENGTH * up
            return world(
                rear.x + 0.5 + facing.stepX * forward,
                rear.y + 0.5 + height,
                rear.z + 0.5 + facing.stepZ * forward
            )
        }

        /** The level-barrel muzzle: where a flat shot starts, and the origin every bearing is taken from. */
        val muzzle: Vec3 = muzzleAt(0.0)

        /** The bore's level azimuth, unit length -- or zero for a degenerate mounting, which [boreOf] rejects. */
        val direction: Vec3 = run {
            val ahead = world(
                rear.x + 0.5 + facing.stepX * (CannonFire.TRUNNION_FORWARD + CannonFire.BORE_LENGTH + 1.0),
                rear.y + 0.5 + CannonFire.TRUNNION_HEIGHT,
                rear.z + 0.5 + facing.stepZ * (CannonFire.TRUNNION_FORWARD + CannonFire.BORE_LENGTH + 1.0)
            )
            val flat = Vec3(ahead.x - muzzle.x, 0.0, ahead.z - muzzle.z)
            val length = flat.length()
            if (length < 1.0e-6) Vec3.ZERO else flat.scale(1.0 / length)
        }

        private fun world(x: Double, y: Double, z: Double): Vec3 {
            val point = Vector3d(x, y, z)
            shipToWorld?.transformPosition(point)
            return Vec3(point.x, point.y, point.z)
        }
    }

    /** The mounting of [gun], or null if that block is not a cannon pointing anywhere. */
    fun boreOf(level: ServerLevel, gun: CannonBlockEntity): Bore? {
        val state = gun.blockState
        if (!state.hasProperty(HORIZONTAL_FACING)) return null
        val rear = gun.blockPos
        val bore = Bore(rear, state.getValue(HORIZONTAL_FACING), level.getLoadedShipManagingPos(rear)?.shipToWorld)
        return if (bore.direction.lengthSqr() < 1.0e-12) null else bore
    }

    /** [Solution] plus the gun geometry the shot needs: the true muzzle and the bore's level azimuth. */
    class GunSolution(
        val rear: BlockPos,
        val pitchDegrees: Double,
        val speed: Double,
        val flightTicks: Int,
        val muzzle: Vec3,
        val azimuth: Vec3
    )

    /**
     * Pitch and speed to carry a ball [horizontal] blocks out and [vertical] blocks up from the muzzle,
     * or null when no arc within the gun's limits reaches that point.
     */
    fun solve(drag: Double, gravity: Double, horizontal: Double, vertical: Double, maxSpeed: Double): Solution? {
        if (horizontal < MIN_RANGE || maxSpeed <= 0.0) return null
        for (n in 1..flightTickBound()) {
            val sn: Double
            val sink: Double
            if (abs(1.0 - drag) < 1.0e-9) {
                // The drag-free limit of the formulas below, not a separate physics: S_N -> N and the
                // sink term -> the triangular-number free-fall sum.
                sn = n.toDouble()
                sink = gravity * n * (n - 1) / 2.0
            } else {
                sn = (1.0 - pow(drag, n)) / (1.0 - drag)
                sink = (gravity / (1.0 - drag)) * (n - sn)
            }
            val vh = horizontal / sn
            val vy = (vertical + sink) / sn
            val speed = sqrt(vh * vh + vy * vy)
            if (speed > maxSpeed) continue
            val pitch = Math.toDegrees(atan2(vy, vh))
            if (abs(pitch) > MAX_PITCH_DEGREES) continue
            return Solution(pitch, speed, n)
        }
        return null
    }

    /**
     * The full solution for one gun against a world-space point: bearing gate, muzzle geometry, and the
     * arc. Null when the target is off the bore line, or out of reach of every legal arc.
     *
     * The muzzle rides the trunnion arc, so where the ball starts depends on the pitch being solved for
     * -- a genuine circularity, closed by iteration: solve from the level-barrel muzzle, then once more
     * from the muzzle at the solved pitch. The muzzle moves at most a bore length (~1.4 blocks), so the
     * second pass lands within hand-tremble of exact and a third would change nothing visible.
     */
    fun solveForGun(
        level: ServerLevel,
        gun: CannonBlockEntity,
        target: Vec3,
        drag: Double,
        gravity: Double,
        maxSpeed: Double,
        bearingToleranceDegrees: Double
    ): GunSolution? {
        val mounting = boreOf(level, gun) ?: return null
        val muzzleLevel = mounting.muzzle
        val bore = mounting.direction

        // Bearing gate: the ship aims the gun, this only reports whether she has.
        val toTargetFlat = Vec3(target.x - muzzleLevel.x, 0.0, target.z - muzzleLevel.z)
        val range = toTargetFlat.length()
        if (range < MIN_RANGE) return null
        val cosBearing = (toTargetFlat.x * bore.x + toTargetFlat.z * bore.z) / range
        if (cosBearing < cos(Math.toRadians(bearingToleranceDegrees))) return null

        // Pass one from the level-barrel muzzle, pass two from the muzzle the solved pitch implies.
        val first = solve(drag, gravity, range, target.y - muzzleLevel.y, maxSpeed) ?: return null
        val muzzle = mounting.muzzleAt(Math.toRadians(first.pitchDegrees))
        val refinedRange = sqrt(
            (target.x - muzzle.x) * (target.x - muzzle.x) + (target.z - muzzle.z) * (target.z - muzzle.z)
        )
        val refined = solve(drag, gravity, refinedRange, target.y - muzzle.y, maxSpeed) ?: first
        return GunSolution(mounting.rear, refined.pitchDegrees, refined.speed, refined.flightTicks, muzzle, bore)
    }

    private fun pow(base: Double, n: Int): Double {
        var result = 1.0
        var b = base
        var e = n
        while (e > 0) {
            if (e and 1 == 1) result *= b
            b *= b
            e = e shr 1
        }
        return result
    }

    /** Point-blank floor: inside this the geometry degenerates and a gun has no business solving anything. */
    private const val MIN_RANGE = 2.0

    /** The mount's mechanical limit, same as the player's elevation range. */
    private const val MAX_PITCH_DEGREES = 45.0

    /**
     * The sweep bound: an arc longer than the ball lives ([CannonShot.flightCapTicks]) is not a solution,
     * and MAX_SOLVER_TICKS keeps one AI shot from sweeping an hour-long cap one tick at a time -- crews
     * simply refuse arcs past ten seconds, where a hand-laid gun may fly them.
     */
    private fun flightTickBound(): Int = minOf(MAX_SOLVER_TICKS.toLong(), CannonShot.flightCapTicks()).toInt()
    private const val MAX_SOLVER_TICKS = 200
}
