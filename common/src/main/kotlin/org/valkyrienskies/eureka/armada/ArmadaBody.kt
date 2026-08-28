package org.valkyrienskies.eureka.armada

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.PhysShip

/**
 * An armada seen as ONE rigid body, and the distributor that makes it behave like one.
 *
 * ## Why this exists
 *
 * [org.valkyrienskies.eureka.ship.EurekaShipControl] is a single-hull control law: it sizes forces from a ship's
 * own mass, torques from its own moment of inertia, and lift from its own balloons. Rigid welds make an armada
 * one body, but they do not make the control law aware of that -- so run per ship it produced a result none of
 * the instances modelled. Every force applied at one member's centre of mass alone acts on the formation
 * through a lever arm, which is a yaw generator; every torque sized for one hull moves a body many times its
 * inertia. Chasing those term by term is endless, because each new term has to be classified by hand.
 *
 * So the control law computes ONCE, against the armada's combined properties, and hands the result here. This
 * class owns the only two operations it needs -- "push the armada" and "spin the armada" -- and splits each
 * across the members so the response is exactly that of the single rigid body they form.
 *
 * ## The distribution
 *
 * A force `F` is split by mass share: member `i` applies `F * m_i/M` at its own centre. The parts sum to `F`,
 * and because each share is proportional to its own mass the net passes through the combined centre of mass --
 * so a pure push produces no rotation, whatever the geometry.
 *
 * An angular acceleration `alpha` about the combined centre is split into two parts per member: the torque
 * `I_i * alpha` that spins that hull about its own centre, plus a force `m_i * (alpha x r_i)` at its own centre
 * that carries it around the combined one, with `r_i` the offset from the combined centre. Summed about the
 * combined centre those give `(sum I_i + sum m_i*(|r_i|^2*E - r_i*r_i^T)) * alpha`, which is the parallel-axis
 * definition of the armada's own inertia tensor -- so the commanded angular acceleration is delivered exactly,
 * with no tensor ever assembled. The forces cancel in aggregate (`sum m_i*r_i` is zero about the centre of
 * mass), so spinning never also shoves the armada sideways.
 *
 * That split is also why this loads the welds far less than driving from one ship would: each hull is handed
 * its own share of the rotation instead of being dragged around by its neighbours through the joints.
 *
 * ## Solo ships
 *
 * A lone ship is a one-member armada. Its mass share is 1, its offset from the combined centre is zero, and the
 * cross-product term vanishes -- so it takes exactly the force and torque the control law asked for, and
 * nothing about its handling changes.
 *
 * ## Lifetime
 *
 * Physics-thread only, and rebuilt every tick by [clear]/[add]/[build] into a single instance held by the
 * controlling ship, so a steady state allocates nothing. Vectors handed to the ships are freshly allocated
 * because vs-core queues force applications by reference.
 */
class ArmadaBody {

    private var ships = arrayOfNulls<PhysShip>(INITIAL_CAPACITY)
    private var masses = DoubleArray(INITIAL_CAPACITY)
    private var offsets = Array(INITIAL_CAPACITY) { Vector3d() }

    /** Number of members contributing to this body. */
    var size = 0
        private set

    /** Combined mass of every member, in kg. */
    var mass = 0.0
        private set

    private val com = Vector3d()
    private val vel = Vector3d()
    private val angVel = Vector3d()

    /** The armada's combined centre of mass, in world space. */
    val centerOfMass: Vector3dc get() = com

    /** Velocity of the combined centre of mass -- the mass-weighted mean of the members'. */
    val velocity: Vector3dc get() = vel

    /** The armada's angular velocity. Read from the lead: the welds make every member share it. */
    val angularVelocity: Vector3dc get() = angVel

    /**
     * The member whose attitude and heading stand for the armada's -- the first one [add]ed, i.e. the parent.
     * Everything that asks "which way is this thing pointing" (seat facing, upright correction, turn radius)
     * reads it, so the armada steers and rights itself as the parent would alone.
     */
    val lead: PhysShip get() = ships[0]!!

    /** True when nothing is bound to the lead, i.e. this is an ordinary single ship. */
    val isSolo: Boolean get() = size <= 1

    /** The [index]th member, in the order they were [add]ed -- index 0 is the [lead]. */
    fun memberAt(index: Int): PhysShip = ships[index]!!

    /** Begins a rebuild. Add the parent FIRST -- it becomes the [lead]. */
    fun clear() {
        for (i in 0 until size) ships[i] = null
        size = 0
        mass = 0.0
    }

    /** Adds a member. Never add a static ship: [PhysShip.applyQueuedForces] skips those, so anything queued on
     *  one is never applied and never dropped -- it accumulates without bound. */
    fun add(ship: PhysShip) {
        if (size == ships.size) grow()
        ships[size] = ship
        masses[size] = ship.mass
        size++
    }

    /** Computes the combined properties from the members added since [clear]. */
    fun build() {
        var totalMass = 0.0
        com.set(0.0, 0.0, 0.0)
        vel.set(0.0, 0.0, 0.0)

        for (i in 0 until size) {
            val ship = ships[i]!!
            val m = masses[i]
            totalMass += m
            com.fma(m, ship.transform.positionInWorld)
            vel.fma(m, ship.velocity)
        }

        mass = totalMass
        if (totalMass > 0.0) {
            com.div(totalMass)
            vel.div(totalMass)
        } else {
            // A massless body can't be driven at all; fall back to the lead's pose so nothing reads NaN.
            com.set(ships[0]!!.transform.positionInWorld)
            vel.set(ships[0]!!.velocity)
        }

        angVel.set(ships[0]!!.angularVelocity)

        for (i in 0 until size) {
            offsets[i].set(ships[i]!!.transform.positionInWorld).sub(com)
        }
    }

    /**
     * Applies [forceInWorld] to the armada as a whole, split across the members by mass share so the net acts
     * through the combined centre of mass and adds no rotation.
     */
    fun applyForce(forceInWorld: Vector3dc) {
        if (mass <= 0.0) return
        if (size == 1) {
            ships[0]!!.applyInvariantForce(Vector3d(forceInWorld))
            return
        }
        for (i in 0 until size) {
            ships[i]!!.applyInvariantForce(Vector3d(forceInWorld).mul(masses[i] / mass))
        }
    }

    /**
     * Spins the armada at [alphaInWorld] radians/s^2 about its combined centre of mass, sized for the whole
     * formation's inertia rather than any one hull's. See the class docs for the split.
     */
    fun applyAngularAcceleration(alphaInWorld: Vector3dc) {
        for (i in 0 until size) {
            val ship = ships[i]!!

            // The torque that spins this hull about its own centre: I_i * alpha, with the model-space inertia
            // tensor conjugated into world space (rotate alpha into the model frame, apply, rotate back).
            val rotation = ship.transform.shipToWorldRotation
            val torque = Vector3d(alphaInWorld)
            rotation.transformInverse(torque)
            ship.momentOfInertia.transform(torque)
            rotation.transform(torque)
            ship.applyInvariantTorque(torque)

            // The force that carries this hull around the COMBINED centre: m_i * (alpha x r_i). Zero for a lone
            // ship, whose offset from that centre is zero.
            if (size > 1) {
                ship.applyInvariantForce(Vector3d(alphaInWorld).cross(offsets[i]).mul(masses[i]))
            }
        }
    }

    private fun grow() {
        val capacity = ships.size * 2
        ships = ships.copyOf(capacity)
        masses = masses.copyOf(capacity)
        offsets = Array(capacity) { if (it < offsets.size) offsets[it] else Vector3d() }
    }

    private companion object {
        // A parent plus three children covers any armada anyone has built; it grows if not.
        const val INITIAL_CAPACITY = 4
    }
}
