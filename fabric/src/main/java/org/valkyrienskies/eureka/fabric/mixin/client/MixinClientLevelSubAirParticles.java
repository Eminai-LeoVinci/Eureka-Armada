package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.armada.SubAir;

/**
 * No water particles in a dry room -- whoever asks for them.
 *
 * <p>The rising motes come from {@code WaterFluid.animateTick} (cancelled at the source), but 1.21.11 also
 * scatters the suspended underwater dust through the environment-attribute ambient particles that
 * {@code ClientLevel.doAnimateTick} spawns around the camera, and splashes and drowning bubbles come from
 * entities. All of them end in {@code doAddParticle}, so that is where a water particle at a world position
 * that is sub air inside a hull is dropped.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelSubAirParticles {

    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$noWaterParticlesInSubAir(final ParticleOptions options, final boolean force,
        final boolean canSpawnOnMinimal, final double x, final double y, final double z, final double xd,
        final double yd, final double zd, final CallbackInfo ci) {
        final ParticleType<?> type = options.getType();
        if (type != ParticleTypes.UNDERWATER && type != ParticleTypes.BUBBLE && type != ParticleTypes.BUBBLE_POP
            && type != ParticleTypes.BUBBLE_COLUMN_UP && type != ParticleTypes.CURRENT_DOWN) {
            return;
        }
        if (SubAir.INSTANCE.isShielded((ClientLevel) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }
}
