package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

/**
 * The crew the server has asked this client to mark, by entity id.
 *
 * Ids rather than positions, so the marks track the crew as they walk the deck without another packet. The
 * renderer resolves each one against the client level every frame and skips anything that has gone -- died,
 * unloaded, or walked out of tracking range -- which is also what keeps this from needing any invalidation of
 * its own.
 *
 * Cleared by an empty list from the server (the toggle going off) and on leaving a world.
 */
@Environment(EnvType.CLIENT)
object ClientCrewMarkers {

    private val EMPTY = IntArray(0)

    @Volatile
    private var ids: IntArray = EMPTY

    val isEmpty: Boolean get() = ids.isEmpty()

    fun replace(newIds: IntArray) {
        ids = newIds
    }

    fun clear() {
        ids = EMPTY
    }

    /** Read by the renderer once per frame. */
    fun current(): IntArray = ids

    /**
     * Whether this entity is currently wearing one of our plates.
     *
     * Asked per entity per frame by the render-state mixin that suppresses vanilla's own name tag, so the empty
     * case has to be free: it is one field read and an `isEmpty` test, and the scan below only ever runs over a
     * list bounded by the berth cap while plates are actually up.
     */
    fun contains(entityId: Int): Boolean {
        val current = ids
        for (id in current) if (id == entityId) return true
        return false
    }
}
