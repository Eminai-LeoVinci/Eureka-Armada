package org.valkyrienskies.eureka.armada

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.core.internal.ShipTeleportData
import org.valkyrienskies.mod.common.vsCore

/**
 * Carries a whole armada when its parent is teleported, so `/vs teleport <parent> ...` moves the formation
 * rather than tearing the parent out of it.
 *
 * Driven from a mixin on vs-core's `VSCoreImpl.teleportShip`, which every teleport funnels through (the
 * server and client cores both delegate to it), so this covers the command and anything else that moves a
 * ship the same way. It runs BEFORE the core applies the move, which is what makes the arithmetic easy: the
 * parent's current pose is still its old one, and [ShipTeleportData.createNewShipTransform] says where it is
 * about to land.
 *
 * Every member then undergoes the SAME rigid motion -- the parent's position delta plus its rotation delta
 * about the parent -- so the armada arrives in exactly the shape it left in, at whatever attitude the
 * teleport asked for.
 */
object ArmadaTeleport {

    // Teleporting each child goes back through the very call this hooks. Set while that is happening so the
    // nested calls pass straight through. Server-thread only (commands, world ticks), so a plain flag is enough
    // -- and it also means a nested armada's grandchildren are not carried, which is fine because nothing
    // supports building one.
    private var carrying = false

    @JvmStatic
    @OptIn(VsBeta::class)
    fun carryChildren(world: ServerShipWorld, ship: ServerShip, teleportData: ShipTeleportData) {
        if (carrying) return
        val parent = ship as? LoadedServerShip ?: return
        val armada = ArmadaShipControl.get(parent) ?: return
        if (armada.childShips.isEmpty()) return

        val oldPos = Vector3d(parent.transform.positionInWorld)
        val landing = teleportData.createNewShipTransform(parent.transform)
        val newPos = Vector3d(landing.position)
        // The rotation the parent is about to gain, which is also the rotation each child's offset from it has
        // to be swung through for the formation to hold its shape.
        val rotDelta = Quaterniond(landing.rotation)
            .mul(Quaterniond(parent.transform.shipToWorldRotation).invert())

        // The welds cannot survive this. They hold the CURRENT relative pose, and the parent is about to jump
        // out of it -- left attached, the solver would try to close a several-hundred-block gap and either tear
        // the armada apart or drag the parent straight back. Dropped here, before the move; ArmadaBindings
        // re-welds every child at its new (identical) relative pose once the whole formation has landed.
        ArmadaBindings.dropWelds(parent)

        carrying = true
        try {
            for (child in armada.childShips.values) {
                // The child's offset from the parent, carried through the parent's rotation.
                val offset = Vector3d(child.transform.positionInWorld).sub(oldPos)
                rotDelta.transform(offset)

                vsCore.teleportShip(
                    world, child,
                    vsCore.newShipTeleportData(
                        newPos = Vector3d(newPos).add(offset),
                        newRot = Quaterniond(rotDelta).mul(child.transform.shipToWorldRotation),
                        // Rigid-body velocity at the child's centre: what the armada was given, plus the part
                        // its offset picks up from the armada's spin -- the same relation ArmadaBody drives
                        // the formation by, so it arrives already moving as one piece.
                        newVel = Vector3d(landing.angularVelocity).cross(offset).add(landing.velocity),
                        newOmega = landing.angularVelocity,
                        newDimension = teleportData.newDimension,
                        // Pinned explicitly so `newPos` unambiguously means "put this ship's centre of mass
                        // here", which is what the offset above was measured between.
                        newPosInShip = Vector3d(child.transform.positionInShip)
                    )
                )
            }
        } finally {
            carrying = false
        }
    }
}
