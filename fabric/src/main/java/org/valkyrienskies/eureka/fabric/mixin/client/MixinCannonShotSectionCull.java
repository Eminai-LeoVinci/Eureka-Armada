package org.valkyrienskies.eureka.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.cannon.CannonShot;

/**
 * The extraction pipeline refuses to draw an entity whose render SECTION was never compiled --
 * extractVisibleEntities asks isSectionCompiledAndVisible AFTER shouldRender has already said yes.
 * Sensible for a cow standing in unloaded space; wrong for a cannonball the server is deliberately
 * tracking beyond the viewer's loaded chunks (MixinCannonShotTracking), which blinked out at exactly
 * the loaded-chunk line however far the render-distance knob was turned up. Lifted for CannonShot
 * alone; the shot's own distance gate (/vs cannonball-render-distance) has already had its say.
 *
 * The gated entity is taken from the blockPosition() call feeding the gate rather than from a local
 * capture: the local table of a renderer method is a moving target, where "the entity whose position
 * the gate is about to test" is the shape of the check itself.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinCannonShotSectionCull {

    @Unique
    private Entity vs_eureka$sectionGated;

    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos vs_eureka$rememberGatedEntity(final Entity instance, final Operation<BlockPos> original) {
        this.vs_eureka$sectionGated = instance;
        return original.call(instance);
    }

    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean vs_eureka$drawCannonballsBeyondCompiledSections(
        final LevelRenderer instance, final BlockPos pos, final Operation<Boolean> original
    ) {
        final Entity gated = this.vs_eureka$sectionGated;
        this.vs_eureka$sectionGated = null;
        if (gated instanceof CannonShot) return true;
        return original.call(instance, pos);
    }
}
