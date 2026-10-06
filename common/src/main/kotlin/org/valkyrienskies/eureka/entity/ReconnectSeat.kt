package org.valkyrienskies.eureka.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3d
import org.valkyrienskies.mod.common.config.VSGameConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Log back in on a ship and you are sitting, as if you had pressed the sit key: SHIFT to stand.
 *
 * VS2 already remembers the ship a player logged out on and their spot on it (`LastShipId` and `RelativeShipX/Y/Z`
 * in the player's save) and puts them back there on login. Sitting them down on top of that means a ship that is
 * moving, or moves before the world has settled, can never leave them behind, and it shows new players the sit
 * feature in passing.
 *
 * - [onSave]: a player who logs out SITTING in a [DeckSeat] is written as standing on that seat's ship, and the seat
 *   is dropped from the save. Otherwise vanilla would save the seat with them and bring it back with no anchor,
 *   parked wherever the ship was at logout.
 * - [onLoad] notes the spot from the save; [onJoin] seats them once they are fully in the world.
 * - Only a player who logged out standing on the deck, or sitting, comes back seated. Falling, gliding with an elytra
 *   or flying in creative they come back exactly that way: VS2 still puts them back at the same spot by the ship,
 *   and they carry on falling, gliding or hovering instead of being sat down in mid-air.
 *
 * Called from MixinServerPlayerReconnectSeat and the server's join event. Our VS2 build did this inside VS2.
 */
object ReconnectSeat {

    private class Pending(val shipId: Long, val rel: Vector3d)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Written by [onSave] for a player who logged out in a deck seat (a rider is never on the ground). */
    private const val SEATED = "vs_eureka:LoggedOutSeated"

    @JvmStatic
    fun onSave(player: ServerPlayer, tag: CompoundTag) {
        val seat = player.vehicle as? DeckSeat ?: return
        val (shipId, rel) = DeckSeat.anchorOf(seat) ?: return
        tag.putLong("LastShipId", shipId)
        tag.putDouble("RelativeShipX", rel.x)
        tag.putDouble("RelativeShipY", rel.y)
        tag.putDouble("RelativeShipZ", rel.z)
        tag.remove("RootVehicle")
        tag.putBoolean(SEATED, true)
    }

    @JvmStatic
    fun onLoad(player: ServerPlayer, tag: CompoundTag) {
        if (!VSGameConfig.SERVER.teleportReconnectedPlayers || !tag.contains("LastShipId")) return
        val standing = tag.getBoolean("OnGround") && !tag.getBoolean("FallFlying") &&
            !tag.getCompound("abilities").getBoolean("flying")
        if (!tag.getBoolean(SEATED) && !standing) return
        pending[player.uuid] = Pending(
            tag.getLong("LastShipId"),
            Vector3d(tag.getDouble("RelativeShipX"), tag.getDouble("RelativeShipY"), tag.getDouble("RelativeShipZ"))
        )
    }

    @JvmStatic
    fun onJoin(player: ServerPlayer) {
        val spot = pending.remove(player.uuid) ?: return
        // Already riding something (a helm restored from the save, say): leave them be.
        if (player.isPassenger || player.isSpectator) return
        DeckSeat.seatAt(player, spot.shipId, spot.rel)
    }

    @JvmStatic
    fun onLeave(player: ServerPlayer) {
        pending.remove(player.uuid)
    }
}
