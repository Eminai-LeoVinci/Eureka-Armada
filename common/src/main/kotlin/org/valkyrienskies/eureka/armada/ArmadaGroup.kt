package org.valkyrienskies.eureka.armada

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * "Which ships are one vessel with this one?" -- answered server-side.
 *
 * The client has had this since the ship-mounted camera needed it ([ArmadaClientBonds.sharesArmadaWith]), but
 * the server side never did: every caller that wanted it spelled out `parentShipId` or `childShipIds` inline,
 * because each only ever needed one direction. Anything that has to reason about the armada as a WHOLE -- like
 * deciding whether the ship you are pointing at is really just your own vessel seen from a different deck --
 * needs both directions and a root, so they live here.
 *
 * Bonds are one level deep today: [ArmadaBindings.bindChild] refuses a parent that is itself a child. The walk
 * below is still depth-capped rather than a single hop, so that if nesting ever arrives this doesn't become an
 * infinite loop -- and a corrupted save pointing a ship at itself would hang the server tick otherwise.
 */
object ArmadaGroup {

    /**
     * The ship at the top of [ship]'s armada, or [ship] itself if it isn't in one.
     *
     * This is the ship that can actually be STEERED: `EurekaShipControl.physTick` returns early for a child with
     * a live parent, so a child never runs its own control law.
     */
    fun rootOf(level: ServerLevel, ship: LoadedServerShip): LoadedServerShip {
        val ships = level.shipObjectWorld.loadedShips
        var current = ship
        var hops = 0
        while (hops++ < MAX_DEPTH) {
            val parentId = ArmadaShipControl.get(current)?.parentShipId ?: return current
            // An unloaded parent means we can't climb further, but `current` is still the best answer we have.
            val parent = ships.getById(parentId) ?: return current
            if (parent.id == current.id) return current
            current = parent
        }
        return current
    }

    /**
     * Every ship id in [ship]'s armada, root included. Just `{ship.id}` for a ship that isn't in one.
     *
     * Read off the parent's live `childShips` map, which is transient -- so a parent whose children haven't been
     * re-welded since a reload reports fewer of them for a tick or two. That is the right failure for the uses
     * here (a membership test that briefly says "no" refuses an order which can simply be given again), and the
     * alternative -- scanning every loaded ship in every dimension -- costs far more than it is worth.
     */
    fun idsOf(level: ServerLevel, ship: LoadedServerShip): Set<Long> {
        val root = rootOf(level, ship)
        val children = ArmadaShipControl.get(root)?.childShipIds
        if (children.isNullOrEmpty()) return setOf(root.id)
        return HashSet<Long>(children.size + 1).apply {
            add(root.id)
            addAll(children)
        }
    }

    /** Whether two ships are the same vessel: the same hull, or two hulls of one armada. */
    fun sameVessel(level: ServerLevel, a: LoadedServerShip, b: LoadedServerShip): Boolean =
        a.id == b.id || rootOf(level, a).id == rootOf(level, b).id

    /** Matches the client's [ArmadaClientBonds] cap, for the same reason: never trust a chain to terminate. */
    private const val MAX_DEPTH = 8
}
