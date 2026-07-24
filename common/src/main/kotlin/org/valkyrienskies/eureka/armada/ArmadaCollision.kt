package org.valkyrienskies.eureka.armada

import net.minecraft.server.level.ServerLevel
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider.NextTransformAndVelocityData
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Holds the pose the armada's parent is allowed to take next tick, or null to let the physics engine decide.
 *
 * [ArmadaCollision] recomputes the clamp once per server tick as an ABSOLUTE world pose, never as an increment, so
 * it doesn't matter how many times VS asks for it before the next solve -- the parent lands on the same spot either
 * way.
 */
class ArmadaParentClampProvider : ServerShipTransformProvider {

    @Volatile
    var clamp: NextTransformAndVelocityData? = null

    /** Human-readable state of the last solve, for "/armada debug". */
    @Volatile
    var report: String = "clear"

    /** Total hull sample points probed on the last solve, for "/armada debug". */
    @Volatile
    var samples: Int = 0

    /**
     * Consecutive ticks with some part of the armada inside a solid block, which the repel ramps against. Solver
     * state, only ever touched on the game thread; it lives here because it has to persist per parent between ticks
     * and this object already does.
     */
    var repelTicks: Int = 0

    override fun provideNextTransformAndVelocity(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform
    ): NextTransformAndVelocityData? = clamp
}

/**
 * Gives an armada world collision by expressing its CHILDREN's contacts through its PARENT.
 *
 * A child is pose-slaved ([ArmadaFollowProvider]): its transform is written every tick from the parent's pose, which
 * leaves the physics engine nothing to solve -- a forced pose cannot also yield to terrain, so children fly straight
 * through mountains. The parent, meanwhile, is a perfectly normal ship that collides with the world already. So the
 * fix isn't to give children their own physics (that is the road back to the joint, and to an armada that tears
 * itself apart); it is to make the parent refuse any motion that would put a child inside terrain. The armada then
 * stops, slides and grinds as ONE hull, which is how it is meant to behave in the first place.
 *
 * The response is modelled on how the physics engine resolves the parent itself -- a SWEPT, sub-stepped
 * collide-and-slide -- because anything coarser phases. An earlier version predicted a pose several ticks ahead and,
 * when that read clear, let the parent free-fall through physics for the whole tick; a ship near a wall then took one
 * full block-step into it before the next tick reacted, and once a point was inside, a look-ahead-scaled slide test
 * refused nearly all motion and left it stuck. The rewrite fixes the source: it never lets a point reach the inside
 * of a block to begin with.
 *
 * Each server tick, for each parent:
 *  1. Test the real one-tick step against every child's hull cloud ([ArmadaHullProbe]). Clear of terrain -- the
 *     overwhelmingly common case, one pass over the points -- means no clamp at all: the parent flies under normal
 *     physics and this costs nothing but the probe.
 *  2. Otherwise advance that step in sub-steps no longer than [MAX_PIECE_BLOCKS] (so a fast ship can't skip over a
 *     thin wall), stopping each piece at the surface. A blocked piece keeps only the part of itself running ALONG the
 *     surface, so the armada scrapes past a wall the way the parent does rather than halting against it. No piece is
 *     ever allowed to land inside a block, so the armada stops a hair short of contact, not a block deep in it.
 *  3. A repel (ramped, cut off the instant it's free) eases out anything that ended up inside anyway -- a coarse
 *     sample clipping a corner, terrain placed around a parked armada. It is a safety net, not the mechanism.
 *  4. Publish the surviving motion as an absolute pose plus a matching velocity, so speed into the surface reads as
 *     zero and the ship control can't keep integrating thrust into the wall.
 *
 * Everything is judged by how many sample points land inside terrain compared to how many are inside RIGHT NOW, not
 * by whether they touch it at all. With a clear armada that is the same rule ("stay out"), but it also means an
 * armada that somehow ends up buried can always move in any direction that reduces the overlap -- it can never be
 * trapped, the one failure mode a hard "must not touch" rule hands out for free.
 */
object ArmadaCollision {

    /** One server tick, in seconds -- velocities are m/s and this runs once per world tick. */
    private const val TICK_SECONDS = 0.05

    private val NO_MOVE = Vector3d()
    private val NO_TURN = Quaterniond()

    /**
     * The longest a single swept sub-step may be, in blocks. The tick's motion is cut into pieces no longer than this
     * so the sweep can't jump over a wall thinner than the step -- the classic tunnelling failure. Well under a block
     * so even a one-block-thick wall is always sampled.
     */
    private const val MAX_PIECE_BLOCKS = 0.25

    /** Sub-step count floor (slow ships still get a few) and ceiling (bounds the per-tick cost of a very fast one). */
    private const val SUBSTEPS_MIN = 4
    private const val SUBSTEPS_MAX = 32

    private class ChildProbe(val toWorld: Matrix4dc, val points: DoubleArray)

    /**
     * Solve every armada in [level]. Called each server world tick, after [ArmadaBindings.reconcile] so freshly
     * re-established bonds are included the same tick they come back.
     */
    fun tick(level: ServerLevel) {
        val enabled = EurekaConfig.SERVER.armadaChildTerrainCollision
        // VS's ServerShipWorld is GLOBAL -- "loadedShips" returns ships from every dimension -- while this event
        // fires once per level. Without this filter an overworld armada also gets solved during the nether and end
        // ticks, where its coordinates hold nothing: those passes read no hull and no terrain, conclude "clear",
        // and overwrite the overworld's verdict, which switches collision off entirely.
        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val armada = ArmadaShipControl.get(ship) ?: continue
            // A child's pose is already owned by its follow provider, and its contacts are answered by its parent.
            if (armada.isChild) continue

            val existing = ship.transformProvider as? ArmadaParentClampProvider
            if (!enabled || armada.childShipIds.isEmpty()) {
                if (existing != null) ship.transformProvider = null
                continue
            }

            var provider = existing
            if (provider == null) {
                if (ship.transformProvider != null) continue // something else owns this ship's pose; don't fight it
                provider = ArmadaParentClampProvider()
                ship.transformProvider = provider
            }
            solve(level, ship, armada, provider)
        }
        if (level.gameTime % 600L == 0L) ArmadaHullProbe.pruneIdle(level.gameTime)
    }

    private fun solve(
        level: ServerLevel,
        parent: LoadedServerShip,
        armada: ArmadaShipControl,
        provider: ArmadaParentClampProvider
    ) {
        val world = level.shipObjectWorld
        val budget = EurekaConfig.SERVER.armadaCollisionSampleBudget
        val parentTransform = parent.transform

        // Each child's hull cloud plus the matrix placing it in the world at the armada's CURRENT pose. The child's
        // world pose is rebuilt from the parent's pose and the stored bind offset -- the same construction the follow
        // provider uses -- rather than read off the child, so the probe geometry can't be a tick behind the parent.
        val children = ArrayList<ChildProbe>(armada.childShipIds.size)
        var samples = 0
        for (childId in armada.childShipIds) {
            val child = world.loadedShips.getById(childId) ?: continue
            val childArmada = ArmadaShipControl.get(child) ?: continue
            val posInParent = childArmada.intendedPosInParent ?: continue
            val rotInParent = childArmada.intendedRotInParent ?: Quaterniond()
            val points = ArmadaHullProbe.pointsFor(level, child, budget)
            if (points.isEmpty()) continue

            val childWorldPos = parentTransform.shipToWorld.transformPosition(Vector3d(posInParent))
            val childWorldRot = Quaterniond(parentTransform.shipToWorldRotation).mul(rotInParent)
            val toWorld = vsApi.newBodyTransform(
                childWorldPos,
                childWorldRot,
                Vector3d(child.transform.shipToWorldScaling),
                Vector3d(child.transform.positionInShip)
            ).toWorld
            children.add(ChildProbe(toWorld, points))
            samples += points.size / 3
        }
        provider.samples = samples
        if (children.isEmpty()) {
            provider.clamp = null
            provider.report = "no child hulls to probe"
            return
        }

        val reader = ArmadaBlockReader(level)
        val pivot = Vector3d(parentTransform.positionInWorld)
        fun hits(move: Vector3dc, turn: Quaterniond, limit: Int): Int =
            countHits(reader, children, pivot, move, turn, limit)

        // What physics wants to do THIS tick -- the real one-tick step, not a multiple of it. The old solver tested a
        // pose several ticks downrange and, when that read clear, handed the parent to free physics for the whole
        // tick; a ship near a wall then took one full step into it before the next tick noticed. Everything here works
        // on the real step so contact is caught the tick it happens.
        val step = Vector3d(parent.velocity).mul(TICK_SECONDS)
        val turn = spinOver(parent.angularVelocity, TICK_SECONDS)

        // base = points inside a solid block right now. Everything is judged against this, so an armada that is already
        // overlapping can still move any way that doesn't make the overlap worse -- it can never be trapped.
        val base = hits(NO_MOVE, NO_TURN, Int.MAX_VALUE)

        // Engage if part of the armada is inside something, or the real step (widened a little by lookahead so a fast
        // ship engages a touch early) is about to put it there. Otherwise hand back to physics -- but only genuinely
        // clear of terrain, tested on the real motion, so this doesn't flicker on and off along a wall the way an
        // N-ticks-ahead prediction does.
        val lookahead = EurekaConfig.SERVER.armadaCollisionLookaheadTicks.coerceIn(1, 10).toDouble()
        val approaching = hits(Vector3d(step).mul(lookahead), spinOver(parent.angularVelocity, TICK_SECONDS * lookahead), base + 1) > base
        if (base == 0 && !approaching) {
            provider.repelTicks = 0
            provider.clamp = null
            provider.report = "clear"
            return
        }

        // Turn is resolved as a whole: allow it only if it doesn't push more of the armada into terrain.
        val turnOk = hits(NO_MOVE, turn, base + 1) <= base
        val turnActive = if (turnOk) turn else NO_TURN

        // Collide-and-slide, swept. Advance the real step in small sub-steps (sized so no single piece is longer than
        // MAX_PIECE_BLOCKS, however fast the ship is going -- that's what stops it tunnelling through a thin wall) and
        // stop each piece at the surface. When a piece is refused, take only the part of it running ALONG the surface
        // and keep going, so the armada scrapes past a wall exactly the way the parent's own physics does instead of
        // halting against it. This is the crux of the rewrite: no piece is ever allowed to land inside terrain, so
        // the armada is stopped a hair short of contact rather than a whole block deep in it.
        val stepLen = step.length()
        val subSteps = ceil(stepLen / MAX_PIECE_BLOCKS).toInt().coerceIn(SUBSTEPS_MIN, SUBSTEPS_MAX)
        val piece = Vector3d(step).div(subSteps.toDouble())
        val applied = Vector3d()
        val candidate = Vector3d()
        val slideNormal = Vector3d()
        for (s in 0 until subSteps) {
            candidate.set(applied).add(piece)
            if (hits(candidate, turnActive, base + 1) <= base) {
                applied.set(candidate)
                continue
            }
            // Blocked. Find the surface here and slide the piece along it.
            slideNormal.zero()
            gatherOutward(reader, children, pivot, applied, turnActive, slideNormal)
            fallbackNormal(slideNormal, parent.velocity)
            val into = piece.dot(slideNormal)
            candidate.set(applied)
            if (into < 0.0) {
                candidate.add(piece).sub(slideNormal.x * into, slideNormal.y * into, slideNormal.z * into)
            }
            if (hits(candidate, turnActive, base + 1) <= base) applied.set(candidate)
            // else: even the tangent is blocked here; leave `applied` and let later sub-steps / the repel try.
        }

        val friction = EurekaConfig.SERVER.armadaCollisionSlideFriction.coerceIn(0.0, 1.0)
        if (friction > 0.0) applied.mul(1.0 - friction)

        // The repel: a safety net for when a point ends up genuinely INSIDE terrain despite the above (a coarse hull
        // sample clips a corner, terrain is placed around a parked armada, a bind starts inside a hill). It pushes out
        // along the surface normal, ramping from nothing to full over armadaCollisionRepelRampTicks so it reads as a
        // magnet rather than a shove, and cutting out the instant nothing is inside any more -- it never fades. Merely
        // touching a surface never triggers it; that is the glide's job.
        if (base > 0) provider.repelTicks++ else provider.repelTicks = 0
        var repel = 0.0
        if (base > 0) {
            val ramp = EurekaConfig.SERVER.armadaCollisionRepelRampTicks.coerceAtLeast(1)
            repel = EurekaConfig.SERVER.armadaCollisionRepelSpeed.coerceAtLeast(0.0) *
                (provider.repelTicks.toDouble() / ramp).coerceAtMost(1.0)
            if (repel > 0.0) {
                slideNormal.zero()
                gatherOutward(reader, children, pivot, applied, turnActive, slideNormal)
                fallbackNormal(slideNormal, parent.velocity)
                val push = repel * TICK_SECONDS
                candidate.set(applied).add(slideNormal.x * push, slideNormal.y * push, slideNormal.z * push)
                // Pushing OUT can only reduce the buried-point count, so this is allowed to move us toward `base` from
                // above; accept it whenever it doesn't make things worse.
                if (hits(candidate, turnActive, base + 1) <= base) applied.set(candidate)
            }
        }

        val nextPos = Vector3d(parentTransform.positionInWorld).add(applied)
        val nextRot = Quaterniond(turnActive).mul(parentTransform.shipToWorldRotation)
        val nextTransform = vsApi.newBodyTransform(
            nextPos,
            nextRot,
            Vector3d(parentTransform.shipToWorldScaling),
            Vector3d(parentTransform.positionInShip)
        )
        // Pin the POSITION out of terrain, but hand the VELOCITY straight back to physics (null = keep the
        // engine/pilot-driven velocity, don't override it). This is the whole difference between "collides like the
        // parent" and "freezes until you /tp out". The old code forced velocity to applied/dt -- which is ZERO
        // whenever the armada is blocked head-on -- and that zero both threw away the pilot's thrust and became the
        // velocity this solver reads next tick, so once wedged the ship was frozen and no amount of throttle could
        // reach it. Keeping the real velocity means the pilot always has authority: reverse, and next tick's `step`
        // points away from the wall, so the resolved position walks back out -- exactly why the parent can never be
        // trapped. The position can't creep through meanwhile, because it is re-pinned to a non-penetrating pose
        // every single tick no matter where the velocity is pointing.
        provider.clamp = NextTransformAndVelocityData(nextTransform, null, null)
        provider.report = buildString {
            val kept = 100.0 * applied.length() / step.length().coerceAtLeast(1.0e-9)
            append("%s (%.0f%% of step, %d substeps)".format(if (applied.lengthSquared() > 1.0e-12) "sliding" else "held", kept, subSteps))
            if (!turnOk) append(", turn blocked")
            if (base > 0) append(", repelling %.1f m/s from %d pts inside".format(repel, base))
        }
    }

    /** Point [n] out of the world: itself if it has a direction, else back the way we came, else straight up. */
    private fun fallbackNormal(n: Vector3d, velocity: Vector3dc) {
        if (n.lengthSquared() < 1.0e-12) n.set(velocity).negate()
        if (n.lengthSquared() < 1.0e-12) n.set(0.0, 1.0, 0.0)
        n.normalize()
    }

    /**
     * Accumulates into [dest] the direction that leads out of the world, summed over every sample point that lands
     * inside a solid block once the armada is moved by [move]/[turn]. Each buried point contributes the axis
     * directions whose neighbouring block is open, so the result is un-normalized and weighted by how much of the
     * armada is against each face. Returns how many points were buried.
     */
    private fun gatherOutward(
        reader: ArmadaBlockReader,
        children: List<ChildProbe>,
        pivot: Vector3dc,
        move: Vector3dc,
        turn: Quaterniond,
        dest: Vector3d
    ): Int {
        val delta = deltaOf(move, turn, pivot)
        val toWorld = Matrix4d()
        val point = Vector3d()
        var buried = 0
        for (child in children) {
            delta.mul(child.toWorld, toWorld)
            val points = child.points
            var i = 0
            while (i < points.size) {
                point.set(points[i], points[i + 1], points[i + 2])
                toWorld.transformPosition(point)
                i += 3

                val x = floor(point.x).toInt()
                val y = floor(point.y).toInt()
                val z = floor(point.z).toInt()
                if (!reader.blocksMotionAt(x, y, z)) continue
                buried++
                if (!reader.blocksMotionAt(x - 1, y, z)) dest.x -= 1.0
                if (!reader.blocksMotionAt(x + 1, y, z)) dest.x += 1.0
                if (!reader.blocksMotionAt(x, y - 1, z)) dest.y -= 1.0
                if (!reader.blocksMotionAt(x, y + 1, z)) dest.y += 1.0
                if (!reader.blocksMotionAt(x, y, z - 1)) dest.z -= 1.0
                if (!reader.blocksMotionAt(x, y, z + 1)) dest.z += 1.0
            }
        }
        return buried
    }

    /**
     * How many of the children's sample points sit inside terrain once the whole armada is moved by [move] and turned
     * by [turn] about [pivot]. Stops early once [limit] hits are reached: callers only ever need to know whether the
     * count beats a threshold, so the clear case walks the points once and the rest bail on the first point too many.
     */
    private fun countHits(
        reader: ArmadaBlockReader,
        children: List<ChildProbe>,
        pivot: Vector3dc,
        move: Vector3dc,
        turn: Quaterniond,
        limit: Int
    ): Int {
        val delta = deltaOf(move, turn, pivot)
        val toWorld = Matrix4d()
        val point = Vector3d()
        var hits = 0
        for (child in children) {
            delta.mul(child.toWorld, toWorld)
            val points = child.points
            var i = 0
            while (i < points.size) {
                point.set(points[i], points[i + 1], points[i + 2])
                toWorld.transformPosition(point)
                if (reader.blocksMotionAt(point.x, point.y, point.z)) {
                    hits++
                    if (hits >= limit) return hits
                }
                i += 3
            }
        }
        return hits
    }

    /**
     * The world-space movement of the whole armada when the parent moves by [move] and turns by [turn] about
     * [pivot]. The armada is rigid, so this one delta carries every child the same way.
     */
    private fun deltaOf(move: Vector3dc, turn: Quaterniond, pivot: Vector3dc): Matrix4d = Matrix4d()
        .translate(move)
        .translate(pivot)
        .rotate(turn)
        .translate(-pivot.x(), -pivot.y(), -pivot.z())

    /** The rotation [omega] (rad/s, world axes) sweeps out in [seconds]. */
    private fun spinOver(omega: Vector3dc, seconds: Double): Quaterniond {
        val rate = omega.length()
        if (rate < 1e-9) return Quaterniond()
        return Quaterniond().rotateAxis(rate * seconds, omega.x() / rate, omega.y() / rate, omega.z() / rate)
    }
}
