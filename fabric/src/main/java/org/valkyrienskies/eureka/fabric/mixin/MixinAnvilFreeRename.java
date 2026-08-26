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
import org.valkyrienskies.eureka.blueprint.Blueprint;

/**
 * Naming a ship's wheel or a set of plans at an anvil costs nothing.
 *
 * <p>A wheel's name is its SHIP's name -- the master wheel names whatever hull it assembles -- and a ship is
 * renamed for free from the helm menu. A blueprint's
 * name is what its plans will be FILED under, and that is renamed for free from the page itself. The anvil is
 * the same job done with the thing in hand, so charging for one route and not the other would only mean the
 * free one is the route nobody discovers -- and a captain who has just spent 28 Hearts of the Sea on berths,
 * or a blank blueprint on a copy, should not also be levelling up to tell two of them apart.
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
public abstract class MixinAnvilFreeRename {

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
    private void vs_eureka$renameIsFree(final CallbackInfo ci) {
        if (!vs_eureka$isFreeToName()) {
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
        if (hasStack && vs_eureka$isFreeToName()) {
            cir.setReturnValue(true);
        }
    }

    private boolean vs_eureka$isFreeToName() {
        final ItemStack input = ((AnvilMenu) (Object) this).getSlot(AnvilMenu.INPUT_SLOT).getItem();
        if (input.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShipHelmBlock) {
            return true;
        }
        // A DRAFTED blueprint. read() answers null for anything that is not one -- the page rides in the
        // item's own component -- so this needs no separate item check, and a BLANK page is correctly
        // excluded: it describes no ship, so naming one is ordinary labelling at the ordinary price.
        return Blueprint.INSTANCE.read(input) != null;
    }
}
