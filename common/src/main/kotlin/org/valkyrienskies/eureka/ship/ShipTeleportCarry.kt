package org.valkyrienskies.eureka.ship

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.ShipTeleportData
import org.valkyrienskies.eureka.entity.DeckSeat
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Brings everyone aboard along when a ship is teleported (`/vs teleport`, or anything else that moves a ship the
 * same way).
 *
 * VS2 carries what stands on a ship by the ship's movement from one tick to the next, and a teleport does not show
 * up as that kind of movement, so the crew, passengers on deck and anyone standing there were left hanging where
 * the ship used to be (and only the crewman-overboard rescue brought crew back, if they were still loaded).
 *
 * [beforeTeleport] runs from MixinVSCoreTeleport while the ship is still in its old place and notes, in the ship's own
 * coordinates, everything VS2 is carrying with it (plus deck seats anchored to it). Once the ship has landed, [tick]
 * puts each one back on the same spot of the deck, facing the same way relative to the ship. Anything VS2 did carry
 * itself is already there and left alone. The landing chunk is loaded first, so nobody is moved into a chunk the
 * game is not holding. Teleports across dimensions are not carried.
 */
object ShipTeleportCarry {

    /** Shorter jumps are left to VS2's own carry, which handles small moves fine. */
    private const val MIN_JUMP = 4.0

    /** Closer than this to its spot on deck, an entity was carried already. */
    private const val ALREADY_THERE = 2.0

    /** Give up on a ship that has not landed after this many ticks. */
    private const val TIMEOUT_TICKS = 60

    private class Rider(val entity: Entity, val local: Vector3dc, val localLook: Vector3dc)

    private class Pending(
        val level: ServerLevel,
        val shipId: Long,
        val from: Vector3dc,
        val jump: Double,
        val riders: List<Rider>,
        var ticks: Int = 0
    )

    // Server thread only (commands and world ticks).
    private val pending = ArrayList<Pending>()

    @JvmStatic
    @OptIn(VsBeta::class)
    fun beforeTeleport(ship: ServerShip, data: ShipTeleportData) {
        val server = ValkyrienSkiesMod.currentServer ?: return
        if (!server.isSameThread) return
        val dimension = ship.chunkClaimDimension
        if (data.newDimension != null && data.newDimension != dimension) return
        val level = server.getLevelFromDimensionId(dimension) ?: return

        val from = Vector3d(ship.transform.positionInWorld)
        val jump = from.distance(data.createNewShipTransform(ship.transform).position)
        if (jump < MIN_JUMP) return

        val box = TransitionHold.worldAABBForShip(ship)
        val search = AABB(box.minX - 3, box.minY - 3, box.minZ - 3, box.maxX + 3, box.maxY + 3, box.maxZ + 3)
        val worldToShip = ship.transform.worldToShip
        val riders = ArrayList<Rider>()
        for (entity in level.getEntities(null as Entity?, search) { true }) {
            if (entity.isRemoved || entity.isPassenger) continue
            val seated = entity is DeckSeat && DeckSeat.anchorOf(entity)?.first == ship.id
            val carried = (entity as? IEntityDraggingInformationProvider)?.draggingInformation?.let {
                it.lastShipStoodOn == ship.id && it.isEntityBeingDraggedByAShip()
            } == true
            if (!seated && !carried) continue
            val yaw = Math.toRadians(entity.yRot.toDouble())
            val look = Vector3d(-sin(yaw), 0.0, cos(yaw))
            riders.add(
                Rider(
                    entity,
                    worldToShip.transformPosition(Vector3d(entity.x, entity.y, entity.z)),
                    worldToShip.transformDirection(look)
                )
            )
        }
        if (riders.isNotEmpty()) pending.add(Pending(level, ship.id, from, jump, riders))
    }

    @JvmStatic
    fun tick(server: MinecraftServer) {
        if (pending.isEmpty()) return
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.ticks++
            val ship = p.level.shipObjectWorld.allShips.getById(p.shipId)
            if (ship == null || p.ticks > TIMEOUT_TICKS) {
                it.remove()
                continue
            }
            val now = ship.transform.positionInWorld
            // Not landed yet.
            if (now.distance(p.from) < p.jump / 2) continue
            // Landed this very tick: VS2 may still be about to carry by the jump itself. Wait one more.
            if (ship.prevTickTransform.positionInWorld.distance(now) > p.jump / 2) continue
            it.remove()
            place(p.level, ship, p.riders)
        }
    }

    private fun place(level: ServerLevel, ship: Ship, riders: List<Rider>) {
        val shipToWorld = ship.transform.shipToWorld
        for (rider in riders) {
            val entity = rider.entity
            if (entity.isRemoved || entity.level() !== level || entity.isPassenger) continue
            // A deck seat goes back to its own anchor, the spot it drives itself to every tick.
            val local = (entity as? DeckSeat)?.let { DeckSeat.anchorOf(it)?.second } ?: rider.local
            val to = shipToWorld.transformPosition(Vector3d(local))
            if (entity.position().distanceToSqr(to.x, to.y, to.z) < ALREADY_THERE * ALREADY_THERE) continue
            val look = shipToWorld.transformDirection(Vector3d(rider.localLook))
            val yaw = Math.toDegrees(-atan2(look.x, look.z)).toFloat()

            val chunk = ChunkPos(BlockPos.containing(to.x, to.y, to.z))
            level.chunkSource.addRegionTicket(TicketType.POST_TELEPORT, chunk, 1, entity.id)
            level.getChunk(chunk.x, chunk.z)

            if (entity is ServerPlayer) {
                entity.connection.teleport(to.x, to.y, to.z, yaw, entity.xRot)
            } else {
                entity.teleportTo(to.x, to.y, to.z)
                if (entity !is DeckSeat) {
                    entity.yRot = yaw
                    entity.yHeadRot = yaw
                    (entity as? LivingEntity)?.yBodyRot = yaw
                }
                entity.deltaMovement = Vec3.ZERO
                // VS2 sends carried entities' moves as ship-relative motion, which would slide them across the
                // whole jump on screen; one explicit teleport puts them there at once.
                level.chunkSource.chunkMap.broadcast(entity, ClientboundTeleportEntityPacket(entity))
            }
            entity.resetFallDistance()
            if (entity !is DeckSeat) {
                (entity as? IEntityDraggingInformationProvider)?.draggingInformation?.let {
                    it.lastShipStoodOn = ship.id
                    it.addedMovementLastTick = Vector3d()
                }
            }
        }
    }

    /**
     * Is [entity] waiting to be put back aboard a ship that has just been teleported? Between the landing and the
     * carry, a sleeper is still where the ship was while its bed has gone with the ship, and the sleep behaviour
     * would wake it for being too far from the bed (MixinSleepInBed asks this).
     */
    @JvmStatic
    fun inTransit(entity: Entity): Boolean = pending.any { p -> p.riders.any { it.entity === entity } }

    @JvmStatic
    fun clear() = pending.clear()
}
