package org.valkyrienskies.eureka.blockentity.renderer

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a cannon's barrel at its exact elevation, the way [ShipHelmBlockEntityRenderer] draws the helm's
 * wheel: the blockstate models carry only the static carriage, and the barrel is a virtual-block model
 * ([EurekaBlocks.CANNON_BARREL]) pitched here on the pose stack. Baked models could only ever offer the
 * five angles vanilla element rotation permits; this is what lets the gun elevate in 5-degree steps.
 *
 * Only the rear half hosts the block entity, so exactly one barrel is drawn per gun.
 *
 * The drawn angle is not the ordered angle: it slews toward it at the config's
 * `cannonBarrelSlewDegreesPerSecond`, so a laying order visibly traverses the barrel (zero snaps it,
 * the old baked-model behaviour). The bookkeeping lives on the block entity
 * ([CannonBlockEntity.barrelPitchShown]), advanced here per frame off the wall clock -- a gun that goes
 * unrendered simply arrives at its target, which is also what a freshly loaded gun does (NaN sentinel).
 * Ballistics never look at any of this: CannonFire reads the blockstate, so a shot mid-slew flies at the
 * ordered angle. Orders are the truth; the barrel is catching up to it.
 *
 * (1.21.11 splits this into extract + submit over a render state; 1.21.1 block entities render
 * immediately, so the slew bookkeeping and the draw share [render].)
 */
class CannonBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CannonBlockEntity> {

    // The barrel spans a block past the breech the block entity sits on; without this the whole gun
    // barrel vanishes whenever the breech's own chunk section leaves the frustum while the muzzle's
    // section is still in view.
    override fun shouldRenderOffScreen(blockEntity: CannonBlockEntity): Boolean = true

    // Reused across draws (render thread only), same idiom as the helm's wheel.
    private val scratchRotation = Quaternionf()
    private val scratchPos = Vector3d()

    /**
     * Whether this gun is far enough behind the viewer that drawing it cannot possibly matter.
     *
     * [shouldRenderOffScreen] above buys the barrel correctness at a real price: a global block entity is
     * exempt from SECTION culling entirely, so every gun on every loaded hull is re-tessellated every
     * frame whichever way the camera happens to be pointing. A profile of a 120-gun ship put this
     * renderer at 18.8 percent of a fully saturated render thread with shaders off -- the largest single
     * cost in the frame, and most of it guns behind the player's head.
     *
     * The obvious hook is `shouldRender`, and it does not work: VS2's own
     * `MixinBlockEntityRenderDispatcher` WRAPS that call and turns a false back into a true for any block
     * entity belonging to a ship inside its distance check. So the test has to live here, where nothing
     * can overrule it.
     *
     * A HALF-SPACE test rather than a cone, which is what makes it safe to do at all: anything behind the
     * plane through the camera cannot appear on screen at any field of view, so there is no angle to tune
     * and nothing to pop at the edge of vision as the ship swings. [CULL_MARGIN] then holds the plane a
     * few blocks back, which covers the barrel's own length and the fact that the position tested is the
     * breech rather than the muzzle.
     *
     * Ship guns live in the shipyard, so the render transform is applied first -- the same conversion
     * VS2's distance check performs, and for the same reason. Off a ship the two spaces are one.
     */
    private fun behindTheCamera(blockEntity: CannonBlockEntity): Boolean {
        if (!EurekaConfig.CLIENT.cannonBarrelCullBehindCamera) return false
        val level = blockEntity.level ?: return false
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        if (!camera.isInitialized) return false

        val pos = blockEntity.blockPos
        scratchPos.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        (level.getLoadedShipManagingPos(pos) as? ClientShip)
            ?.renderTransform?.shipToWorld?.transformPosition(scratchPos)

        val eye = camera.position
        val look = camera.lookVector
        val ahead = (scratchPos.x - eye.x) * look.x() +
            (scratchPos.y - eye.y) * look.y() +
            (scratchPos.z - eye.z) * look.z()
        return ahead < -CULL_MARGIN
    }

    override fun render(
        blockEntity: CannonBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val blockState = blockEntity.blockState
        if (!blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return
        if (behindTheCamera(blockEntity)) return
        // Iris draws the world twice and a barrel is a block entity, so every gun is tessellated
        // once for the camera and once for the sun. The behind-camera cull above cannot touch the second
        // pass -- a gun behind the player is still in front of the shadow map. Measured at 12.4% of the
        // render thread on a 120-gun ship under BLS, on top of the 12.7% the main pass costs.
        // See [IrisShadowPass] for what this gives up, which is only the muzzle.
        if (!EurekaConfig.CLIENT.cannonBarrelCastsShadows && IrisShadowPass.active()) return
        val facing = blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        val barrelState = EurekaBlocks.CANNON_BARREL.get().defaultBlockState()

        val target = if (blockState.hasProperty(EurekaProperties.ELEVATION)) {
            EurekaProperties.elevationDegrees(blockState.getValue(EurekaProperties.ELEVATION)).toFloat()
        } else {
            0f
        }

        val now = System.nanoTime()
        val rate = EurekaConfig.CLIENT.cannonBarrelSlewDegreesPerSecond.toFloat()
        var shown = blockEntity.barrelPitchShown
        shown = if (shown.isNaN() || rate <= 0f) {
            target
        } else {
            // Seconds since this gun was last drawn. A long gap (off-screen, dimension change) yields a
            // step past the whole remaining arc, which lands exactly on target -- the desired outcome.
            val step = rate * (now - blockEntity.barrelPitchNanos) / 1e9f
            if (shown < target) min(target, shown + step) else max(target, shown - step)
        }
        blockEntity.barrelPitchShown = shown
        blockEntity.barrelPitchNanos = now

        poseStack.pushPose()
        // Turn the barrel to the gun's facing about the block's vertical axis -- the same yaw the
        // blockstate applies to the carriage. The model's muzzle points -z, so north is zero and the
        // blockstate's clockwise "y" degrees are toYRot + 180; pose-stack rotation counts the other way.
        poseStack.translate(0.5, 0.0, 0.5)
        poseStack.mulPose(
            scratchRotation.setAngleAxis(
                (-(facing.toYRot() + 180.0) * Math.PI / 180.0).toFloat(),
                0.0f, 1.0f, 0.0f
            )
        )
        // To the trunnion, the axle the barrel pitches about: rear-local (0.5, 1.0, 0.3125), the
        // [8, 16, 5] the old baked models rotated their barrel elements around. The -0.1875 folds in
        // the -0.5 that undoes the yaw pivot above.
        poseStack.translate(0.0, 1.0, -0.1875)
        // Pitch to the drawn elevation. Same sign convention as element rotation: positive is muzzle up.
        poseStack.mulPose(
            scratchRotation.setAngleAxis((shown * Math.PI / 180.0).toFloat(), 1.0f, 0.0f, 0.0f)
        )
        // Undo the trunnion, and half a block more on z: the barrel model is authored shifted +8 units
        // backward so the muzzle swell stays inside vanilla's [-16, 32] element range.
        poseStack.translate(-0.5, -1.0, -0.8125)
        // Pre-baked quads, not renderSingleBlock: a broadside of guns re-traversing the barrel model per
        // frame was ~11% of the render thread. See VirtualBlockRenderCache.
        VirtualBlockRenderCache.submit(buffers, poseStack, barrelState, packedLight)
        poseStack.popPose()
    }

    private companion object {
        /**
         * How far behind the camera plane a gun must sit before it is dropped, in blocks.
         *
         * Covers the barrel reaching past the breech this position was taken from, plus a little slack,
         * so a gun whose muzzle is still just in shot is never the one that vanishes.
         */
        const val CULL_MARGIN = 6.0
    }
}
