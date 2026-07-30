package org.valkyrienskies.eureka.follow

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.path.ShipPaths
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Owns every ship currently keeping station on another, and drives them from the server tick.
 *
 * Shaped after [ShipPaths], which does the same job for recorded routes, with one deliberate difference: this
 * is RUNTIME ONLY. A route is a place, and a ship bound to one can sensibly pick it up again next week; a
 * follow is an order given by a player who is standing there, about a leader that may not even exist by the
 * time they come back. So nothing here is written to the save, and logging out ends every pursuit.
 *
 * ## Why this isn't part of ShipPaths
 * The two cannot both run on one hull -- they would fight over the wheel -- so they are mutually exclusive by
 * construction (see the guards in [begin] and in `ShipPaths`). But they want different things from a ship: a
 * route needs the keel, a saved binding and a re-arm poll; a follow needs the hull's centre, a live second ship
 * and none of the persistence. Folding them together would have meant a class where half the fields are null
 * depending on which mode it is in.
 */
object ShipFollows {

    /** follower ship id -> its pursuit. */
    private val followers = ConcurrentHashMap<Long, ShipFollower>()

    private val scratch = Vector3d()

    // region tick

    /**
     * Advance every pursuit whose follower lives in [level]. Call once per server world tick.
     *
     * The dimension test is the same trap [ShipPaths.tick] documents: `loadedShips` is GLOBAL across dimensions
     * while this runs once per LEVEL, so without it a ship would be steered once per dimension that exists, and
     * looked up against the wrong level's blocks.
     */
    fun tick(level: ServerLevel) {
        if (followers.isEmpty()) return

        val dimension = level.dimensionId
        val world = level.shipObjectWorld

        for ((shipId, follower) in followers) {
            val ship = world.loadedShips.getById(shipId)
            // Deliberately KEPT rather than removed. A ship that has drifted out of simulation hasn't stopped
            // following, and if we dropped the entry here there would be no way to reach its EurekaShipControl to
            // clear `pathFollowing` -- the hull would come back believing something was steering it, with nothing
            // steering it, and the abandoned-hull brakes it suppresses would never come back on. Holding the
            // entry means the ship is released properly the moment it reloads, and costs one failed lookup a tick.
            if (ship == null) continue
            if (ship.chunkClaimDimension != dimension) continue
            tickFollower(level, ship, follower)
        }
    }

    private fun tickFollower(level: ServerLevel, ship: LoadedServerShip, follower: ShipFollower) {
        val cfg = EurekaConfig.SERVER
        val world = level.shipObjectWorld

        // Welded into an armada since the order was given: a child never runs its own control law (physTick
        // returns early for one), so it cannot steer itself anywhere and the pursuit is over.
        if (ArmadaShipControl.get(ship)?.isChild == true) {
            release(ship, stopShip = false)
            ShipCrew.tell(level, ship, "Pursuit ended -- this ship is part of an armada now.", PathMessages.Kind.WARN)
            return
        }

        val control = ship.getAttachment(EurekaShipControl::class.java)
        if (control == null) {
            // The helm is gone, so there is nothing left to steer with.
            release(ship, stopShip = false)
            return
        }
        // Not stale, just early -- a ship reloads into the loaded-ship index before its shipyard chunks tick
        // block entities, and the helm's tick is the only thing that hands the control its own ship.
        if (!control.pathHullReady) return

        if (checkManualTakeover(level, ship, control, follower)) return

        val leader = world.loadedShips.getById(follower.leaderId)
        if (leader == null || leader.chunkClaimDimension != ship.chunkClaimDimension) {
            breakOff(level, ship, follower, "Lost contact -- the leader is gone.")
            return
        }

        // No blocks to measure yet. Early rather than stale, same as the helm above.
        val frame = FollowGeometry.frameOf(leader) ?: return

        updateSide(level, ship, leader, follower, frame)

        if (!follower.tick(ship, control, leader, frame)) {
            breakOff(level, ship, follower, "Pursuit ended -- the ship lost its course.")
            return
        }

        // Normally just the configured distance. A pursuit ordered from further out than that -- the crosshair
        // reaches further than a follower can hold station -- is instead measured against how close it has
        // actually managed to get, so it breaks off when it stops making ground rather than on the tick after
        // the order was given. The moment it closes inside the configured range this collapses back to it.
        if (follower.range > max(cfg.followBreakRange, follower.closestRange)) {
            val name = ShipCrew.name(leader)
            release(ship, stopShip = true)
            ShipCrew.tell(
                level, ship,
                "Lost '$name' -- too far astern to keep up. Breaking off.",
                PathMessages.Kind.WARN
            )
            ShipCrew.tell(
                level, leader,
                "'${ShipCrew.name(ship)}' has fallen behind and broken off.",
                PathMessages.Kind.WARN
            )
        }
    }

    /**
     * Move the follower to the leader's other beam once it is clearly over there, and re-slot it.
     *
     * The margin is what makes this usable rather than maddening. Which side a ship is on is the sign of one
     * dot product, and a follower sitting dead astern -- which is exactly where one spends the whole approach --
     * has that dot product hovering around zero. Flipping on the raw sign would swap its station from port to
     * starboard and back every tick, and the ship would simply shake.
     *
     * What this buys is the case the user actually asked about: a leader that turns about. Its beam vector
     * reverses with it, which puts the follower a whole standoff onto the other side in one go -- comfortably
     * past any sensible margin -- so it takes station on the near beam instead of swinging round the stern.
     */
    private fun updateSide(
        level: ServerLevel,
        ship: LoadedServerShip,
        leader: LoadedServerShip,
        follower: ShipFollower,
        frame: FollowGeometry.Frame
    ) {
        val cfg = EurekaConfig.SERVER
        val centre = FollowGeometry.centreOf(ship, scratch) ?: return
        val standoff = FollowGeometry.standoff(leader, ship, frame, cfg.followGap)

        val lateral = (centre.x - frame.centre.x) * frame.beam.x + (centre.z - frame.centre.z) * frame.beam.z
        val threshold = standoff * cfg.followSideFlipMargin

        if (lateral * follower.side >= -threshold) return

        follower.side = -follower.side
        follower.slot = claimSlot(follower.leaderId, follower.side, follower.shipId)
    }

    /**
     * Hand the ship back to the pilot when they hold any control input, and report having done so.
     *
     * Unlike a route, the THROTTLE counts here alongside steering and elevation. A route never owned speed, so
     * driving while bound to one was ordinary use; a follow owns speed completely, so a pilot pushing the
     * throttle is arguing with the ship and has to be able to win. They win instantly in the physics -- a live
     * forward input outranks the commanded speed on the very next tick -- and holding it is what makes the
     * handover permanent. A brief nudge past an obstacle stays a nudge, and the station re-acquires.
     *
     * Returns true if the pursuit was ended, in which case the caller must not steer this tick.
     */
    private fun checkManualTakeover(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        follower: ShipFollower
    ): Boolean {
        val hold = EurekaConfig.SERVER.followCancelHold
        if (hold <= 0.0) return false

        // Signed seconds accumulated on the game thread and zeroed the moment the input stops, so nothing stale
        // can build up while nobody is at the helm.
        val turning = abs(control.turnHold)
        val climbing = abs(control.vertHold)
        val driving = abs(control.fwdHold)
        val held = max(driving, max(turning, climbing))
        if (held <= 0.0) return false

        if (held >= hold) {
            // Not stopShip: the pilot is taking over, so leave them the way they have on rather than dropping a
            // moving vessel's throttle out from under them.
            release(ship, stopShip = false)
            ShipCrew.tell(level, ship, "You have the wheel -- pursuit ended.", PathMessages.Kind.WARN)
            return true
        }

        if (held >= hold * PROMPT_AT) {
            val what = when {
                driving >= turning && driving >= climbing -> "the throttle"
                turning >= climbing -> "turning"
                else -> "climbing"
            }
            // Only the people who can see the wheel need this one, and only one of them is holding a key.
            for (player in ShipCrew.aboard(level, ship)) {
                PathMessages.send(player, "Keep on $what to break off the pursuit…", PathMessages.Kind.PROMPT)
            }
        }
        return false
    }

    /** End a pursuit that can no longer be flown, bring the ship up, and tell whoever is aboard. */
    private fun breakOff(level: ServerLevel, ship: LoadedServerShip, follower: ShipFollower, message: String) {
        release(ship, stopShip = true)
        ShipCrew.tell(level, ship, message, PathMessages.Kind.ERROR)
    }

    /**
     * Take a ship off its pursuit and release the hull's guidance. Returns what was removed, or null.
     *
     * Every way OFF a pursuit goes through here. The slot the follower held is freed implicitly, because slots
     * are read back out of this map rather than tracked separately -- there is no second structure to leave
     * stale, which is the failure this shape is chosen to make impossible.
     */
    private fun release(ship: LoadedServerShip, stopShip: Boolean): ShipFollower? {
        val follower = followers.remove(ship.id)
        // Guarded: `stopShip` drops the ship's cruise too, and a ship that was never following must not lose its
        // throttle just because something asked whether it was.
        if (follower != null) ship.getAttachment(EurekaShipControl::class.java)?.pathRelease(stopShip)
        return follower
    }

    /**
     * Drop every pursuit. Call when the SERVER stops, not when a level unloads.
     *
     * This object outlives a world: in single player, quitting to the title screen stops the integrated server
     * but leaves every singleton in the JVM standing. Without this, a ship in the NEXT world that happened to
     * take a follower's id would be steered at a leader from the last one. The route feature learned this the
     * hard way -- see `ShipPaths.reset`.
     */
    fun reset() = followers.clear()

    // endregion

    // region player actions

    /**
     * Sneak+F. Begin (or end) a pursuit of whatever ship the player is looking at.
     *
     * Pressing it again while already chasing that same ship breaks off, which is why there is no separate stop
     * key: the gesture that starts a thing is the obvious one for ending it, and the pilot is by definition
     * standing on the ship in question.
     */
    fun begin(level: ServerLevel, player: ServerPlayer) {
        val cfg = EurekaConfig.SERVER
        val world = level.shipObjectWorld

        val ownId = ShipCrew.standingOn(player)
        val own = ownId?.let { world.loadedShips.getById(it) }
            ?: return fail(player, "Stand on a ship to make it follow another.")

        // The user's rule, and deliberately NOT what ShipPaths.resolveShip does -- that one silently promotes a
        // child to its parent. An armada moves as one vessel under the flagship's control, so an order given
        // from a welded-on child is ambiguous about which ship is meant to be doing the following. Making the
        // player walk to the flagship makes it unambiguous, and it is where the wheel is anyway.
        if (ArmadaShipControl.get(own)?.isChild == true) {
            return fail(player, "An armada follows as one vessel -- give the order from the flagship's deck.")
        }

        val control = own.getAttachment(EurekaShipControl::class.java)
            ?: return fail(player, "This ship has no helm to steer it.")
        if (!control.pathHullReady) {
            return fail(player, "This ship's helm hasn't come up yet -- try again in a moment.")
        }

        val target = lookedAtShip(level, player, cfg.followTargetRange)
            ?: return fail(player, "Look at a ship to follow it (within ${cfg.followTargetRange.toInt()}m).")

        if (ArmadaGroup.sameVessel(level, own, target)) {
            return fail(player, "That's your own vessel.")
        }

        // Already chasing this exact ship: the key is a toggle.
        val existing = followers[own.id]
        if (existing != null && existing.leaderId == target.id) {
            release(own, stopShip = true)
            ShipCrew.tell(level, own, "Broke off pursuit of '${ShipCrew.name(target)}'.", PathMessages.Kind.GOOD)
            ShipCrew.tell(
                level, target,
                "'${ShipCrew.name(own)}' is no longer following you.",
                PathMessages.Kind.GOOD
            )
            return
        }

        if (ShipPaths.isBusy(own.id)) {
            return fail(player, "This ship is busy with a route -- stop that first.")
        }

        // A chases B chases A is a standoff neither ship can resolve: each keeps station on something that is
        // keeping station on it, and the pair drifts off together doing it.
        if (leadsBackTo(own.id, target.id)) {
            return fail(player, "'${ShipCrew.name(target)}' is already following yours.")
        }

        // BEFORE pathBegin, not after. pathBegin sets `pathFollowing`, which suppresses every abandoned-hull
        // brake in physTick on the promise that something is about to steer. Bailing out after it would leave
        // that promise unkept and the flag set with no follower to clear it -- the hull would coast, unbraked
        // and unsteered, until someone sat down at the wheel. Everything that can refuse has to refuse first.
        val frame = FollowGeometry.frameOf(target)
            ?: return fail(player, "That ship has no blocks to take station on.")
        val centre = FollowGeometry.centreOf(own, Vector3d())
            ?: return fail(player, "This ship has no blocks to measure from.")

        if (!control.pathBegin()) {
            return fail(player, "This ship needs a helm before it can follow anything.")
        }

        // Whichever beam of the leader this ship is already on. Because the side is taken from where the
        // follower IS, the approach never has to cross the leader's centreline -- which is most of the reason
        // this can be a simple controller and still not cut across the leader's bow.
        // 0 (dead on the centreline, i.e. directly ahead or astern) has to become something, and either answer
        // is equally good there; +1 keeps it deterministic.
        val side = FollowGeometry.sideOf(frame, centre, 0.0).let { if (it == 0) 1 else it }

        followers[own.id] = ShipFollower(
            shipId = own.id,
            leaderId = target.id,
            ownerId = player.uuid,
            side = side,
            slot = claimSlot(target.id, side, own.id),
            acquiredAt = frame.centre.distance(centre)
        )

        val leaderName = ShipCrew.name(target)
        PathMessages.send(player, "In pursuit of '$leaderName' -- coming alongside.", PathMessages.Kind.GOOD)
        ShipCrew.tellOthers(
            level, own, player,
            "This ship is now in pursuit of '$leaderName'.",
            PathMessages.Kind.GOOD
        )
        ShipCrew.tell(
            level, target,
            "'${ShipCrew.name(own)}' is following you.",
            PathMessages.Kind.WARN
        )

        // Worth saying once rather than leaving someone watching a ship that isn't moving. The follow owns the
        // throttle outright, so unlike a route it does NOT need a cruise speed set -- but it does need engines.
        if (control.estimateTopSpeed() < UNDER_POWERED) {
            PathMessages.send(
                player,
                "This ship has little power -- it may not be able to keep up.",
                PathMessages.Kind.WARN
            )
        }
    }

    /** Stop a ship's pursuit without needing a player. */
    fun stopShip(ship: LoadedServerShip): Boolean = release(ship, stopShip = true) != null

    // endregion

    // region queries

    fun followerFor(shipId: Long): ShipFollower? = followers[shipId]
    fun isFollowing(shipId: Long): Boolean = followers.containsKey(shipId)

    // endregion

    // region helpers

    /**
     * The ship the player is looking at, or null.
     *
     * Cast on the SERVER, which is where the answer has to come from: the client's own hit result is famously
     * unreliable here (see the note in `ShipHelmScreen`), and a client-supplied ship id would be a client
     * choosing which ship it gets to command.
     *
     * `Entity.pick` goes through `Level.clip`, which VS2 wraps so the ray is transformed into each ship's model
     * space -- so this resolves ships as well as terrain, and the returned `blockPos` is in SHIPYARD
     * coordinates, which is exactly what makes the chunk-claim lookup below find the ship. Do not try to
     * reverse-map it. Terrain in the way blocks the pick, which is correct: you have to be able to see it.
     */
    private fun lookedAtShip(level: ServerLevel, player: ServerPlayer, range: Double): LoadedServerShip? {
        val hit = player.pick(range, 1.0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) return null
        return level.getLoadedShipManagingPos(hit.blockPos) as? LoadedServerShip
    }

    /**
     * The lowest station index on [leaderId]'s [side] that no other follower is holding.
     *
     * Slots are derived from the live map every time they are asked for, so a ship breaking off frees its
     * station with no bookkeeping. Deliberately NOT re-packed afterwards: the remaining ships keep the stations
     * they were given rather than all shuffling up one when someone leaves, which is both what a station order
     * means and far less alarming to watch.
     */
    private fun claimSlot(leaderId: Long, side: Int, selfId: Long): Int {
        val taken = HashSet<Int>()
        for ((id, other) in followers) {
            if (id == selfId) continue
            if (other.leaderId == leaderId && other.side == side) taken.add(other.slot)
        }
        var slot = 0
        while (slot in taken) slot++
        return slot
    }

    /** Whether following [leaderId] would close a loop back to [ownId]. Depth-capped, never trusting a chain. */
    private fun leadsBackTo(ownId: Long, leaderId: Long): Boolean {
        var current = leaderId
        var hops = 0
        while (hops++ < MAX_CHAIN) {
            if (current == ownId) return true
            current = followers[current]?.leaderId ?: return false
        }
        // A chain this long is either a cycle or something that should not exist; refuse either way.
        return true
    }

    private fun fail(player: ServerPlayer, message: String) =
        PathMessages.send(player, message, PathMessages.Kind.ERROR)

    /** Fraction of the cancel hold at which the "keep holding" prompt appears. */
    private const val PROMPT_AT = 0.25

    /** Estimated top speed (m/s) below which a ship is warned it probably can't keep up with anything. */
    private const val UNDER_POWERED = 1.0

    /** How far to walk a follow chain before calling it a loop. */
    private const val MAX_CHAIN = 16

    // endregion
}
