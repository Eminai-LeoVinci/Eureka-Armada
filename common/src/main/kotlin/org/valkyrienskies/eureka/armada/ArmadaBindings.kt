package org.valkyrienskies.eureka.armada

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
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
}
