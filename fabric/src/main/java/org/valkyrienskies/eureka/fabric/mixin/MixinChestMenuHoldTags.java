package org.valkyrienskies.eureka.fabric.mixin;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.crew.HoldLabelSync;
import org.valkyrienskies.eureka.crew.HoldOpenSnapshot;
import org.valkyrienskies.eureka.crew.HoldRetag;

/**
 * Watch what a captain leaves in a chest or barrel, and tag the box accordingly.
 *
 * <p>{@code ChestMenu} is the one seam that covers everything worth covering: single chests, double chests
 * and barrels all open one, so this is a single hook rather than one per block. (It also overrides
 * {@code removed}, so both halves of the job live on the same class -- worth checking by decompiling rather
 * than reading {@code mappings.tiny}, which lists a method only at its root declaration and would have said
 * otherwise.)
 *
 * <p>The two injections are a pair and neither works alone: the constructor records what was in the boxes
 * when the lid came up, and {@code removed} compares that against what is in them now. See
 * {@link HoldOpenSnapshot} for why a bare recompute-on-close is wrong.
 *
 * <p>The public constructor is the one that takes a real {@code Container}; the private one builds a
 * {@code SimpleContainer} for the client's copy of the menu and has no block entity to tag. Both sides are
 * additionally gated on {@code ServerLevel}, because tags are server state and the client's mirror of a
 * chest must never write any.
 */
@Mixin(ChestMenu.class)
public abstract class MixinChestMenuHoldTags implements HoldOpenSnapshot {

    @Shadow
    public abstract Container getContainer();

    @Unique
    private Map<BlockPos, Integer> vs_eureka$openTags;

    @Override
    public Map<BlockPos, Integer> vs_eureka$openSnapshot() {
        return this.vs_eureka$openTags;
    }

    @Override
    public void vs_eureka$setOpenSnapshot(final Map<BlockPos, Integer> snapshot) {
        this.vs_eureka$openTags = snapshot;
    }

    @Inject(
        method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;"
            + "Lnet/minecraft/world/Container;I)V",
        at = @At("RETURN")
    )
    private void vs_eureka$captureOpenTags(final MenuType<?> type, final int containerId,
        final Inventory inventory, final Container container, final int rows, final CallbackInfo ci) {
        if (inventory.player.level() instanceof final ServerLevel level) {
            this.vs_eureka$openTags = HoldRetag.INSTANCE.snapshot(level, container);
            if (inventory.player instanceof final ServerPlayer player) {
                // The number is derived from the whole ship's geometry, which the client does not have, so
                // it has to be told. Open time is the only moment it can change from the viewer's side.
                HoldLabelSync.INSTANCE.push(level, player, containerId, container);
            }
        }
    }

    // Closing a screen used to re-read the contents and rewrite the tags from them. That is gone: what
    // a hold is FOR is now a decision a captain makes with the checkboxes on the screen, and decisions do
    // not evaporate because somebody emptied the box. See HoldRetag.toggle for the one path that moves a
    // tag now.
}
