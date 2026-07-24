package org.valkyrienskies.eureka.armada

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side registry of armada bonds, kept current by a small periodic server snapshot (see
 * ArmadaNetworkingFabric). The bond itself lives only on the server -- an [ArmadaShipControl] attachment,
 * which VS doesn't sync -- so the client learns of it here. For each bound child this installs an
 * [ArmadaClientFollowProvider] on the child's ClientShip, gluing its render pose to the parent's smooth pose.
 *
 * The install is idempotent and re-checked every client tick ([tick]) so it survives ships that load (or
 * reload) on the client after a snapshot has already arrived.
 */
@Environment(EnvType.CLIENT)
object ArmadaClientBonds {

    /** A single bond as seen by the client: the parent to follow and the fixed offset into the parent's frame. */
    class Bond(val parentShipId: Long, val posInParent: Vector3d, val rotInParent: Quaterniond)

    /** childId -> bond. Replaced wholesale by each [update] snapshot. */
    private val bonds = HashMap<Long, Bond>()

    /**
     * Apply a full snapshot from the server: drop the follow provider from any child that is no longer bound,
     * then (re)install providers for the current set. Full-snapshot semantics mean bind, unbind, relog and
     * ship (un)tracking all converge here with no per-event bookkeeping.
     */
    fun update(snapshot: Map<Long, Bond>) {
        for (childId in bonds.keys.toList()) {
            if (childId !in snapshot) removeProvider(childId)
        }
        bonds.clear()
        bonds.putAll(snapshot)
        ensureProviders()
    }

    /** Called each client tick: (re)install providers on any bound child that doesn't have one yet. */
    fun tick() = ensureProviders()

    /** True when this client knows of at least one bond -- lets callers skip armada work entirely when there is none. */
    fun hasBonds(): Boolean = bonds.isNotEmpty()

    /**
     * True when both ships belong to the same armada (same parent, or one is the other's parent). Used by the
     * ship-mounted camera clip: the whole formation moves as one, so no ship in it should shove the camera in --
     * only the world and ships outside the armada should.
     */
    fun sharesArmadaWith(a: Long, b: Long): Boolean {
        if (bonds.isEmpty()) return false
        if (a == b) return true
        return rootOf(a) == rootOf(b)
    }

    /** Walk up to the armada's top parent. Bonds are one level deep today; the cap just makes a cycle harmless. */
    private fun rootOf(shipId: Long): Long {
        var id = shipId
        repeat(MAX_BOND_DEPTH) {
            id = bonds[id]?.parentShipId ?: return id
        }
        return id
    }

    private fun ensureProviders() {
        // Nothing to install, and asking for the ship world outside a level (title screen, world load) makes VS log
        // a warning with a stack trace -- once per client tick, which is a lot of log for no work.
        if (bonds.isEmpty()) return
        val world = Minecraft.getInstance().shipObjectWorld
        for ((childId, bond) in bonds) {
            val child = world.loadedShips.getById(childId) as? ClientShip ?: continue
            if (child.transformProvider is ArmadaClientFollowProvider) continue
            child.transformProvider = ArmadaClientFollowProvider(
                bond.parentShipId, Vector3d(bond.posInParent), Quaterniond(bond.rotInParent)
            )
        }
    }

    private fun removeProvider(childId: Long) {
        val child = Minecraft.getInstance().shipObjectWorld.loadedShips.getById(childId) as? ClientShip ?: return
        if (child.transformProvider is ArmadaClientFollowProvider) child.transformProvider = null
    }

    private const val MAX_BOND_DEPTH = 8
}
