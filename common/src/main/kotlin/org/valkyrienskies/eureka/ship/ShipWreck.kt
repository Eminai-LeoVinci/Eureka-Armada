package org.valkyrienskies.eureka.ship

import kotlin.math.floor
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * How deep a wreck buries herself, and which way she lies when she does.
 *
 * A ship that goes down should end up PART of the ground rather than parked on top of it -- half in the
 * sand, coral standing through her deck, the landscape closed over her. That is what this measures.
 *
 * ## How deep, and measured against what
 * A fraction of her height AFTER she is laid on her side -- `wreckBurialFraction`, half by default, so the
 * surface runs through her middle.
 *
 * Against the ROLLED height, which is the whole trick. The first cut measured a fraction of her UPRIGHT
 * height and applied it to a hull lying on her side, and a first-rate on her beam ends is as tall as she is
 * wide: one test wreck showed five blocks of a thirty-six-block hull and another could not be found at all.
 * Half of what you can actually see is what "half buried" means.
 *
 * The notional box below is what remains of an earlier design and now only feeds the `/vs wreck-box`
 * overlay, where it marks the burial line and the hull's size tier at a glance.
 *
 * ## Why this is a measurement and not a collider
 * It was a collider first, and that does not work. VS2 offers no way to replace a ship's collision shape,
 * so the attempt cleared the hull's physics voxels and wrote a small box of them instead. That does remove
 * her collision with the world. It also takes the player's footing with it.
 *
 * The reason is worth keeping. `Ship.getShipAABB` is the ship's VOXEL bounding box -- VS2's own name for it
 * -- it is read-only, and it collapses to whatever voxels remain. Player-vs-ship collision begins with
 * `loadedShips.getIntersecting(entityBox)` and only then looks at blocks, so a hull whose AABB has shrunk to
 * the size of a rowboat is a hull nobody can stand on. That same AABB feeds the influence border that drags
 * riders along, the water probes, the world freeze during teardown, and this feature's own "has everyone
 * left?" test. Emptying the voxels does not remove a ship's collision. It removes the ship's EXTENT, and
 * everything that asks how big she is gets a different answer.
 *
 * Keeping the voxels means the physics engine will always rest her on her own hull, and there is no per-ship
 * way to tell it otherwise -- `disableCollisionBetween` resolves both its arguments as ships, so it cannot
 * be aimed at the ground body. Driving her down ourselves through `ServerShip.setTransformProvider` is the
 * one route still open, and it trades simulated tumbling for choreography. So she falls exactly as she
 * always has, and the burial happens at the moment she comes apart -- where it costs nothing, cannot break
 * anything, and is the half of it anyone sees afterwards.
 */
object ShipWreck {

    /** The notional box, in shipyard coordinates. Inclusive bounds. */
    class Box(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int
    ) {
        override fun toString() = "[" + minX + "," + minY + "," + minZ + " .. " + maxX + "," + maxY + "," + maxZ + "]"
    }


    /**
     * Where the box goes, and how big.
     *
     * Sized by the hull's block count at assembly -- an O(1) read off the attachment, not a survey -- and
     * laid with its long axis down whichever horizontal span of the hull is longer. That is the keel for any
     * ship built the ordinary way, and it needs no forward vector to work out, which matters because a hull
     * this far gone may have lost the wheel that would have told us.
     *
     * Height is a fraction of the hull rather than a fixed offset, so the burial scales with the ship: the
     * box is what she would have come to rest on, and how far up it sits is how much of her ends up under.
     */
    fun boxFor(ship: LoadedServerShip, control: EurekaShipControl): Box? {
        val cfg = EurekaConfig.SERVER
        val hull = ship.shipAABB ?: return null

        val blocks = control.assembledBlocks
        val dims = when {
            blocks >= cfg.wreckBoxLargeMinBlocks -> cfg.wreckBoxLarge
            blocks >= cfg.wreckBoxMediumMinBlocks -> cfg.wreckBoxMedium
            else -> cfg.wreckBoxSmall
        }
        // A hand-edited config is allowed to be wrong without taking the feature down with it.
        val width = dims.getOrElse(0) { 1 }.coerceIn(1, 64)
        val height = dims.getOrElse(1) { 2 }.coerceIn(1, 64)
        val length = dims.getOrElse(2) { 3 }.coerceIn(1, 64)

        val alongX = rollsAboutX(ship)
        val sizeX = if (alongX) length else width
        val sizeZ = if (alongX) width else length

        val centreX = (hull.minX() + hull.maxX() + 1) * 0.5
        val centreZ = (hull.minZ() + hull.maxZ() + 1) * 0.5
        val hullHeight = (hull.maxY() - hull.minY() + 1).toDouble()
        val centreY = hull.minY() + hullHeight * cfg.wreckBurialFraction.coerceIn(0.0, 1.0)

        val startX = floor(centreX - sizeX * 0.5).toInt()
        val startY = floor(centreY - height * 0.5).toInt().coerceIn(hull.minY(), hull.maxY())
        val startZ = floor(centreZ - sizeZ * 0.5).toInt()

        return Box(
            startX, startY, startZ,
            startX + sizeX - 1, startY + height - 1, startZ + sizeZ - 1
        )
    }

    /**
     * Which of the hull's own axes she rolls about when she is laid on her side: the longer horizontal one,
     * which for anything built like a ship is her keel line. Also decides which way the box lies.
     */
    fun rollsAboutX(ship: LoadedServerShip): Boolean {
        val hull = ship.shipAABB ?: return true
        return (hull.maxX() - hull.minX()) >= (hull.maxZ() - hull.minZ())
    }

    // region The overlay -- "/vs wreck-box <bool>"
    //
    // The box is a number rather than a thing, which leaves "how deep is she actually going?" a question
    // with no visible answer. This draws it: GREEN on a sound hull, RED once she is a wreck and that box is
    // the depth she will bury to. Because it draws on healthy ships too, it also shows at a glance which
    // size tier a hull falls into, before anything has happened to her.
    //
    // Single-player only, like every other "/vs" debug toggle: a client command cannot reach a dedicated
    // server, and this reads a static the integrated server writes. DEV ONLY, strip-listed with the command.

    @Volatile
    var publishBoxes = false

    /** One hull's box as the render thread needs it. [active] is the green/red: is she a wreck yet? */
    class BoxView(
        val shipId: Long,
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
        val active: Boolean
    )

    private val published = java.util.concurrent.ConcurrentHashMap<String, List<BoxView>>()

    fun publishedBoxes(dimension: String): List<BoxView> = published[dimension] ?: emptyList()

    /**
     * Publish every loaded hull's box for the overlay. Called every server tick and self-silencing to one
     * boolean read while the toggle is off.
     */
    fun publish(level: ServerLevel) {
        if (!publishBoxes) {
            if (published.isNotEmpty()) published.clear()
            return
        }
        val dimension = level.dimensionId
        val out = ArrayList<BoxView>()
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val control = ship.getAttachment(EurekaShipControl::class.java) ?: continue
            val box = boxFor(ship, control) ?: continue
            out.add(
                BoxView(
                    ship.id,
                    box.minX.toDouble(), box.minY.toDouble(), box.minZ.toDouble(),
                    // +1: the bounds are inclusive BLOCK coordinates, and a block is a whole cube wide.
                    (box.maxX + 1).toDouble(), (box.maxY + 1).toDouble(), (box.maxZ + 1).toDouble(),
                    control.wrecked
                )
            )
        }
        published[dimension] = out
    }

    /** SERVER_STOPPED teardown. */
    fun reset() {
        published.clear()
    }
    // endregion
}
