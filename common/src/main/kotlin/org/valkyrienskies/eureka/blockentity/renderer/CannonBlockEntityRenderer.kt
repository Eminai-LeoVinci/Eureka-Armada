package org.valkyrienskies.eureka.blockentity.renderer

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.CameraRenderState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a cannon's barrel at its exact elevation, the way [ShipHelmBlockEntityRenderer] draws the helm's
 * wheel: the blockstate models carry only the static carriage, and the barrel is a virtual-block model
 * ([EurekaBlocks.CANNON_BARREL]) pitched here on the pose stack. Baked models could only ever offer the
 * five angles vanilla element rotation permits; this is what lets the gun elevate in 5-degree steps.
 *
 * Only the rear half hosts the block entity, so exactly one barrel is submitted per gun.
 *
 * The drawn angle is not the ordered angle: it slews toward it at the config's
 * `cannonBarrelSlewDegreesPerSecond`, so a laying order visibly traverses the barrel (zero snaps it,
 * the old baked-model behaviour). The bookkeeping lives on the block entity
 * ([CannonBlockEntity.barrelPitchShown]), advanced here per frame off the wall clock -- a gun that goes
 * unrendered simply arrives at its target, which is also what a freshly loaded gun does (NaN sentinel).
 * Ballistics never look at any of this: CannonFire reads the blockstate, so a shot mid-slew flies at the
 * ordered angle. Orders are the truth; the barrel is catching up to it.
 */
class CannonBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CannonBlockEntity, CannonBlockEntityRenderer.CannonRenderState> {

    class CannonRenderState : BlockEntityRenderState() {
        @JvmField
        var barrelPitch: Float = 0f
    }

    override fun createRenderState(): CannonRenderState = CannonRenderState()

    // The barrel spans a block past the breech the block entity sits on; without this the whole gun
    // barrel vanishes whenever the breech's own chunk section leaves the frustum while the muzzle's
    // section is still in view.
    override fun shouldRenderOffScreen(): Boolean = true

    // Reused across submits (render thread only), same idiom as the helm's wheel.
    private val scratchRotation = Quaternionf()

    override fun extractRenderState(
        blockEntity: CannonBlockEntity,
        state: CannonRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumblingOverlay: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay)

        val blockState = blockEntity.blockState
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
        state.barrelPitch = shown
    }

    override fun submit(
        state: CannonRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        cameraState: CameraRenderState
    ) {
        if (!state.blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return
        val facing = state.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        val barrelState = EurekaBlocks.CANNON_BARREL.get().defaultBlockState()

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
            scratchRotation.setAngleAxis((state.barrelPitch * Math.PI / 180.0).toFloat(), 1.0f, 0.0f, 0.0f)
        )
        // Undo the trunnion, and half a block more on z: the barrel model is authored shifted +8 units
        // backward so the muzzle swell stays inside vanilla's [-16, 32] element range.
        poseStack.translate(-0.5, -1.0, -0.8125)
        // Pre-baked quads, not submitBlock: a broadside of guns re-traversing the barrel model per frame
        // was ~11% of the render thread. See VirtualBlockRenderCache.
        VirtualBlockRenderCache.submit(collector, poseStack, barrelState, state.lightCoords)
        poseStack.popPose()
    }

}
