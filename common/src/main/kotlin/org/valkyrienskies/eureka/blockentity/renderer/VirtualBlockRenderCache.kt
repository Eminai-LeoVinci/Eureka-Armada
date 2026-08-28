package org.valkyrienskies.eureka.blockentity.renderer

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState

/**
 * Draws a virtual block's model (the cannon's barrel, the helm's wheel) as pre-baked quads instead of
 * through the block renderer's renderSingleBlock.
 *
 * renderSingleBlock re-traverses the whole block model EVERY FRAME per draw: model lookup and per-face
 * quad assembly, which Sodium redirects into its own quad-emission context. With an anchorage's worth of
 * gun decks that chain was ~11% of the render thread on 1.21.11, and the shape is identical here. A given
 * BlockState's model never changes between resource reloads, so its quads are collected ONCE and every
 * frame just replays them through [com.mojang.blaze3d.vertex.VertexConsumer.putBulkData] -- the same
 * memcpy path vanilla's own renderModel uses per quad.
 *
 * (1.21.11 does this via the render-state collector's submitCustomGeometry; 1.21.1 block entities draw
 * immediately, so this writes straight into the [MultiBufferSource] -- same quads, same layer, one
 * architecture earlier.)
 *
 * Semantics mirror renderSingleBlock exactly: quads collected with RandomSource.create(42),
 * [ItemBlockRenderTypes.getRenderType] picks the layer, and BlockColors tint index 0 colours tinted
 * quads only. A resource reload swaps the [BakedModel] instance, so entries key on model identity
 * and rebake when it changes. Render thread only.
 */
object VirtualBlockRenderCache {

    private class Entry(
        val model: BakedModel,
        val quads: Array<BakedQuad>,
        val red: Float,
        val green: Float,
        val blue: Float,
        val renderType: RenderType
    )

    private val entries = HashMap<BlockState, Entry>()

    fun submit(buffers: MultiBufferSource, poseStack: PoseStack, state: BlockState, lightCoords: Int) {
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(state)
        var entry = entries[state]
        if (entry == null || entry.model !== model) {
            entry = bake(state, model)
            entries[state] = entry
        }
        val e = entry
        val consumer = buffers.getBuffer(e.renderType)
        val pose = poseStack.last()
        for (quad in e.quads) {
            if (quad.isTinted) {
                consumer.putBulkData(pose, quad, e.red, e.green, e.blue, lightCoords, OverlayTexture.NO_OVERLAY)
            } else {
                consumer.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, lightCoords, OverlayTexture.NO_OVERLAY)
            }
        }
    }

    private fun bake(state: BlockState, model: BakedModel): Entry {
        val quads = ArrayList<BakedQuad>()
        val random = RandomSource.create(42L)
        for (direction in Direction.entries) {
            random.setSeed(42L)
            quads.addAll(model.getQuads(state, direction, random))
        }
        random.setSeed(42L)
        quads.addAll(model.getQuads(state, null, random))
        val color = Minecraft.getInstance().blockColors.getColor(state, null, null, 0)
        return Entry(
            model,
            quads.toTypedArray(),
            (color shr 16 and 0xFF) / 255.0f,
            (color shr 8 and 0xFF) / 255.0f,
            (color and 0xFF) / 255.0f,
            ItemBlockRenderTypes.getRenderType(state, false)
        )
    }
}
