package org.valkyrienskies.eureka.armada

import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Server-side teardown helpers for armada welds, shared by [ArmadaCommand] and the disassembly hook in
 * ShipHelmBlockEntity. A ship must be released from its armada before it disassembles: otherwise the weld
 * joint would reference a ship that no longer exists, and the ship could be torn down while the joint is
 * still hauling it around.
 */
object ArmadaBindings {

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
