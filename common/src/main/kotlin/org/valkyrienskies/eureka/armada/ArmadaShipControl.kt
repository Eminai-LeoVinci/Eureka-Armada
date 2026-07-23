package org.valkyrienskies.eureka.armada

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip

/**
 * Per-ship armada state: whether this ship is locked to a parent (child side) and which ships are locked to
 * it (parent side). The per-tick following itself is done by an [ArmadaFollowProvider] installed on the
 * child's ServerShip.transformProvider; this attachment records the relationship for /list, /unbind, /debug
 * (and, later, the border wireframes and the helm-menu Child checkbox).
 *
 * PERSISTED (Jackson, via useLegacySerializer in EurekaMod): the child-side bind -- [parentShipId] and the
 * fixed offset [intendedPosInParent]/[intendedRotInParent] -- survives a world reload, so a bound child
 * re-joins its parent on relog. The follow provider itself is a runtime object and is NOT serialized, so
 * [ArmadaBindings.reconcile] re-installs it each server tick for any loaded child whose parent is loaded but
 * whose provider is missing (i.e. just after load).
 *
 * [childShipIds] is deliberately NOT persisted: it's rebuilt at runtime by reconcile from the children's
 * [parentShipId], which keeps the child's link the single source of truth (the two sides can't disagree after
 * a half-loaded reload where only one of the pair came back).
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
class ArmadaShipControl {

    /** Child side: the parent ship this one is locked to, or null when this ship is not a child. Persisted. */
    @JsonProperty("parent")
    var parentShipId: Long? = null

    /**
     * The child's centre in the parent's model frame and its orientation relative to the parent, captured at
     * bind and persisted. On reload [ArmadaBindings.reconcile] rebuilds the follow provider from these, so the
     * child snaps back to the exact same spot in the armada. Diagnostics ([ArmadaCommand] debug) recompute the
     * child's live pose in the parent frame and compare against these to report drift.
     */
    @JsonProperty("pos")
    var intendedPosInParent: Vector3d? = null

    @JsonProperty("rot")
    var intendedRotInParent: Quaterniond? = null

    /** Parent side: the ships locked to this one as children. Rebuilt at runtime (see class doc), not persisted. */
    @JsonIgnore
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
