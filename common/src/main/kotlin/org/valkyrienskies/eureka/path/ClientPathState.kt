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

    /** A saved route the server has told us about, already expanded into a followable/drawable curve. */
    class Route(val id: Long, val name: String, val path: ShipPath)

    /** A recording in progress: the trail so far, plus the snap markers once the loop can close. */
    class Recording(val shipId: Long) {
        val points = ArrayList<Double>()
        var armed = false
        val start = Vector3d()
        val keel = Vector3d()
        var gap = 0.0
    }

    /** A ship flying a route, and the fixed displacement it is holding from the line. */
    class Following(val shipId: Long, val pathId: Long, val offset: Vector3d)

    val routes = ConcurrentHashMap<Long, Route>()
    val recordings = ConcurrentHashMap<Long, Recording>()
    val following = ConcurrentHashMap<Long, Following>()

    /** Client-side render toggle, driven by SHIFT+O. Routes being recorded or flown draw regardless. */
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
     * Routes the player has explicitly hidden with SHIFT+O while riding them.
     *
     * Beats BOTH reasons a route would otherwise draw -- [showAll] and being actively flown. That is the whole
     * point: a ship on autopilot is exactly when you want the view out the window rather than a line down the
     * middle of it, and "being flown" was previously an unconditional reason to draw.
     */
    val hiddenRoutes: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Returns true if the route is now hidden. */
    fun toggleHidden(routeId: Long): Boolean =
        if (!hiddenRoutes.add(routeId)) { hiddenRoutes.remove(routeId); false } else true

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
