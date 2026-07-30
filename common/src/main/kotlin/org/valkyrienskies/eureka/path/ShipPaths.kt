package org.valkyrienskies.eureka.path

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Owns every in-progress recording and every ship currently flying a route, and drives them from the server
 * tick. This is the only entry point the rest of the mod needs: hotkeys, commands and networking all come
 * through here.
 *
 * Recording is RUNTIME ONLY: a half-flown loop is a gesture in progress, not a thing, and a recording that
 * survived a reload would be missing however much of the route was flown before the save. Cancel and fly it
 * again.
 *
 * FOLLOWING is persisted, on the ship rather than here ([PathBinding]), and [tick] re-arms it. See that class
 * for why -- briefly: cruise already persists, so the old runtime-only behaviour didn't stop a reloaded ship, it
 * only stopped it steering.
 */
object ShipPaths {

    private val recorders = ConcurrentHashMap<Long, PathRecorder>()
    private val followers = ConcurrentHashMap<Long, PathFollower>()

    private val scratchKeel = Vector3d()

    // region tick

    /**
     * Re-arm any saved binding that has no follower yet, then advance every recording and every follower whose
     * ship lives in [level].
     *
     * Call once per server world tick, beside `ArmadaBindings.reconcile`.
     *
     * The dimension test is load-bearing and easy to get wrong: `loadedShips` is GLOBAL across dimensions while
     * this method runs once per LEVEL, so without it every ship would be ticked once per dimension that exists
     * -- and worse, would be looked up against the wrong level's blocks. `getById` returning a ship whose claim
     * is elsewhere means "not this level's problem", NOT "gone"; only a null lookup means the ship is really
     * unloaded, which is what makes the cleanup below safe.
     */
    fun tick(level: ServerLevel) {
        restoreBindings(level)
        if (recorders.isEmpty() && followers.isEmpty()) return

        val dimension = level.dimensionId
        val world = level.shipObjectWorld

        for ((shipId, recorder) in recorders) {
            val ship = world.loadedShips.getById(shipId)
            if (ship == null) {
                recorders.remove(shipId)
                KeelAnchor.forget(shipId)
                tell(level, recorder.playerId, "Recording lost -- the ship unloaded.", error = true)
                continue
            }
            if (ship.chunkClaimDimension != dimension) continue
            tickRecorder(level, ship, recorder)
        }

        for ((shipId, follower) in followers) {
            val ship = world.loadedShips.getById(shipId)
            if (ship == null) {
                // Deliberately silent, and deliberately leaves the ship's binding alone: an unloaded ship has
                // not stopped following, it has stopped existing for now, and [restoreBindings] re-arms it from
                // the binding the moment it comes back. This is the same path a world reload takes.
                followers.remove(shipId)
                KeelAnchor.forget(shipId)
                continue
            }
            if (ship.chunkClaimDimension != dimension) continue
            tickFollower(level, ship, follower)
        }
    }

    private fun tickRecorder(level: ServerLevel, ship: LoadedServerShip, recorder: PathRecorder) {
        val keel = KeelAnchor.world(level, ship, scratchKeel) ?: return
        when (recorder.tick(keel)) {
            PathRecorder.Step.RECORDING -> Unit

            PathRecorder.Step.SNAPPED -> {
                recorders.remove(ship.id)
                finishRecording(level, recorder)
            }

            PathRecorder.Step.OVERFLOW -> {
                recorders.remove(ship.id)
                tell(
                    level, recorder.playerId,
                    "Recording cancelled -- route passed ${EurekaConfig.SERVER.pathMaxPoints} points " +
                        "without closing the loop.",
                    error = true
                )
            }
        }
    }

    private fun finishRecording(level: ServerLevel, recorder: PathRecorder) {
        val cfg = EurekaConfig.SERVER
        val control = PathSmoothing.finish(
            raw = recorder.toArray(),
            seamBlend = cfg.pathSeamBlend,
            smoothWindow = cfg.pathSmoothWindow,
            iterations = cfg.pathSmoothIterations,
            jagThresholdDeg = cfg.pathSmoothJagThreshold,
            maxDeviation = cfg.pathMaxSmoothDeviation,
            rdpEpsilon = RDP_EPSILON
        )

        if (control == null) {
            tell(level, recorder.playerId, "That route was too short or too tangled to save.", error = true)
            return
        }

        val path = PathStore.get(level).create(level.dimensionId, control)
        tell(
            level, recorder.playerId,
            "Route '${path.name}' saved -- ${path.length.toInt()} blocks, ${path.control.size / 3} points.",
            error = false
        )
    }

    private fun tickFollower(level: ServerLevel, ship: LoadedServerShip, follower: PathFollower) {
        val control = ship.getAttachment(EurekaShipControl::class.java)
        if (control == null) {
            // The helm is gone, so there is nothing left to steer with -- and the binding has to go with it, or
            // the reload path would keep trying to re-arm a ship that can no longer follow anything.
            release(ship, stopShip = false)
            return
        }

        if (checkManualTakeover(level, ship, control, follower)) return

        val keel = KeelAnchor.world(level, ship, scratchKeel) ?: return

        if (!follower.tick(keel, control, ship.velocity.length())) {
            release(ship, stopShip = true)
            tell(level, follower.playerId, "Stopped following -- the ship lost its course.", error = true)
            return
        }

        // Mirror the follower's progress onto the saved binding, the same way physTick mirrors the live cruise
        // course onto its flat persisted fields. Three field stores a tick, and it is what lets a reload pick the
        // route up where the ship actually was rather than re-acquiring from scratch.
        PathBinding.get(ship)?.let { binding ->
            binding.arc = follower.arc
            binding.laps = follower.laps
            follower.copyOffset(binding.offset)
        }
    }

    /**
     * Hand the ship back to the pilot when they hold a turn or an elevation input, and report having done so.
     *
     * The same hold gesture SHIFT+C uses, for the same reason: a brief input is how you nudge a following ship
     * past an obstacle, and it should stay a nudge -- the route simply re-acquires afterwards, which is the
     * whole point of pure pursuit. Only a sustained input means "I have the wheel now".
     *
     * Forward/back is deliberately NOT included. Speed was never the route's to own, so driving or stopping
     * while bound to a line is ordinary use, not a takeover.
     *
     * Returns true if the ship was released, in which case the caller must not steer it this tick.
     */
    private fun checkManualTakeover(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        follower: PathFollower
    ): Boolean {
        val hold = EurekaConfig.SERVER.pathManualCancelHold
        if (hold <= 0.0) return false

        // Both are signed seconds accumulated on the game thread and zeroed the moment the input stops, so a
        // stale reading can't build up while nobody is at the helm.
        val turning = abs(control.turnHold)
        val climbing = abs(control.vertHold)
        val held = max(turning, climbing)
        if (held <= 0.0) return false

        if (held >= hold) {
            // Not stopShip: the pilot is taking over, so leave whatever cruise they had running rather than
            // dropping a moving vessel's throttle out from under them.
            release(ship, stopShip = false)
            tell(
                level, follower.playerId,
                "You have the wheel -- '${follower.path.name}' released.",
                PathMessages.Kind.WARN
            )
            return true
        }

        if (held >= hold * PROMPT_AT) {
            val what = if (turning >= climbing) "turning" else "climbing"
            tell(
                level, follower.playerId,
                "Keep $what to stop following '${follower.path.name}'…",
                PathMessages.Kind.PROMPT
            )
        }
        return false
    }

    // endregion

    // region resuming

    /**
     * Rebuild a [PathFollower] for every loaded ship in [level] that has a saved binding but no live follower.
     *
     * Structured exactly like `ArmadaBindings.reconcile`, and for the same reasons: it is idempotent, so it can
     * simply run every tick; it is O(1) per ship in the steady state (one attachment lookup, then the `isBound`
     * test); and being a poll rather than a load hook it does not care what order the ship, the level and the
     * route store came up in -- whatever isn't ready yet is retried next tick.
     *
     * This is the path a WORLD RELOAD takes, and also the path a ship that simply drifted out of simulation and
     * back takes. They are the same event as far as this is concerned.
     */
    private fun restoreBindings(level: ServerLevel) {
        if (!EurekaConfig.SERVER.pathResumeOnLoad) return

        val dimension = level.dimensionId
        val world = level.shipObjectWorld
        // Lazy: a dimension with no bound ships must not create a route store, because asking for one WRITES one.
        val store by lazy { PathStore.get(level) }

        for (ship in world.loadedShips) {
            val binding = PathBinding.get(ship) ?: continue
            if (!binding.isBound) continue
            if (followers.containsKey(ship.id)) continue
            // loadedShips spans every dimension while this runs once per level -- see [tick].
            if (ship.chunkClaimDimension != dimension) continue
            restore(level, ship, binding, store)
        }
    }

    /**
     * Re-arm one ship's saved binding, or drop the binding if it can no longer be honoured.
     *
     * The distinction that matters here is STALE versus NOT READY YET. A route that no longer exists, a helm that
     * was dismantled, a ship that has since joined an armada -- those bindings will never become valid again, so
     * they are cleared rather than retried forever. A ship whose voxels aren't there yet is merely early, so it
     * is left for the next tick.
     */
    private fun restore(level: ServerLevel, ship: LoadedServerShip, binding: PathBinding, store: PathStore) {
        // A ship that has become an armada child no longer steers itself -- the parent drives the whole vessel --
        // so its own binding is stale, not pending. Left in place it would fight the weld every tick.
        if (ArmadaShipControl.get(ship)?.isChild == true) return binding.clear()

        val path = store.byId(binding.routeId) ?: return binding.clear()
        val control = ship.getAttachment(EurekaShipControl::class.java) ?: return binding.clear()

        // NOT READY, not stale -- and this one is the whole ballgame on a reload. A ship turns up in VS's loaded
        // -ship index before its shipyard chunks tick block entities, and the helm's tick is the only thing that
        // ever hands EurekaShipControl its own ship. Arm before that and `pathForward` has no transform to derive
        // a heading from, so the follower reports a lost course on the very tick it resumed -- and the release
        // that follows would clear the binding, destroying exactly what was saved.
        if (!control.pathHullReady) return

        // Likewise: the ship is loaded but its blocks aren't readable yet, so try again next tick rather than
        // freezing an offset measured from a fallback anchor.
        val keel = KeelAnchor.world(level, ship, Vector3d()) ?: return

        if (!control.pathBegin()) {
            binding.clear()
            tell(
                level, binding.ownerId,
                "'${path.name}' was dropped on load -- the ship has no course to steer from.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val offset = Vector3d(binding.offset)

        // Trust the saved progress only while it still describes where the ship IS. It is worth saving: a route
        // that crosses itself has two answers to "where am I on this loop" and only this one is right. But a hull
        // that moved while it was out of simulation would be aimed at a point it has already passed -- possibly
        // most of a lap back -- and a full re-acquire from position is the better answer past that point.
        val saved = binding.arc
        val stillThere = saved >= 0.0 &&
            path.sampleAt(saved, Vector3d()).add(offset).distance(keel) <= EurekaConfig.SERVER.pathEngageRange

        followers[ship.id] = PathFollower(
            ship.id, binding.ownerId, path, offset,
            startArc = if (stillThere) saved else -1.0,
            startLaps = binding.laps
        )

        // Worth saying out loud. The ship is about to start steering itself with nobody aboard who asked it to,
        // and silence there reads as a bug rather than as a feature working.
        tell(
            level, binding.ownerId,
            if (stillThere) "Resumed '${path.name}' where the ship left off."
            else "Resumed '${path.name}' -- the ship had drifted, so it is re-acquiring the line.",
            PathMessages.Kind.GOOD
        )
    }

    /**
     * Take a ship off its route: drop the follower, release the hull's path guidance and forget the saved
     * binding. Returns the follower that was removed, or null if it wasn't following.
     *
     * Every way OFF a route goes through here, because the binding is the thing that would otherwise be left
     * behind -- and a stale binding doesn't fail quietly, it re-arms the route on the next reload.
     */
    private fun release(ship: LoadedServerShip, stopShip: Boolean): PathFollower? {
        val follower = followers.remove(ship.id)
        // Guarded, unlike the binding clear below: `stopShip` drops the ship's cruise too, and a ship that was
        // never following must not lose its throttle just because something asked whether it was.
        if (follower != null) ship.getAttachment(EurekaShipControl::class.java)?.pathRelease(stopShip)
        PathBinding.clear(ship)
        return follower
    }

    /**
     * Drop every scrap of runtime state. Call when the SERVER stops, not when a level unloads.
     *
     * This object outlives a world. In single player, quitting to the title screen stops the integrated server
     * but leaves every singleton in the JVM standing -- so without this, a route being flown when you logged out
     * is still sitting in [followers] when you log back in. That is not a tidiness problem, it defeats the whole
     * resume: [restoreBindings] skips a ship that already has a follower, so the hull never gets handed back to
     * `pathBegin`, comes up with `pathFollowing` false (it is transient), loses its course on the first physics
     * tick and reports having stopped -- clearing the very binding that was saved to prevent exactly that.
     *
     * The stale follower would ALSO be steering by ship id alone, so a ship in the next world that happened to
     * take that id would be flown along a route from the last one.
     */
    fun reset() {
        recorders.clear()
        followers.clear()
        KeelAnchor.clear()
    }

    // endregion

    // region player actions

    /** SHIFT+R. Begin recording the ship the player is aboard. */
    fun startRecording(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to record a route.")

        if (recorders.containsKey(ship.id)) return fail(player, "This ship is already recording a route.")
        if (followers.containsKey(ship.id)) return fail(player, "Stop following a route before recording one.")

        val keel = KeelAnchor.world(level, ship, Vector3d())
            ?: return fail(player, "This ship has no blocks to measure from.")

        recorders[ship.id] = PathRecorder(ship.id, player.uuid, keel)
        ok(
            player,
            "Recording. Fly the route and come back here to close the loop " +
                "(SHIFT+C to discard)."
        )
    }

    /** SHIFT+C. Throw away the in-progress recording. */
    fun cancelRecording(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to cancel its recording.")
        val recorder = recorders.remove(ship.id) ?: return fail(player, "This ship isn't recording a route.")
        ok(player, "Recording discarded (${recorder.length.toInt()} blocks).")
    }

    /** SHIFT+P. Start following the nearest route. */
    fun play(level: ServerLevel, player: ServerPlayer) {
        val cfg = EurekaConfig.SERVER
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to fly a route.")

        if (recorders.containsKey(ship.id)) return fail(player, "This ship is still recording a route.")
        if (followers.containsKey(ship.id)) return fail(player, "This ship is already following a route.")

        val control = ship.getAttachment(EurekaShipControl::class.java)
            ?: return fail(player, "This ship has no helm.")
        val keel = KeelAnchor.world(level, ship, Vector3d())
            ?: return fail(player, "This ship has no blocks to measure from.")

        val store = PathStore.get(level)
        if (store.isEmpty) return fail(player, "No routes recorded in this dimension yet.")

        val (path, distance) = store.nearest(keel, cfg.pathEngageRange) ?: run {
            val nearest = store.nearestAny(keel)
            return fail(
                player,
                if (nearest == null) "No routes recorded in this dimension yet."
                else "Nearest route '${nearest.first.name}' is ${nearest.second.toInt()}m away -- " +
                    "move within ${cfg.pathEngageRange.toInt()}m."
            )
        }

        if (!control.pathBegin()) {
            return fail(player, "This ship needs a helm before it can follow a route.")
        }

        // The gap between the ship and the line right now becomes a fixed displacement applied to the whole
        // route -- see PathFollower. Measured from the keel, the same point the route was recorded from.
        val onPath = path.sampleAt(path.nearestArcLength(keel, -1.0), Vector3d())
        val offset = Vector3d(keel).sub(onPath)

        followers[ship.id] = PathFollower(ship.id, player.uuid, path, offset)
        // Saved on the ship, so a reload (or the ship drifting out of simulation and back) re-arms all of this
        // rather than dropping the route. Arc and laps start unset; the follower mirrors them in from here on.
        PathBinding.getOrCreate(ship).bind(path.id, offset, player.uuid, arc = -1.0, laps = 0)

        if (distance < ON_LINE_TOLERANCE) {
            ok(player, "Following '${path.name}'.")
        } else {
            ok(player, "Following '${path.name}', holding a ${distance.toInt()}m offset from the line.")
        }

        // Binding a stopped ship is perfectly valid -- the route owns steering, never the throttle, so it can
        // sit bound to the line indefinitely and be driven by hand. Say so rather than implying something is
        // missing: the old wording read as an error on a ship that was working exactly as intended.
        if (!control.cruiseHorizontalArmed && ship.velocity.length() < UNDER_WAY_SPEED) {
            tell(
                level, player.uuid,
                "Bound and stopped -- steer with the throttle or set a cruise speed to get under way.",
                PathMessages.Kind.WARN
            )
        }
    }

    /** SHIFT+S. Stop following, and bring the ship to rest. */
    fun stop(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to stop it.")
        val follower = release(ship, stopShip = true) ?: return fail(player, "This ship isn't following a route.")
        ok(player, "Stopped following '${follower.path.name}'.")
    }

    /** Stop a ship following a route, without needing a player. Used by the command and by unbinding. */
    fun stopShip(ship: LoadedServerShip): Boolean = release(ship, stopShip = true) != null

    // endregion

    // region queries (networking, commands)

    fun recorderFor(shipId: Long): PathRecorder? = recorders[shipId]
    fun followerFor(shipId: Long): PathFollower? = followers[shipId]
    fun isBusy(shipId: Long): Boolean = recorders.containsKey(shipId) || followers.containsKey(shipId)

    /** Recorders whose ship currently lives in [level]. */
    fun recordersIn(level: ServerLevel): List<PathRecorder> {
        if (recorders.isEmpty()) return emptyList()
        val dimension = level.dimensionId
        val world = level.shipObjectWorld
        return recorders.values.filter { world.loadedShips.getById(it.shipId)?.chunkClaimDimension == dimension }
    }

    /** Followers whose ship currently lives in [level]. */
    fun followersIn(level: ServerLevel): List<PathFollower> {
        if (followers.isEmpty()) return emptyList()
        val dimension = level.dimensionId
        val world = level.shipObjectWorld
        return followers.values.filter { world.loadedShips.getById(it.shipId)?.chunkClaimDimension == dimension }
    }

    // endregion

    // region helpers

    /**
     * The ship a player's path hotkeys act on: the one they are standing on, or seated in.
     *
     * A child of an armada resolves to its PARENT. The armada is one vessel -- only the parent is steered, the
     * children are welded to it -- so a route bound to a child could never be flown.
     */
    fun resolveShip(level: ServerLevel, player: Entity): LoadedServerShip? {
        val world = level.shipObjectWorld

        // Seated at a helm, or standing on the deck. EntityDragger already stamps lastShipStoodOn every tick
        // for the carry logic, so the standing case costs nothing to answer.
        val direct = (getShipMountedTo(player) as? LoadedServerShip)
            ?: (player as? IEntityDraggingInformationProvider)
                ?.draggingInformation?.lastShipStoodOn
                ?.let { world.loadedShips.getById(it) }
            ?: return null

        val parentId = ArmadaShipControl.get(direct)?.parentShipId ?: return direct
        return world.loadedShips.getById(parentId) ?: direct
    }

    private fun ok(player: ServerPlayer, message: String) =
        PathMessages.send(player, message, PathMessages.Kind.GOOD)

    private fun fail(player: ServerPlayer, message: String) =
        PathMessages.send(player, message, PathMessages.Kind.ERROR)

    private fun tell(level: ServerLevel, playerId: UUID?, message: String, error: Boolean) =
        tell(level, playerId, message, if (error) PathMessages.Kind.ERROR else PathMessages.Kind.GOOD)

    /** No-op when [playerId] is null (a follower resumed with no owner recorded) or that player is offline. */
    private fun tell(level: ServerLevel, playerId: UUID?, message: String, kind: PathMessages.Kind) {
        val player = playerId?.let { level.server.playerList.getPlayer(it) } ?: return
        PathMessages.send(player, message, kind)
    }

    /** How far off the line still counts as "on it" for the engage message. */
    private const val ON_LINE_TOLERANCE = 3.0

    /** Fraction of the cancel hold at which the "keep holding" prompt appears. */
    private const val PROMPT_AT = 0.25

    /** Below this (m/s) a ship counts as stopped for the "you'll need some throttle" hint. */
    private const val UNDER_WAY_SPEED = 0.5

    /**
     * Decimation tolerance in blocks. Storage-only: [ShipPath] rebuilds a spline through the kept points, so
     * this trades save size against reconstruction fidelity, not against steering accuracy.
     */
    private const val RDP_EPSILON = 0.25

    // endregion
}
