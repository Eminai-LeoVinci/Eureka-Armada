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
import org.valkyrienskies.eureka.ship.ShipFootprint
import org.valkyrienskies.eureka.ship.ShipIntegrity
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

        val leader = world.loadedShips.getById(follower.leaderId)
        if (leader == null || leader.chunkClaimDimension != ship.chunkClaimDimension) {
            breakOff(level, ship, follower, "Lost contact -- the leader is gone.")
            return
        }

        // A leader going down is not a leader to station on. Attachment gone means every helm aboard was
        // broken (the control deletes itself with its last helm); too far gone means the damage floor put it
        // in freefall. Either way the ship ahead is falling, and a follower that kept station would follow
        // it faithfully into the ground or the seabed -- pursuit is for ships that are going somewhere.
        val leaderControl = leader.getAttachment(EurekaShipControl::class.java)
        if (leaderControl == null || ShipIntegrity.freefall(ShipIntegrity.integrityPercent(leaderControl))) {
            breakOff(level, ship, follower, "Breaking off -- the leader is going down.")
            return
        }

        // No blocks to measure yet. Early rather than stale, same as the helm above.
        val frame = FollowGeometry.frameOf(leader) ?: return

        // The koi case: the leader is following US, so the pair orbit each other instead of one keeping
        // station on the other. Answered here because this object owns the followers map.
        val mutual = followers[follower.leaderId]?.leaderId == follower.shipId

        // No side or slot while orbiting -- a circle has neither beams nor a queue, and letting the flip
        // hysteresis run on orbital geometry would churn the slot for nothing.
        if (!follower.circling) updateSide(level, ship, leader, follower, frame)

        if (!follower.tick(ship, control, leader, frame, mutual)) {
            breakOff(level, ship, follower, "Pursuit ended -- the ship lost its course.")
            return
        }

        // One line at each edge of the orbit, to whoever is standing on deck. Consumed here rather than sent
        // from the follower because messages need the level, and the follower deliberately never sees one.
        when (follower.consumeCircleTransition()) {
            1 -> ShipCrew.tell(
                level, ship,
                if (mutual) "Circling with '${ShipCrew.name(leader)}'." else "Circling '${ShipCrew.name(leader)}'.",
                PathMessages.Kind.GOOD
            )
            -1 -> ShipCrew.tell(
                level, ship,
                "'${ShipCrew.name(leader)}' is under way -- resuming pursuit.",
                PathMessages.Kind.GOOD
            )
        }

        // One rule, and it is the user's: a pursuit ends when the leader OUTPACES the follower, and at nothing
        // else. Everything that makes that hold is in `secondsAdrift` -- the distance is measured from the
        // station rather than the leader's centre so a wide berth doesn't count against a ship that is keeping
        // perfect formation, and it has to hold continuously, because a leader putting the wheel over throws
        // the station point sideways faster than any hull can chase it and that is manoeuvring, not escaping.
        // The `> 0.0` is not redundant: it is what keeps a grace of zero meaning "the moment it goes adrift".
        if (follower.secondsAdrift > 0.0 && follower.secondsAdrift >= cfg.followBreakGrace) {
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

        val lateral = (centre.x - frame.centre.x) * frame.beam.x + (centre.z - frame.centre.z) * frame.beam.z
        // Last tick's standoff rather than a fresh one: this runs before the follower has been stepped, and the
        // ship's own heading -- which the standoff is now measured against -- is only worked out in there. A
        // tick of staleness in a hysteresis band is worth nothing. Zero on the very first tick, which is safe:
        // the side was just taken from this same dot product, so the test below cannot flip it.
        val threshold = follower.standoff * cfg.followSideFlipMargin

        if (lateral * follower.side >= -threshold) return

        follower.side = -follower.side
        follower.slot = claimSlot(follower.leaderId, follower.side, follower.shipId)
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
        val world = level.shipObjectWorld

        val ownId = ShipCrew.standingOn(player)
        val own = ownId?.let { world.loadedShips.getById(it) }
            ?: return fail(player, "Stand on a ship to make it follow another.")

        // Sized off the ship giving the order, not the one being pointed at -- reach is a fact about the vessel
        // setting off, and the target isn't known until the ray has already been cast.
        val reach = targetRange(own)
        val target = lookedAtShip(level, player, reach)
            ?: return fail(player, "Look at a ship to follow it (within ${reach.toInt()}m).")

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

        when (bind(level, own, target, ownerId = player.uuid)) {
            null -> Unit
            BindRefusal.ARMADA_CHILD ->
                return fail(player, "An armada follows as one vessel -- give the order from the flagship's deck.")
            BindRefusal.NO_CONTROL ->
                return fail(player, "This ship has no helm to steer it.")
            BindRefusal.NOT_READY ->
                return fail(player, "This ship's helm hasn't come up yet -- try again in a moment.")
            BindRefusal.SAME_VESSEL ->
                return fail(player, "That's your own vessel.")
            BindRefusal.BUSY_ROUTE ->
                return fail(player, "This ship is busy with a route -- hold SHIFT+P to release it first.")
            BindRefusal.FOLLOW_CHAIN ->
                return fail(player, "'${ShipCrew.name(target)}' is already in this ship's follow chain.")
            BindRefusal.NO_TARGET_FRAME ->
                return fail(player, "That ship has no blocks to take station on.")
            BindRefusal.NO_OWN_CENTRE ->
                return fail(player, "This ship has no blocks to measure from.")
            BindRefusal.NO_HELM ->
                return fail(player, "This ship needs a helm before it can follow anything.")
        }
        // Non-null after a successful bind -- bind() refused with NO_CONTROL otherwise.
        val control = own.getAttachment(EurekaShipControl::class.java) ?: return

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

    /** Why [bind] said no. [begin] maps each to its player-worded refusal; other callers act on the value. */
    enum class BindRefusal {
        ARMADA_CHILD, NO_CONTROL, NOT_READY, SAME_VESSEL, BUSY_ROUTE, FOLLOW_CHAIN,
        NO_TARGET_FRAME, NO_OWN_CENTRE, NO_HELM
    }

    /**
     * Bind [own] to keep station on [target] -- the whole mechanics of a pursuit, with no player anywhere
     * in it. [begin] is the Sneak+F wrapper (raycast, toggle, messages); this is what pirate ships call, and
     * every guard a pursuit needs lives HERE so the two callers cannot drift apart.
     *
     * @param ownerId who gave the order, if anyone. Recorded on the follower and read by nothing today; a
     * pirate's pursuit has no owner and passes null.
     * @return null on success, or the reason the bind was refused.
     */
    fun bind(level: ServerLevel, own: LoadedServerShip, target: LoadedServerShip, ownerId: UUID?): BindRefusal? {
        // Deliberately NOT what ShipPaths.resolveShip does -- that one silently promotes a child to its
        // parent. An armada moves as one vessel under the flagship's control, so a follow order bound to a
        // welded-on child is ambiguous about which ship is meant to be doing the following.
        if (ArmadaShipControl.get(own)?.isChild == true) return BindRefusal.ARMADA_CHILD

        val control = own.getAttachment(EurekaShipControl::class.java) ?: return BindRefusal.NO_CONTROL
        if (!control.pathHullReady) return BindRefusal.NOT_READY
        if (ArmadaGroup.sameVessel(level, own, target)) return BindRefusal.SAME_VESSEL
        if (ShipPaths.isBusy(own.id)) return BindRefusal.BUSY_ROUTE

        // A pair marking EACH OTHER is the koi manoeuvre -- both ships enter the circling mode and orbit their
        // shared midpoint -- so the direct A<->B case is welcomed through. Longer loops (A follows B follows C
        // follows A) are still refused: nothing in a three-ship ring is circling anything in particular, and
        // each keeps station on something that is keeping station on it while the whole ring drifts.
        val mutualPair = followers[target.id]?.leaderId == own.id
        if (!mutualPair && leadsBackTo(own.id, target.id)) return BindRefusal.FOLLOW_CHAIN

        // BEFORE pathBegin, not after. pathBegin sets `pathFollowing`, which suppresses every abandoned-hull
        // brake in physTick on the promise that something is about to steer. Bailing out after it would leave
        // that promise unkept and the flag set with no follower to clear it -- the hull would coast, unbraked
        // and unsteered, until someone sat down at the wheel. Everything that can refuse has to refuse first.
        val frame = FollowGeometry.frameOf(target) ?: return BindRefusal.NO_TARGET_FRAME
        val centre = FollowGeometry.centreOf(own, Vector3d()) ?: return BindRefusal.NO_OWN_CENTRE

        if (!control.pathBegin()) return BindRefusal.NO_HELM

        // Whichever beam of the leader this ship is already on. Because the side is taken from where the
        // follower IS, the approach never has to cross the leader's centreline -- which is most of the reason
        // this can be a simple controller and still not cut across the leader's bow.
        // 0 (dead on the centreline, i.e. directly ahead or astern) has to become something, and either answer
        // is equally good there; +1 keeps it deterministic.
        val side = FollowGeometry.sideOf(frame, centre, 0.0).let { if (it == 0) 1 else it }

        followers[own.id] = ShipFollower(
            shipId = own.id,
            leaderId = target.id,
            ownerId = ownerId,
            side = side,
            slot = claimSlot(target.id, side, own.id)
        )
        return null
    }

    // endregion

    // region queries

    fun followerFor(shipId: Long): ShipFollower? = followers[shipId]
    fun isFollowing(shipId: Long): Boolean = followers.containsKey(shipId)

    // endregion

    // region helpers

    /**
     * How far this ship can reach to pick up something to follow, in blocks.
     *
     * Scaled because a fixed reach reads as two different features depending on what you are standing on: on a
     * raft 160 blocks is most of the horizon, and on a galleon that can barely see past its own rigging it is
     * the next ship over. Every size band adds `followTargetRangeStep`, so an 80-block reach on the smallest
     * hull becomes 200 at a footprint of 20 and tops out at `followTargetRangeMax`.
     */
    fun targetRange(ship: LoadedServerShip): Double {
        val cfg = EurekaConfig.SERVER
        val reach = cfg.followTargetRange + cfg.followTargetRangeStep * ShipFootprint.bands(ShipFootprint.of(ship))
        // coerceIn rather than coerceAtMost: a max edited below the base would otherwise invert the pair.
        return reach.coerceIn(cfg.followTargetRange, max(cfg.followTargetRange, cfg.followTargetRangeMax))
    }

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

    /**
     * Whether following [leaderId] would close a loop back to [ownId].
     *
     * The walk has to survive meeting SOMEONE ELSE'S mutual pair: two ships legitimately circling each other
     * are a cycle that never reaches [ownId], and the old depth-cap-means-refuse reading would have banned
     * following either of them. A visited set tells "a loop that includes me" from "a loop I merely lead to",
     * and only the first is a reason to refuse.
     */
    private fun leadsBackTo(ownId: Long, leaderId: Long): Boolean {
        val visited = HashSet<Long>()
        var current = leaderId
        var hops = 0
        while (hops++ < MAX_CHAIN) {
            if (current == ownId) return true
            if (!visited.add(current)) return false
            current = followers[current]?.leaderId ?: return false
        }
        // Deeper than any sane formation; whatever it is, it doesn't come back to us.
        return false
    }

    private fun fail(player: ServerPlayer, message: String) =
        PathMessages.send(player, message, PathMessages.Kind.ERROR)

    /** Estimated top speed (m/s) below which a ship is warned it probably can't keep up with anything. */
    private const val UNDER_POWERED = 1.0

    /** How far to walk a follow chain before calling it a loop. */
    private const val MAX_CHAIN = 16

    // endregion
}
