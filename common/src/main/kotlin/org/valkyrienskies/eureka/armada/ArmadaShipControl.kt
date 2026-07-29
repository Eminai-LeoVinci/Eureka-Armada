package org.valkyrienskies.eureka.armada

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.internal.joints.VSJointId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-ship armada state: whether this ship is locked to a parent (child side) and which ships are locked to
 * it (parent side). The child is a normal physics body welded to its parent by a rigid
 * [org.valkyrienskies.core.internal.joints.VSFixedJoint] (see [ArmadaBindings]); this attachment records the
 * relationship for /list, /unbind, /debug (and, later, the border wireframes and the helm-menu Child checkbox).
 *
 * PERSISTED (Jackson, via useLegacySerializer in EurekaMod): the child-side bind -- [parentShipId] and the
 * fixed offset [intendedPosInParent]/[intendedRotInParent] -- survives a world reload, so a bound child
 * re-joins its parent on relog. The weld itself is a runtime physics object and is NOT serialized, so
 * [ArmadaBindings.reconcile] re-creates it each server tick for any loaded child whose parent is loaded but
 * whose weld is missing (i.e. just after load).
 *
 * [childShips] is deliberately NOT persisted: it's rebuilt at runtime by reconcile from the children's
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

    /**
     * Parent side: the ships locked to this one as children, by id. Rebuilt at runtime (see class doc), not
     * persisted.
     *
     * Holds live ship handles rather than bare ids because the PHYSICS thread needs more than identity: the
     * parent gathers each child's mass, pose and Eureka attachment every tick to drive the armada as one body
     * (see [org.valkyrienskies.eureka.armada.ArmadaBody]), and only a handle gets it there.
     *
     * Concurrent because bind/unbind write it on the GAME thread while the physics thread iterates it; a plain
     * LinkedHashMap would throw on that read.
     */
    @JsonIgnore
    val childShips: MutableMap<Long, LoadedServerShip> = ConcurrentHashMap()

    /** Read-only view of [childShips]' keys, for the command/helm code that only cares about identity. */
    val childShipIds: Set<Long> get() = childShips.keys

    /**
     * Parent side: the Keep Active value this armada last pushed onto its children, so
     * [ArmadaBindings.reconcile] can push again only when it CHANGES.
     *
     * Edge-triggered rather than mirrored every tick because a continuous mirror silently overwrites any
     * deliberate per-ship change -- turn the flag off anywhere in the armada and it was simply back on a tick
     * later, with nothing to say why. Null until the first push, which is what makes a freshly loaded armada
     * take the parent's value once. Runtime only.
     */
    @JsonIgnore
    var mirroredKeepActive: Boolean? = null

    /**
     * Child side: the live ids of the rigid welds holding this ship to its parent (several, at spread hull points,
     * so the armada can't twist on a lever arm). Delivered asynchronously by the physics adapter after
     * [ArmadaBindings] requests them. Runtime only -- joints are physics objects, re-created by
     * [ArmadaBindings.reconcile] on reload rather than serialized.
     */
    @JsonIgnore
    val weldJointIds: MutableList<VSJointId> = ArrayList()

    /** True from requesting the welds until the adapter delivers them, so reconcile can't add a second set. */
    @JsonIgnore
    var weldPending: Boolean = false

    /**
     * Child side: a live handle to the parent ship, set on the game thread at bind/reconcile and read on the
     * PHYSICS thread by [org.valkyrienskies.eureka.ship.EurekaShipControl] so a welded child can drive itself from
     * the parent's pilot input (every ship in the armada propels its own mass in lockstep). Volatile for the
     * cross-thread hand-off; runtime only. Null when this ship isn't a child or the parent isn't loaded yet.
     */
    @JsonIgnore
    @Volatile
    var parentShip: LoadedServerShip? = null

    /** True when this ship is a child of some parent. */
    val isChild: Boolean get() = parentShipId != null

    /** True while this child is welded to (or is about to be welded to) its parent. */
    val hasWeld: Boolean get() = weldPending || weldJointIds.isNotEmpty()

    companion object {
        fun getOrCreate(ship: LoadedServerShip): ArmadaShipControl =
            ship.getAttachment<ArmadaShipControl>() ?: ArmadaShipControl().also { ship.setAttachment(it) }

        fun get(ship: LoadedServerShip): ArmadaShipControl? =
            ship.getAttachment(ArmadaShipControl::class.java)
    }
}
