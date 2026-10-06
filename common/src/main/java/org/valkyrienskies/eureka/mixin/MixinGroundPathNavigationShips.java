package org.valkyrienskies.eureka.mixin;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * Keeps a ship mob's path target on the deck.
 *
 * <p>Before pathfinding, {@code GroundPathNavigation.createPath(BlockPos, int)} walks an air target DOWN to the first
 * solid block, reading raw chunks rather than the pathfinding region, so the ship overlay
 * ({@link MixinPathNavigationRegionShips}) never sees it: over a deck the world below is sky, and the target sinks to
 * the ground far beneath the ship. For a mob a ship is carrying, skip that descent and hand the target straight on,
 * which is where vanilla goes next anyway, keeping vanilla's "target chunk not loaded means no path" guard. Every
 * other mob is untouched. Follows VS2's {@code aiOnShips} setting.
 *
 * <p>Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
 */
@Mixin(GroundPathNavigation.class)
public abstract class MixinGroundPathNavigationShips {

    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"), cancellable = true)
    private void vs_eureka$keepTargetOnDeck(final BlockPos target, final int accuracy,
        final CallbackInfoReturnable<Path> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips()) {
            return;
        }
        final Mob mob = ((PathNavigationMobAccessor) (Object) this).vs_eureka$getMob();
        if (!(mob instanceof final IEntityDraggingInformationProvider provider)
            || !provider.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return;
        }
        if (mob.level().getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(target.getX()), SectionPos.blockToSectionCoord(target.getZ())) == null) {
            cir.setReturnValue(null);
            return;
        }
        cir.setReturnValue(((PathNavigation) (Object) this).createPath(ImmutableSet.of(target), accuracy));
    }
}
