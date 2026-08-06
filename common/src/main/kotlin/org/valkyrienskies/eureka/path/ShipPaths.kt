package org.valkyrienskies.eureka.path

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.follow.ShipFollows
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipFootprint
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    /** Consecutive server ticks each replaying ship has been too far off its line to be merely lagging. */
    private val blockedTicks = ConcurrentHashMap<Long, Int>()

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
        when (recorder.tick(keel, ship.velocity.length())) {
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

        // The timeline is measured against the FINISHED geometry, which does not exist until the control
        // points have been through ShipPath's spline -- and a raw sample index means nothing after smoothing,
        // decimation and the seam blend have all moved things about (see MotionTrack.build). So the route is
        // built twice: once bare, purely as the ruler to measure arc lengths against, and once for real. That
        // costs one extra spline expansion on an event that happens once per recording, and it is much the
        // lesser evil next to giving an otherwise immutable class a setter to be filled in afterwards.
        var timeSamples = ShipPath.EMPTY
        var dwellSamples = ShipPath.EMPTY
        if (cfg.pathRecordTiming) {
            val bare = runCatching { ShipPath(0L, "", level.dimensionId, control) }.getOrNull()
            val track = bare?.let {
                MotionTrack.build(
                    it, recorder.toArray(), recorder.timeArray(), recorder.dwellArray(), cfg.pathTimeEpsilon
                )
            }
            if (track != null) {
                timeSamples = track.times
                dwellSamples = track.dwells
            }
        }

        val path = PathStore.get(level).create(level.dimensionId, control, timeSamples, dwellSamples)
        // Reporting the timeline is not decoration: it is the only confirmation the pilot gets that the
        // recording captured more than a line, short of flying the whole loop back to find out.
        val recorded = path.motion?.let { track ->
            val stops =
                if (track.dwellCount == 0) ""
                else ", ${track.dwellCount} stop(s) totalling ${formatDuration(track.lapSeconds - track.movingSeconds)}"
            " -- ${formatDuration(track.movingSeconds)} flying$stops"
        } ?: " -- no timing recorded, so SHIFT+P only"
        tell(
            level, recorder.playerId,
            "Route '${path.name}' saved: ${path.length.toInt()} blocks, " +
                "${path.control.size / 3} points$recorded.",
            error = false
        )
    }

    /** "1m 20s", or "45s" under a minute. */
    private fun formatDuration(seconds: Double): String {
        val total = seconds.roundToInt().coerceAtLeast(0)
        return if (total < 60) "${total}s" else "${total / 60}m ${total % 60}s"
    }

    private fun tickFollower(level: ServerLevel, ship: LoadedServerShip, follower: PathFollower) {
        // Paused: still bound, still holding its place in the route, simply not being steered. Deliberately
        // ahead of the helm check below -- a paused ship whose helm is being rebuilt has no course to lose, so
        // there is nothing here worth tearing the binding down over.
        if (follower.paused) return

        val control = ship.getAttachment(EurekaShipControl::class.java)
        if (control == null) {
            // The helm is gone, so there is nothing left to steer with -- and the binding has to go with it, or
            // the reload path would keep trying to re-arm a ship that can no longer follow anything.
            release(ship, stopShip = false)
            return
        }

        if (checkManualTakeover(level, ship, control, follower)) return

        val keelLocal = KeelAnchor.local(level, ship) ?: return
        val keel = KeelAnchor.world(level, ship, scratchKeel) ?: return

        if (!follower.tick(keel, keelLocal, control, ship.velocity.length())) {
            release(ship, stopShip = true)
            tell(level, follower.playerId, "Stopped following -- the ship lost its course.", error = true)
            return
        }

        if (checkBlocked(level, ship, control, follower)) return

        // Mirror the follower's progress onto the saved binding, the same way physTick mirrors the live cruise
        // course onto its flat persisted fields. A handful of field stores a tick, and it is what lets a reload
        // pick the route up where the ship actually was rather than re-acquiring from scratch -- including,
        // for a replayed route, part-way through a wait at a dock.
        PathBinding.get(ship)?.let { binding ->
            binding.arc = follower.arc
            binding.laps = follower.laps
            binding.clock = follower.clock
            binding.nextDwell = follower.nextDwell
            binding.dwellIndex = follower.dwellIndex
            binding.dwellRemaining = follower.dwellRemaining
            follower.copyOffset(binding.offset)
        }

        // Worth saying out loud both ways round: a ship that stops dead in the middle of a route looks broken
        // unless something explains it, and one that sets off again after minutes of stillness is equally
        // startling if nothing announced it.
        if (follower.dwellJustStarted) {
            tell(
                level, follower.playerId,
                "Holding on '${follower.path.name}' for ${follower.dwellSecondsLeft()}s.",
                PathMessages.Kind.WARN
            )
        } else if (follower.dwellJustEnded) {
            tell(level, follower.playerId, "Under way again on '${follower.path.name}'.", PathMessages.Kind.GOOD)
        }
    }

    /**
     * Give up on a replay that cannot reach its line, and say so.
     *
     * A replay servos the hull onto the route rather than steering it there, so an obstruction that was not
     * present when the route was recorded -- terrain, a build, another vessel -- is a ship shoving at something
     * immovable with nobody told. The test is deliberately BOTH far off and for a while: a replay is routinely
     * several blocks out for a moment while it eases onto the line or rides out a collision, and neither is a
     * reason to abandon a route.
     *
     * ## Distance alone is not evidence
     * The first version of this test used distance alone, and it threw ships off perfectly good routes. It had
     * to: a servo chasing a station that is itself moving carries a standing lag, and that lag grows with
     * speed -- eight blocks is a third of a second at a brisk cruise, so a route with a fast leg trips a
     * distance threshold simply by being flown quickly. Being behind is not being blocked.
     *
     * What actually distinguishes them is whether the hull is GOING anywhere. A ship lagging on a fast leg is
     * making nearly the speed it was asked for and closing; a blocked one is being asked for speed and making
     * none of it. So the distance is kept only as a precondition and [EurekaShipControl.pathServoStalled] is
     * the evidence -- with a much larger distance standing on its own, for the case where a hull is being
     * carried somewhere else entirely (a current, a bigger vessel) and is moving briskly in the wrong
     * direction.
     *
     * Only replay can be blocked in this sense. A geometry-mode ship is being steered by the pilot's own
     * throttle and stopping against a wall is theirs to notice.
     *
     * Returns true if the ship was released.
     */
    private fun checkBlocked(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        follower: PathFollower
    ): Boolean {
        if (!follower.mode.isReplay) {
            blockedTicks.remove(ship.id)
            return false
        }

        val cfg = EurekaConfig.SERVER
        val error = control.pathServoError
        val stuck = error > cfg.pathReplayMaxError &&
            (control.pathServoStalled || error > cfg.pathReplayMaxError * BLOCKED_FAR_MULTIPLE)
        if (!stuck) {
            blockedTicks.remove(ship.id)
            return false
        }

        val ticks = (blockedTicks[ship.id] ?: 0) + 1
        if (ticks * TICK_SECONDS < cfg.pathReplayBlockedSeconds) {
            blockedTicks[ship.id] = ticks
            return false
        }

        blockedTicks.remove(ship.id)
        // Left where it stopped rather than walked home: the ship is up against something, and the useful
        // thing is for it to still be there when its owner comes to look.
        release(ship, stopShip = true)
        tell(
            level, follower.playerId,
            // The distance is in the message on purpose: "blocked" is a judgement made from two numbers, and
            // when it is wrong the only useful thing anyone can tell you afterwards is how far out it was.
            "'${follower.path.name}' is blocked ${error.roundToInt()}m off its line -- stopped there.",
            error = true
        )
        return true
    }

    /**
     * Hand the ship back to the pilot when they hold a turn or an elevation input, and report having done so.
     *
     * The same hold gesture the hotkeys use, for the same reason: a brief input is how you nudge a following ship
     * past an obstacle, and it should stay a nudge -- the route simply re-acquires afterwards, which is the
     * whole point of pure pursuit. Only a sustained input means "I have the wheel now".
     *
     * Forward/back counts only in [PathMode.REPLAY]. On a plain route speed was never the route's to own, so
     * driving or stopping while bound to a line is ordinary use rather than a takeover -- but a replay DOES
     * own the throttle, and there the same input is a pilot taking it back.
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

        // All signed seconds accumulated on the game thread and zeroed the moment the input stops, so a stale
        // reading can't build up while nobody is at the helm.
        val turning = abs(control.turnHold)
        val climbing = abs(control.vertHold)
        val driving = if (follower.mode.isReplay) abs(control.fwdHold) else 0.0
        val held = max(max(turning, climbing), driving)
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
            val what = when (held) {
                driving -> "driving"
                turning -> "turning"
                else -> "climbing"
            }
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

        // Paused when it was saved, so it comes back paused: rebuild the bookkeeping and leave the hull alone.
        // No pathBegin, because that is what tells the physics something is about to steer -- and nothing is.
        // Nothing is announced either; a ship that was deliberately held is not news when the world comes back.
        if (binding.paused) {
            followers[ship.id] = PathFollower(
                ship.id, binding.ownerId, path, Vector3d(binding.offset),
                startArc = binding.arc,
                startLaps = binding.laps,
                startMode = resumeMode(binding, path),
                startClock = binding.clock,
                startDwell = binding.dwellIndex,
                startDwellRemaining = binding.dwellRemaining,
                startNextDwell = binding.nextDwell
            ).also { it.paused = true }
            return
        }

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

        val mode = resumeMode(binding, path)

        followers[ship.id] = PathFollower(
            ship.id, binding.ownerId, path, offset,
            startArc = if (stillThere) saved else -1.0,
            startLaps = binding.laps,
            startMode = mode,
            // The replay clock likewise only survives alongside the position that gave it meaning -- handed
            // -1 it is re-derived from wherever the ship actually turned up, which is what re-acquiring means.
            startClock = if (stillThere) binding.clock else -1.0,
            // A pause only survives alongside the position that gave it meaning. Re-acquiring means the hull
            // moved while it was out of simulation, and finishing a dock wait somewhere else is worse than
            // simply not finishing it.
            startDwell = if (stillThere) binding.dwellIndex else -1,
            startDwellRemaining = if (stillThere) binding.dwellRemaining else 0.0,
            startNextDwell = if (stillThere) binding.nextDwell else -1
        )

        // Worth saying out loud. The ship is about to start steering itself with nobody aboard who asked it to,
        // and silence there reads as a bug rather than as a feature working. The mode is named because the two
        // look very different from the deck: one waits for a throttle, the other sets off on its own.
        tell(
            level, binding.ownerId,
            if (stillThere) "Resumed '${path.name}' (${mode.label}) where the ship left off."
            else "Resumed '${path.name}' (${mode.label}) -- the ship had drifted, so it is re-acquiring the line.",
            PathMessages.Kind.GOOD
        )
    }

    /**
     * The mode a re-armed binding comes back in.
     *
     * Falls back to [PathMode.GEOMETRY] when the saved mode was replay but the route has no timeline. That
     * should not be reachable -- a route cannot lose its track -- but the alternative if it ever were is a
     * follower asking a null for its clock every tick, and steering the line is a perfectly good answer.
     */
    private fun resumeMode(binding: PathBinding, path: ShipPath): PathMode =
        if (binding.pathMode.isReplay && path.motion == null) PathMode.GEOMETRY else binding.pathMode

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
        blockedTicks.remove(ship.id)
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
        blockedTicks.clear()
        KeelAnchor.clear()
    }

    // endregion

    // region player actions

    /** SHIFT+R, tapped. Begin recording the ship the player is aboard. */
    fun startRecording(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to record a route.")

        if (recorders.containsKey(ship.id)) return fail(player, "This ship is already recording a route.")
        // A paused ship counts: it still owns the wheel and still holds a binding.
        if (followers.containsKey(ship.id)) {
            return fail(player, "This ship is on a route -- hold SHIFT+P to release it first.")
        }
        if (ShipFollows.isFollowing(ship.id)) {
            return fail(player, "This ship is following another -- break that off first.")
        }

        val keel = KeelAnchor.world(level, ship, Vector3d())
            ?: return fail(player, "This ship has no blocks to measure from.")

        recorders[ship.id] = PathRecorder(
            ship.id, player.uuid, keel,
            markerScale = PathRecorder.markerScaleFor(ShipFootprint.of(ship))
        )
        ok(
            player,
            "Recording. Fly the route and come back here to close the loop " +
                "(hold SHIFT+R to discard)."
        )
    }

    /** SHIFT+R held for two seconds. Throw away the in-progress recording. */
    fun cancelRecording(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to cancel its recording.")
        val recorder = recorders.remove(ship.id) ?: return fail(player, "This ship isn't recording a route.")
        ok(player, "Recording discarded (${recorder.length.toInt()} blocks).")
    }

    /**
     * SHIFT+P or CTRL+SHIFT+P, tapped. One key for start, pause, resume and switching between the two modes.
     *
     * Which of those is meant is decided HERE rather than on the client, because the client knows only which
     * route a ship is bound to -- not whether it is paused, and not which mode it is in. A toggle that guessed
     * wrong would put a ship under way when the player meant to hold it. Releasing the route outright is the
     * 2-second hold on the same key, which arrives as [stop].
     *
     * [requested] is what the modifier picked. On a ship that is already bound it means "be in this mode",
     * which is a different question from pause/resume -- so a press that names the mode the ship is NOT in
     * switches it, and a press that names the mode it IS in is the pause toggle.
     */
    fun playOrPause(level: ServerLevel, player: ServerPlayer, requested: PathMode) {
        val cfg = EurekaConfig.SERVER
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to fly a route.")

        if (recorders.containsKey(ship.id)) return fail(player, "This ship is still recording a route.")

        followers[ship.id]?.let { follower ->
            return if (follower.mode == requested) togglePause(level, ship, player, follower)
            else switchMode(ship, player, follower, requested)
        }

        // A route and a pursuit both own the wheel, so a hull can only be under one of them. Refused rather than
        // silently taking over, because which one you meant is not something this can guess.
        if (ShipFollows.isFollowing(ship.id)) {
            return fail(player, "This ship is following another -- break that off first.")
        }

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

        // Refused before pathBegin, like every other way this can fail: the flag that tells the physics
        // something is about to steer must never be set with no follower behind it.
        if (requested.isReplay && path.motion == null) {
            return fail(
                player,
                "'${path.name}' was recorded without timing -- record it again to replay it, " +
                    "or SHIFT+P to fly the line."
            )
        }

        if (!control.pathBegin()) {
            return fail(player, "This ship needs a helm before it can follow a route.")
        }

        // The gap between the ship and the line right now. In GEOMETRY mode it becomes a fixed displacement
        // applied to the whole route; in REPLAY it is eased away to nothing over the first few seconds, since
        // a replay's whole point is to fly the line the recording actually flew. Measured from the keel, the
        // same point the route was recorded from.
        val startArc = path.nearestArcLength(keel, -1.0)
        val onPath = path.sampleAt(startArc, Vector3d())
        val offset = Vector3d(keel).sub(onPath)

        followers[ship.id] =
            PathFollower(ship.id, player.uuid, path, offset, startArc = startArc, startMode = requested)
        // Saved on the ship, so a reload (or the ship drifting out of simulation and back) re-arms all of this
        // rather than dropping the route. Laps start at zero; the follower mirrors everything in from here on.
        PathBinding.getOrCreate(ship)
            .bind(path.id, offset, player.uuid, arc = startArc, laps = 0, mode = requested)

        val approach = when {
            distance < ON_LINE_TOLERANCE -> ""
            requested.isReplay -> ", easing ${distance.toInt()}m onto the line"
            else -> ", holding a ${distance.toInt()}m offset from the line"
        }
        ok(player, "Following '${path.name}' (${requested.label})$approach.")

        // Binding a stopped ship is perfectly valid in GEOMETRY mode -- the route owns steering, never the
        // throttle, so it can sit bound to the line indefinitely and be driven by hand. Say so rather than
        // implying something is missing: the old wording read as an error on a ship working exactly as
        // intended. A replay needs no such hint, since it is about to drive itself.
        if (!requested.isReplay &&
            !control.cruiseHorizontalArmed &&
            ship.velocity.length() < UNDER_WAY_SPEED
        ) {
            tell(
                level, player.uuid,
                "Bound and stopped -- steer with the throttle, set a cruise speed, or CTRL+SHIFT+P to " +
                    "replay the recording.",
                PathMessages.Kind.WARN
            )
        }
    }

    /**
     * Change which way a bound ship flies its route, without letting go of it.
     *
     * Deliberately does NOT touch the paused state. A player pressing this has said something about the mode
     * and nothing about whether the ship should be moving, and reading it as "and set off" would mean a key
     * that can put a deliberately held vessel under way -- next to a dock, which is where ships get held.
     *
     * Arc, laps and route all survive, so switching mid-flight is seamless: the ship carries on from exactly
     * where it is, with a different thing driving it.
     */
    private fun switchMode(
        ship: LoadedServerShip,
        player: ServerPlayer,
        follower: PathFollower,
        requested: PathMode
    ) {
        if (requested.isReplay && follower.path.motion == null) {
            return fail(
                player,
                "'${follower.path.name}' was recorded without timing -- record it again to replay it."
            )
        }

        val wasHolding = follower.dwelling
        // Puts the clock where the ship has got to, re-arms the ease onto the line, and abandons any pause in
        // progress -- a pause is counted down only by replay, so one left standing in geometry mode would
        // never end. See PathFollower.switchTo.
        follower.switchTo(requested)
        PathBinding.get(ship)?.mode = requested.ordinal

        val note = when {
            follower.paused -> " -- still paused, SHIFT+P to carry on"
            wasHolding && !requested.isReplay -> " -- the ship is yours to drive from here"
            else -> ""
        }
        ok(player, "'${follower.path.name}' switched to ${requested.label}$note.")
    }

    /**
     * Hold the ship on its route, or set it going again. The middle state of [playOrPause].
     *
     * Pausing hands the hull back exactly as stopping does -- steering released, cruise dropped, the ship walked
     * down to rest -- because a route never owned the throttle. Releasing the wheel alone would leave a cruising
     * ship carrying straight on off the line, which is the one thing "paused" must not look like. What survives
     * is the binding: the arc, the lap count and the offset, so resuming picks the line up where it was rather
     * than re-acquiring the nearest point.
     */
    private fun togglePause(
        level: ServerLevel,
        ship: LoadedServerShip,
        player: ServerPlayer,
        follower: PathFollower
    ) {
        val control = ship.getAttachment(EurekaShipControl::class.java)
            ?: return fail(player, "This ship has no helm.")

        if (!follower.paused) {
            follower.paused = true
            PathBinding.get(ship)?.paused = true
            control.pathRelease(stopShip = true)
            ok(player, "Paused on '${follower.path.name}' -- press again to carry on.")
            return
        }

        // Same refusal play() makes, and it has to be repeated: a ship can be told to chase another while it
        // sits paused on a route, and letting it resume would put two controllers on one wheel.
        if (ShipFollows.isFollowing(ship.id)) {
            return fail(player, "This ship is following another -- break that off first.")
        }
        if (!control.pathBegin()) {
            return fail(player, "This ship needs a helm before it can follow a route.")
        }

        follower.paused = false
        PathBinding.get(ship)?.paused = false
        ok(player, "Following '${follower.path.name}' again.")
    }

    /** SHIFT+P held for two seconds. Stop following, forget the binding, and bring the ship to rest. */
    fun stop(level: ServerLevel, player: ServerPlayer) {
        val ship = resolveShip(level, player) ?: return fail(player, "Stand on a ship to stop it.")
        val follower = release(ship, stopShip = true) ?: return fail(player, "This ship isn't following a route.")
        ok(player, "Released from '${follower.path.name}'.")
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

    /** Server tick length, for turning the blocked-route tick count into seconds. */
    private const val TICK_SECONDS = 0.05

    /**
     * How many times `pathReplayMaxError` counts as blocked on distance ALONE, with no stall to corroborate it.
     *
     * The escape hatch for a hull that is moving perfectly well and simply not where the route is -- carried
     * off by something bigger, or shoved through a portal's worth of terrain. Well clear of any lag a servo
     * chasing a moving station can accumulate on its own.
     */
    private const val BLOCKED_FAR_MULTIPLE = 5.0

    /**
     * Decimation tolerance in blocks. Storage-only: [ShipPath] rebuilds a spline through the kept points, so
     * this trades save size against reconstruction fidelity, not against steering accuracy.
     */
    private const val RDP_EPSILON = 0.25

    // endregion
}
