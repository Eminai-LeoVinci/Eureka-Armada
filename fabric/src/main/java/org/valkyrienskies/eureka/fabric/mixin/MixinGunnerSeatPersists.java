package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

/**
 * A seat with a gunner in it saves, so the gunner survives the save.
 *
 * <p>Vanilla never saves a passenger standalone -- riders are written INSIDE their vehicle's NBT -- and a
 * {@link ShipMountingEntity} passenger seat refuses to be saved at all, deliberately: it is built for the
 * reconnect feature, its drive state is not serialized, and a reloaded one would be a dead anchor its player
 * is stuck on. Put those two rules together and a stationed gunner was saved NOWHERE: log out with a crew at
 * the guns and every villager in a seat was simply erased, leaving eyeless ghosts on the manifest.
 *
 * <p>So a passenger seat whose rider is a VILLAGER opts back into saving, and vanilla then stores the whole
 * stack the ordinary way. The dead-anchor concern does not apply to this rider: on load the seat wakes
 * driveless and inert, and {@code GunStations}' reconcile -- which re-derives every seating from the crew
 * ledger once a second -- kills it and seats the gunner on a freshly driven one. The only cost is the one
 * second the loaded seat spends parked where the ship was saved.
 *
 * <p>Controller seats (the helm's) and player-carrying passenger seats (reconnect) keep their own rules.
 */
@Mixin(ShipMountingEntity.class)
public abstract class MixinGunnerSeatPersists {

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$saveTheGunnerWithTheSeat(final CallbackInfoReturnable<Boolean> cir) {
        final ShipMountingEntity self = (ShipMountingEntity) (Object) this;
        if (self.isController()) {
            return;
        }
        for (final Entity passenger : self.getPassengers()) {
            if (passenger instanceof Villager) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
