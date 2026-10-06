package org.valkyrienskies.eureka.ship

import java.util.concurrent.ConcurrentHashMap

/**
 * A short grace for a ship that has just been built, so nobody near it is frozen while it streams in.
 *
 * VS2 stops anything touching a ship that is not fully loaded for it: the server refuses a player's movement and
 * snaps them back ("moved while colliding with unloaded ships" in the log), and the client cancels its own. Fair
 * for a ship that really is still loading, but a ship that was only just ASSEMBLED is unknown to every player for
 * its first moments, so a player gliding past a bottle as it opened was stopped dead in mid-air. For [GRACE_TICKS]
 * after a ship is created, MixinUnloadedShipSpawnGrace leaves it out of that check.
 *
 * Marked by MixinShipAssemblerSpawnGrace the moment VS2's assembly creates the ship, which covers every Armada
 * assembly (helm, bottle, shipwright, templates, pirates). Deadlines are in game ticks, which every dimension shares.
 * Our VS2 build did this inside VS2; it lives in Armada now so Armada runs on the official release.
 */
object ShipSpawnGrace {

    private const val GRACE_TICKS = 100L // 5 s

    private val deadlines = ConcurrentHashMap<Long, Long>()

    @JvmStatic
    fun mark(shipId: Long, now: Long) {
        deadlines[shipId] = now + GRACE_TICKS
    }

    /** Is any ship in its grace? Asked on every entity move, so the usual answer (none) is a single isEmpty. */
    @JvmStatic
    fun anyActive(now: Long): Boolean {
        if (deadlines.isEmpty()) return false
        deadlines.values.removeIf { now > it }
        return deadlines.isNotEmpty()
    }

    @JvmStatic
    fun isGraced(shipId: Long, now: Long): Boolean {
        val deadline = deadlines[shipId] ?: return false
        return now <= deadline
    }

    @JvmStatic
    fun clear() = deadlines.clear()
}
