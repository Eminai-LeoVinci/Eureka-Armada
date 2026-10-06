package org.valkyrienskies.eureka.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.eureka.ship.ShipSpawnGrace;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

/**
 * Starts a new ship's spawn grace (see {@link ShipSpawnGrace}) the moment VS2's assembly creates it, before any blocks
 * move: player movement is still being handled while the assembly waits on chunk work, so marking it afterwards
 * would be too late for the player it is meant to spare.
 *
 * <p>{@code remap = false}: VS2's own method and a vs-core call, no Minecraft names to map.
 */
@Mixin(value = ShipAssembler.class, remap = false)
public abstract class MixinShipAssemblerSpawnGrace {

    @ModifyExpressionValue(
        method = "assembleToShipFull",
        at = @At(value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/world/VsiServerShipWorld;createNewShipAtBlock(Lorg/joml/Vector3ic;ZDLjava/lang/String;)Lorg/valkyrienskies/core/api/ships/ServerShip;")
    )
    private static ServerShip vs_eureka$graceNewShip(final ServerShip ship,
        @Local(argsOnly = true) final ServerLevel level) {
        ShipSpawnGrace.mark(ship.getId(), level.getGameTime());
        return ship;
    }
}
