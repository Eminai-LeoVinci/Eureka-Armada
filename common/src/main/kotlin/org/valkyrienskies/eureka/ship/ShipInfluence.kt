package org.valkyrienskies.eureka.ship

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Keeps you carried by a ship while you are in the air around it.
 *
 * VS2 carries an entity with a ship for 25 ticks after it last touched the ship. That is fine for a hop, but a
 * player who falls off a tall ship, glides with an elytra, or flies in creative leaves that window and is left
 * behind as the ship sails on. So before VS2 drags anything each tick ([beforeDrag], called from
 * MixinEntityDraggerInfluence), this adjusts the drag state VS2 keeps on each entity:
 *
 * - Standing on the hull keeps the carry alive outright, measured against the ship's raw box in ship space. VS2's
 *   own standing test misreads slabs, stairs, fences and deck edges, and dropped players standing on them.
 * - Standing or swimming OFF the hull (on shore, in world water) releases the carry at once.
 * - Airborne (falling, gliding, creative flight) inside the ship's influence border keeps the carry; leaving the
 *   border releases it at once. The border is the hull's box grown per face by the client config's
 *   `influenceExtend*` values, with Front/Back/Left/Right following the helm ([ShipInfluenceOrientation]).
 *
 * Only an entity VS2 already carries is touched: the carry is still only ever picked up by touching the ship.
 * The drag itself stays entirely VS2's; this only decides whether its window stays open.
 *
 * Our VS2 build did this inside its own entity dragger. It lives in Armada now so Armada runs on the official
 * release.
 */
object ShipInfluence {

    /**
     * Horizontal slack, in ship-space blocks, on the standing-on-hull test, so a bounding box that hangs over the
     * deck rim still reads as aboard. Always on, and deliberately not widened by the influence border.
     */
    private const val HULL_EDGE_MARGIN = 0.5

    @JvmStatic
    fun beforeDrag(entities: Iterable<Entity>) {
        for (entity in entities) {
            if (entity.isRemoved) continue
            val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: continue
            var carriedShipId = info.lastShipStoodOn ?: continue

            val isGliding = (entity as? LivingEntity)?.isFallFlying == true
            val isFreefallingPlayer = entity is Player && !entity.onGround() && !entity.abilities.flying
            val isFlyingPlayer = entity is Player && entity.abilities.flying

            if (entity is Player) {
                val hullShip = entity.level().shipObjectWorld.allShips.getById(carriedShipId)
                val hull = hullShip?.shipAABB
                val onCarriedHull = if (hullShip != null && hull != null) {
                    // The player's position before this tick's carry is anchored to the ship's PREVIOUS transform,
                    // so measure it with that transform; the current one would slide a stern-stander backward by a
                    // tick of travel and drop them off a fast ship.
                    val wp = entity.position()
                    val lp = hullShip.prevTickTransform.worldToShip.transformPosition(wp.x, wp.y, wp.z, Vector3d())
                    lp.x >= hull.minX() - HULL_EDGE_MARGIN && lp.x <= hull.maxX() + 1.0 + HULL_EDGE_MARGIN &&
                        lp.z >= hull.minZ() - HULL_EDGE_MARGIN && lp.z <= hull.maxZ() + 1.0 + HULL_EDGE_MARGIN &&
                        lp.y >= hull.minY() - 1.0 && lp.y <= hull.maxY() + 2.0
                } else {
                    false
                }
                if (!onCarriedHull && (entity.onGround() || entity.isInWater)) {
                    // On shore or in world water: let go now.
                    release(info)
                    continue
                } else if (entity.onGround() && onCarriedHull) {
                    info.ticksSinceStoodOnShip = 0
                }
            }

            if (!(isGliding || isFreefallingPlayer || isFlyingPlayer)) continue
            val ship = entity.level().shipObjectWorld.allShips.getById(carriedShipId)
            val inside = ship != null && insideBorder(ship, entity.x, entity.y, entity.z)
            if (inside) {
                // Keep VS2's carry window open.
                info.ticksSinceStoodOnShip = 0
            } else {
                // Left the border: let go now, with nothing buffered to push them along afterwards.
                release(info)
            }
        }
    }

    /**
     * Is world position ([x], [y], [z]) inside [ship]'s influence border: its hull box grown per face by the
     * `influenceExtend*` config, Front/Back/Left/Right following the helm? False if the ship has no box yet.
     */
    @JvmStatic
    fun insideBorder(ship: org.valkyrienskies.core.api.ships.Ship, x: Double, y: Double, z: Double): Boolean {
        val box = ship.shipAABB ?: return false
        val lp = ship.worldToShip.transformPosition(x, y, z, Vector3d())
        val cfg = EurekaConfig.CLIENT
        val h = ShipInfluenceOrientation.horizontalExtents(
            ShipInfluenceOrientation.forwardFor(ship.id),
            cfg.influenceExtendFront, cfg.influenceExtendBack, cfg.influenceExtendLeft, cfg.influenceExtendRight
        )
        return lp.x >= box.minX() - h[0] && lp.x <= box.maxX() + h[1] &&
            lp.y >= box.minY() - cfg.influenceExtendBottom && lp.y <= box.maxY() + cfg.influenceExtendTop &&
            lp.z >= box.minZ() - h[2] && lp.z <= box.maxZ() + h[3]
    }

    private fun release(info: org.valkyrienskies.mod.common.util.EntityDraggingInformation) {
        info.lastShipStoodOn = null
        info.addedMovementLastTick = Vector3d()
        info.addedYawRotLastTick = 0.0
    }
}
