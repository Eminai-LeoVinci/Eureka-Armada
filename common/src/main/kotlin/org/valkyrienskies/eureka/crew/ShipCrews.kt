package org.valkyrienskies.eureka.crew

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import java.util.UUID

/**
 * Sneak+C: sign a villager on, read a ship's articles off its wheel, or pick out the crew on the deck you are
 * standing on.
 *
 * Berths are the captain's and are counted per SHIP. With eight of them you can crew one hull to six and
 * another to eight; it is a limit on any one vessel at a time, not a global budget. That is why nothing here
 * keeps a running total: the count is the answer to a question asked about a particular ship, and it is
 * computed on the spot every time it is asked.
 *
 * The membership itself lives on the ship's crew-station helm -- see [CrewRoster] and [CrewStations] -- so a
 * ship's crew is the INTERSECTION of that roster with the villagers actually standing on that ship. A crew
 * member is someone who is both signed on and here.
 *
 * What a crew member DOES is still deliberately nothing. This is the roster and the recruiting; duties come
 * later, and building them on a roster that already works beats guessing at both at once.
 */
object ShipCrews {

    /**
     * One key, three meanings, arbitrated here rather than on the client.
     *
     * The order is by how deliberate each aim is. A villager under the crosshair is unmistakably a choice about
     * that villager. A wheel under the crosshair is a choice about that ship's articles. Standing somewhere is
     * merely where you happen to be, so it is the fallback -- and the one that answers "which of these
     * villagers are mine" by marking them, rather than by making you read a list and match names.
     */
    @JvmStatic
    fun gesture(level: ServerLevel, player: ServerPlayer) {
        val villager = lookedAtVillager(level, player)
        if (villager != null) {
            toggleRecruit(level, player, villager)
            return
        }
        val helm = lookedAtHelm(level, player)
        if (helm != null) {
            showArticles(level, player, helm)
            return
        }
        markCrewOnDeck(level, player)
    }

    // region recruiting

    private fun toggleRecruit(level: ServerLevel, player: ServerPlayer, villager: Villager) {
        // The berth limit is a fact about a SHIP, and the articles are kept on a ship's wheel, so both questions
        // have the same precondition: the villager has to be standing on one.
        val ship = shipUnder(level, villager)
        if (ship == null) {
            PathMessages.send(player, "Walk them aboard first -- crew are signed on at the wheel.", PathMessages.Kind.ERROR)
            return
        }

        val station = CrewStations.stationOf(level, ship)
        if (station == null) {
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} has no wheel keeping articles. Look at a helm and press the crew key.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val existing = station.crew.ownerOf(villager.uuid)

        if (existing == player.uuid) {
            // Both of these have to happen before the entry is struck out: the berth is what says which
            // default name is being handed back, and the name is what the message calls them by.
            val paidOff = CrewNames.displayName(villager)
            CrewNames.clearDefault(villager, station.crew.slotOf(villager.uuid))
            station.crew.payOff(villager.uuid)
            station.setChanged()
            // Counted after the entry is struck out, so the number quoted is the one they now have.
            PathMessages.send(
                player,
                "Paid off $paidOff, a ${professionName(villager)}. Crew aboard: " +
                    "${crewOf(level, ship, station, player.uuid).size}/${CrewData.slots(player)}.",
                PathMessages.Kind.GOOD
            )
            return
        }

        if (existing != null) {
            PathMessages.send(player, "That one already sails with another captain.", PathMessages.Kind.ERROR)
            return
        }

        if (villager.isBaby) {
            PathMessages.send(player, "They're too young to sign on.", PathMessages.Kind.ERROR)
            return
        }

        val slots = CrewData.slots(player)
        val aboard = crewOf(level, ship, station, player.uuid).size
        if (aboard >= slots) {
            PathMessages.send(
                player,
                "No berth for them -- ${ShipCrew.name(ship)} is already carrying $aboard of your $slots crew.",
                PathMessages.Kind.ERROR
            )
            return
        }

        // The berth number is an identity, not a reservation: it names them and fixes their row in the
        // manifest, and it is deliberately NOT what the limit above is counted from.
        val berth = station.crew.freeSlot(player.uuid, EurekaConfig.SERVER.crewSlotsMax)
        station.crew.sign(villager.uuid, player.uuid, berth)
        station.setChanged()
        CrewNames.applyDefault(villager, berth)
        PathMessages.send(
            player,
            "Signed on ${CrewNames.displayName(villager)}, a ${professionName(villager)}. " +
                "Crew aboard: ${aboard + 1}/$slots.",
            PathMessages.Kind.GOOD
        )
    }

    // endregion

    // region the articles

    /**
     * Open the crew manifest for the ship this [helm] belongs to.
     *
     * Reading the articles off the wheel is the natural gesture -- it is where they are kept -- and it also
     * works from the quay, which standing on the deck does not.
     *
     * A wheel on a ship with no crew station CLAIMS the role here rather than reporting an error. That is the
     * one action a player would otherwise have no way to perform, and it is exactly what someone aiming at a
     * wheel and asking about crew is trying to do.
     *
     * The manifest is a screen, and [printArticles] below is what happens when it cannot be opened -- a client
     * the payload cannot reach still gets the roster, in chat, rather than a key that does nothing.
     */
    private fun showArticles(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity) {
        val ship = CrewStations.shipOf(level, helm)
        if (ship == null) {
            PathMessages.send(player, "Assemble the ship first -- articles are kept by a ship's wheel.", PathMessages.Kind.ERROR)
            return
        }

        var station = CrewStations.stationOf(level, ship)
        if (station == null) {
            if (!CrewStations.claim(level, helm)) {
                PathMessages.send(player, "This wheel isn't part of a ship.", PathMessages.Kind.ERROR)
                return
            }
            station = helm
            PathMessages.send(
                player,
                "This wheel now keeps ${ShipCrew.name(ship)}'s articles. Sign your crew on.",
                PathMessages.Kind.GOOD
            )
        } else if (station !== helm) {
            // Every helm steers; only one holds the crew. Saying so here is the whole explanation of why a
            // second wheel did not give them more berths.
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)}'s articles are kept at another wheel; this one only steers.",
                PathMessages.Kind.GOOD
            )
        }

        if (CrewManifest.sender(player, CrewManifest.build(level, player, station))) return
        printArticles(level, player, ship, station)
    }

    /**
     * The roster in chat -- what a client that cannot receive the manifest payload gets instead.
     *
     * This was the whole of the crew key's second meaning before the manifest existed, and it is kept rather
     * than deleted because a key that silently does nothing is worse than a key that answers plainly.
     */
    private fun printArticles(
        level: ServerLevel, player: ServerPlayer, ship: LoadedServerShip, station: ShipHelmBlockEntity
    ) {
        val slots = CrewData.slots(player)
        // One query answers both questions -- who is signed on, and who is merely standing on the deck.
        val villagers = ShipCrew.villagersAboard(level, ship)
        val crew = villagers.filter { station.crew.ownerOf(it.uuid) == player.uuid }
        val free = (slots - crew.size).coerceAtLeast(0)

        // The detail goes to CHAT, not the stacking HUD: PathHud holds six entries and drops the oldest, so a
        // roster of any length would eat its own head and bury whatever else was on screen. The one-line
        // summary still goes to the HUD, so the answer is readable without opening chat -- the same split
        // ArmadaCommand and PathCommand already make.
        val msg = Component.literal("Crew of ${ShipCrew.name(ship)}").withStyle(ChatFormatting.AQUA)
        msg.append(
            Component.literal("\n  ${crew.size} signed on -- ${crew.size}/$slots berths, $free free")
                .withStyle(ChatFormatting.GRAY)
        )
        if (crew.isEmpty()) {
            msg.append(Component.literal("\n    (nobody signed on)").withStyle(ChatFormatting.GRAY))
        } else {
            crew.groupingBy { professionName(it) }.eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (profession, count) ->
                    msg.append(Component.literal("\n    $count x $profession").withStyle(ChatFormatting.GRAY))
                }
        }

        // Everyone else on deck: passengers, other captains' crew, villagers who wandered aboard. Worth saying,
        // because "there are villagers here but none of them are yours" is otherwise indistinguishable from
        // "there are no villagers here". A ship may carry any number of them; only the crew count against
        // berths.
        val unsigned = villagers.size - crew.size
        if (unsigned > 0) {
            msg.append(
                Component.literal("\n  $unsigned other villager${if (unsigned == 1) "" else "s"} aboard")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
        }
        player.sendSystemMessage(msg)

        PathMessages.send(player, "Crew: ${crew.size}/$slots aboard ${ShipCrew.name(ship)}.", PathMessages.Kind.GOOD)
    }

    // endregion

    // region marking the deck

    /**
     * Mark this ship's crew so they can be told apart at a glance.
     *
     * The list is deliberately scoped to the deck the player is standing on. Crews are per ship and a player is
     * free to hop between hulls, so "who is crew" only has an answer relative to somewhere -- and here is the
     * only place the gesture can mean.
     *
     * Toggles, like the route-hide key: pressing it again puts the markers away rather than making the player
     * wait one out. Recruiting someone new while they are up does not update them; press twice to refresh.
     */
    private fun markCrewOnDeck(level: ServerLevel, player: ServerPlayer) {
        if (CrewMarkers.clearIfShowing(player)) {
            PathMessages.send(player, "Crew markers off.", PathMessages.Kind.GOOD)
            return
        }

        val ship = shipUnder(level, player)
        if (ship == null) {
            PathMessages.send(
                player,
                "Look at a villager to sign them on, or at a wheel to read its articles.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val station = CrewStations.stationOf(level, ship)
        if (station == null) {
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} has no wheel keeping articles. Look at a helm and press the crew key.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val crew = crewOf(level, ship, station, player.uuid)
        if (crew.isEmpty()) {
            PathMessages.send(
                player,
                "Nobody signed on aboard ${ShipCrew.name(ship)}. Look at a villager to sign them on.",
                PathMessages.Kind.ERROR
            )
            return
        }

        CrewMarkers.show(player, crew.map { it.id })
        PathMessages.send(
            player,
            "Marked ${crew.size} crew aboard ${ShipCrew.name(ship)}.",
            PathMessages.Kind.GOOD
        )
    }

    // endregion

    // region lookups

    /** [owner]'s crew currently aboard [ship] -- the list the berth limit is tested against, and the one marked. */
    private fun crewOf(
        level: ServerLevel,
        ship: LoadedServerShip,
        station: ShipHelmBlockEntity,
        owner: UUID
    ): List<Villager> =
        ShipCrew.villagersAboard(level, ship).filter { station.crew.ownerOf(it.uuid) == owner }

    /**
     * The villager under the crosshair, or null.
     *
     * There is no entity raycast anywhere else in this mod -- `ShipFollows.lookedAtShip` picks BLOCKS -- so
     * this is built from the two vanilla halves. `Entity.pick` runs first purely as the occlusion clamp: VS2
     * wraps `Level.clip`, so it stops on a ship's hull as well as on terrain, and you cannot sign on someone
     * through a bulkhead.
     *
     * A villager on a deck stands at ordinary WORLD coordinates -- VS2 drags entities rather than parking them
     * in the shipyard -- so the ray needs no ship transform, whether the player is aboard or on the quay.
     */
    private fun lookedAtVillager(level: ServerLevel, player: ServerPlayer): Villager? {
        val eye = player.eyePosition
        val far = eye.add(player.getViewVector(1.0f).scale(REACH))

        val blocked = player.pick(REACH, 1.0f, false)
        val limit = if (blocked.type != HitResult.Type.MISS) blocked.location else far

        val hit = ProjectileUtil.getEntityHitResult(
            level, player, eye, limit, AABB(eye, limit).inflate(1.0),
            { it is Villager && it.isAlive }, 0.0f
        ) ?: return null
        return hit.entity as? Villager
    }

    /**
     * The ship's-wheel block entity under the crosshair, of any wood, or null.
     *
     * Reach here is the player's own BLOCK interaction range rather than the recruiting reach, so "close enough
     * to open its manifest" is the same distance as "close enough to mount it or break it". A wheel you cannot
     * touch does not answer questions about its crew.
     *
     * VS2 wraps `Level.clip`, so a hit on a ship's block reports its SHIPYARD position -- which is exactly the
     * coordinate the block entity lives at, so this needs no transform of its own. The same property is what
     * `ShipFollows.lookedAtShip` relies on.
     */
    private fun lookedAtHelm(level: ServerLevel, player: ServerPlayer): ShipHelmBlockEntity? {
        val hit = player.pick(player.blockInteractionRange(), 1.0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) return null
        return level.getBlockEntity(hit.blockPos) as? ShipHelmBlockEntity
    }

    /**
     * The ship an entity is standing on or seated in, not normalized to an armada parent.
     *
     * `ShipCrew.villagersAboard` tests membership against the whole group anyway, so promoting a child to its
     * parent here would only discard which deck they are actually on -- and the roster header is nicer for
     * naming the hull you are stood on rather than the flagship it happens to be welded to.
     */
    private fun shipUnder(level: ServerLevel, entity: Entity): LoadedServerShip? {
        val world = level.shipObjectWorld
        return (getShipMountedTo(entity) as? LoadedServerShip)
            ?: (entity as? IEntityDraggingInformationProvider)
                ?.draggingInformation?.lastShipStoodOn
                ?.let { world.loadedShips.getById(it) }
    }

    /** The localised profession name, which reads "Crewman" for ours and "Farmer" for a vanilla one. */
    private fun professionName(villager: Villager): String =
        villager.villagerData.profession().value().name().string

    // endregion

    /**
     * How far you can sign someone on from. A little past vanilla's block reach -- close enough that you are
     * plainly addressing a particular person, and not so close that you must stand on their toes.
     */
    private const val REACH = 6.0
}
