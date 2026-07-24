package org.valkyrienskies.eureka.armada

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Server-side make/break of armada bonds, shared by [ArmadaCommand], the helm menu's Armada Parent/Child
 * checkboxes and the disassembly hook in ShipHelmBlockEntity, so every entry point binds by the same rules and
 * tears down the same way. A ship must be released from its armada before it disassembles: otherwise the parent
 * would keep hauling a ship that no longer exists.
 */
object ArmadaBindings {

    // Both ships must be this close to stationary (m/s) to bind, so the orientation snap and the freshly
    // positioned child are born stress-free rather than fighting live momentum.
    private const val REST_VELOCITY_EPS = 0.5

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

        // The fixed offset the child holds: its current centre of mass expressed in the parent's model
        // (shipyard) frame. The follow provider maps this back through the parent's live pose each physics
        // tick, so the child keeps this exact spot in the armada. Orientation is locked to the parent
        // exactly (identity relative rotation), so on the first tick the child snaps to face forward -- no
        // pre-align needed, since pose-slaving simply places it there.
        val childCenterInParentModel = parent.transform.worldToShip.transformPosition(
            Vector3d(child.transform.positionInWorld), Vector3d()
        )
        val relRot = Quaterniond()

        // Lock the child to the parent by POSITIONING it every physics tick (no joint, nothing to flex).
        child.transformProvider = ArmadaFollowProvider(
            parent,
            Vector3d(childCenterInParentModel),
            Quaterniond(relRot),
            Vector3d(child.transform.shipToWorldScaling),
            Vector3d(child.transform.positionInShip)
        )

        // Ships collide with each other by default; a locked child doesn't need to (its pose is forced), and
        // an overlapping close armada could otherwise shove the parent. Turn it off between these two.
        ValkyrienSkiesMod.getOrCreateGTPA(parent.chunkClaimDimension).disableCollisionBetween(parent.id, child.id)

        childArmada.parentShipId = parent.id
        childArmada.intendedPosInParent = Vector3d(childCenterInParentModel)
        childArmada.intendedRotInParent = Quaterniond(relRot)
        ArmadaShipControl.getOrCreate(parent).childShipIds.add(child.id)
        // A ship that just became a child can't also be someone's marked parent.
        ArmadaSelection.forgetShip(child.id)
        return null
    }

    /**
     * Release [child] from its parent: stop the per-tick follow (clear its transform provider so it returns
     * to normal physics), re-enable ship-ship collision with the parent, and clear the link on both sides.
     * No-op (returns false) if it isn't currently a child.
     */
    fun unbindChild(level: ServerLevel, child: LoadedServerShip): Boolean {
        val armada = ArmadaShipControl.get(child) ?: return false
        val parentId = armada.parentShipId ?: return false

        child.transformProvider = null
        ValkyrienSkiesMod.getOrCreateGTPA(child.chunkClaimDimension).enableCollisionBetween(parentId, child.id)
        // Nothing probes this hull once it's flying itself again; the parent's clamp provider is dropped by
        // ArmadaCollision.tick the moment its last child leaves.
        ArmadaHullProbe.forget(child.id)

        armada.parentShipId = null
        armada.intendedPosInParent = null
        armada.intendedRotInParent = null

        level.shipObjectWorld.loadedShips.getById(parentId)
            ?.let { ArmadaShipControl.get(it) }
            ?.childShipIds?.remove(child.id)
        return true
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
     * persisted on [ArmadaShipControl], but the [ArmadaFollowProvider] that actually positions the child is a
     * runtime object and isn't serialized -- so just after load a bound child has its parent id but no provider
     * and would briefly behave as a free ship. Called every server-world tick: for each loaded child whose
     * provider is missing and whose parent is loaded, it re-installs the follow provider, re-disables ship-ship
     * collision with the parent, and rebuilds the parent's [ArmadaShipControl.childShipIds] entry. Idempotent --
     * a child that's already following is skipped in O(1), so the steady-state cost is negligible.
     */
    fun reconcile(level: ServerLevel) {
        val world = level.shipObjectWorld
        for (child in world.loadedShips) {
            val armada = ArmadaShipControl.get(child) ?: continue
            val parentId = armada.parentShipId ?: continue
            if (child.transformProvider is ArmadaFollowProvider) continue // already following
            val parent = world.loadedShips.getById(parentId) ?: continue  // parent not loaded yet; retry next tick
            val pos = armada.intendedPosInParent ?: continue
            val rot = armada.intendedRotInParent ?: Quaterniond()

            // Rebuild the follow provider from the persisted offset. Scaling and centre-of-mass are intrinsic
            // ship properties, so they're re-read live rather than persisted.
            child.transformProvider = ArmadaFollowProvider(
                parent,
                Vector3d(pos),
                Quaterniond(rot),
                Vector3d(child.transform.shipToWorldScaling),
                Vector3d(child.transform.positionInShip)
            )
            ValkyrienSkiesMod.getOrCreateGTPA(parent.chunkClaimDimension).disableCollisionBetween(parent.id, child.id)
            ArmadaShipControl.getOrCreate(parent).childShipIds.add(child.id)
        }
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
