package org.valkyrienskies.eureka.armada

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider.NextTransformAndVelocityData
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.mod.api.vsApi

/**
 * Locks a child ship rigidly to its [parent] by POSITIONING it every physics tick, instead of
 * force-coupling it with a joint. VS calls [provideNextTransformAndVelocity] each tick; we throw away the
 * physics-computed pose and instead return the child's pose reconstructed from the parent's CURRENT pose
 * plus the fixed offset captured at bind. No forces, no joint, nothing to flex -- the child simply IS
 * wherever the parent puts it, so the armada moves as one regardless of ship size or how far apart they sit.
 *
 * [childCenterInParentModel] is the child's centre of mass expressed in the parent's model (shipyard)
 * frame; run it through the parent's live shipToWorld each tick to get the child's world centre.
 * [relRot] is the child's orientation relative to the parent -- identity, since a child is locked facing
 * the parent exactly.
 */
class ArmadaFollowProvider(
    private val parent: ServerShip,
    private val childCenterInParentModel: Vector3d,
    private val relRot: Quaterniond,
    private val childScaling: Vector3d,
    private val childPositionInModel: Vector3d
) : ServerShipTransformProvider {

    override fun provideNextTransformAndVelocity(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform
    ): NextTransformAndVelocityData {
        val pt = parent.transform

        // Child world pose = the parent's live pose applied to the fixed bind offset.
        val childWorldPos = pt.shipToWorld.transformPosition(Vector3d(childCenterInParentModel))
        val childWorldRot = Quaterniond(pt.shipToWorldRotation).mul(relRot)
        val nextTransform = vsApi.newBodyTransform(childWorldPos, childWorldRot, childScaling, childPositionInModel)

        // Rigid-body velocity so entities riding the child and the client's interpolation stay in step:
        // v_child = v_parent + omega x r, and the child shares the parent's angular velocity.
        val r = Vector3d(childWorldPos).sub(pt.positionInWorld)
        val nextVel = Vector3d(parent.velocity).add(parent.angularVelocity.cross(r, Vector3d()))
        val nextOmega = Vector3d(parent.angularVelocity)

        return NextTransformAndVelocityData(nextTransform, nextVel, nextOmega)
    }
}
