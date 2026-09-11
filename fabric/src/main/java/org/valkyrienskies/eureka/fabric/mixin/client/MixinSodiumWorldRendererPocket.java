package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.fabric.client.ArmadaPocketOccluder;

/**
 * Brackets the world's translucent terrain draw with the submarine pocket occluder.
 *
 * <p>This is the one method that draws world water: vanilla's {@code ChunkSectionsToRender.renderGroup} is
 * replaced by Sodium (the pipeline this mod is built and tested against), and for {@code TRANSLUCENT} it makes exactly one
 * {@code RenderSectionManager.renderLayer} call. Hooking HEAD and RETURN here puts the occluder's depth write
 * immediately before the water and the restore immediately after it -- after Iris has copied its
 * pre-translucent depth, after VS2 has flushed the ship's own glass (that happens in the feature pass, before
 * this), and before particles, clouds and the shaderpack's composite ever look at the depth buffer.
 *
 * <p>{@code remap = false}: Sodium is not obfuscated. The parameter types are vanilla classes and are remapped
 * by Loom like any other reference, so the descriptor matches Sodium's intermediary-named build at runtime.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRendererPocket {

    @Inject(method = "drawChunkLayer", at = @At("HEAD"))
    private void vs_eureka$occludeBeforeWater(final ChunkSectionLayerGroup group,
        final ChunkRenderMatrices matrices, final double x, final double y, final double z,
        final GpuSampler sampler, final CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            // The renderer and the call's arguments go along: under a shaderpack the occluder draws this very
            // layer a second time itself (see ArmadaPocketOccluder.beginShaderInner).
            ArmadaPocketOccluder.beginWorldTranslucent(this, matrices, x, y, z, sampler);
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("RETURN"))
    private void vs_eureka$restoreAfterWater(final ChunkSectionLayerGroup group,
        final ChunkRenderMatrices matrices, final double x, final double y, final double z,
        final GpuSampler sampler, final CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            ArmadaPocketOccluder.endWorldTranslucent();
        }
    }
}
