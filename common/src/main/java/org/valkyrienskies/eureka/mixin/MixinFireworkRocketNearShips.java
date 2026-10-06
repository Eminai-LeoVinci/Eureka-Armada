package org.valkyrienskies.eureka.mixin;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes the elytra speed-boost glitch that happened when a firework rocket attached to
 * a gliding player drifted into VS2 ship-space coordinates as the ship's chunk-tracker
 * churned around its AABB boundary. The firework's client-side tick keeps running and
 * keeps applying its boost block to the local player, with the firework's position
 * sometimes ~28 million blocks away (ghost-ship coords) and sometimes back at normal
 * coords near the player. A single discard()/setRemoved() does not stick - the entity
 * slot gets revived by the chunk-tracker, life counter reset to 1, and the boost
 * block runs for another ~60 ticks before our cap re-fires.
 *
 * The fix is sticky: any client-side firework id we ever discard for the bug pattern
 * is remembered in a bounded LRU set, and every subsequent tick on that id is cancelled
 * at HEAD - so revival cannot run the boost block again. Entity-id reuse is rare; if
 * a real new firework ever lands on a remembered id the player just fires another.
 *
 * Initial detection triggers:
 *  - distance: firework is more than 10 blocks from the player it's attached to
 *    (ghost-ship coords are millions of blocks away - catches the bug on tick 1)
 *  - life cap: client-side life > 60 ticks (vanilla flight-3 max is 51, so 60 covers
 *    every normal firework and catches anything that lingers without ghost-coords)
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(FireworkRocketEntity.class)
public abstract class MixinFireworkRocketNearShips {

    private static final int VS_CLIENT_LIFETIME_CAP = 60;
    private static final double VS_DESYNC_DIST_SQ = 100.0;
    private static final int VS_KILL_LIST_MAX = 1024;

    private static final Set<Integer> vs_eureka$killedFireworkIds =
        Collections.synchronizedSet(new LinkedHashSet<>());

    @Shadow
    private int life;

    @Shadow
    @org.jetbrains.annotations.Nullable
    private LivingEntity attachedToEntity;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$capClientFirework(final CallbackInfo ci) {
        final FireworkRocketEntity self = (FireworkRocketEntity) (Object) this;
        if (!self.level().isClientSide()) return;

        final int id = self.getId();

        if (vs_eureka$killedFireworkIds.contains(id)) {
            ci.cancel();
            if (!self.isRemoved()) {
                self.discard();
            }
            return;
        }

        if (this.attachedToEntity == null) return;

        final LivingEntity attached = this.attachedToEntity;
        final double dx = self.getX() - attached.getX();
        final double dy = self.getY() - attached.getY();
        final double dz = self.getZ() - attached.getZ();
        final double distSq = dx * dx + dy * dy + dz * dz;

        final boolean desync = distSq > VS_DESYNC_DIST_SQ;
        final boolean overLife = this.life > VS_CLIENT_LIFETIME_CAP;

        if (!desync && !overLife) return;

        vs_eureka$rememberKilled(id);
        self.discard();
        ci.cancel();
    }

    private static void vs_eureka$rememberKilled(final int id) {
        synchronized (vs_eureka$killedFireworkIds) {
            vs_eureka$killedFireworkIds.add(id);
            while (vs_eureka$killedFireworkIds.size() > VS_KILL_LIST_MAX) {
                final Iterator<Integer> it = vs_eureka$killedFireworkIds.iterator();
                it.next();
                it.remove();
            }
        }
    }
}
