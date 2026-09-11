package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.CameraPositionDuck;
import org.valkyrienskies.eureka.fabric.client.SubAirClient;
import org.valkyrienskies.eureka.fabric.client.SubAirWindowHook;

/**
 * Stops the underwater look from following the camera into a submarine -- under shaders as well as vanilla.
 *
 * <p>Vanilla's blue overlay and the oxygen meter read {@code Entity.fluidOnEyes}, which the entity-side sub air
 * mixin already clears. Iris does NOT: its {@code isEyeInWater} uniform is
 * {@code client.gameRenderer.getMainCamera().getFluidInCamera()} (verified by disassembling
 * {@code CommonUniforms}), so every shaderpack kept drawing water fog inside a perfectly dry hull. One method,
 * two consumers -- patching it here fixes vanilla fog and every shaderpack at once.
 *
 * <p>Because the test is on the CAMERA rather than the player, the behaviour the ship third-person view wants
 * falls out for free: pull the camera out through the hull into open water and it reads water again, so the
 * screen goes blue for the camera exactly when it should, and clears the moment it rises out.
 *
 * <p>VS2 declares a config for this (`VSGameConfig.CLIENT.sealedAreaCameraGracePeriod`) that nothing in the
 * codebase reads -- the camera half of its sealed-area feature was designed and never wired. No grace period is
 * used here: a block test flips exactly at the hull surface, so there is nothing to smooth over.
 */
@Mixin(Camera.class)
public abstract class MixinCameraSubAir implements CameraPositionDuck {

    // The FIELD, not a getter: 1.21.11's Camera has no getPosition(). (VS2's own fluid_camera_fix mixin still
    // shadows that getter, so it fails to apply on this version -- worth knowing before trusting it.)
    @Shadow
    private Vec3 position;

    /** The only way to read the camera position on 1.21.11 -- see {@link CameraPositionDuck}. */
    @Override
    public Vec3 vs_eureka$cameraPosition() {
        return this.position;
    }

    @Inject(method = "getFluidInCamera", at = @At("RETURN"), cancellable = true)
    private void vs_eureka$noFluidInSubAir(final CallbackInfoReturnable<FogType> cir) {
        // Injected at RETURN so this sees the final answer, including the ship-space fluid VS2's own mixin adds.
        if (cir.getReturnValue() == FogType.NONE) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        final Vec3 pos = this.position;
        if (pos == null) {
            return;
        }
        // Judged in the RENDER pose of each hull (the one it is drawn in), not the ticked physics pose: the
        // latter trails by up to a tick of movement, and a moving sub read its own camera as outside for a frame
        // -- one frame is all a shaderpack needs to start its several-second "entered the water" transition.
        if (!SubAirClient.cameraInSubAir(level, pos)) {
            return;
        }
        // Under a shaderpack it is the pack, not vanilla, that draws the water look, and it takes its cue from
        // this one call. The interior can only read as air once the windows read as water (VS2's route through
        // the pack's water program plus the window hook -- SubAirWindowHook.active()); until then, or by choice
        // (the legacy toggle), the pack is left believing the eye is in water: the sea past the windows renders
        // as sea at the price of its tint reaching the interior. Vanilla's overlay and oxygen meter read the
        // entity, not the camera, so they stay off either way; vanilla's fog gets its own answer from
        // MixinFogRendererSubAir.
        if (SubAirClient.shaderPackInUse()
            && (EurekaConfig.CLIENT.getSubmarineShaderLegacyUnderwater() || !SubAirWindowHook.active())) {
            return;
        }
        cir.setReturnValue(FogType.NONE);
    }
}
