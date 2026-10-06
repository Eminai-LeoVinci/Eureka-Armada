package org.valkyrienskies.eureka.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;

/**
 * Entity light on ships.
 *
 * <p>Entities carried by a ship (players, mobs, armor stands) are WORLD entities at world positions, but a
 * ship's blocks -- and their light sources (torches, lanterns, glowstone) -- live in the far-off shipyard
 * region, NOT at the entity's world coordinates. Vanilla samples block light at the entity's world position,
 * which is empty open space: at night, with skylight 0 and no world blocks there, everything on deck renders
 * pitch black. Disassembling the ship moves the blocks back into world space, so lighting works again.
 *
 * <p>Fix: for each ship whose world AABB contains the entity, sample BLOCK light at the entity's corresponding
 * shipyard position and take the brighter of (world, ship). Block light is the channel that matters -- skylight
 * is dominated by the open-sky value at the floating world position and already behaves.
 *
 * <p>FRAME-INDEPENDENCE (the moving-ship flicker fix): vanilla feeds in the INTERPOLATED render probe
 * ({@code getLightProbePosition(partialTick)}). An entity standing still RELATIVE to a moving ship has a
 * CONSTANT ship-local position, so its ship-light sample must be frame-independent -- but the interpolated
 * probe advances smoothly between ticks while the ship transform steps once per tick, so feeding the
 * interpolated probe through any ship transform leaves a sub-tick mismatch that oscillates the sampled
 * shipyard block: a flicker that scales with ship speed. So we sample at {@code getLightProbePosition(1.0)}
 * (the current-tick logical probe, no interpolation) through the ship's current (physics) transform -- both
 * "this tick" -- so a stationary-relative entity hits the same block every frame; a walking entity still steps
 * light per tick (normal). Covers player/mob/armor-stand (all inherit getBlockLightLevel).
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererShipLight {

    @ModifyReturnValue(method = "getBlockLightLevel", at = @At("RETURN"))
    private int vs_eureka$addShipBlockLight(final int original, final Entity entity, final BlockPos worldPos) {
        // MOUNTED RIDER (e.g. piloting the Eureka helm): a ship-seat passenger is NOT carried by EntityDragger
        // (the dragger gates on vehicle == null); instead VS2's MixinGameRenderer.preRender re-anchors the
        // rider's live position to the ship's RENDER (interpolated) transform EVERY FRAME, so the world-space
        // probe path below no longer round-trips through the PHYSICS getWorldToShip -- the sampled shipyard
        // block oscillates by the sub-tick render-vs-physics delta = the original flicker, back only while
        // mounted (scales with ship speed). Sample BLOCK light directly at the seat's frame-stable ship-space
        // mount position (no transform round-trip), matching the standing case's stability. getShipMountedToData
        // is non-null ONLY for ship mounts (normal boats/horses return null -> fall through to the probe path).
        final ShipMountedToData mounted = VSGameUtilsKt.getShipMountedToData(entity, null);
        if (mounted != null) {
            final Vector3dc m = mounted.getMountPosInShip();
            return Math.max(original, entity.level().getBrightness(
                LightLayer.BLOCK, BlockPos.containing(m.x(), m.y(), m.z())));
        }

        final Vec3 probe = entity.getLightProbePosition(1.0F);
        int best = original;
        // 1.20.1 has no point overload of getShipsIntersecting; a degenerate AABB is equivalent.
        final AABBd probeBox = new AABBd(probe.x, probe.y, probe.z, probe.x, probe.y, probe.z);
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(entity.level(), probeBox)) {
            final Vector3d shipPos = new Vector3d(probe.x, probe.y, probe.z);
            ship.getTransform().getWorldToShip().transformPosition(shipPos);
            final int shipBlockLight = entity.level().getBrightness(
                LightLayer.BLOCK, BlockPos.containing(shipPos.x(), shipPos.y(), shipPos.z()));
            if (shipBlockLight > best) {
                best = shipBlockLight;
            }
        }
        return best;
    }
}
