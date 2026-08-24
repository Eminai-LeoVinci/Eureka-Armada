package org.valkyrienskies.eureka.cannon

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity

/**
 * Gun-laying without a hand on the breech: what a crew works out between a ship bearing up and the ball
 * leaving the muzzle.
 *
 * The ship aims (the helmsman's business, or the pursuit AI's), the solver computes ([CannonSolver]'s),
 * the gun fires ([CannonFire.fireAimed]'s). What lives HERE is the judgment in between -- which point of
 * the enemy this gun can actually reach, which powder measure to spend on it, and the scatter that keeps
 * a gun crew from boring one hole -- and it lives in ONE place because a pillager gunner and a villager
 * gunner do exactly the same arithmetic. `PirateGunnery` owns who the raiders shoot at; `FireAtWill` owns
 * who a captain's crew shoot at; both come here to work out how.
 *
 * ## Charges walked lightest-first
 * A close target is a one-powder shot and a distant one costs three -- the same economy a player runs at
 * the breech, because powder is finite on both sides of a fight. The walk tries each measure the gun's
 * own magazine can afford, solving under THAT measure's drag, gravity and speed ceiling, and the first
 * that reaches wins. A gun whose crew are under orders not to be touched ([only]) is solved on the
 * measure already in her, and refuses rather than being re-charged behind her captain's back.
 */
object AutoGunnery {

    /** One gun's answer: the arc, and the powder measure that flies it. */
    class Lay(val solution: CannonSolver.GunSolution, val charge: PowderCharge)

    /**
     * Where THIS gun lays against [target]: the piece of enemy hull its own bore can reach. Per gun, never
     * once per broadside.
     *
     * The first version of this aimed every gun at the enemy CAPTAIN, which is a fine idea until the enemy
     * is a hundred blocks long. A gun answers only within a few degrees of its bore ([CannonSolver]'s
     * bearing gate), so a captain walking from the stern to the bow swung the aim point through most of a
     * right angle and the whole broadside lost its bearing at once -- guns falling silent for no reason
     * visible from either deck, and speaking again when they walked back. Cannons shoot SHIPS; who is
     * standing where only decides WHICH ship.
     *
     * So each gun is asked the geometric question instead: of every point in the enemy's hull box, which
     * lies closest to my bore line? Answered in the TARGET's ship space, where its box is axis-aligned and
     * the closest point is a clamp -- alternating clamp-to-box and project-onto-bore, because each of
     * those answers depends on the other, which is the standard convex alternation and settles in two
     * passes at these scales. A bore that passes through the hull converges to a point inside it and the
     * ball strikes the near face on its way; a bore that misses converges on the nearest planking, and the
     * bearing gate is then free to decide that is no shot at all.
     */
    fun aimFor(level: ServerLevel, gun: CannonBlockEntity, target: LoadedServerShip): Vec3? {
        val bore = CannonSolver.boreOf(level, gun) ?: return null
        val box = target.shipAABB ?: return null

        val origin = Vector3d(bore.muzzle.x, bore.muzzle.y, bore.muzzle.z)
        target.worldToShip.transformPosition(origin)
        val line = Vector3d(bore.direction.x, 0.0, bore.direction.z)
        target.worldToShip.transformDirection(line)
        if (line.lengthSquared() < 1.0e-12) return null
        line.normalize()

        val minX = box.minX().toDouble()
        val minY = box.minY().toDouble()
        val minZ = box.minZ().toDouble()
        val maxX = box.maxX() + 1.0
        val maxY = box.maxY() + 1.0
        val maxZ = box.maxZ() + 1.0

        val point = Vector3d(origin)
        repeat(AIM_PASSES) {
            // Clamp to the hull: the nearest planking to wherever we last were.
            point.set(
                point.x.coerceIn(minX, maxX),
                point.y.coerceIn(minY, maxY),
                point.z.coerceIn(minZ, maxZ)
            )
            // Project back onto the bore, forwards only -- a gun has no interest in what is behind it.
            val along = (
                (point.x - origin.x) * line.x +
                    (point.y - origin.y) * line.y +
                    (point.z - origin.z) * line.z
                ).coerceAtLeast(0.0)
            point.set(origin.x + line.x * along, origin.y + line.y * along, origin.z + line.z * along)
        }
        point.set(
            point.x.coerceIn(minX, maxX),
            point.y.coerceIn(minY, maxY),
            point.z.coerceIn(minZ, maxZ)
        )

        target.shipToWorld.transformPosition(point)
        return Vec3(point.x, point.y, point.z)
    }

    /**
     * Distance squared from [from] to the nearest point of [ship]'s hull -- the range a gunner would call
     * out, which is neither of the two this was first written as.
     *
     * Centre to centre came first, and had the guns declaring an enemy out of range whose rails were ten
     * blocks from their own. Centre to the PEOPLE aboard it came second, which fixed that and quietly
     * introduced the bug the aim point above describes: on a long ship the range then depended on where
     * the captain happened to be standing. The hull is the thing being shot at, so the hull is the thing
     * measured, by the same clamp into ship space [aimFor] lays by.
     */
    fun hullDistanceSq(ship: LoadedServerShip, from: Vec3): Double? {
        val box = ship.shipAABB ?: return null
        val local = Vector3d(from.x, from.y, from.z)
        ship.worldToShip.transformPosition(local)
        local.set(
            local.x.coerceIn(box.minX().toDouble(), box.maxX() + 1.0),
            local.y.coerceIn(box.minY().toDouble(), box.maxY() + 1.0),
            local.z.coerceIn(box.minZ().toDouble(), box.maxZ() + 1.0)
        )
        ship.shipToWorld.transformPosition(local)
        return local.distanceSquared(from.x, from.y, from.z)
    }

    /** The hull's live centre: shipyard box middle through the live transform, never the stored worldAABB. */
    fun shipCentre(ship: LoadedServerShip): Vec3? {
        val box = ship.shipAABB ?: return null
        val centre = Vector3d(
            (box.minX() + box.maxX() + 1) * 0.5,
            (box.minY() + box.maxY() + 1) * 0.5,
            (box.minZ() + box.maxZ() + 1) * 0.5
        )
        ship.shipToWorld.transformPosition(centre)
        return Vec3(centre.x, centre.y, centre.z)
    }

    /**
     * The best affordable arc from [gun] to [target], or null when no measure in her magazine carries that
     * far or the target is off the bore line. Cooldown is NOT checked -- readiness is the caller's rhythm;
     * this is pure "could she reach". [only] restricts the walk to one measure, for a gun whose settings
     * are under a lock.
     */
    fun lay(
        level: ServerLevel,
        gun: CannonBlockEntity,
        target: Vec3,
        bearingToleranceDegrees: Double,
        only: PowderCharge? = null
    ): Lay? {
        for (charge in (if (only != null) listOf(only) else PowderCharge.entries)) {
            if (gun.powderCount < charge.powder) continue
            val solution = CannonSolver.solveForGun(
                level, gun, target,
                drag = charge.drag,
                gravity = charge.gravity,
                maxSpeed = charge.speed,
                bearingToleranceDegrees = bearingToleranceDegrees
            ) ?: continue
            return Lay(solution, charge)
        }
        return null
    }

    /**
     * Solve and fire one gun at [target], jittered by [jitterBlocks]. Returns null on a shot away, or the
     * reason there wasn't one -- either the solver's silence ("cannot bear") or the gun's own refusal
     * (cooling, nothing in the breech).
     *
     * The breech's powder measure is WRITTEN with the chosen charge before firing, because everything
     * downstream -- powder cost, reload, and the drag/gravity pair synced to the client so it can fly the
     * same arc -- reads the measure off the breech. The AI setting the measure it solved with is the same
     * gesture as a player choosing it at the breech, just made per shot.
     */
    fun fireAt(
        level: ServerLevel,
        gun: CannonBlockEntity,
        target: Vec3,
        jitterBlocks: Double,
        bearingToleranceDegrees: Double,
        consume: Boolean,
        only: PowderCharge? = null,
        /** Reload to hand the gun instead of its own, in ticks. The caller's fire-at-will rate, or null. */
        cooldownTicks: Long? = null
    ): Component? {
        if (CannonShot.loadOf(gun.shot) == null) {
            return Component.translatable("info.vs_eureka.cannon_no_shot")
        }
        val aimPoint = if (jitterBlocks > 0.0) jitter(level, target, jitterBlocks) else target
        val lay = lay(level, gun, aimPoint, bearingToleranceDegrees, only)
            ?: return Component.literal("cannot bear")
        gun.powderCharge = lay.charge
        return CannonFire.fireAimed(
            level, gun.blockPos, lay.solution.pitchDegrees, lay.solution.speed, consume,
            cooldownTicks = cooldownTicks
        )
    }

    /**
     * The deliberate hand-tremble: a uniform scatter in a horizontal disc plus half a block of height,
     * applied to the AIM POINT before solving -- so the error flows through the whole arc the way a real
     * mislay would, rather than being pasted onto the muzzle direction afterwards.
     */
    private fun jitter(level: ServerLevel, target: Vec3, radius: Double): Vec3 {
        val random = level.random
        val angle = random.nextDouble() * 2.0 * Math.PI
        val reach = radius * Math.sqrt(random.nextDouble())
        return Vec3(
            target.x + Math.cos(angle) * reach,
            target.y + (random.nextDouble() - 0.5),
            target.z + Math.sin(angle) * reach
        )
    }

    /** Clamp-and-project passes in [aimFor]. Two settle this geometry; a third moves the point by microns. */
    private const val AIM_PASSES = 2
}
