package org.valkyrienskies.eureka.fabric.mixin;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.armada.SubAir;

/**
 * Makes the ocean stop existing for anything standing in a submarine's sub air.
 *
 * <p>The three suppressed methods are the whole of vanilla's "am I wet" state. {@code updateFluidOnEyes} drives
 * the screen overlay, the oxygen meter and drowning; {@code updateSwimming} drives the swim pose; and
 * {@code updateInWaterStateAndDoWaterCurrentPushing} drives buoyancy, drag and current pushing. Cancel all three
 * and the entity walks its dry deck exactly as it would on land.
 *
 * <p>VS2 does the same thing for its own "sealed area" pockets, but every one of its checks is gated on
 * {@code ValkyrienSkies.isConnectivityEnabled(...)} -- and turning connectivity on also arms VS2's ship splitter.
 * So this deliberately does NOT reuse VS2's flag; it is an independent set of cancels driven by the block. The
 * two compose safely: each injector only ever cancels, so whichever runs first, the union is what happens.
 *
 * <p>The per-tick cost is two block reads for anything not in water at all. Only something the game is about to
 * treat as wet pays for a ship lookup.
 */
@Mixin(Entity.class)
public abstract class MixinEntitySubAir {

    @Unique
    private boolean vs_eureka$inSubAir = false;

    @Shadow
    public Level level;

    @Shadow
    protected boolean wasTouchingWater;

    @Shadow
    protected boolean wasEyeInWater;

    @Shadow
    private Set<TagKey<Fluid>> fluidOnEyes;

    @Shadow
    public abstract void setSwimming(boolean swimming);

    /**
     * Decide once per tick, before {@code baseTick} runs any of the water updates it drives.
     *
     * <p>Evaluated at BOTH the eye and the feet because they answer different questions -- the eye decides the
     * overlay and breathing, the feet decide footing -- and a hull is either dry or it isn't, so either being in
     * sub air is enough.
     */
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void vs_eureka$updateSubAir(final CallbackInfo ci) {
        vs_eureka$inSubAir = false;
        final Entity self = (Entity) (Object) this;
        final Level lvl = this.level;
        if (lvl == null || self.isRemoved()) {
            return;
        }
        final double eyeY = self.getEyeY();
        // Cheap gate: with no water at the feet or the eye there is nothing to suppress, and no ship lookup.
        if (lvl.getFluidState(self.blockPosition()).isEmpty()
            && lvl.getFluidState(BlockPos.containing(self.getX(), eyeY, self.getZ())).isEmpty()) {
            return;
        }
        vs_eureka$inSubAir = SubAir.INSTANCE.isShielded(lvl, self.getX(), eyeY, self.getZ())
            || SubAir.INSTANCE.isShielded(lvl, self.getX(), self.getY(), self.getZ());
    }

    /** No eye-in-water: kills the blue overlay, the oxygen meter and drowning damage. */
    @Inject(method = "updateFluidOnEyes", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$noFluidOnEyes(final CallbackInfo ci) {
        if (!vs_eureka$inSubAir) {
            return;
        }
        // Vanilla clears this at the top of the method it is about to skip; leaving last tick's entry would
        // keep the entity "eye in water" forever.
        this.wasEyeInWater = false;
        this.fluidOnEyes.clear();
        ci.cancel();
    }

    /** No swim pose -- stand, walk and run as if on land. */
    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$noSwimming(final CallbackInfo ci) {
        if (!vs_eureka$inSubAir) {
            return;
        }
        this.wasTouchingWater = false;
        this.setSwimming(false);
        ci.cancel();
    }

    /** No buoyancy, drag or current pushing -- gravity behaves exactly as it does in air. */
    @Inject(method = "updateInWaterStateAndDoWaterCurrentPushing", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$noWaterState(final CallbackInfo ci) {
        if (!vs_eureka$inSubAir) {
            return;
        }
        this.wasTouchingWater = false;
        ci.cancel();
    }
}
