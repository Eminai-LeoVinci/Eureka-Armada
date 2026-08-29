package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.cannon.CannonFire
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/**
 * What the gun crews actually DO -- the first job in the mod that a villager holds rather than a player.
 *
 * The duties were built in pairs on purpose because each is what makes the other worth paying for: an
 * incendiary round sets a deck alight, and a fire party is what puts it out. A ship with guns and no fire
 * watch wins the exchange and burns; a ship with a fire watch and no guns survives a fight it cannot end.
 * The fire half lives in [FireBrigade], where the crew genuinely run to the flames; this file is the gunnery.
 *
 * ## The crew are the ship's, not the captain's
 * Berths are bought per captain, but a duty is performed for whatever hull the crew member is standing on. So a
 * broadside is fired by every gunner ABOARD, whoever signed them on -- two captains sailing one ship crew it
 * between them without any agreement having to be recorded anywhere. This is the same intersection rule the
 * roster already uses ("crew is who is signed on AND here"), applied to work instead of to membership.
 *
 * ## One crewman per cannon
 * A gun does nothing on its own. The number of guns that fire is `min(gunners aboard, guns aboard)`, which is
 * the whole of the rule -- a six-gun broadside costs six berths, and berths cost Hearts of the Sea. Extra guns
 * are dead weight until somebody is bought to stand at them, and extra gunners are simply idle. Neither is an
 * error worth refusing: hulls lose guns to cannon fire mid-battle, and a crew that had to be re-assigned every
 * time a gunport was blown in would be unmanageable exactly when it mattered.
 *
 * ## Why guns are ordered to fire rather than firing on sight
 * There is nothing yet for a gunner to decide to shoot AT -- pirates are the next phase. Until there is, the
 * captain calls the volley and the crew serve the guns, which is both the truer picture of a gun deck and the
 * half that has to exist first either way: an autonomous gunner is this code plus a target, not instead of it.
 */
object CrewDuties {

    // region the broadside

    /**
     * "Fire!" -- every manned gun aboard the ship [player] is on, one after another.
     *
     * Deliberately fires ALL manned guns, including the battery pointing at open sea. Which guns speak is
     * chosen by what the captain has LOADED, not by a side-picker in here: leave the starboard magazines empty
     * and only the port battery answers. That keeps the decision in the world, where the player can see it,
     * rather than in a rule they would have to be told about.
     */
    fun broadside(level: ServerLevel, player: ServerPlayer) {
        val ship = shipUnder(level, player)
        if (ship == null) {
            PathMessages.send(player, "Stand aboard a ship to give the order.", PathMessages.Kind.ERROR)
            return
        }

        // One volley at a time, per ship. Not a nicety: the stagger below is only a rate of fire if a
        // second order cannot be pressed straight through it. At the default two ticks a roll is over
        // before anybody could ask again, but wound out to a couple of seconds a captain could empty a
        // gun deck in one burst by giving the order six times -- every gun still queued is still loaded
        // and still ready, so every one of them would answer at once. Refused rather than queued, because
        // a captain calling it twice means "fire", not "fire again when this one is done".
        val rolling = volleys.firstOrNull { it.ship == ship.id }
        if (rolling != null) {
            val left = rolling.guns.size
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} is still firing -- $left gun${if (left == 1) "" else "s"} yet to speak.",
                PathMessages.Kind.WARN
            )
            return
        }

        val guns = ShipGuns.aboard(level, ship)
        if (guns.isEmpty()) {
            PathMessages.send(player, "${ShipCrew.name(ship)} carries no guns.", PathMessages.Kind.ERROR)
            return
        }

        // One crewman per cannon, for real: a gun speaks only when a gunner is SEATED at it. The ledger's
        // station bindings are the muster -- an unstationed gunner is a hand on deck, not a gun crew, and a
        // stationed one whose villager has died has already had the berth struck and the binding with it.
        val ledger = CrewLedger.get(level.server)
        val stations = ledger.stationedBerths().mapNotNullTo(HashSet()) { it.station }
        // Label order, not readiness order: L1 down the port side, then starboard, then the chasers -- a
        // rolling broadside that reads as the gun line it is. Falls back to the plain scan on a ship whose
        // wheel has no articles (no labels), where nothing can be stationed anyway.
        val labeled = GunLabels.labeled(level, ship)
        val manned = (if (labeled.isEmpty()) guns else labeled.map { it.gun })
            .filter { it.blockPos.asLong() in stations }
        if (manned.isEmpty()) {
            PathMessages.send(
                player,
                "Nobody is stationed at a gun. Open the crew manifest and assign gunners their stations.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val now = level.gameTime
        val ready = manned.filter { it.loaded && it.readyBy(now) }

        if (ready.isEmpty()) {
            PathMessages.send(
                player,
                if (manned.any { !it.loaded }) "The guns want powder and shot." else "Still reloading.",
                PathMessages.Kind.WARN
            )
            return
        }

        volleys.add(
            Volley(
                dimension = level.dimension(),
                ship = ship.id,
                guns = ArrayDeque(ready.map { it.blockPos }),
                by = player.uuid,
                nextAt = now
            )
        )

        val unmanned = guns.size - manned.size
        val note = if (unmanned > 0) " $unmanned unmanned." else ""
        ShipCrew.tellOthers(level, ship, player, "${ShipCrew.name(ship)} is firing.", PathMessages.Kind.WARN, PathMessages.Topic.GUNNERY_BROADSIDE)
        PathMessages.send(
            player,
            "Fire! ${ready.size} of ${guns.size} gun${if (guns.size == 1) "" else "s"}.$note",
            PathMessages.Kind.GOOD, PathMessages.Topic.GUNNERY_BROADSIDE
        )
    }

    /**
     * A volley in progress: the guns still to speak, and when the next one does.
     *
     * Guns are fired a few ticks apart rather than all on one tick, which is presentation rather than balance --
     * six cannons on a single tick is one loud noise and one cloud of smoke, where a rolling broadside is the
     * thing the player built a gun deck to hear. It also spreads six projectile spawns and six sound packets
     * over half a second.
     *
     * It is also the ship's rate of fire under command, which is why it is a config key now and not a constant.
     * Each gun keeps its own reload underneath, so the stagger can only ever make a ship slower -- but wound
     * out from two ticks to forty it stops being a flourish and becomes the whole of how fast a crew work: one
     * gun every two seconds, in label order, however many are manned. Fire at Will has a stagger of its own for
     * the same reason, so the two can be tuned against each other rather than only together.
     */
    private class Volley(
        val dimension: ResourceKey<Level>,
        /** Whose guns these are. One volley per hull at a time, whoever gave the order. */
        val ship: Long,
        val guns: ArrayDeque<BlockPos>,
        /** Resolved to a player at each shot, so a captain who logs out mid-volley leaves the guns firing. */
        val by: UUID,
        var nextAt: Long
    )

    private val volleys = ArrayList<Volley>()

    private fun advanceVolleys(level: ServerLevel) {
        if (volleys.isEmpty()) return
        val now = level.gameTime
        val iterator = volleys.iterator()

        while (iterator.hasNext()) {
            val volley = iterator.next()
            if (volley.dimension != level.dimension()) continue
            if (now < volley.nextAt) continue

            val gun = volley.guns.removeFirstOrNull()
            if (gun == null) {
                iterator.remove()
                continue
            }

            // The refusal is dropped on purpose. A gun that has been shot off its mounting, or emptied by
            // somebody else between the order and its turn, has nothing useful to say to a captain mid-volley --
            // and the count they were given when they gave the order already told them what to expect.
            CannonFire.fire(level, gun, level.server.playerList.getPlayer(volley.by))
            volley.nextAt = now + staggerTicks()

            if (volley.guns.isEmpty()) iterator.remove()
        }
    }

    // endregion

    /** Per world tick: advance any volley in flight. The fire watch runs on [FireBrigade]'s own clocks. */
    fun tick(level: ServerLevel) {
        advanceVolleys(level)
    }

    /**
     * Drop everything in flight. Called when the server stops, for the reason `ShipPaths.reset` is.
     *
     * A volley is runtime-only by design -- half a second long, so persisting it would be recording something
     * that has already finished. But in single player the singleton outlives the world, and a stale volley in
     * the next one would order ghost guns to fire.
     */
    fun reset() {
        volleys.clear()
    }

    /** The ship [player] is standing on or seated in. Seated counts: the order is given from the wheel. */
    private fun shipUnder(level: ServerLevel, player: ServerPlayer): LoadedServerShip? {
        val id = ShipCrew.standingOn(player) ?: return null
        return level.shipObjectWorld.loadedShips.getById(id) as? LoadedServerShip
    }

    /**
     * Ticks between one gun speaking and the next. Two is fast enough to read as one broadside rather than six
     * shots; a floor of one, because zero and one are the same tick either way -- this loop fires a single gun
     * per volley per tick by construction, so "no gap at all" is a promise it could not keep.
     */
    private fun staggerTicks(): Long =
        EurekaConfig.SERVER.crewBroadsideStaggerTicks.toLong().coerceAtLeast(1L)
}
