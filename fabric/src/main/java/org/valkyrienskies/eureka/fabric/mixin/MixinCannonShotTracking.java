package org.valkyrienskies.eureka.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.cannon.CannonShot;

/**
 * A cannonball is visible as far as its own tracking range reaches, not as far as the viewer happens to
 * have their render distance set.
 *
 * Vanilla gates entity tracking twice in {@code TrackedEntity.updatePlayer}: the pairing distance is
 * {@code min(getEffectiveRange(), viewDistance * 16)}, and the entity's chunk must be one the player is
 * being sent. Both are sensible for sheep; neither is sensible for an artillery shell watched from a
 * Voxy-drawn ridge at render distance 3, where the shot blinked out exactly at the loaded-chunk line --
 * which is where the interesting half of an arc begins. Both gates are lifted for {@link CannonShot}
 * alone: the shot's own clientTrackingRange (EurekaEntities) stays the hard ceiling, and the client's
 * draw cull (/vs cannonball-render-distance) stays the player's knob.
 *
 * Past the client's loaded chunks the shot's client copy no longer self-ticks, so its motion there rides
 * the server's per-tick move packets -- CannonShot inherits Entity's snap-to lerp, so that segment simply
 * follows the wire. Server-side simulation distance still bounds how far the FLIGHT itself is ticked;
 * this mixin only stops the viewer's render distance from hiding a flight the server is already flying.
 */
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class MixinCannonShotTracking {

    @Shadow
    @Final
    Entity entity;

    @Shadow
    abstract int getEffectiveRange();

    @WrapOperation(
        method = "updatePlayer",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int vs_eureka$uncapCannonballPairingRange(final int a, final int b, final Operation<Integer> original) {
        if (this.entity instanceof CannonShot) return this.getEffectiveRange();
        return original.call(a, b);
    }

    // (1.20.1 has no ChunkMap.isChunkTracked gate in updatePlayer -- the range lift above is the whole
    // fix here; the chunk-sent check that 1.21.1 also had to widen simply does not exist yet.)
}
