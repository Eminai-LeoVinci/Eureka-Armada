package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.pirate.PirateHelm;

/**
 * A raider's ship is breakable, but she is not a quarry.
 *
 * <p>You can chop a hole in a pirate hull with an axe and climb through it -- that is how a player with no
 * ship of their own gets aboard, and it should stay that way. What you cannot do is take her apart for
 * parts: while ANY pirate wheel still stands, black hub or white, nothing broken on her drops anything. Her
 * planking, her balloons, her engines and her guns all break and all give nothing.
 *
 * <p>Her chests are untouched by this. Looting is opening, not breaking, so a raid you carry out and run
 * from works exactly as before -- which is the difference between plundering a ship and dismantling one.
 *
 * <p>Break the wheel and the rule lifts in the same instant. The wheel IS the fight; what was a fortress
 * becomes a prize, and everything aboard can then be claimed by hand.
 *
 * <p>All three {@code dropResources} overloads are covered rather than only the one a pickaxe uses, because
 * "nothing drops" has to be true however the block came apart -- a neighbour update knocking a torch off her
 * wall, a piston, water. The one path deliberately NOT routed through here is the cannon's own magazine
 * spill, which {@code CannonBlock.playerWillDestroy} pours out directly; that has its own rule, because a
 * conquered gun is meant to yield a raider's token four powder and two shot rather than her whole locker.
 */
@Mixin(Block.class)
public class MixinBlockPirateDrops {

    @Inject(
        method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void vs_eureka$noPirateSalvagePlain(final BlockState state, final Level level,
        final BlockPos pos, final CallbackInfo ci) {
        if (PirateHelm.INSTANCE.dropsSuppressedAt(level, pos)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void vs_eureka$noPirateSalvageWithBe(final BlockState state, final LevelAccessor level,
        final BlockPos pos, final BlockEntity blockEntity, final CallbackInfo ci) {
        if (PirateHelm.INSTANCE.dropsSuppressedAt(level, pos)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/item/ItemStack;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void vs_eureka$noPirateSalvageMined(final BlockState state, final Level level,
        final BlockPos pos, final BlockEntity blockEntity, final Entity breaker, final ItemStack tool,
        final CallbackInfo ci) {
        if (PirateHelm.INSTANCE.dropsSuppressedAt(level, pos)) {
            ci.cancel();
        }
    }
}
