package org.valkyrienskies.eureka.blockentity.renderer

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.BlockStateModel
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState

/**
 * Submits a virtual block's model (the cannon's barrel, the helm's wheel) as pre-baked quads instead of
 * through [SubmitNodeCollector.submitBlock].
 *
 * submitBlock re-traverses the whole block model EVERY FRAME per submission: renderSingleBlock, which
 * Sodium redirects into its FRAPI quad-emission context, plus ImmediatelyFast's serializer-registry lock
 * per batch. With an anchorage's worth of gun decks that chain was ~11% of the render thread. A given
 * BlockState's model never changes between resource reloads, so its quads are collected ONCE here and
 * every frame just replays them through [com.mojang.blaze3d.vertex.VertexConsumer.putBulkData] -- the
 * same memcpy path vanilla's own renderModel uses per quad -- via submitCustomGeometry.
 *
 * Semantics mirror renderSingleBlock exactly: parts collected with RandomSource.create(42),
 * [ItemBlockRenderTypes.getRenderType] picks the layer, and BlockColors tint index 0 colours tinted
 * quads only. A resource reload swaps the [BlockStateModel] instance, so entries key on model identity
 * and rebake when it changes. Render thread only.
 */
object VirtualBlockRenderCache {

    private class Entry(
        val model: BlockStateModel,
        val quads: Array<BakedQuad>,
        val red: Float,
        val green: Float,
        val blue: Float,
        val renderType: RenderType
    )

    private val entries = HashMap<BlockState, Entry>()

    fun submit(collector: SubmitNodeCollector, poseStack: PoseStack, state: BlockState, lightCoords: Int) {
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(state)
        var entry = entries[state]
        if (entry == null || entry.model !== model) {
            entry = bake(state, model)
            entries[state] = entry
        }
        val e = entry
        collector.submitCustomGeometry(poseStack, e.renderType) { pose, consumer ->
            for (quad in e.quads) {
                if (quad.isTinted) {
                    consumer.putBulkData(pose, quad, e.red, e.green, e.blue, 1.0f, lightCoords, OverlayTexture.NO_OVERLAY)
                } else {
                    consumer.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightCoords, OverlayTexture.NO_OVERLAY)
                }
            }
        }
    }

    private fun bake(state: BlockState, model: BlockStateModel): Entry {
        val quads = ArrayList<BakedQuad>()
        for (part in model.collectParts(RandomSource.create(42L))) {
            for (direction in Direction.entries) {
                quads.addAll(part.getQuads(direction))
            }
            quads.addAll(part.getQuads(null))
        }
        val color = Minecraft.getInstance().blockColors.getColor(state, null, null, 0)
        return Entry(
            model,
            quads.toTypedArray(),
            (color shr 16 and 0xFF) / 255.0f,
            (color shr 8 and 0xFF) / 255.0f,
            (color and 0xFF) / 255.0f,
            ItemBlockRenderTypes.getRenderType(state)
        )
    }
}
