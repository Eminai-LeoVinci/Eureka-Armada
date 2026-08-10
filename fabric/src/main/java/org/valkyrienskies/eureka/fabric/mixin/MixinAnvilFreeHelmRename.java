package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.block.ShipHelmBlock;

/**
 * Naming a ship's wheel at an anvil costs nothing.
 *
 * <p>A wheel's name is its CREW's name, and a crew is renamed for free from the crew manifest. The anvil is
 * the same job done with the block in hand, so charging for one and not the other would only mean the free
 * route is the one nobody discovers -- and a captain who has just spent 28 Hearts of the Sea on berths should
 * not also be levelling up to relabel them.
 *
 * <p>Anvil WEAR is deliberately untouched. That is the anvil's cost, not the rename's, and an anvil that never
 * chipped would be a change to the block rather than to this feature.
 *
 * <h2>Why two injections rather than just zeroing the price</h2>
 * Vanilla's {@code mayPickup} is {@code (creative || level >= cost) && cost > 0} -- a zero cost means "there
 * is no valid operation here", not "this one is free", so setting the cost to nothing on its own would leave
 * the result sitting in the slot refusing to be picked up. So the price is zeroed AND the pickup rule is
 * answered directly. With the cost at zero, vanilla's own {@code onTake} then deducts nothing and needs no
 * patching at all.
 */
@Mixin(AnvilMenu.class)
public abstract class MixinAnvilFreeHelmRename {

    @Shadow
    @Final
    private DataSlot cost;

    /**
     * Free, once the result exists.
     *
     * <p>TAIL rather than HEAD: vanilla has to finish deciding whether there is anything to make -- including
     * its own "too expensive" clamp -- before the price can be struck out. Injecting earlier would zero a
     * price that vanilla then recomputes.
     */
    @Inject(method = "createResult", at = @At("TAIL"))
    private void vs_eureka$helmRenameIsFree(final CallbackInfo ci) {
        if (!vs_eureka$isHelm()) {
            return;
        }
        this.cost.set(0);
    }

    /**
     * A free result can still be taken.
     *
     * <p>{@code hasStack} is vanilla's own answer to "is there anything in the result slot", passed in by the
     * slot itself, so it stands in exactly where {@code cost > 0} used to: it is the test for whether an
     * operation happened, which is the thing that check was really asking.
     */
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$freeResultsCanBeTaken(final Player player, final boolean hasStack,
        final CallbackInfoReturnable<Boolean> cir) {
        if (hasStack && vs_eureka$isHelm()) {
            cir.setReturnValue(true);
        }
    }

    private boolean vs_eureka$isHelm() {
        final ItemStack input = ((AnvilMenu) (Object) this).getSlot(AnvilMenu.INPUT_SLOT).getItem();
        return input.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShipHelmBlock;
    }
}
