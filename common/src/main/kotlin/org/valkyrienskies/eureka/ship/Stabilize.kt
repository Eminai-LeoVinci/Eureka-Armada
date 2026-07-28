package org.valkyrienskies.eureka.ship

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.eureka.EurekaConfig
import kotlin.math.atan
import kotlin.math.max

// World-up is never mutated (angle/cross read it only) — shared constant instead of a fresh
// Vector3d per stabilize call. Everything handed to applyInvariantTorque/Force stays freshly
// allocated: vs-core queues those by reference.
private val WORLD_UP: Vector3dc = Vector3d(0.0, 1.0, 0.0)

/**
 * Returns the magnitude of the linear anti-velocity (braking) force applied, or 0 if [linear] is false.
 *
 * [angular] turns off the upright/spin-damping torque while leaving the [linear] brake alone. An armada child
 * passes false: it still brakes its own mass (the brake scales with mass, so every ship braking itself keeps the
 * net force through the armada's combined centre of mass), but righting itself through the rigid weld would
 * fight its parent's stabilization and pin the armada's heading.
 */
fun stabilize(
    ship: PhysShip,
    omega: Vector3dc,
    vel: Vector3dc,
    forces: PhysShip,
    linear: Boolean,
    yaw: Boolean,
    angular: Boolean = true
): Double {
    if (angular) applyStabilizationTorque(ship, omega, forces, yaw)

    if (linear) {
        val idealVelocity = Vector3d(vel).negate()
        idealVelocity.y = 0.0

        // ideally this should work the same way as input is scaled
        val s = EurekaConfig.SERVER.linearStabilizeMaxAntiVelocity * (1 - 1 / smoothingATanMax(EurekaConfig.SERVER.linearMaxMass, ship.mass * EurekaConfig.SERVER.linearMassScaling + 1.0)) / 10.0

        if (idealVelocity.lengthSquared() > s * s)
            idealVelocity.normalize(s)

        idealVelocity.mul(ship.mass * (10 - EurekaConfig.SERVER.antiVelocityMassRelevance))
        forces.applyInvariantForce(idealVelocity)
        return idealVelocity.length()
    }
    return 0.0
}

/** The upright-righting + spin-damping half of [stabilize]. */
private fun applyStabilizationTorque(ship: PhysShip, omega: Vector3dc, forces: PhysShip, yaw: Boolean) {
    val shipUp = Vector3d(0.0, 1.0, 0.0)
    ship.transform.shipToWorldRotation.transform(shipUp)

    val angleBetween = shipUp.angle(WORLD_UP)
    val idealAngularAcceleration = Vector3d()
    if (angleBetween > .01) {
        val stabilizationRotationAxisNormalized = shipUp.cross(WORLD_UP, Vector3d()).normalize()
        idealAngularAcceleration.add(
            stabilizationRotationAxisNormalized.mul(
                angleBetween,
                stabilizationRotationAxisNormalized
            )
        )
    }

    // Only subtract the x/z components of omega.
    // We still want to allow rotation along the Y-axis (yaw).
    // Except if yaw is true, then we stabilize
    idealAngularAcceleration.sub(
        omega.x(),
        if (!yaw) 0.0 else omega.y(),
        omega.z()
    )

    val stabilizationTorque = ship.transform.shipToWorldRotation.transform(
        ship.momentOfInertia.transform(
            ship.transform.shipToWorldRotation.transformInverse(idealAngularAcceleration)
        )
    )

    val speed = ship.velocity.length()

    stabilizationTorque.mul(EurekaConfig.SERVER.stabilizationTorqueConstant / max(1.0, speed * speed * EurekaConfig.SERVER.scaledInstability / ship.mass + speed * EurekaConfig.SERVER.unscaledInstability))
    forces.applyInvariantTorque(stabilizationTorque)
}

private fun smoothingATan(smoothing: Double, x: Double): Double = atan(x * smoothing) / smoothing
private fun smoothingATanMax(max: Double, x: Double): Double = smoothingATan(1 / (max * 0.638), x)
