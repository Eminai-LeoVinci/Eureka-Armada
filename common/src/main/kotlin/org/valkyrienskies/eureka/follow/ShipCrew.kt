package org.valkyrienskies.eureka.follow

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.shipObjectWorld
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
     * The id of the ship an entity is standing on or seated in, or null.
     *
     * Not normalized to a parent, unlike `ShipPaths.resolveShip` -- callers here test membership against a whole
     * group, so promoting would only throw away the information about which deck they are actually on.
     *
     * Takes any [Entity] rather than a player because the crew roster asks the same question of villagers. Both
     * calls below were always entity-shaped; only the parameter was narrow. `lastShipStoodOn` is kept alive for
     * a standing mob -- one that is not moving, and so never triggers the collision-driven write -- by VS2's
     * `drag_standing_mobs` mixin, which re-stamps it every tick. That is what makes this reliable for a
     * villager idling on a deck.
     */
    fun standingOn(entity: Entity): Long? =
        (getShipMountedTo(entity) as? Ship)?.id
            ?: (entity as? IEntityDraggingInformationProvider)?.draggingInformation?.lastShipStoodOn

    /**
     * Every living villager standing on [ship] or on anything welded to it -- signed on or not.
     *
     * Unlike [aboard], which can scan `level.players()` because that list is short and already in hand, there is
     * no list of villagers to scan, so this asks the world over the vessel's own bounding box. It returns
     * EVERYONE rather than only the crew because both readings are wanted at once: who is signed on, and who is
     * merely standing there. One query, and the caller filters.
     *
     * The box is the ship's WORLD aabb, not its shipyard one. VS2 drags entities in world space: a villager on a
     * deck stands at ordinary world coordinates a hair outside the hull, which is why the box is inflated and
     * why membership is decided by [standingOn] rather than by the box itself. Same reasoning, and the same
     * generous margin, as `ShipAssembler`'s existing rider sweep.
     *
     * Runs on a Sneak+C and on a recruit. Never per tick -- one entity query per gesture is nothing; one per
     * tick per ship would not be.
     */
    fun villagersAboard(level: ServerLevel, ship: LoadedServerShip): List<Villager> {
        val group = ArmadaGroup.idsOf(level, ship)
        val ships = level.shipObjectWorld.loadedShips

        var box: AABB? = null
        for (id in group) {
            val hull = ships.getById(id)?.worldAABB ?: continue
            val member = AABB(
                hull.minX() - DECK_MARGIN, hull.minY() - DECK_MARGIN, hull.minZ() - DECK_MARGIN,
                hull.maxX() + DECK_MARGIN, hull.maxY() + DECK_MARGIN, hull.maxZ() + DECK_MARGIN
            )
            box = box?.minmax(member) ?: member
        }
        if (box == null) return emptyList()

        return level.getEntitiesOfClass(Villager::class.java, box) { villager ->
            villager.isAlive && standingOn(villager) in group
        }
    }

    /** Someone standing on deck is a hair outside the hull box; the same slack `ShipAssembler` allows riders. */
    private const val DECK_MARGIN = 2.0

    /**
     * What to call a ship in a message.
     *
     * `slug` is the only name a VS ship has and it is nullable, so this matches what `ArmadaCommand` already
     * prints. A wheel's own name belongs to its CREW, not to the hull, so it deliberately does not appear here.
     */
    fun name(ship: Ship): String = ship.slug ?: "ship #${ship.id}"
}
