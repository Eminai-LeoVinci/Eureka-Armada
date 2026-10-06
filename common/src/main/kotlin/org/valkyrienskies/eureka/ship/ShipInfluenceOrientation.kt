package org.valkyrienskies.eureka.ship

import net.minecraft.core.Direction
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the influence-border faces (Front / Back / Left / Right) follow each ship's HELM instead of the
 * raw ship-space axes.
 *
 * ## Why this exists
 * The per-face influence-border extension ([org.valkyrienskies.eureka.EurekaConfig.Client]) stores six
 * values named Top/Bottom/Left/Right/Front/Back. A naive implementation pins those names to fixed ship-space
 * axes (`+Z = Front`, `-X = Left`, ...). But a ship's forward direction *in ship space* is whatever the helm
 * happened to face when the ship was assembled -- assemble the same raft facing east vs. north and its
 * ship-space forward axis differs by 90 degrees. So a fixed name->axis mapping is only ever correct for ships
 * built in one particular orientation; on every other ship the "Front" command visibly grows a side face, etc.
 * (Re-assembling doesn't help -- it just re-freezes ship space to the same world axes.)
 *
 * ## What it does instead
 * We learn each ship's forward horizontal [Direction] in ship space (see [observeForward]) and rotate the
 * Front/Back/Left/Right values onto the ship's actual faces at read time ([horizontalExtents]). Forward is the
 * helm-thrust direction -- `seat.direction.opposite`, the same value Eureka feeds into
 * `SeatedControllingPlayer.seatInDirection` -- so the border matches the universal helm convention (wheel/player
 * side is the stern, the helm's back faces the bow). Top/Bottom stay pinned to +-Y (gravity is unambiguous).
 *
 * The forward is seeded the instant a player sits at a helm (a driving packet is sent every tick while seated,
 * even with no input) and is never cleared, so a parked ship keeps its heading. Until a ship has ever been
 * sat in, [forwardFor] falls back to [Direction.SOUTH] (`+Z`), i.e. the plain ship-space default.
 *
 * It is fed from the helm on both sides: the client while a player rides the seat (MixinHelmDpadClimb), the server
 * from the ship's SeatedControllingPlayer (ShipKeepActive). In single-player both share this one singleton.
 *
 * Our VS2 build used to carry this; it lives in Armada now, alongside [ShipInfluence].
 */
object ShipInfluenceOrientation {

    // shipId -> learned forward (ship-space, horizontal). Written from the seat-driving paths, read by the
    // carry test and the debug wireframe; ConcurrentHashMap because those live on different threads in SP.
    private val forwardByShip = ConcurrentHashMap<Long, Direction>()

    /**
     * Record [forward] (a ship-space horizontal [Direction]) as [shipId]'s heading. No-op for a vertical
     * direction. Called every tick a player is seated at that ship's helm; the value persists after they
     * stand up so a drifting/parked ship keeps the last-known forward.
     */
    fun observeForward(shipId: Long, forward: Direction) {
        if (forward.axis.isHorizontal) forwardByShip[shipId] = forward
    }

    /**
     * The ship's learned forward, or [Direction.SOUTH] (`+Z`) if it has never been sat in this session --
     * the same axis the old fixed mapping treated as "Front".
     */
    fun forwardFor(shipId: Long): Direction = forwardByShip[shipId] ?: Direction.SOUTH

    /**
     * Rotate the four helm-relative horizontal extension values onto ship-space axes for the given [forward].
     * Returns the outward inflation to apply to each horizontal face as `[negX, posX, negZ, posZ]`:
     * subtract `[0]` from minX, add `[1]` to maxX, subtract `[2]` from minZ, add `[3]` to maxZ. Top/Bottom
     * (+-Y) are handled by the caller since they never rotate.
     */
    fun horizontalExtents(
        forward: Direction, front: Double, back: Double, left: Double, right: Double
    ): DoubleArray {
        val e = DoubleArray(4) // negX, posX, negZ, posZ
        val f = if (forward.axis.isHorizontal) forward else Direction.SOUTH
        fun put(dir: Direction, v: Double) {
            when (dir) {
                Direction.WEST -> e[0] = v
                Direction.EAST -> e[1] = v
                Direction.NORTH -> e[2] = v
                Direction.SOUTH -> e[3] = v
                else -> {}
            }
        }
        put(f, front)                    // bow
        put(f.opposite, back)            // stern
        put(f.counterClockWise, left)    // port
        put(f.clockWise, right)          // starboard
        return e
    }
}
