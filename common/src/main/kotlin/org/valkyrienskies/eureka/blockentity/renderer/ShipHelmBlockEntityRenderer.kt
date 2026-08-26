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
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.block.ShipHelmWheelBlock
import org.valkyrienskies.eureka.block.WoodType
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos

class ShipHelmBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ShipHelmBlockEntity, ShipHelmBlockEntityRenderer.ShipHelmRenderState> {

    class ShipHelmRenderState : BlockEntityRenderState() {
        @JvmField
        var wheelRotation: Float = 0f
    }

    override fun createRenderState(): ShipHelmRenderState = ShipHelmRenderState()

    // Reused across submits (render thread only): mulPose reads the quaternion without retaining
    // it, and submit runs per helm per frame.
    private val scratchRotation = Quaternionf()

    override fun extractRenderState(
        blockEntity: ShipHelmBlockEntity,
        state: ShipHelmRenderState,
        partialTick: Float,
        cameraPos: Vec3,
        crumblingOverlay: ModelFeatureRenderer.CrumblingOverlay?
    ) {
        super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay)
        // The wheel deflects with the ship's current turn rate, like a helmsman holding it.
        val ship = blockEntity.level?.getShipManagingPos(blockEntity.blockPos)
        state.wheelRotation = if (ship != null) ship.angularVelocity.y().toFloat() else 0f
    }

    override fun submit(
        state: ShipHelmRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        cameraState: CameraRenderState
    ) {
        val helmBlock = state.blockState.block as? ShipHelmBlock ?: return
        val woodType = helmBlock.woodType as? WoodType ?: return
        val wheelState = EurekaBlocks.SHIP_HELM_WHEEL.get().defaultBlockState()
            .setValue(ShipHelmWheelBlock.WOOD, woodType)
            // The hub colour follows the helm's mark: blue, pirate black, or conquered white.
            .setValue(EurekaProperties.MARK, state.blockState.getValue(EurekaProperties.MARK))

        poseStack.pushPose()
        // Wheel pivot above the helm base.
        poseStack.translate(0.5, 0.60, 0.5)
        // Rotate the wheel to face the helm's direction.
        poseStack.mulPose(
            scratchRotation.setAngleAxis(
                (-state.blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    .toYRot() * Math.PI / 180.0).toFloat(),
                0.0f, 1.0f, 0.0f
            )
        )
        // Push the wheel out from the base along the facing axis.
        poseStack.translate(0.0, 0.0, 0.19)
        // Spin the wheel with the ship's angular velocity.
        poseStack.mulPose(
            scratchRotation.setAngleAxis(state.wheelRotation / 20f * Math.PI.toFloat(), 0.0f, 0.0f, 1.0f)
        )
        // The wheel model isn't centred on its own origin.
        poseStack.translate(-0.5, -0.625, -0.25)
        // Pre-baked quads, not submitBlock -- see VirtualBlockRenderCache (one entry per wood x mark state).
        VirtualBlockRenderCache.submit(collector, poseStack, wheelState, state.lightCoords)
        poseStack.popPose()
    }
}
