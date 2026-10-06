package org.valkyrienskies.eureka.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedShip
import org.valkyrienskies.eureka.EurekaEntities
import org.valkyrienskies.eureka.crew.GunnerMounts
import org.valkyrienskies.mod.common.entity.ShipMountedToData
import org.valkyrienskies.mod.common.entity.ShipMountedToDataProvider
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * A seat on a ship's deck: a gunner behind his cannon, or a player who sat down with the sit key.
 *
 * It lives in WORLD space, not the shipyard, and drives itself: every tick it moves to the ship-relative spot
 * it was given, so it and its rider are carried along with the ship. A shipyard seat would sit in chunks that
 * never tick. The anchor is also synced to the client as a string, so the client can place the seat from the
 * ship's own transform and render the rider still relative to the deck. Standing up is vanilla's SHIFT dismount:
 * the seat ticks, so vanilla handles it, and since the seat keeps the rider at deck height there is no fall.
 *
 * This used to be a passenger mode of VS2's ShipMountingEntity in our VS2 build. It lives in Armada now, so
 * Armada runs on the official VS2 release. The helm keeps VS2's ShipMountingEntity.
 */
class DeckSeat(type: EntityType<DeckSeat>, level: Level) : Entity(type, level), ShipMountedToDataProvider {

    /** Server-side anchor: the ship, and the stand point in that ship's shipyard coordinates. */
    var driveShipId: Long? = null
    private var driveRelPos: Vector3d? = null

    /** A player who sat down gets the "press SHIFT" hint once; gunners get nothing. Server side only. */
    private var promptPending = false

    init {
        // Don't stop blocks being placed where the seat is, and don't collide with terrain.
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()
        val lvl = level()
        if (lvl is ServerLevel) {
            if (passengers.isEmpty()) {
                kill()
                return
            }
            serverDrive(lvl)
        } else {
            clientDrive()
        }
    }

    private fun serverDrive(lvl: ServerLevel) {
        val rider = passengers.firstOrNull() ?: return
        // A seat loaded from a save has no anchor. It sits inert until the owning reconcile (GunStations for
        // villagers, GunnerMounts for mobs) kills it and seats the gunner on a fresh one.
        val shipId = driveShipId ?: return
        val rel = driveRelPos ?: return
        val ship = lvl.shipObjectWorld.allShips.getById(shipId)
        if (ship == null) {
            // Ship unloaded or deleted under him: let him down where he is.
            rider.stopRiding()
            rider.resetFallDistance()
            kill()
            return
        }
        val world = ship.shipToWorld.transformPosition(Vector3d(rel))
        moveTo(world.x, world.y, world.z, yRot, xRot)
        if (promptPending && rider is ServerPlayer) {
            promptPending = false
            // Action bar, so it fades on its own after a few seconds.
            rider.displayClientMessage(SEATED_PROMPT, true)
        }
    }

    /** Parse the synced "shipId;relX;relY;relZ" anchor. Available on both sides, unlike [driveShipId]. */
    private fun syncedAnchor(): Pair<Long, Vector3d>? {
        val data = entityData.get(DRIVE_DATA)
        if (data.isEmpty()) return null
        val parts = data.split(';')
        if (parts.size < 4) return null
        val shipId = parts[0].toLongOrNull() ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        return shipId to Vector3d(x, y, z)
    }

    /** Keep the client's logical position right. The per-frame render goes through [provideShipMountedToData]. */
    private fun clientDrive() {
        val anchor = syncedAnchor() ?: return
        val ship = level().shipObjectWorld.allShips.getById(anchor.first) ?: return
        val world = ship.transform.shipToWorld.transformPosition(anchor.second)
        setPos(world.x, world.y, world.z)
    }

    /**
     * Render the rider through the ship's render transform, the path a helm rider takes, so someone seated sits
     * still on a moving deck instead of being interpolated tick to tick. For a player this also drives the camera.
     */
    override fun provideShipMountedToData(passenger: Entity, partialTicks: Float?): ShipMountedToData? {
        // A portrait (the crew roster's head icon) draws the rider on his own, facing the viewer. Handing VS2 a
        // ship mount there would turn him to the ship's heading instead.
        if (drawingPortrait) return null
        val anchor = syncedAnchor() ?: return null
        val ship = level().shipObjectWorld.allShips.getById(anchor.first) as? LoadedShip ?: return null
        val rel = anchor.second
        // The anchor is the rider's feet, but the sitting model draws about half a block above its position.
        return ShipMountedToData(ship, Vector3d(rel.x, rel.y - SEAT_RENDER_DROP, rel.z))
    }

    override fun defineSynchedData() {
        entityData.define(DRIVE_DATA, "")
    }

    // The anchor is not saved: a reloaded seat wakes inert, see serverDrive.
    override fun readAdditionalSaveData(compound: CompoundTag) {}

    override fun addAdditionalSaveData(compound: CompoundTag) {}

    /**
     * Save the seat only while a gunner is in it.
     *
     * Vanilla never saves a passenger on its own; riders are written inside their vehicle. So a seat that
     * refused to save would erase its gunner on every relog. An occupied seat saves, the gunner goes with it,
     * and on load the reconcile re-seats him on a freshly anchored seat. A player's seat never saves: a
     * reloaded one would have no anchor, so the player simply loads in standing.
     */
    override fun shouldBeSaved(): Boolean =
        passengers.any { it is Villager || GunnerMounts.isGunner(it) } && super.shouldBeSaved()

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    companion object {
        // Synced "shipId;relX;relY;relZ". There is no Long or vector serializer by default; a string carries
        // the full precision of shipyard coordinates.
        private val DRIVE_DATA: EntityDataAccessor<String> =
            SynchedEntityData.defineId(DeckSeat::class.java, EntityDataSerializers.STRING)

        private const val SEAT_RENDER_DROP = 0.5

        private val SEATED_PROMPT: Component = Component.literal("You Are Seated: Press SHIFT to stand")

        /**
         * True while a screen draws a seated rider as a portrait. Render thread only; set and cleared around
         * the one draw call.
         */
        @JvmStatic
        var drawingPortrait = false

        /**
         * Create a seat at the given WORLD position, anchored to [shipId] at ship-relative ([relX], [relY],
         * [relZ]), and add it to [level]. The caller mounts the rider. Returns null if the entity can't be made.
         */
        fun spawn(
            level: ServerLevel, worldX: Double, worldY: Double, worldZ: Double, yaw: Float, pitch: Float,
            shipId: Long, relX: Double, relY: Double, relZ: Double
        ): DeckSeat? {
            val seat = EurekaEntities.DECK_SEAT.get().create(level) ?: return null
            seat.moveTo(worldX, worldY, worldZ, yaw, pitch)
            seat.driveShipId = shipId
            seat.driveRelPos = Vector3d(relX, relY, relZ)
            seat.entityData.set(DRIVE_DATA, "$shipId;$relX;$relY;$relZ")
            level.addFreshEntity(seat)
            return seat
        }

        /**
         * Sit [player] down where they stand on the ship carrying them (the sit key). Does nothing unless VS2 is
         * actually carrying them on a ship: the server's own drag state decides, never anything the client says.
         */
        @JvmStatic
        fun sitDown(player: ServerPlayer) {
            if (player.isRemoved || player.isPassenger || player.isSpectator) return
            val level = player.level() as? ServerLevel ?: return
            val info = (player as IEntityDraggingInformationProvider).draggingInformation
            if (!info.isEntityBeingDraggedByAShip()) return
            val shipId = info.lastShipStoodOn ?: return
            val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return
            // The carry's own ship-space position is the most accurate; fall back to transforming the player's.
            val rel = info.bestRelativeEntityPosition()?.let { Vector3d(it) }
                ?: ship.worldToShip.transformPosition(Vector3d(player.x, player.y, player.z))
            seatAt(player, shipId, rel)
        }

        /**
         * Seat [player] on ship [shipId] at ship-relative [rel] (their feet), with the one-time SHIFT hint. Used by
         * the sit key and by ReconnectSeat. Does nothing if the ship no longer exists.
         */
        @JvmStatic
        fun seatAt(player: ServerPlayer, shipId: Long, rel: Vector3d) {
            val level = player.level() as? ServerLevel ?: return
            val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return
            val world = ship.shipToWorld.transformPosition(Vector3d(rel))
            val seat = spawn(level, world.x, world.y, world.z, player.yRot, player.xRot, shipId, rel.x, rel.y, rel.z)
                ?: return
            seat.promptPending = true
            if (!player.startRiding(seat, true)) seat.kill()
        }

        /** The ship and ship-relative spot [seat] is anchored to, or null for a seat with no anchor. */
        @JvmStatic
        fun anchorOf(seat: DeckSeat): Pair<Long, Vector3d>? {
            val shipId = seat.driveShipId ?: return null
            val rel = seat.driveRelPos ?: return null
            return shipId to Vector3d(rel)
        }

        /**
         * Is [vehicle] a gun seat? Also true for the non-controller VS2 seats older builds put gunners on, so a
         * save made on our VS2 build still has its gunners recognised and re-seated.
         */
        fun isGunSeat(vehicle: Entity?): Boolean =
            vehicle is DeckSeat || (vehicle is ShipMountingEntity && !vehicle.isController)
    }
}
