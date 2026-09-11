package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.SubAirClient;

/**
 * The underwater fog, as seen from inside a dry hull: the sea beyond the glass goes navy, the interior stays
 * clear.
 *
 * <p>Vanilla's water fog starts behind the eye and ends at a distance that ramps up with time spent under
 * water -- which is never, in sub air, so it would sit at its murkiest. Both ends are replaced with the client
 * config's: the start is pushed out to roughly the size of the interior, so nothing inside the hull is fogged,
 * and the end is brought in so the sea past the windows reads as sea. Fog is by distance, so there is a clear
 * zone in the water immediately outside the glass the width of the start; that is the price of doing this
 * without a per-pixel mask, and the two numbers are there to be tuned.
 */
@Mixin(WaterFogEnvironment.class)
public abstract class MixinWaterFogEnvironmentSubAir {

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void vs_eureka$pocketFog(final FogData data, final Camera camera, final ClientLevel level,
        final float renderDistance, final DeltaTracker delta, final CallbackInfo ci) {
        if (!SubAirClient.fogPocket()) {
            return;
        }
        final float start = (float) Math.max(0.0, EurekaConfig.CLIENT.getSubmarineFogStart());
        final float end = (float) Math.max(start + 1.0, EurekaConfig.CLIENT.getSubmarineFogEnd());
        data.environmentalStart = start;
        data.environmentalEnd = end;
        data.skyEnd = end;
        data.cloudEnd = end;
    }
}
