package org.valkyrienskies.eureka.ship

import com.fasterxml.jackson.annotation.JsonIgnore
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.eureka.EurekaConfig

/**
 * Makes a water-borne ship ride the ocean swell (heave, pitch and roll) in step with the waves the shader draws, by
 * sampling [OceanWaveField] over a grid of hull points and pushing each point toward the local wave surface.
 * Because the push goes through VS2's own physics, everyone aboard is carried along with the bob.
 *
 * Per sample point: a spring toward the wave's deviation from mean sea level plus damping on the point's vertical
 * speed, scaled by the ship's mass. Using the deviation rather than the absolute height adds only the oscillation, so
 * the floaters that hold the ship at sea level are not fought. Points either side of a crest give pitch and roll.
 *
 * Put on every ship as it loads (transient, never saved). Does nothing unless `waveBuoyancy` is on and the ship is in
 * water. An error turns the waves off for that ship only rather than taking the physics thread down.
 *
 * Our VS2 build carried this; it lives in Armada now so Armada runs on the official release.
 */
class WaveBuoyancyAttachment : ShipPhysicsListener {

    @JsonIgnore
    internal var ship: LoadedServerShip? = null

    /** Set after an error, to keep this ship's wave forces off without crashing the physics thread. */
    @JsonIgnore
    private var disabled = false

    // Scratch vectors reused across the grid. Safe for the position argument because vs-core copies it; the FORCE
    // vector is queued by reference, so it stays a fresh allocation.
    @JsonIgnore
    private val scratchModelPos = Vector3d()

    @JsonIgnore
    private val scratchWorldPos = Vector3d()

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (disabled) return
        val cfg = EurekaConfig.SERVER
        if (!cfg.waveBuoyancy) return
        // Only ships touching liquid bob.
        if (physShip.liquidOverlap <= 0.0) return

        val ship = ship ?: return
        try {
            val mass = physShip.mass
            if (mass <= 0.0) return

            val aabb = ship.shipAABB ?: return
            val minX = aabb.minX().toDouble()
            val maxX = aabb.maxX().toDouble()
            val minZ = aabb.minZ().toDouble()
            val maxZ = aabb.maxZ().toDouble()
            // Sampled at the hull's vertical middle; only the horizontal spread matters for pitch and roll.
            val midY = (aabb.minY().toDouble() + aabb.maxY().toDouble()) * 0.5

            val transform = physShip.transform
            val shipToWorld = transform.shipToWorld
            val center = transform.positionInWorld
            val vel = physShip.velocity
            val omega = physShip.angularVelocity

            val n = cfg.waveSampleGrid.coerceIn(1, 8)
            val pointMass = mass / (n * n)
            val stiffness = cfg.waveStiffness
            val damping = cfg.waveDamping

            for (ix in 0 until n) {
                for (iz in 0 until n) {
                    val fx = if (n == 1) 0.5 else ix.toDouble() / (n - 1)
                    val fz = if (n == 1) 0.5 else iz.toDouble() / (n - 1)
                    val mx = minX + (maxX - minX) * fx
                    val mz = minZ + (maxZ - minZ) * fz

                    val modelPos = scratchModelPos.set(mx, midY, mz)
                    val worldPos = shipToWorld.transformPosition(mx, midY, mz, scratchWorldPos)

                    // The wave's deviation from mean sea level under this point.
                    val deviation = OceanWaveField.height(worldPos.x, worldPos.z)

                    // Vertical speed of this point: vel.y + (omega x r).y, r = worldPos - center.
                    val rx = worldPos.x - center.x()
                    val rz = worldPos.z - center.z()
                    val pointVelY = vel.y() + (omega.z() * rx - omega.x() * rz)

                    val force = (deviation * stiffness - pointVelY * damping) * pointMass
                    if (force.isFinite()) {
                        physShip.applyWorldForceToModelPos(Vector3d(0.0, force, 0.0), modelPos)
                    }
                }
            }
        } catch (t: Throwable) {
            disabled = true
            LOGGER.error("[wave buoyancy] turned off for ship ${ship.id} after an error in physTick", t)
        }
    }

    companion object {
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("vs_eureka-wave-buoyancy")
    }
}
