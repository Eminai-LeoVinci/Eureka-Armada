package org.valkyrienskies.eureka.ship

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaBody
import kotlin.math.atan
import kotlin.math.max

// World-up is never mutated (angle/cross read it only) — shared constant instead of a fresh
// Vector3d per stabilize call.
private val WORLD_UP: Vector3dc = Vector3d(0.0, 1.0, 0.0)

/**
 * Keeps a hands-off ship upright and brings it to rest.
 *
 * Everything is expressed against [body] -- the ship, or the whole armada it leads, seen as one rigid body --
 * so an armada rights and brakes itself as a single vessel: the brake is one force through the combined centre
 * of mass (no lever arm, so no yaw), and the righting torque is sized for the formation's inertia rather than
 * the parent hull's. See [ArmadaBody] for how each is split across the members.
 *
 * [cfg] is the caller's SHIP-CATEGORY settings block ([EurekaShipControl.engineCfg]), not the global one:
 * how hard a hull rights itself and how quickly it comes to rest is a large part of what makes an airship
 * feel loose and a boat feel planted, so all six knobs read here are per-category.
 *
 * Returns the magnitude of the linear anti-velocity (braking) force applied, or 0 if [linear] is false.
 */
fun stabilize(
    body: ArmadaBody,
    cfg: EurekaConfig.Server,
    linear: Boolean,
    yaw: Boolean
): Double {
    applyStabilizationTorque(body, cfg, yaw)

    if (linear) {
        val idealVelocity = Vector3d(body.velocity).negate()
        idealVelocity.y = 0.0

        // ideally this should work the same way as input is scaled
        val s = cfg.linearStabilizeMaxAntiVelocity * (1 - 1 / smoothingATanMax(cfg.linearMaxMass, body.mass * cfg.linearMassScaling + 1.0)) / 10.0

        if (idealVelocity.lengthSquared() > s * s)
            idealVelocity.normalize(s)

        idealVelocity.mul(body.mass * (10 - cfg.antiVelocityMassRelevance))
        body.applyForce(idealVelocity)
        return idealVelocity.length()
    }
    return 0.0
}

/** The upright-righting + spin-damping half of [stabilize]. */
private fun applyStabilizationTorque(body: ArmadaBody, cfg: EurekaConfig.Server, yaw: Boolean) {
    // Attitude comes from the lead: the welds make the armada one body, so the parent's up IS the armada's up.
    val shipUp = Vector3d(0.0, 1.0, 0.0)
    body.lead.transform.shipToWorldRotation.transform(shipUp)

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
    val omega = body.angularVelocity
    idealAngularAcceleration.sub(
        omega.x(),
        if (!yaw) 0.0 else omega.y(),
        omega.z()
    )

    val speed = body.velocity.length()

    // Scaling the commanded acceleration is equivalent to scaling the torque it becomes, since the body turns
    // one into the other linearly.
    idealAngularAcceleration.mul(cfg.stabilizationTorqueConstant / max(1.0, speed * speed * cfg.scaledInstability / body.mass + speed * cfg.unscaledInstability))
    body.applyAngularAcceleration(idealAngularAcceleration)
}

private fun smoothingATan(smoothing: Double, x: Double): Double = atan(x * smoothing) / smoothing
private fun smoothingATanMax(max: Double, x: Double): Double = smoothingATan(1 / (max * 0.638), x)
