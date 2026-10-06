package org.valkyrienskies.eureka.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.ship.ShipInfluence;
import org.valkyrienskies.mod.common.util.EntityDragger;

/**
 * Runs {@link ShipInfluence} just before VS2 drags entities with ships, on both sides, over the same entities VS2
 * is about to drag. This is the one hook Armada needs inside VS2 for the airborne carry; everything it changes is
 * VS2's public per-entity drag state.
 */
@Mixin(value = EntityDragger.class, remap = false)
public abstract class MixinEntityDraggerInfluence {

    @Inject(method = "dragEntitiesWithShips", at = @At("HEAD"))
    private void vs_eureka$keepAirborneCarry(final Iterable<Entity> entities, final boolean preTick,
        final CallbackInfo ci) {
        ShipInfluence.beforeDrag(entities);
    }
}
