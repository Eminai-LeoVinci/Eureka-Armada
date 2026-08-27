package org.valkyrienskies.eureka.follow

import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipFootprint
import org.valkyrienskies.mod.common.util.toJOMLD
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where a ship is, which way it is pointing, and how big it is along a given direction.
 *
 * All of station-keeping reduces to those three questions asked about two hulls, so they live here as plain
 * functions rather than being spread through the controller. Nothing in this file has state or side effects;
 * everything is derived from the ships' live transforms each tick.
 */
object FollowGeometry {

    /**
     * A leader reduced to the frame a follower takes station in.
     *
     * [alignTrusted] is the honest bit. Three of the four ways we can work out which way a hull points give a
     * direction whose SIGN means something -- that end is the bow. The last one, falling back to the hull's
     * longest axis, gives a line rather than an arrow: it is exactly as true reversed. The controller uses the
     * frame either way (a beam is a beam whichever end is the bow) but must not try to match heading with it,
     * because half the time it would square the follower up facing backwards.
     */
    class Frame(
        /** Geometric centre of the hull, world space. */
        val centre: Vector3d,
        /** Horizontal unit vector along the hull's length. Bow-ward when [alignTrusted]. */
        val forward: Vector3d,
        /** Horizontal unit vector across the hull's beam, `up x forward`. */
        val beam: Vector3d,
        /** Whether [forward] points at the bow rather than merely along the keel line. */
        val alignTrusted: Boolean
    )

    /**
     * Build a leader's frame, or null if the ship has no blocks to measure.
     *
     * The order the forward direction is looked for matters more than it looks:
     *
     *  1. **The live course.** If the ship is being driven or is cruising, this is the direction its own thrust
     *     is pointing, so a follower matching it ends up genuinely parallel rather than merely alongside.
     *  2. **The helm's facing.** Survives with nobody aboard, because the helm block entity restamps it every
     *     tick, so a ship parked at anchor still has a bow.
     *  3. **Velocity.** For a hull with no helm at all that is nonetheless moving -- under tow, or shoved.
     *  4. **The longest horizontal axis.** A guess at the keel line, sign unknown.
     *
     * Velocity is deliberately BELOW the two hull-orientation sources rather than above them. A ship making way
     * astern is still to be come alongside by its hull, not by where it happens to be going, and a leader hard
     * over in a turn has a velocity that swings about while its bow does not.
     */
    fun frameOf(ship: LoadedServerShip): Frame? {
        val centre = centreOf(ship, Vector3d()) ?: return null
        val forward = Vector3d()
        val control = ship.getAttachment(EurekaShipControl::class.java)

        // Read once into a local: helmSeatDir is @Volatile and written from the helm's own tick, so testing the
        // field and then dereferencing it are two different reads.
        val helmDir = control?.helmSeatDir

        var trusted = true
        val found = when {
            control?.pathForward(forward) != null -> true

            helmDir != null -> {
                forward.set(helmDir.normal.toJOMLD())
                ship.transform.shipToWorldRotation.transform(forward)
                // False for a helm facing UP or DOWN, which flattens to nothing -- falls through to the axis guess.
                flatten(forward)
            }

            // Speed is tested BEFORE flattening, because flattening normalizes and there would be no magnitude
            // left to test afterwards.
            ship.velocity.length() > MOVING_SPEED -> flatten(forward.set(ship.velocity))

            else -> false
        }

        if (!found) {
            // Longest horizontal axis of the voxel box, rotated out to the world. A line, not an arrow.
            trusted = false
            if (!longAxis(ship, forward)) return null
        }

        // up x forward. Which side this names is arbitrary and never surfaced -- the follower's side is captured
        // as a sign against this same vector, so whichever way round it is, it cancels.
        val beam = Vector3d(forward.z, 0.0, -forward.x)
        if (beam.lengthSquared() < EPSILON) return null
        beam.normalize()

        return Frame(centre, forward, beam, trusted)
    }

    /**
     * The hull's geometric centre in world space, written into [dest]; null if it has no voxel bounds yet.
     *
     * The voxel box centre rather than the centre of mass, for two reasons. The user's ask is that the two ships'
     * CENTRES line up, and a centre of mass sits wherever the ballast happens to be -- often well below and aft
     * of what anyone looking at the ship would call its middle. And the half-extents below are measured about
     * this same box, so a gap computed from it is actually the gap between the hulls.
     */
    fun centreOf(ship: LoadedServerShip, dest: Vector3d): Vector3d? {
        val aabb = ship.shipAABB ?: return null
        // maxX is the last OCCUPIED block index, so the box spans [minX, maxX + 1] -- hence the +1 before halving.
        dest.set(
            (aabb.minX() + aabb.maxX() + 1) * 0.5,
            (aabb.minY() + aabb.maxY() + 1) * 0.5,
            (aabb.minZ() + aabb.maxZ() + 1) * 0.5
        )
        return ship.transform.shipToWorld.transformPosition(dest)
    }

    /**
     * Half the hull's width measured along a world-space direction, in blocks. 0 if it has no bounds.
     *
     * This is a box's support radius: rotate the direction into ship space and take the sum of each half-extent
     * times the size of that component. Exact for a box at any orientation, and it errs large on a hull that
     * doesn't fill its box -- which is the right way to be wrong here, since it opens the gap rather than
     * closing it.
     */
    fun halfExtentAlong(ship: LoadedServerShip, worldDir: Vector3dc): Double {
        val aabb = ship.shipAABB ?: return 0.0
        val local = Quaterniond(ship.transform.shipToWorldRotation).invert()
            .transform(Vector3d(worldDir))

        val hx = (aabb.maxX() + 1 - aabb.minX()) * 0.5
        val hy = (aabb.maxY() + 1 - aabb.minY()) * 0.5
        val hz = (aabb.maxZ() + 1 - aabb.minZ()) * 0.5

        return abs(local.x) * hx + abs(local.y) * hy + abs(local.z) * hz
    }

    /**
     * Blocks of clear water these two hulls should hold between them.
     *
     * Sized off the pair rather than off either ship, because the gap is one piece of water and both of them
     * are in it: two rafts want a few blocks and two galleons want room to turn without touching, and a
     * galleon beside a raft wants something in between. Averaging also makes the result the same whichever of
     * the two is doing the following, which matters because the leader never gets a say.
     *
     * Rounded DOWN, per the rule this was specified with -- a footprint of 38 holds 11 blocks, and it takes 40
     * to reach 12 rather than 38 rounding up to it.
     */
    fun gapBetween(leader: LoadedServerShip, follower: LoadedServerShip): Double {
        val cfg = EurekaConfig.SERVER
        val base = cfg.followGapBase
        // A step of zero would mean "every ship is infinitely large", so the honest reading is that scaling is
        // switched off and every pair holds the base gap.
        if (cfg.followGapStep <= 0.0) return base

        val pair = (ShipFootprint.of(leader) + ShipFootprint.of(follower)) * 0.5
        // coerceIn rather than coerceAtMost: a max edited below the base would otherwise invert the pair.
        return (base + floor(pair / cfg.followGapStep)).coerceIn(base, max(base, cfg.followGapMax))
    }

    /**
     * The beam vector of a hull whose forward direction is [forward], written into [dest].
     *
     * Same handedness as [Frame.beam] -- `up x forward` -- so a sign taken against one is a sign against the
     * other.
     */
    fun beamOf(forward: Vector3dc, dest: Vector3d): Vector3d = dest.set(forward.z(), 0.0, -forward.x())

    /**
     * Centre-to-centre lateral distance that leaves [gap] blocks of clear water between the two hulls.
     *
     * Measured hull to hull rather than centre to centre so the gap means the same thing at any size. A fixed
     * centre distance would put the galleon's bulwark through the sloop's rail.
     *
     * [followerHalfBeam] is the follower's half-width across ITS OWN beam, and passing it in rather than
     * measuring it here is load-bearing. Measured along the LEADER's beam it would be a function of the
     * follower's instantaneous heading, and for a long hull a violent one -- a 20x60 ship yawed thirty degrees
     * reads 24 blocks wide instead of 10. The station would then slide outward exactly when the follower turned
     * to reach it, which is a loop that feeds itself: the ship swings wide, reads wider still, and swings wider.
     * The follower's own beam is fixed in its own frame, so the station stays put while it manoeuvres.
     */
    fun standoff(leader: LoadedServerShip, frame: Frame, followerHalfBeam: Double, gap: Double): Double =
        halfExtentAlong(leader, frame.beam) + followerHalfBeam + gap

    /**
     * Where the follower should be sitting, written into [dest].
     *
     * `slot` 0 is abeam -- level with the leader's centre, which is the position the feature is really about.
     * Higher slots queue astern of it so a second and third ship joining the same side form a line rather than
     * all converging on one point.
     *
     * Height is the leader's, not the follower's: coming alongside means alongside, and a ship holding station
     * ten blocks below the leader is not in the fight.
     *
     * [keelLift] then raises or lowers that height so the two hulls sit on a common BOTTOM rather than a common
     * middle -- see [keelLiftFor]. Zero restores plain centre-to-centre stationing.
     */
    fun stationPoint(
        leader: Frame,
        side: Int,
        standoff: Double,
        astern: Double,
        keelLift: Double,
        dest: Vector3d
    ): Vector3d = dest.set(leader.centre)
        .fma(side * standoff, leader.beam)
        .fma(-astern, leader.forward)
        .also { it.y = leader.centre.y + keelLift }

    /**
     * How much higher than the leader's centre a follower's centre must sit for the two KEELS to be level.
     *
     * Matching centres is the obvious reading of "come alongside" and it is wrong wherever the two hulls are
     * different depths. A tall ship stationed centre-to-centre on a launch hangs half its draught below the
     * launch's bottom -- under water, if that is where the water is -- and a shallow one alongside a galleon
     * floats with nothing under it. Bottoms are what both of them share: it is the waterline at sea and it is
     * the deck of the formation in the air, and it is the one that looks right in both.
     *
     * The difference of the two half-heights, so the follower's centre is raised by exactly as much as its own
     * hull is deeper than the leader's. Measured as a support radius about world UP, so it is the depth of the
     * hull as it is actually lying -- a ship heeled over is wider under its centre than one sitting level, and
     * this follows it rather than reading a number off the un-rotated box.
     */
    fun keelLiftFor(leader: LoadedServerShip, follower: LoadedServerShip): Double =
        halfExtentAlong(follower, UP) - halfExtentAlong(leader, UP)

    /**
     * Half the hull's horizontal DIAGONAL, in blocks: the radius of the smallest vertical cylinder the hull
     * fits inside however it is turned. 0 if it has no bounds.
     *
     * This is the size a hull has for CIRCLING, where the direction between the two ships sweeps every
     * bearing in turn: any direction-dependent measure would give an orbit that breathes in and out as the
     * hulls rotate past each other, and its narrowest reading is exactly the one that lets a corner touch.
     * The diagonal errs wide on a hull that doesn't fill its box, which opens the ring rather than closing it
     * -- the same right-way-to-be-wrong as [halfExtentAlong].
     */
    fun horizHalfDiagonal(ship: LoadedServerShip): Double {
        val aabb = ship.shipAABB ?: return 0.0
        val hx = (aabb.maxX() + 1 - aabb.minX()) * 0.5
        val hz = (aabb.maxZ() + 1 - aabb.minZ()) * 0.5
        return kotlin.math.sqrt(hx * hx + hz * hz)
    }

    /**
     * How wide a circle this follower should hold about this leader.
     *
     * ## Why not both hulls' diagonals
     * The first cut added the two half-DIAGONALS, which reads as fair and is wrong on both ends. A
     * circling ship flies TANGENTIALLY -- its length lies along the orbit, and what it points at the
     * leader is its beam -- so charging the orbit for the follower's whole length inflated every radius
     * by a hull. Two large ships ended up orbiting 100 blocks apart, far outside cannon range; the only
     * way to close that was a deeply negative gap, which then swung the other way and drove a sloop's
     * orbit INSIDE a big leader's bow, because the same offset is a much larger share of a small radius.
     *
     * So: the LEADER pays its half-diagonal (it sits at the centre and the follower passes every bearing,
     * including over its bow, so its longest reach is the one that must clear) and the FOLLOWER pays only
     * its half-beam. That alone brings a big pair from ~100 blocks to ~70 with the default gap, and it
     * makes the small-follower case shrink gently rather than collapse.
     *
     * ## The two rails
     * [EurekaConfig.Server.followCircleGap] may be negative to pull the orbit in tight, so the result is
     * floored at hulls-plus-[CIRCLE_MIN_CLEARANCE]: no tuning can make ships circle through each other.
     * [EurekaConfig.Server.followCircleMaxDiameter] caps the other end, which is the lever for keeping two
     * big ships inside gun range; 0 lifts the cap. The floor outranks the cap -- a cap tighter than the
     * hulls is a request to collide, and is refused.
     */
    fun orbitRadius(
        leader: LoadedServerShip,
        follower: LoadedServerShip,
        followerBeam: Vector3dc,
        cfg: EurekaConfig.Server
    ): Double {
        val hulls = horizHalfDiagonal(leader) + halfExtentAlong(follower, followerBeam)
        val floor = hulls + CIRCLE_MIN_CLEARANCE
        val wanted = hulls + cfg.followCircleGap
        val capped = if (cfg.followCircleMaxDiameter > 0.0) {
            min(wanted, cfg.followCircleMaxDiameter * 0.5)
        } else {
            wanted
        }
        return max(floor, capped)
    }

    /** Blocks of water an orbit keeps between the hulls however hard the gap is tuned down. */
    private const val CIRCLE_MIN_CLEARANCE = 3.0

    /** Which side of the leader a point is on: +1, -1, or 0 when it is dead on the centreline. */
    fun sideOf(leader: Frame, point: Vector3dc, deadband: Double): Int {
        val lateral = (point.x() - leader.centre.x) * leader.beam.x +
            (point.z() - leader.centre.z) * leader.beam.z
        return if (lateral > deadband) 1 else if (lateral < -deadband) -1 else 0
    }

    /** Flatten to horizontal and normalize in place; false if there was nothing horizontal left to keep. */
    private fun flatten(v: Vector3d): Boolean {
        v.y = 0.0
        if (v.lengthSquared() < EPSILON) return false
        v.normalize()
        return true
    }

    /** The longer of the hull's two horizontal axes, rotated to world space. False if it has no bounds. */
    private fun longAxis(ship: LoadedServerShip, dest: Vector3d): Boolean {
        val aabb = ship.shipAABB ?: return false
        val spanX = aabb.maxX() - aabb.minX()
        val spanZ = aabb.maxZ() - aabb.minZ()
        dest.set(if (spanX >= spanZ) 1.0 else 0.0, 0.0, if (spanX >= spanZ) 0.0 else 1.0)
        ship.transform.shipToWorldRotation.transform(dest)
        return flatten(dest)
    }

    /** Below this (m/s) a hull's velocity says nothing about which way it is pointing. */
    private const val MOVING_SPEED = 0.5

    /** World up, for the hull-depth measurement [keelLiftFor] is built on. */
    private val UP: Vector3dc = Vector3d(0.0, 1.0, 0.0)

    private const val EPSILON = 1.0e-9
}
