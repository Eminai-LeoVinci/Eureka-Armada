package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.pirate.PirateShips;

/**
 * A pirate crew hand's shot flies harder and straighter.
 *
 * <p>The crew's extended shoot range ({@code PirateCombat}) is only real if the bolt can actually carry
 * it: a vanilla crossbow bolt at 3.15 velocity drops below deck height long before the 24 blocks the
 * widened goal is happy to open fire at. One hook on {@link Projectile#shoot(double, double, double,
 * float, float)} covers every launcher at once -- crossbows, bows, tridents, whatever a future undead
 * crew holds -- because they all funnel through it, and the owner is set before any of them call it.
 *
 * <p>Keyed on the crew tag, the same durable mark everything else about a pirate hand keys on. Player
 * shots, villager shots and every other mob's shots pass through untouched.
 */
@Mixin(Projectile.class)
public abstract class MixinCrewProjectileBoost {

    @ModifyVariable(method = "shoot(DDDFF)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float vs_eureka$crewShotFliesHarder(final float velocity) {
        if (!vs_eureka$isCrewShot()) {
            return velocity;
        }
        return velocity * (float) EurekaConfig.SERVER.getPirateCrewProjectileSpeedMultiplier();
    }

    @ModifyVariable(method = "shoot(DDDFF)V", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private float vs_eureka$crewShotFliesStraighter(final float inaccuracy) {
        if (!vs_eureka$isCrewShot()) {
            return inaccuracy;
        }
        // Halved, not zeroed: a crack shot still breathes, and a laser-straight pillager reads as a bug.
        return inaccuracy * 0.5f;
    }

    @Unique
    private boolean vs_eureka$isCrewShot() {
        final Projectile self = (Projectile) (Object) this;
        return self.getOwner() instanceof Mob mob && mob.getTags().contains(PirateShips.CREW_TAG);
    }
}
