package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.fabric.client.ArmadaPocketOccluder;

/**
 * Turns the depth test around for the pocket occluder's "beyond" draw under a shaderpack.
 *
 * <p>Sodium's {@code begin} applies the translucent layer's pipeline state (LEQUAL, depth writes on) every time
 * it starts a layer, so a depth function set before the draw is overwritten. Injected at its RETURN -- after
 * Iris's own hooks on the same method have bound the pack's program and framebuffer -- this is the last word
 * before the sections draw. Only while {@link ArmadaPocketOccluder#beyondPassActive()}; the occluder restores
 * LEQUAL as soon as that draw returns.
 *
 * <p>{@code remap = false}: Sodium is not obfuscated.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class MixinShaderChunkRendererPocketDepth {

    @Inject(method = "begin", at = @At("RETURN"))
    private void vs_eureka$greaterForBeyondPass(final CallbackInfo ci) {
        if (ArmadaPocketOccluder.beyondPassActive()) {
            GlStateManager._depthFunc(GL11.GL_GREATER);
        }
    }
}
