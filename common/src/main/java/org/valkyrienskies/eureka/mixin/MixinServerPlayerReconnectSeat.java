package org.valkyrienskies.eureka.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.entity.ReconnectSeat;

/**
 * Feeds the player's save and load to {@link ReconnectSeat}. Priority 1100 so this runs after VS2's own reconnect
 * hooks on the same methods: on save it can correct what VS2 wrote, and on load VS2 has already put the player back
 * on the ship.
 */
@Mixin(value = ServerPlayer.class, priority = 1100)
public abstract class MixinServerPlayerReconnectSeat {

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void vs_eureka$saveSeatedOnShip(final CompoundTag tag, final CallbackInfo ci) {
        ReconnectSeat.onSave((ServerPlayer) (Object) this, tag);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void vs_eureka$noteShipSpot(final CompoundTag tag, final CallbackInfo ci) {
        ReconnectSeat.onLoad((ServerPlayer) (Object) this, tag);
    }
}
