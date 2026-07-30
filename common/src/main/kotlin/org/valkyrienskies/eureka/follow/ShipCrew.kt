package org.valkyrienskies.eureka.follow

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Everyone standing on a ship, and how to tell them something.
 *
 * Route playback never needed this: a route is flown by the one person who pressed the key, and every message
 * it produces is feedback on that person's own action. A follow is different in kind -- it is one crew's
 * decision that changes what happens to ANOTHER crew's ship, and the people on the leader have every right to
 * be told they have picked up a shadow. So the unit of address here is the vessel, not the player.
 *
 * "Aboard" spans the whole armada, because an armada is one vessel: a deckhand who happens to be standing on a
 * welded-on child is part of the same crew, and would find it strange to be the only one who wasn't told.
 */
object ShipCrew {

    /**
     * Every online player standing on, or seated in, [ship] or any ship welded to it.
     *
     * This is the inverse of `ShipPaths.resolveShip`, and it is built by scanning players rather than by asking
     * the ship, because nothing on a ship keeps a list of who is on it. The scan is over the players in one
     * level and happens on the rare events (a follow starting, ending or breaking off), never per tick.
     */
    fun aboard(level: ServerLevel, ship: LoadedServerShip): List<ServerPlayer> {
        val group = ArmadaGroup.idsOf(level, ship)
        return level.players().filter { standingOn(it) in group }
    }

    /** Send [message] to everyone aboard [ship]. Does nothing when the decks are empty. */
    fun tell(level: ServerLevel, ship: LoadedServerShip, message: String, kind: PathMessages.Kind) {
        for (player in aboard(level, ship)) PathMessages.send(player, message, kind)
    }

    /**
     * Send [message] to everyone aboard [ship] except [except], who is presumably getting a better-worded one.
     *
     * The player who gave the order gets told what they just did; their shipmates get told what happened to
     * their ship. Same event, and the two readings deserve different sentences.
     */
    fun tellOthers(
        level: ServerLevel,
        ship: LoadedServerShip,
        except: ServerPlayer,
        message: String,
        kind: PathMessages.Kind
    ) {
        for (player in aboard(level, ship)) {
            if (player.uuid != except.uuid) PathMessages.send(player, message, kind)
        }
    }

    /**
     * The id of the ship a player is standing on or seated in, or null.
     *
     * Not normalized to a parent, unlike `ShipPaths.resolveShip` -- callers here test membership against a whole
     * group, so promoting would only throw away the information about which deck they are actually on.
     */
    fun standingOn(player: ServerPlayer): Long? =
        (getShipMountedTo(player) as? Ship)?.id
            ?: (player as? IEntityDraggingInformationProvider)?.draggingInformation?.lastShipStoodOn

    /**
     * What to call a ship in a message.
     *
     * `slug` is the only name a VS ship has and it is nullable, so this matches what `ArmadaCommand` already
     * prints: the name if it has one, the raw id if it doesn't. A ship nobody has named is still a thing you
     * need to be able to refer to.
     */
    fun name(ship: Ship): String = ship.slug ?: "ship #${ship.id}"
}
