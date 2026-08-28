package org.valkyrienskies.eureka.blockentity.renderer

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaternionf
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.block.ShipHelmWheelBlock
import org.valkyrienskies.eureka.block.WoodType
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Draws the helm's wheel as a virtual block, spun with the ship's turn rate like a helmsman holding it.
 * (1.21.11 splits this into extract + submit over a render state; 1.21.1 block entities render
 * immediately, so both halves live in [render].)
 */
class ShipHelmBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ShipHelmBlockEntity> {

    // Reused across draws (render thread only): mulPose reads the quaternion without retaining
    // it, and render runs per helm per frame.
    private val scratchRotation = Quaternionf()

    override fun render(
        blockEntity: ShipHelmBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val blockState = blockEntity.blockState
        val helmBlock = blockState.block as? ShipHelmBlock ?: return
        val woodType = helmBlock.woodType as? WoodType ?: return
        val wheelState = EurekaBlocks.SHIP_HELM_WHEEL.get().defaultBlockState()
            .setValue(ShipHelmWheelBlock.WOOD, woodType)
            // The hub colour follows the helm's mark: blue, pirate black, or conquered white.
            .setValue(EurekaProperties.MARK, blockState.getValue(EurekaProperties.MARK))

        // The wheel deflects with the ship's current turn rate, like a helmsman holding it.
        val ship = blockEntity.level?.getShipManagingPos(blockEntity.blockPos)
        val wheelRotation = if (ship != null) ship.angularVelocity.y().toFloat() else 0f

        poseStack.pushPose()
        // Wheel pivot above the helm base.
        poseStack.translate(0.5, 0.60, 0.5)
        // Rotate the wheel to face the helm's direction.
        poseStack.mulPose(
            scratchRotation.setAngleAxis(
                (-blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    .toYRot() * Math.PI / 180.0).toFloat(),
                0.0f, 1.0f, 0.0f
            )
        )
        // Push the wheel out from the base along the facing axis.
        poseStack.translate(0.0, 0.0, 0.19)
        // Spin the wheel with the ship's angular velocity.
        poseStack.mulPose(
            scratchRotation.setAngleAxis(wheelRotation / 20f * Math.PI.toFloat(), 0.0f, 0.0f, 1.0f)
        )
        // The wheel model isn't centred on its own origin.
        poseStack.translate(-0.5, -0.625, -0.25)
        // Pre-baked quads, not renderSingleBlock -- see VirtualBlockRenderCache (one entry per wood x mark state).
        VirtualBlockRenderCache.submit(buffers, poseStack, wheelState, packedLight)
        poseStack.popPose()
    }
}
