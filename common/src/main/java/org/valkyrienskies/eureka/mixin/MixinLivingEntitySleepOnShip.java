package org.valkyrienskies.eureka.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Villager (and any non-player mob) bed-sleep on ships -- the {@link LivingEntity} side of the
 * fix. Two distinct problems are solved here; both are scoped to in-shipyard beds / sleeping
 * non-player mobs so off-ship beds and players stay byte-identical.
 *
 * <p><b>PART 2 -- world-convert the sleep snap.</b> {@code LivingEntity.startSleeping(BlockPos)}
 * calls {@code setPosToBed(BlockPos)} which hard-teleports the sleeper to
 * {@code setPos(bedX+0.5, bedY+0.6875, bedZ+0.5)} at the bed's SHIPYARD pos -- i.e. into the
 * far shipyard (millions of blocks away). The client {@code SLEEPING_POS_ID} datawatcher
 * callback re-invokes {@code setPosToBed}, so this is also the client snap. We wrap the single
 * {@code setPos(DDD)V} call: if the bed is in a shipyard, transform the shipyard bed-center
 * through the managing ship's {@code shipToWorld} and snap to the WORLD center instead. The bed
 * BlockPos itself (used by {@code setSleepingPos} / {@code OCCUPIED} / {@code checkBedExists})
 * is NOT altered -- ship blocks genuinely live in the shipyard.
 *
 * <p><b>PART 3 -- stop the sleeping mob from falling.</b> Verified via javap: a sleeping PLAYER
 * does not fall because the SERVER never simulates a player's movement
 * ({@code isLocalInstanceAuthoritative()} is false for the client-authoritative player, so
 * {@code aiStep}'s {@code canSimulateMovement() && isEffectiveAi()} gate skips {@code travel()});
 * the locally-authoritative client suppresses its own movement while sleeping.
 * {@code LivingEntity.isImmobile()} is read in {@code aiStep} ONLY to zero {@code xxa/zza/jumping}
 * and skip {@code serverAiStep()} -- it does NOT gate {@code travel()} and does NOT suppress
 * gravity. A server-side villager IS locally authoritative, so {@code travel() -> travelInAir()}
 * unconditionally subtracts {@code getEffectiveGravity()} from deltaMovement and {@code move()}
 * applies it, free-falling the villager out of the (shipyard-blocked, world-air-below) bed within
 * a few ticks. Therefore {@code isImmobile} alone is INSUFFICIENT (it would not stop the fall).
 *
 * <p>We instead suppress the fall at its source: cancel {@code travel(Vec3)} at HEAD and zero
 * deltaMovement whenever a non-player mob is sleeping. {@code travel} is the sole umbrella for
 * every {@code move()} call in {@link LivingEntity}, so cancelling it stops both gravity
 * accumulation and the positional consume; the mob holds station. The existing
 * {@code drag_standing_mobs} foot-probe (foot at {@code minY-0.5} lands on the bed ship block)
 * re-stamps {@code lastShipStoodOn} and {@code EntityDragger} re-anchors the mob via {@code setPos}
 * each tick, so the still mob rides the moving bed. Zeroing deltaMovement composes cleanly with
 * that re-anchor (no leftover velocity to fight). Non-sleeping behavior is untouched.
 *
 * <p>1.20.1: all three targets ({@code setPosToBed}'s inner {@code setPos(DDD)V},
 * {@code method_18404}'s inner {@code setPos(DDD)V}, and {@code travel(Vec3)}) keep the exact same
 * names/owners/descriptors as 1.21.11 (verified via javap), so this is a straight port.</p>
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntitySleepOnShip {

    // PART 2 ------------------------------------------------------------------------------------

    @WrapOperation(
        method = "setPosToBed",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setPos(DDD)V"
        ),
        require = 1
    )
    private void vs_eureka$setPosToBedOnShip(
        final LivingEntity self, final double x, final double y, final double z,
        final Operation<Void> original,
        @Local(argsOnly = true) final BlockPos bedPos) {

        final Level level = self.level();
        if (!VSGameUtilsKt.isBlockInShipyard(level, bedPos)) {
            // Off-ship / vanilla bed -- byte-identical.
            original.call(self, x, y, z);
            return;
        }
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, bedPos);
        if (ship == null) {
            // In the shipyard region but no managing ship (deleted ship); don't teleport the
            // mob into stale shipyard coords -- leave it where it is by skipping the snap.
            return;
        }
        // x/y/z are the shipyard bed center (bedX+0.5, bedY+0.6875, bedZ+0.5); map to world.
        final Vector3dc worldPos = ship.getTransform().getShipToWorld()
            .transformPosition(new Vector3d(x, y, z), new Vector3d());
        original.call(self, worldPos.x(), worldPos.y(), worldPos.z());
    }

    // PART 2b -----------------------------------------------------------------------------------
    //
    // World-convert the wake-from-bed stand-up snap. PART 2 only covers setPosToBed (the SLEEP
    // snap); the WAKE snap lives in a different code path that setPosToBed never touches.
    //
    // Verified via javap on the 1.20.1 merged jar: LivingEntity.stopSleeping() does
    //   getSleepingPos().filter(...).ifPresent(this::method_18404)
    // where the ifPresent consumer is the private synthetic helper
    //   private void method_18404(BlockPos) (descriptor (Lnet/minecraft/core/BlockPos;)V) -- the
    // SAME intermediary name as 1.21.11. method_18404 calls
    //   BedBlock.findStandUpPosition(EntityType, CollisionGetter, BlockPos, Direction, float)
    //     .orElseGet(() -> Vec3.atBottomCenterOf(...))   // the bed is shipyard-pos
    // and snaps via a SINGLE setPos(DDD)V (invoke owner LivingEntity, confirmed via the constant
    // pool) at the resulting Vec3 -- which is still SHIPYARD space. That dumps a woken ship
    // villager onto the raw shipyard coordinate (~-28e6), where chunks are only BLOCK_TICKING
    // (never ENTITY_TICKING): the mob stops ticking -> frozen/floating/un-interactable
    // client-server desync that a reload heals. (Players get the equivalent world-convert in
    // feature/bed_fix/MixinServerPlayer; mobs had no equivalent.)
    //
    // We wrap that single setPos(DDD)V. We only convert POSITION (the desync-dissolving part);
    // the yaw computed from a shipyard-space facing is left unconverted (purely cosmetic). The
    // bed BlockPos / OCCUPIED / sleeping-pos reads are untouched (ship blocks genuinely live in
    // the shipyard).
    @WrapOperation(
        method = "method_18404",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setPos(DDD)V"
        ),
        require = 1
    )
    private void vs_eureka$worldConvertWakeStandup(
        final LivingEntity instance, final double x, final double y, final double z,
        final Operation<Void> original) {

        if (instance.level().isClientSide() || instance instanceof Player) {
            // Players have their own respawn/wake world-convert in MixinServerPlayer; the client
            // side is driven by datawatcher, not this server path. Byte-identical for both.
            original.call(instance, x, y, z);
            return;
        }
        final Level level = instance.level();
        final BlockPos standPos = BlockPos.containing(x, y, z);
        if (!VSGameUtilsKt.isBlockInShipyard(level, standPos)) {
            // Off-ship / vanilla bed -- byte-identical.
            original.call(instance, x, y, z);
            return;
        }
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, standPos);
        if (ship == null) {
            // In the shipyard region but no managing ship (deleted ship); mirror PART 2 /
            // MixinServerPlayer null-ship handling -- do NOT snap the mob into stale shipyard
            // coords. Skip the snap and leave it where it is.
            return;
        }
        // x/y/z are the shipyard-space stand-up Vec3 (from findStandUpPosition / the
        // atBottomCenterOf fallback); map to world via the SAME transform PART 2 uses.
        final Vector3dc worldPos = ship.getTransform().getShipToWorld()
            .transformPosition(new Vector3d(x, y, z), new Vector3d());
        original.call(instance, worldPos.x(), worldPos.y(), worldPos.z());
    }

    // PART 3 ------------------------------------------------------------------------------------

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void vs_eureka$holdSleepingMobStill(final Vec3 input, final CallbackInfo ci) {
        final LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) {
            // Players are never server-simulated here and have their own client-side sleep
            // immobilization; leave them byte-identical.
            return;
        }
        if (!self.isSleeping()) {
            return;
        }
        // Sleeping non-player mob: zero velocity and skip travel so gravity/move never run.
        // EntityDragger's per-tick setPos re-anchor (composes with zero deltaMovement) carries
        // it with the moving bed.
        //
        // travel() is also where the walk animation is updated (it ends in calculateEntityAnimation), so skipping it
        // froze a villager's legs mid-stride if he lay down walking: the renderer blends this tick's leg speed with
        // last tick's, and with the two stuck apart the legs flickered every frame until the villager was reloaded.
        // Settle both to zero here instead (see MixinLivingEntityWalkOnShip for why update(0, 1)).
        self.walkAnimation.update(0.0F, 1.0F);
        self.setDeltaMovement(Vec3.ZERO);
        ci.cancel();
    }
}
