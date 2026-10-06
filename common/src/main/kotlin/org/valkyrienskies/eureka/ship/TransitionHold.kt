package org.valkyrienskies.eureka.ship

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Holds everything standing in a hull's footprint up while the hull swaps between world and shipyard.
 *
 * Assembly and disassembly move the blocks one way and the collision the other, and for a moment neither copy
 * is solid under anyone standing on it. [markWorldFreeze] arms a short hold over the hull's world box, and
 * MixinEntityTransitionHold clamps only the DOWNWARD part of movement for anything inside it: walking and the
 * camera stay free, falling through the deck does not happen.
 *
 * A held player is let go early, as soon as something would actually hold them up (see [probeWouldSupport]), so
 * nobody hangs in the air for the whole window. Every hold has a deadline, so none can outlive its transition.
 */
object TransitionHold {

    private class Hold(
        val dimensionId: String,
        val aabb: AABBd,
        val deadlineGameTime: Long,
        val createdGameTime: Long
    )

    private val holds = ConcurrentLinkedQueue<Hold>()

    /**
     * Release bookkeeping for held players, keyed by entity id. [supportSeen] is when a support probe first
     * passed; [releasedAt] exempts a player from every hold created before it. Server side only: in
     * singleplayer both sides share this object, and the client can't probe. Both maps are cleared whenever no
     * hold is armed, which keeps them to the transition windows themselves.
     */
    private val supportSeen = ConcurrentHashMap<Int, Long>()
    private val releasedAt = ConcurrentHashMap<Int, Long>()

    /** How far below a held player the support probe reaches: covers the grid snap and a jump caught mid-air. */
    private const val SUPPORT_PROBE_BLOCKS = 2.0

    /** A support stamp must be this old before the next passing probe releases: detect one tick, free the next. */
    private const val SUPPORT_CONFIRM_TICKS = 1L

    /** The ship's world-space box, from its shipyard box transformed to world. */
    @JvmStatic
    fun worldAABBForShip(ship: Ship): AABBd {
        val sb = ship.shipAABB
        return if (sb != null) {
            AABBd(
                sb.minX().toDouble(), sb.minY().toDouble(), sb.minZ().toDouble(),
                (sb.maxX() + 1).toDouble(), (sb.maxY() + 1).toDouble(), (sb.maxZ() + 1).toDouble()
            ).transform(ship.shipToWorld)
        } else {
            val p = ship.transform.position
            AABBd(p.x() - 32.0, p.y() - 32.0, p.z() - 32.0, p.x() + 32.0, p.y() + 32.0, p.z() + 32.0)
        }
    }

    /** Hold everything in the WORLD box [aabb] up for [durationTicks] game ticks. */
    @JvmStatic
    fun markWorldFreeze(level: Level, aabb: AABBd, durationTicks: Long) {
        val now = level.gameTime
        holds.add(Hold(level.dimensionId, aabb, now + durationTicks, now))
    }

    /** Should [entity]'s fall be held this tick? */
    @JvmStatic
    fun shouldHoldGravity(entity: Entity): Boolean {
        if (holds.isEmpty()) {
            if (supportSeen.isNotEmpty()) supportSeen.clear()
            if (releasedAt.isNotEmpty()) releasedAt.clear()
            return false
        }
        val level = entity.level()
        val now = level.gameTime
        val dim = level.dimensionId
        val px = entity.x
        val py = entity.y
        val pz = entity.z
        // A granted release exempts this player from every hold that existed when it was granted, and from
        // nothing younger, so the next transition holds them again like anyone else.
        val released = if (entity is Player) releasedAt[entity.id] ?: 0L else 0L
        var held = false
        val it = holds.iterator()
        while (it.hasNext()) {
            val h = it.next()
            if (now - h.deadlineGameTime > 0) {
                it.remove()
                continue
            }
            if (!held && h.createdGameTime > released && h.dimensionId == dim &&
                px >= h.aabb.minX() && px <= h.aabb.maxX() &&
                py >= h.aabb.minY() && py <= h.aabb.maxY() &&
                pz >= h.aabb.minZ() && pz <= h.aabb.maxZ()
            ) {
                held = true
            }
        }
        if (!held) return false

        if (entity is Player && level is ServerLevel) {
            if (probeWouldSupport(entity, level)) {
                val firstSeen = supportSeen.putIfAbsent(entity.id, now) ?: now
                if (now - firstSeen >= SUPPORT_CONFIRM_TICKS) {
                    supportSeen.remove(entity.id)
                    releasedAt[entity.id] = now
                    return false
                }
            } else {
                // Support has to hold on consecutive probes; a transient never releases into a fall.
                supportSeen.remove(entity.id)
            }
        }
        return true
    }

    /**
     * Would anything hold [player] up right now? The ship half is VS2's own ship-collision query cast a short way
     * down, the same predicate real entity collision runs, so "the probe passes" and "the deck is solid" can't
     * disagree. The world half is a plain collision test, which releases the disassembly direction and frees a
     * player who was only standing on terrain inside the footprint.
     */
    private fun probeWouldSupport(player: Player, level: ServerLevel): Boolean {
        val box = player.boundingBox
        if (!level.noCollision(player, box.move(0.0, -SUPPORT_PROBE_BLOCKS, 0.0))) return true
        return EntityShipCollisionUtils.getShipPolygonsCollidingWithEntity(
            player, Vec3(0.0, -SUPPORT_PROBE_BLOCKS, 0.0), box, level
        ).isNotEmpty()
    }
}
