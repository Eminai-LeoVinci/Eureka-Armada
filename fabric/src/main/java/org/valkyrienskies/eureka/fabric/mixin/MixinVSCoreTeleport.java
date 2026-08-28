package org.valkyrienskies.eureka.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.world.ServerShipWorld;
import org.valkyrienskies.core.internal.ShipTeleportData;
import org.valkyrienskies.eureka.armada.ArmadaTeleport;

/**
 * Makes {@code /vs teleport} on an armada's parent carry the whole formation instead of pulling the parent out
 * of it. See {@link ArmadaTeleport} for the move itself.
 *
 * <p>Targets vs-core's {@code VSCoreImpl} because it is the single funnel: {@code VSCoreServerImpl} and
 * {@code VSCoreClientImpl} both hold one of these and delegate {@code teleportShip} straight to it, so hooking
 * this one class covers every teleport on either side (verified with {@code javap -c} against the shipped VS2
 * jar, which carries the class unrelocated). Targeted by NAME so nothing has to compile against vs-core's impl
 * artifact; the parameter types are all api/internal, which Eureka already builds against.
 *
 * <p>{@code remap = false} because none of these are Minecraft types.
 */
@Mixin(targets = "org.valkyrienskies.core.impl.program.VSCoreImpl", remap = false)
public abstract class MixinVSCoreTeleport {

    /**
     * HEAD, so the parent has NOT moved yet -- the children are placed from its old pose and the teleport data
     * says where it is about to land, which is what makes the formation arithmetic a plain rigid transform.
     */
    @Inject(method = "teleportShip", at = @At("HEAD"))
    private void vs_eureka$carryArmadaWithParent(final ServerShipWorld world, final ServerShip ship,
        final ShipTeleportData teleportData, final CallbackInfo ci) {
        ArmadaTeleport.carryChildren(world, ship, teleportData);
    }
}
