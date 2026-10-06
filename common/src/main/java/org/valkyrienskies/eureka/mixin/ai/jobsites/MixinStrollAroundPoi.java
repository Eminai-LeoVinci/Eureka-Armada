package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.StrollAroundPoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * The wander that keeps a working villager alive-looking, for a job site aboard a ship.
 *
 * <p>During work hours this is what scatters a villager around its workstation -- an eight-block hop every
 * couple hundred ticks. It only runs while the villager is already NEAR the site, and that gate compared the
 * job site's raw shipyard position against the villager's world position, which aboard a ship is never within
 * anything. So the gate never opened, the villager never strolled, and a crew stood frozen wherever the
 * approach had dropped them -- which is on the workstation itself. This, not any pull toward the helm, is the
 * pile-up: the pull behaviours were translated long ago, and what remained untranslated was precisely the set
 * of behaviours whose whole job is to disperse the crowd again.
 *
 * <p>Patched at the source like {@link MixinSetWalkTargetFromBlockMemory}: everything positional in the
 * trigger derives from {@code GlobalPos.pos()} (here it is only the gate -- the wander target itself is
 * mob-relative), so one conversion covers the method however it evolves.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(StrollAroundPoi.class)
public class MixinStrollAroundPoi {

    @WrapOperation(
        method = "method_47152",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private static BlockPos valkyrienskies$strollNearTheWorldPosition(final GlobalPos site,
        final Operation<BlockPos> original, @Local(argsOnly = true) final ServerLevel level) {
        return ShipJobSites.toWorld(level, original.call(site));
    }
}
