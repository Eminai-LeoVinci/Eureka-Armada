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
 * State here is deliberately RUNTIME ONLY. Finished routes persist ([PathStore]); the act of recording or
 * following does not. A ship that was mid-route when the world was saved comes back stopped, which beats the
 * alternative of a freighter waking up under power in a chunk nobody is standing in.
 */
object ShipPaths {

    private val recorders = ConcurrentHashMap<Long, PathRecorder>()
    private val followers = ConcurrentHashMap<Long, PathFollower>()

    private val scratchKeel = Vector3d()

    // region tick

    /**
     * Advance every recording and every follower whose ship lives in [level].
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
            followers.remove(ship.id)
            return
        }

        if (checkManualTakeover(level, ship, control, follower)) return

        val keel = KeelAnchor.world(level, ship, scratchKeel) ?: return

        if (!follower.tick(keel, control, ship.velocity.length())) {
            followers.remove(ship.id)
            control.pathRelease(stopShip = true)
            tell(level, follower.playerId, "Stopped following -- the ship lost its course.", error = true)
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
            followers.remove(ship.id)
            // Not stopShip: the pilot is taking over, so leave whatever cruise they had running rather than
            // dropping a moving vessel's throttle out from under them.
            control.pathRelease(stopShip = false)
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
        val follower = followers.remove(ship.id) ?: return fail(player, "This ship isn't following a route.")
        ship.getAttachment(EurekaShipControl::class.java)?.pathRelease(stopShip = true)
        ok(player, "Stopped following '${follower.path.name}'.")
    }

    /** Stop a ship following a route, without needing a player. Used by the command and by unbinding. */
    fun stopShip(ship: LoadedServerShip): Boolean {
        val removed = followers.remove(ship.id) != null
        if (removed) ship.getAttachment(EurekaShipControl::class.java)?.pathRelease(stopShip = true)
        return removed
    }

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

    private fun tell(level: ServerLevel, playerId: UUID, message: String, error: Boolean) =
        tell(level, playerId, message, if (error) PathMessages.Kind.ERROR else PathMessages.Kind.GOOD)

    private fun tell(level: ServerLevel, playerId: UUID, message: String, kind: PathMessages.Kind) {
        val player = level.server.playerList.getPlayer(playerId) ?: return
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
