package org.valkyrienskies.eureka.armada

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.ClientShipTransformProvider
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side twin of [ArmadaFollowProvider], and the fix for the child-ship render stutter.
 *
 * The server positions each child from the parent's `transform`, which VS documents as the *game-tick*
 * (un-interpolated) pose -- correct for physics, but only 20 Hz. The parent, however, renders off its own
 * smooth [ClientShip.renderTransform] (interpolated to the display framerate). So the child ends up glued to a
 * stepped 20 Hz pose while the parent glides between steps: during acceleration and turns the child appears to
 * stop, then skip forward. (Confirmed cause; matches the ~15%-of-flight, "is it the parent or the child?"
 * symptom, and that it persists with player ship-influence off.)
 *
 * This provider rebuilds the child's RENDER pose every frame from the parent's already-smooth
 * `renderTransform` plus the same fixed bind offset captured at bind. The child is then locked frame-for-frame
 * to the parent's interpolated pose and cannot stutter relative to it, whatever the server timing does. We
 * leave [provideNextTransform] (the logical/ticked transform) at VS's default and override only the render
 * transform -- that is all the eye sees, and it keeps the ship's tick-level state on VS's normal path.
 */
@Environment(EnvType.CLIENT)
class ArmadaClientFollowProvider(
    private val parentShipId: Long,
    private val childCenterInParentModel: Vector3d,
    private val relRot: Quaterniond
) : ClientShipTransformProvider {

    override fun provideNextTransform(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform,
        latestNetworkTransform: ShipTransform
    ): BodyTransform? = null // keep VS's default ticked transform; we only smooth the render pose

    override fun provideNextRenderTransform(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform,
        partialTick: Double
    ): BodyTransform? {
        // Parent not loaded on this client (e.g. out of render distance): fall back to VS's default render,
        // which just shows the child's own network stream. If you can't see the parent, the stutter is moot.
        val parent = parentClientShip() ?: return null
        val pt = parent.renderTransform

        // Child render pose = parent's smooth render pose applied to the fixed bind offset -- the same math as
        // the server ArmadaFollowProvider, but off the interpolated parent pose. renderTransform already folds
        // in partialTick, so we must not apply it again.
        val childWorldPos = pt.shipToWorld.transformPosition(Vector3d(childCenterInParentModel))
        val childWorldRot = Quaterniond(pt.shipToWorldRotation).mul(relRot)

        // Scaling and centre-of-mass are the child's own intrinsic properties -- read live from its transform.
        return vsApi.newBodyTransform(
            childWorldPos,
            childWorldRot,
            Vector3d(shipTransform.shipToWorldScaling),
            Vector3d(shipTransform.positionInShip)
        )
    }

    private fun parentClientShip(): ClientShip? =
        Minecraft.getInstance().shipObjectWorld.loadedShips.getById(parentShipId) as? ClientShip
}
