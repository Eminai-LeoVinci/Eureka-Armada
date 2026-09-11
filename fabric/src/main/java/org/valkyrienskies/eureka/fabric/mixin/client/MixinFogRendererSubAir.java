package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.SubAirClient;

/**
 * Gives the fog its own opinion of where the camera is.
 *
 * <p>The camera says {@code NONE} inside a hull (see {@code MixinCameraSubAir}) so that the blue overlay, the
 * FOV squeeze and -- unless asked otherwise -- the shaderpack all treat the interior as air. Left at that, the
 * fog renderer picks the atmospheric fog and the sea outside the windows renders crisp and sky-blue, like the
 * surface. The July screenshots. So the fog TYPE alone is steered back to water whenever the camera is in sub
 * air with world water around it; the water fog environment then supplies the biome's navy and the sub-air
 * water-fog hook pushes its start out past the hull, which is what keeps the interior clear.
 */
@Mixin(FogRenderer.class)
public abstract class MixinFogRendererSubAir {

    @Inject(method = "getFogType", at = @At("RETURN"), cancellable = true)
    private void vs_eureka$fogSeesTheSea(final Camera camera, final CallbackInfoReturnable<FogType> cir) {
        final boolean pocket = EurekaConfig.CLIENT.getSubmarineExteriorFog()
            && SubAirClient.cameraSubmergedInPocket();
        // Known to the water-fog hook either way: in the legacy shader look the camera itself answers WATER,
        // vanilla's water fog runs, and the pack reads its start/end -- which should still be the tuned ones.
        SubAirClient.setFogPocket(pocket);
        if (!pocket) {
            return;
        }
        // The fog TYPE is steered for vanilla rendering only. A shaderpack draws its own fog and reads vanilla's
        // fog colour as a hint; handing it the water colour while it believes the eye is in air would tint the
        // cabin in some packs.
        if (SubAirClient.shaderPackInUse()) {
            return;
        }
        final FogType type = cir.getReturnValue();
        if (type == FogType.NONE || type == FogType.ATMOSPHERIC) {
            cir.setReturnValue(FogType.WATER);
        }
    }
}
