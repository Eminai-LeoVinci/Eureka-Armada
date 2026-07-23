package org.valkyrienskies.eureka.armada

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip

/**
 * Per-ship armada state: whether this ship is locked to a parent (child side) and which ships are locked to
 * it (parent side). The per-tick following itself is done by an [ArmadaFollowProvider] installed on the
 * child's ServerShip.transformProvider; this attachment just records the relationship for /list, /unbind and
 * /debug (and, later, the parent/child graph, the border wireframes and the helm-menu controls).
 *
 * Transient in this build: the follow isn't persisted, so a reload drops it and this bookkeeping with it,
 * which keeps the two consistent. A later step will persist [parentShipId] and re-install the provider on load.
 */
class ArmadaShipControl {

    /** Child side: the parent ship this one is locked to, or null when this ship is not a child. */
    var parentShipId: Long? = null

    /**
     * The child's centre in the parent's model frame and its orientation relative to the parent, captured at
     * bind. Diagnostics ([ArmadaCommand] debug) recompute the child's live pose in the parent frame and
     * compare against these to report drift -- which, with pose-slaving, should stay ~0.
     */
    var intendedPosInParent: Vector3d? = null
    var intendedRotInParent: Quaterniond? = null

    /** Parent side: the ships locked to this one as children. Empty when this ship is not a parent. */
    val childShipIds: MutableSet<Long> = LinkedHashSet()

    /** True when this ship is a child of some parent. */
    val isChild: Boolean get() = parentShipId != null

    companion object {
        fun getOrCreate(ship: LoadedServerShip): ArmadaShipControl =
            ship.getAttachment<ArmadaShipControl>() ?: ArmadaShipControl().also { ship.setAttachment(it) }

        fun get(ship: LoadedServerShip): ArmadaShipControl? =
            ship.getAttachment(ArmadaShipControl::class.java)
    }
}
