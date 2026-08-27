package org.valkyrienskies.eureka.armada

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which ship each player has marked as the parent of the armada they're building, set from the helm menu's
 * "Armada Parent" checkbox.
 *
 * A helm only ever knows about ITS ship, but a bind needs two, so the menu splits the job the way the
 * `/armada bind <parent> <child>` command takes its two arguments: tick Parent at one helm to remember that
 * ship, then tick Child at each of the others to bind them to it. The mark is per-player so two people can
 * assemble separate armadas at the same time, and it is only a pointer -- a marked ship with no children yet
 * isn't a parent in any real sense, and nothing about it changes until a child actually binds.
 *
 * In-memory and per-session, like [org.valkyrienskies.eureka.command.AssemblerPreferences]: the BOND is what's
 * persisted (on ArmadaShipControl), not the half-finished intent to make one. ConcurrentHashMap because helm
 * clicks arrive on the server thread while cleanup ([forgetShip]) can run from a ship teardown.
 */
object ArmadaSelection {

    private val byPlayer = ConcurrentHashMap<UUID, Long>()

    /** Mark [shipId] as this player's armada parent, replacing any previous mark. */
    fun select(playerId: UUID, shipId: Long) {
        byPlayer[playerId] = shipId
    }

    /** The ship this player has marked as parent, or null. */
    fun get(playerId: UUID): Long? = byPlayer[playerId]

    fun isSelected(playerId: UUID, shipId: Long): Boolean = byPlayer[playerId] == shipId

    /**
     * Forget [shipId] for everyone -- called when it stops being a plausible parent (it disassembled, or it
     * became someone's child), so a stale mark can't leave a player ticking Child against a ship that's gone.
     */
    fun forgetShip(shipId: Long) {
        byPlayer.entries.removeIf { it.value == shipId }
    }
}
