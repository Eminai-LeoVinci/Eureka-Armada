package org.valkyrienskies.eureka.armada

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.internal.joints.VSFixedJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.settings

/**
 * Server-side make/break of armada bonds, shared by [ArmadaCommand], the helm menu's Armada Parent/Child
 * checkboxes and the disassembly hook in ShipHelmBlockEntity, so every entry point binds by the same rules and
 * tears down the same way. A ship must be released from its armada before it disassembles: otherwise the parent
 * would keep hauling a ship that no longer exists.
 *
 * ## How a child is held
 * A child is welded to its parent by a rigid [VSFixedJoint] fed to the physics engine (the same joint pipeline
 * the shipped hinge block uses). It stays a NORMAL physics body, so the engine collides it with the world on its
 * own: the whole armada stops, slides and grinds off terrain the way a single ship does. This replaces the older
 * pose-slaving approach, where the child's transform was forced from the parent every tick -- a forced pose can't
 * yield to terrain, so children flew straight through it and a hand-rolled solver had to fake the contact. The
 * weld hands all of that back to the engine.
 */
object ArmadaBindings {

    // Both ships must be this close to stationary (m/s) to bind, so the weld is born at rest rather than fighting
    // live momentum.
    private const val REST_VELOCITY_EPS = 0.5

    // Inverse stiffness of the weld. VS's tuned default (1e-10) is rigid enough to read as one hull while staying
    // solver-stable; a true zero can spike corrective impulses. If a large child on a long lever arm visibly
    // flexes, the fix is more weld points, not a stiffer single one.
    private const val WELD_COMPLIANCE = 1.0e-10

    /**
     * Lock [child] into [parent]'s frame. Shared by `/armada bind` and the helm menu's "Armada Child" checkbox,
     * so both enforce the same rules and produce the same bond. Returns null on success, or the reason it
     * refused (ready to show to whoever asked).
     */
    fun bindChild(parent: LoadedServerShip, child: LoadedServerShip): Component? {
        if (parent.id == child.id) {
            return Component.literal("A ship can't be its own parent.")
        }
        if (parent.chunkClaimDimension != child.chunkClaimDimension) {
            return Component.literal("Both ships must be in the same dimension.")
        }
        val childArmada = ArmadaShipControl.getOrCreate(child)
        if (childArmada.isChild) {
            return Component.literal("That ship is already bound to a parent -- unbind it first.")
        }
        if (parent.velocity.length() > REST_VELOCITY_EPS || child.velocity.length() > REST_VELOCITY_EPS) {
            return Component.literal("Bring both ships to a stop before binding.")
        }

        // The offset recorded for this bond: the child's current centre of mass in the parent's model (shipyard)
        // frame, plus its orientation relative to the parent. The weld physically holds the ships at their current
        // relative pose; this record drives the /armada debug drift readout and the client bond snapshot. Captured
        // at rest, so the weld is born stress-free.
        val childCenterInParentModel = parent.transform.worldToShip.transformPosition(
            Vector3d(child.transform.positionInWorld), Vector3d()
        )
        val relRot = Quaterniond(parent.transform.shipToWorldRotation).invert()
            .mul(child.transform.shipToWorldRotation, Quaterniond())

        // Ships collide with each other by default; welded hulls sit flush and would otherwise shove each
        // other, so turn ship-ship collision off across the armada. Before the child is added to the parent's
        // list below, so it pairs against the siblings that were already there.
        disableArmadaCollisions(parent, child)

        childArmada.parentShipId = parent.id
        childArmada.parentShip = parent
        childArmada.intendedPosInParent = Vector3d(childCenterInParentModel)
        childArmada.intendedRotInParent = Quaterniond(relRot)
        createWeld(parent, child, childArmada)

        val parentArmada = ArmadaShipControl.getOrCreate(parent)
        parentArmada.childShips[child.id] = child

        // An armada has to keep itself loaded as ONE thing, so forming one turns Keep Active on for both ends
        // rather than waiting for someone to notice. A parent that falls out of simulation strands its children;
        // a child that does is a welded body that has stopped stepping, which stalls the whole formation dead.
        // Recorded as the mirrored value so the edge-triggered push in reconcile doesn't read this as a change.
        parent.settings.keepActive = true
        child.settings.keepActive = true
        parentArmada.mirroredKeepActive = true

        // A ship that just became a child can't also be someone's marked parent.
        ArmadaSelection.forgetShip(child.id)
        return null
    }

    /**
     * Release [child] from its parent: drop the weld (so it returns to free physics), re-enable ship-ship
     * collision with the parent, and clear the link on both sides. No-op (returns false) if it isn't currently a
     * child.
     */
    fun unbindChild(level: ServerLevel, child: LoadedServerShip): Boolean {
        val armada = ArmadaShipControl.get(child) ?: return false
        val parentId = armada.parentShipId ?: return false

        removeWeld(child, armada)
        // While it is still listed as a child, so the siblings it stops sharing an armada with are still known.
        val parentArmada = level.shipObjectWorld.loadedShips.getById(parentId)?.let { ArmadaShipControl.get(it) }
        enableArmadaCollisions(child, parentId, parentArmada)

        armada.parentShipId = null
        armada.parentShip = null
        armada.intendedPosInParent = null
        armada.intendedRotInParent = null

        parentArmada?.childShips?.remove(child.id)
        return true
    }

    /**
     * Turn ship-ship collision off between [child] and every other ship in the armada: the parent AND the
     * siblings already bound to it.
     *
     * The engine takes these a PAIR at a time, so each new member has to be paired off against all of them. With
     * only the parent pair disabled, two children welded side by side still collided with each other -- and since
     * neither weld yields, the contact and the joints fought to a standstill and the armada stopped moving
     * altogether. That is what a third ship did to it: two ships have no sibling pair to miss.
     *
     * Call BEFORE adding [child] to the parent's children (it skips itself either way). Every ship still collides
     * with the world on its own.
     */
    private fun disableArmadaCollisions(parent: LoadedServerShip, child: LoadedServerShip) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(parent.chunkClaimDimension)
        gtpa.disableCollisionBetween(parent.id, child.id)
        ArmadaShipControl.get(parent)?.childShipIds?.forEach { siblingId ->
            if (siblingId != child.id) gtpa.disableCollisionBetween(siblingId, child.id)
        }
    }

    /** Undoes [disableArmadaCollisions]: [child] collides with its former parent and siblings again. */
    private fun enableArmadaCollisions(child: LoadedServerShip, parentId: Long, parentArmada: ArmadaShipControl?) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(child.chunkClaimDimension)
        gtpa.enableCollisionBetween(parentId, child.id)
        parentArmada?.childShipIds?.forEach { siblingId ->
            if (siblingId != child.id) gtpa.enableCollisionBetween(siblingId, child.id)
        }
    }

    /**
     * Release [ship] from the armada whether it is a child (unbind it) or a parent (unbind every child).
     * Called before disassembly so a welded ship never tears down with a live joint.
     */
    fun releaseFromArmada(level: ServerLevel, ship: LoadedServerShip) {
        // Before the attachment check: a ship marked as a parent that never picked up a child has no attachment
        // at all, and its mark still has to go when it disassembles.
        ArmadaSelection.forgetShip(ship.id)
        val armada = ArmadaShipControl.get(ship) ?: return
        unbindChild(level, ship)
        // Copy the set first -- unbindChild mutates the parent's childShipIds.
        for (childId in armada.childShipIds.toList()) {
            level.shipObjectWorld.loadedShips.getById(childId)?.let { unbindChild(level, it) }
        }
    }

    /**
     * Re-establish any armada bond that a world reload dropped. The child-side bind (parent id + offset) is
     * persisted on [ArmadaShipControl], but the weld that actually holds the child is a runtime physics object and
     * isn't serialized -- so just after load a bound child has its parent id but no weld and would drift as a free
     * ship. Called every server-world tick: for each loaded child whose weld is missing and whose parent is
     * loaded, it re-welds them at their current relative pose, re-disables ship-ship collision across the armada,
     * and rebuilds the parent's [ArmadaShipControl.childShipIds] entry. Idempotent -- a child that already has (or has
     * requested) a weld is skipped in O(1), so the steady-state cost is negligible.
     */
    fun reconcile(level: ServerLevel) {
        val world = level.shipObjectWorld
        syncArmadaSettings(level)
        for (child in world.loadedShips) {
            val armada = ArmadaShipControl.get(child) ?: continue
            val parentId = armada.parentShipId ?: continue
            if (armada.hasWeld) continue // already welded, or the weld is on its way
            val parent = world.loadedShips.getById(parentId) ?: continue // parent not loaded yet; retry next tick

            // Pairs against whichever siblings have already reconciled; the ones that haven't will pair against
            // this child when their turn comes, so every pair is covered whatever order they load in.
            disableArmadaCollisions(parent, child)
            armada.parentShip = parent
            createWeld(parent, child, armada)
            ArmadaShipControl.getOrCreate(parent).childShips[child.id] = child
        }
    }

    /**
     * Push VS's **Keep Active** from each armada parent onto its children whenever the parent's value CHANGES.
     *
     * Keep Active belongs to the vessel, not to one hull: a child left un-kept while the parent flies out of
     * anyone's simulation range stops being simulated, and the armada -- welded to a ship that is no longer
     * stepping -- stalls and stops dead. So it is carried from the parent, whichever way it was set there (the
     * helm's "Keep Active?" checkbox, `/vs set-keep-active`, or anything else), rather than hooked at any one
     * click.
     *
     * **Edge-triggered, deliberately.** Mirroring the parent's value onto the children EVERY tick meant no
     * deliberate change could survive: turn Keep Active off and the next tick put it straight back, so the flag
     * read as permanently stuck on with nothing to explain it. Pushing only on a change leaves the armada in
     * lockstep whenever the parent is touched, while still letting a single ship be set on its own afterwards.
     * The null start ([ArmadaShipControl.mirroredKeepActive]) makes a freshly loaded armada take the parent's
     * value once.
     */
    private fun syncArmadaSettings(level: ServerLevel) {
        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            // loadedShips spans every dimension while this runs once per level -- see reconcile's callers.
            if (ship.chunkClaimDimension != dimension) continue
            val armada = ArmadaShipControl.get(ship) ?: continue
            if (armada.isChild || armada.childShips.isEmpty()) continue
            val keepActive = ship.settings.keepActive
            if (armada.mirroredKeepActive == keepActive) continue
            armada.mirroredKeepActive = keepActive
            for (child in armada.childShips.values) child.settings.keepActive = keepActive
        }
    }

    /**
     * Request rigid welds holding [child] to [parent] at their CURRENT relative pose, and record their ids on
     * [control] as the physics adapter delivers them.
     *
     * Several fixed joints are placed at spread points across the child's hull ([weldAnchors]) rather than one at
     * its centre: a single central weld holds POSITION fine but lets a wide child TWIST on the lever arm (measured
     * 15.8° once), because all the rotational load rides one joint's angular constraint. Spreading the anchors to
     * the hull's extremes turns that torque into linear loads at wide arms, and rotational stiffness then scales
     * with the anchor spread squared. Each joint's two poses are the SAME world point expressed in each ship's
     * model frame, with orientations chosen so the joint frames coincide in the world right now -- so the welds
     * freeze whatever relative pose the ships are in at this instant rather than snapping them together.
     * [ArmadaShipControl.weldPending] guards against a second set being requested before the adapter answers.
     */
    private fun createWeld(parent: LoadedServerShip, child: LoadedServerShip, control: ArmadaShipControl) {
        val worldRot = Quaterniond(parent.transform.shipToWorldRotation)
        // Joint-frame orientation in each ship's own model frame, chosen so both map to `worldRot` in the world
        // right now: identity for the parent (worldRot is its own rotation), the current relative rotation for the
        // child. Shared by every anchor's pose.
        val pose0Rot = Quaterniond(parent.transform.shipToWorldRotation).invert().mul(worldRot, Quaterniond())
        val pose1Rot = Quaterniond(child.transform.shipToWorldRotation).invert().mul(worldRot, Quaterniond())

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(parent.chunkClaimDimension)
        control.weldPending = true
        for (anchorInChild in weldAnchors(child)) {
            val worldPoint = child.transform.shipToWorld.transformPosition(Vector3d(anchorInChild), Vector3d())
            val pose0 = VSJointPose(
                parent.transform.worldToShip.transformPosition(Vector3d(worldPoint), Vector3d()),
                Quaterniond(pose0Rot)
            )
            val pose1 = VSJointPose(Vector3d(anchorInChild), Quaterniond(pose1Rot))
            // maxForceTorque = null: the weld never yields, so a hard terrain hit can't snap the armada apart.
            val weld = VSFixedJoint(parent.id, pose0, child.id, pose1, null, WELD_COMPLIANCE)
            gtpa.addJoint(weld, 0) { id ->
                control.weldJointIds.add(id)
                control.weldPending = false
            }
        }
    }

    /**
     * Points on [child] (in its model/shipyard frame) to weld to the parent. Four mutually-diagonal corners of the
     * child's block AABB -- a tetrahedron inscribed in the hull, so the anchors span all three axes at the widest
     * lever arm the ship offers. A blockless ship falls back to a single anchor at its centre of mass.
     */
    private fun weldAnchors(child: LoadedServerShip): List<Vector3d> {
        val aabb = child.shipAABB ?: return listOf(Vector3d(child.transform.positionInShip))
        val minX = aabb.minX().toDouble(); val minY = aabb.minY().toDouble(); val minZ = aabb.minZ().toDouble()
        val maxX = aabb.maxX().toDouble(); val maxY = aabb.maxY().toDouble(); val maxZ = aabb.maxZ().toDouble()
        return listOf(
            Vector3d(minX, minY, minZ),
            Vector3d(maxX, maxY, minZ),
            Vector3d(maxX, minY, maxZ),
            Vector3d(minX, maxY, maxZ)
        )
    }

    /** Drop all of [child]'s welds from the physics engine and clear their handles on [control]. */
    private fun removeWeld(child: LoadedServerShip, control: ArmadaShipControl) {
        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(child.chunkClaimDimension)
        // Our recorded ids, plus any joint currently on the child (covers a weld whose id hasn't come back yet, or
        // a stale weld from before a reload). A bound child only ever holds our welds, so removing all is safe.
        val ids = control.weldJointIds.toMutableSet()
        gtpa.getJointsFromShip(child.id)?.let { ids.addAll(it) }
        ids.forEach { gtpa.removeJoint(it) }
        control.weldJointIds.clear()
        control.weldPending = false
    }

    /** A single child's bond flattened for the client-sync snapshot (see ArmadaNetworkingFabric). */
    class ArmadaBondData(val childId: Long, val parentId: Long, val pos: Vector3d, val rot: Quaterniond)

    /**
     * Snapshot every loaded child's bond in [level] for client sync. Lives here (common) rather than in the
     * fabric networking layer because it touches VS's `shipObjectWorld` extension, which only resolves against
     * the common module's classpath.
     */
    fun collectBonds(level: ServerLevel): List<ArmadaBondData> {
        val out = ArrayList<ArmadaBondData>()
        for (ship in level.shipObjectWorld.loadedShips) {
            val armada = ArmadaShipControl.get(ship) ?: continue
            val parentId = armada.parentShipId ?: continue
            val pos = armada.intendedPosInParent ?: continue
            val rot = armada.intendedRotInParent ?: continue
            out.add(ArmadaBondData(ship.id, parentId, Vector3d(pos), Quaterniond(rot)))
        }
        return out
    }

    /** Stable per-dimension key (VS DimensionId string) for [level]; used to gate the bond broadcast. */
    fun dimKey(level: ServerLevel): String = level.dimensionId
}
