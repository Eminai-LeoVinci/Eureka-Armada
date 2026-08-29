package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.ClientCrewMarkers;

/**
 * The half of "show the crew's names" that 1.21.1 and 1.20.1 need and 1.21.11 does not.
 *
 * <p>{@link MixinEntityRendererCrewNameTag} answers yes for a marked crew member in
 * {@code EntityRenderer.shouldShowName}, which is the whole story on the modern branch: its render-state
 * pipeline asks that method and no other. On these branches a villager is drawn through {@code MobRenderer},
 * and MobRenderer keeps its own override:
 *
 * <pre>
 *   super.shouldShowName(mob)
 *       &amp;&amp; (mob.shouldShowName()
 *           || (mob.hasCustomName() &amp;&amp; mob == entityRenderDispatcher.crosshairPickEntity))
 * </pre>
 *
 * <p>Crew wear a custom name but are not flagged to show it always, so the second half was true for exactly
 * one villager -- the one under the crosshair. Answering in the base method could never help: the base method
 * IS the {@code super} call on the first line, so a true there was simply ANDed away on the second. Measured
 * rather than reasoned about, in the end: a counter at the base gate read 3016 true answers in a second
 * against zero name tags drawn, which is only possible if something downstream of it was refusing.
 *
 * <p>So the answer is given at the class that actually decides. Marked, and inside the configured plate
 * range, gets a name; everything else falls through to vanilla's rule untouched, crosshair behaviour and all.
 */
@Mixin(MobRenderer.class)
public class MixinMobRendererCrewNameTag<T extends Mob> {

    @Inject(
        method = "shouldShowName(Lnet/minecraft/world/entity/Mob;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void vs_eureka$plateTheCrew(final T entity, final CallbackInfoReturnable<Boolean> cir) {
        if (!ClientCrewMarkers.INSTANCE.contains(entity.getId())) {
            return;
        }
        final double range = EurekaConfig.CLIENT.getCrewNameplateRange();
        final Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        cir.setReturnValue(entity.distanceToSqr(cam) <= range * range);
    }
}
