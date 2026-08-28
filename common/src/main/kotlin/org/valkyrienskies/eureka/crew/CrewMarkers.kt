package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Who is currently being shown crew markers, and the one call that pushes a set to a client.
 *
 * The toggle lives on the SERVER because only the server can answer the question the markers ask -- which
 * villagers on this particular deck are on this particular captain's articles. The client is told a list of
 * entity ids and draws over whoever they resolve to, so the marks follow the crew as they walk without any
 * further traffic.
 *
 * [sender] is the same volatile-hook pattern `PathMessages.sender` uses, and for the same reason: :common has
 * fabric-loader on its classpath but not the networking API, so the loader layer installs the send.
 */
object CrewMarkers {

    /** Installed by the fabric layer at init. Sending an empty list is how a client is told to put them away. */
    @Volatile
    @JvmField
    var sender: (ServerPlayer, IntArray) -> Unit = { _, _ -> }

    private val showing = HashSet<UUID>()

    fun show(player: ServerPlayer, entityIds: List<Int>) {
        showing.add(player.uuid)
        sender(player, entityIds.toIntArray())
    }

    /** Puts the markers away if they were up, and reports whether they were. */
    fun clearIfShowing(player: ServerPlayer): Boolean {
        if (!showing.remove(player.uuid)) return false
        sender(player, IntArray(0))
        return true
    }

    /**
     * Forget a player on disconnect.
     *
     * Without this a relogging player is remembered as still showing markers their new client knows nothing
     * about, so their first press would silently toggle OFF and appear to do nothing at all.
     */
    fun forget(playerId: UUID) {
        showing.remove(playerId)
    }
}
