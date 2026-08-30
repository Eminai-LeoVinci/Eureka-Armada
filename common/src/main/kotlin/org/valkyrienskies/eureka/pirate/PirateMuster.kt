package org.valkyrienskies.eureka.pirate

import org.valkyrienskies.eureka.util.nbt.*
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaConfig

/**
 * Putting a pirate crew back on its deck.
 *
 * The complement lives as full entity snapshots in the helm's papers; this restores each one at a spot of
 * its own around the wheel. Restoration is [org.valkyrienskies.eureka.crew.CrewSnapshot.restore]'s idiom
 * generalized to raiders: a NEW uuid on purpose (the corpse's id must never be worn twice), position and
 * motion overwritten after the load, and the whole attempt disposable -- one unreadable snapshot costs one
 * crew member, not the respawn.
 *
 * Placement is a spiral of columns around the wheel with a clip ray dropped down each, which finds the
 * actual deck -- VS2 wraps `Level.clip`, so the ray lands on an assembled hull's planks exactly as it does
 * on a beached one's. A column with no floor within reach (over the rail, open water) is skipped for the
 * next; a crew member the spiral cannot seat at all stands at the wheel, which vanilla's own cramming rules
 * can sort out better than a missing pillager can.
 */
object PirateMuster {

    /**
     * Restore every snapshot in [helmPapers] around [centre] (the wheel, in world space). Returns the new
     * crew's uuids -- the caller files them on the wheel and flips its mark back to PIRATE.
     */
    fun respawn(
        level: ServerLevel,
        helmPapers: List<CompoundTag>,
        centre: Vector3d
    ): List<UUID> {
        val spawned = ArrayList<UUID>(helmPapers.size)
        var seat = 0
        for (snapshot in helmPapers) {
            val at = seatFor(level, centre, seat)
            seat++
            val raider = restore(level, snapshot, at) ?: continue
            raider.setPersistenceRequired()
            raider.restrictTo(BlockPos.containing(centre.x, centre.y, centre.z), HOME_RADIUS)
            raider.addTag(PirateShips.CREW_TAG)
            raider.getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = EurekaConfig.SERVER.pirateCrewSightRange
            spawned.add(raider.uuid)
        }
        return spawned
    }

    /** Haul a crew hand back to a seat by the wheel -- the tether's answer to combat pathing. */
    fun reseat(level: ServerLevel, raider: Mob, centre: Vector3d, seat: Int) {
        val at = seatFor(level, centre, seat)
        raider.teleportTo(at.x, at.y, at.z)
        raider.deltaMovement = Vec3.ZERO
        raider.fallDistance = 0.0f
    }

    private fun restore(level: ServerLevel, tag: CompoundTag, at: Vec3): Mob? = try {
        val id = tag.getStringOpt("id").orElse("")
        val type = EntityType.byString(id).orElse(null)
        val entity = type?.create(level)
        // Any mob the articles admit -- see PirateCrewTypes. A snapshot naming something ineligible is
        // dropped rather than seated: a hull authored before the rule narrowed should lose that hand, not
        // arrive with a cow at the wheel.
        val raider = entity?.takeIf { PirateCrewTypes.eligible(it) } as? Mob
        if (raider == null) {
            entity?.discard()
            null
        } else {
            raider.load(tag)
            raider.uuid = UUID.randomUUID()
            raider.setPos(at.x, at.y, at.z)
            raider.deltaMovement = Vec3.ZERO
            raider.fallDistance = 0.0f
            if (level.addFreshEntity(raider)) raider else null
        }
    } catch (ex: Exception) {
        null
    }

    /**
     * The [seat]th place around the wheel: a short spiral of columns, each probed with a downward clip ray
     * for its floor. Falls back to standing at the wheel itself when a column has no floor in reach.
     */
    private fun seatFor(level: ServerLevel, centre: Vector3d, seat: Int): Vec3 {
        for (attempt in seat until seat + SPIRAL.size) {
            val (dx, dz) = SPIRAL[attempt % SPIRAL.size]
            val x = centre.x + dx
            val z = centre.z + dz
            // 1.20.1's ClipContext cannot be built without an entity, and the seat's raider does not
            // exist yet -- so the vertical probe is a plain block-column walk instead of a clip ray.
            // Same verdict: the first collidable surface below the probe top.
            val bx = Math.floor(x).toInt()
            val bz = Math.floor(z).toInt()
            val cursor = BlockPos.MutableBlockPos()
            for (by in Math.floor(centre.y + PROBE_UP).toInt() downTo Math.ceil(centre.y - PROBE_DOWN).toInt()) {
                cursor.set(bx, by, bz)
                val shape = level.getBlockState(cursor).getCollisionShape(level, cursor)
                if (shape.isEmpty) continue
                return Vec3(x, by + shape.max(net.minecraft.core.Direction.Axis.Y) + 0.05, z)
            }
        }
        return Vec3(centre.x, centre.y, centre.z)
    }

    /** Offsets ring by ring, one block of shoulder room each -- the anti-cramming lesson, kept simple. */
    private val SPIRAL = listOf(
        1 to 1, -1 to 1, 1 to -1, -1 to -1,
        2 to 0, 0 to 2, -2 to 0, 0 to -2,
        2 to 2, -2 to 2, 2 to -2, -2 to -2,
        3 to 0, 0 to 3, -3 to 0, 0 to -3
    )

    private const val PROBE_UP = 4.0
    private const val PROBE_DOWN = 10.0

    /** How far from the wheel a respawned crew member is allowed to wander. */
    private const val HOME_RADIUS = 24

}
