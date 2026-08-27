package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.crew.HoldTagged;

/**
 * Give every block entity somewhere to remember what it is for, and persist it.
 *
 * <p>Only chests and barrels ever set it -- see {@code HoldTags} -- but the field lives on the base class
 * because that is where {@code saveAdditional} and {@code loadAdditional} are ultimately implemented.
 * Verified against the mapped jar rather than assumed: {@code ChestBlockEntity} and
 * {@code BarrelBlockEntity} both override the pair, and the chain calls {@code super} at every link
 * (Chest/Barrel -> BaseContainerBlockEntity -> BlockEntity), so a TAIL injection here runs for both. Reading
 * that off {@code mappings.tiny} would have said they do NOT override them, because the tiny format records
 * a name only at its root declaration -- a trap worth remembering for the next mixin.
 *
 * <p>One int on every block entity in the world is the cost. It is a primitive field on an object that
 * already carries a level reference, a position, a block state and a component map, and it is written only
 * when a tag actually changes.
 *
 * <p>Nothing is written when the mask is 0, so the overwhelming majority of block entities -- every one that
 * is not a tagged hold -- add not a single byte to the region file.
 */
@Mixin(BlockEntity.class)
public abstract class MixinBlockEntityHoldTags implements HoldTagged {

    @Unique
    private static final String VS_EUREKA_KEY = "vs_eureka:hold_tags";

    @Unique
    private int vs_eureka$holdTagMask = 0;

    @Override
    public int vs_eureka$holdTags() {
        return this.vs_eureka$holdTagMask;
    }

    @Override
    public void vs_eureka$setHoldTags(final int mask) {
        this.vs_eureka$holdTagMask = mask;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void vs_eureka$saveHoldTags(final CompoundTag output, final HolderLookup.Provider provider, final CallbackInfo ci) {
        if (this.vs_eureka$holdTagMask != 0) {
            output.putInt(VS_EUREKA_KEY, this.vs_eureka$holdTagMask);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void vs_eureka$loadHoldTags(final CompoundTag input, final HolderLookup.Provider provider, final CallbackInfo ci) {
        this.vs_eureka$holdTagMask = input.getInt(VS_EUREKA_KEY);
    }
}
