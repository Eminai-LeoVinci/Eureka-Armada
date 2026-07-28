package org.valkyrienskies.eureka.armada

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import org.joml.Quaterniond
import org.joml.Vector3d

/**
 * Client-side registry of armada bonds, kept current by a small periodic server snapshot (see
 * ArmadaNetworkingFabric). The bond itself lives only on the server -- an [ArmadaShipControl] attachment, which
 * VS doesn't sync -- so the client learns of it here.
 *
 * Child ships are real physics bodies welded to their parents, so VS renders and interpolates them natively; the
 * client needs no follow provider. What it still needs is armada MEMBERSHIP: [sharesArmadaWith] lets the
 * ship-mounted camera treat the whole formation as one, so no ship in it shoves the view.
 */
@Environment(EnvType.CLIENT)
object ArmadaClientBonds {

    /** A single bond as seen by the client: the parent and the fixed offset into its frame (kept for future use). */
    class Bond(val parentShipId: Long, val posInParent: Vector3d, val rotInParent: Quaterniond)

    /** childId -> bond. Replaced wholesale by each [update] snapshot. */
    private val bonds = HashMap<Long, Bond>()

    /** Apply a full snapshot from the server. Full-snapshot semantics mean bind, unbind and relog all converge here. */
    fun update(snapshot: Map<Long, Bond>) {
        bonds.clear()
        bonds.putAll(snapshot)
    }

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

    private const val MAX_BOND_DEPTH = 8
}
