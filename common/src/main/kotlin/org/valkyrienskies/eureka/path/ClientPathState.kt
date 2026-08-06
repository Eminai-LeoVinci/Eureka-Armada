package org.valkyrienskies.eureka.path

import org.joml.Vector3d
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the client knows about ship paths, for rendering.
 *
 * Mirrors [org.valkyrienskies.eureka.armada.ArmadaClientBonds]: the server owns the truth, pushes snapshots,
 * and the client replaces what it holds. Nothing here is authoritative and nothing is persisted.
 *
 * Written from the netty thread via the client executor and read on the render thread, hence the concurrent
 * maps -- the renderer must never see a half-updated route.
 */
object ClientPathState {

    /**
     * A saved route the server has told us about, already expanded into a followable/drawable curve.
     *
     * [dwellArcs] holds where a replayed ship will stop, in arc length. Sent as bare arcs rather than as the
     * whole [MotionTrack] because drawing a marker is all the client does with any of it -- when the ship
     * reaches each one is not something a line on the screen can show.
     */
    class Route(
        val id: Long,
        val name: String,
        val path: ShipPath,
        val dwellArcs: DoubleArray = DoubleArray(0)
    )

    /** A recording in progress: the trail so far, plus the snap markers once the loop can close. */
    class Recording(val shipId: Long) {
        val points = ArrayList<Double>()
        var armed = false
        val start = Vector3d()
        val keel = Vector3d()
        var gap = 0.0

        /**
         * How much bigger this ship's snap spheres are than a raft's. Server-sent, because it is derived from
         * the hull's voxel box and the client has no way to measure a shipyard AABB for itself.
         */
        var markerScale = 1.0
    }

    /** A ship flying a route, and the fixed displacement it is holding from the line. */
    class Following(val shipId: Long, val pathId: Long, val offset: Vector3d)

    val routes = ConcurrentHashMap<Long, Route>()
    val recordings = ConcurrentHashMap<Long, Recording>()
    val following = ConcurrentHashMap<Long, Following>()

    /** Client-side render toggle, driven by SHIFT+H. Routes being recorded or flown draw regardless. */
    @Volatile
    var showAll = false

    /**
     * The route the ship this player is standing on is currently flying, or 0 for none.
     *
     * Server-resolved (it is the only side that can turn a player into a ship, and a child of an armada into
     * the parent that actually holds the follower) and refreshed with every live snapshot.
     */
    @Volatile
    var localRouteId = 0L

    /**
     * Routes the player has hidden with SHIFT+H, one at a time or all at once.
     *
     * Beats BOTH reasons a route would otherwise draw -- [showAll] and being actively flown. That is the whole
     * point: a ship on autopilot is exactly when you want the view out the window rather than a line down the
     * middle of it, and "being flown" is otherwise an unconditional reason to draw.
     */
    val hiddenRoutes: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Whether some ship is flying this route right now, which is a reason of its own to draw it. */
    fun isFlown(routeId: Long): Boolean = following.values.any { it.pathId == routeId }

    /**
     * Whether this route is on screen. The single answer both the renderer and the hotkey work from.
     *
     * It lived as two conditions inside the renderer, which is how the hide key came to have no power over a
     * route being flown: the key reasoned about [showAll] while the renderer had a second, unrelated reason to
     * draw. Anything that wants to know what is visible now asks here.
     *
     * [flown] is a parameter because the renderer has already worked it out for the frame.
     */
    fun isVisible(routeId: Long, flown: Boolean = isFlown(routeId)): Boolean =
        routeId !in hiddenRoutes && (showAll || flown)

    /** Whether anything at all is drawn. What "hide" and "show" are decided against. */
    fun anyVisible(): Boolean = routes.keys.any { isVisible(it) }

    /**
     * Clear the screen: every route the client knows of goes into [hiddenRoutes].
     *
     * Done by writing the ids rather than by adding a second global flag, so that ONE mechanism owns "not
     * drawn" -- and so the per-ship toggle still means something with everything hidden. Board a ship, press
     * the key, and its line alone comes back.
     *
     * [routes] holds everything the renderer can draw, so this is complete. A route recorded after the fact can
     * appear; the next press sweeps it in, which is as much as that case is worth.
     */
    fun hideEverything() {
        showAll = false
        hiddenRoutes.addAll(routes.keys)
    }

    /** Show the lot -- both reasons to draw, and nothing held back. */
    fun showEverything() {
        showAll = true
        hiddenRoutes.clear()
    }

    /**
     * Flip one route between drawn and not. Returns true if it is now shown.
     *
     * Un-hiding is enough to bring a route back because the only caller passes the route its own ship is
     * FLYING, which draws on that basis alone.
     */
    fun toggleVisible(routeId: Long): Boolean {
        if (isVisible(routeId)) {
            hiddenRoutes.add(routeId)
            return false
        }
        hiddenRoutes.remove(routeId)
        return true
    }

    fun replaceRoutes(incoming: Map<Long, Route>) {
        routes.keys.retainAll(incoming.keys)
        routes.putAll(incoming)
    }

    /** Append newly-recorded samples for a ship, starting at index [from] in its point list. */
    fun appendRecording(shipId: Long, from: Int, values: DoubleArray): Recording {
        val rec = recordings.getOrPut(shipId) { Recording(shipId) }
        // A `from` we can't reach means we missed an update; drop what we have rather than draw a line with a
        // hole jumped across it. The next full resync fills it back in.
        if (from > rec.points.size / 3) rec.points.clear()
        if (from == 0) rec.points.clear()
        for (v in values) rec.points.add(v)
        return rec
    }

    fun endRecording(shipId: Long) {
        recordings.remove(shipId)
    }

    /** Wipe everything -- on disconnect, or a dimension change. */
    fun clear() {
        routes.clear()
        recordings.clear()
        following.clear()
        hiddenRoutes.clear()
        localRouteId = 0L
    }
}
