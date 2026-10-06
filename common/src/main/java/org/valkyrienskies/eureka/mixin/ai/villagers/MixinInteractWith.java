package org.valkyrienskies.eureka.mixin.ai.villagers;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.InteractWith;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Reduce villager-to-villager socializing (the idle face-to-face "gather") for villagers ON SHIPS
 * ONLY. Native overworld villagers are byte-identical -- the gate below early-returns whenever the
 * initiator is not currently being dragged by a ship, so vanilla {@code canInteract} runs unchanged.
 *
 * <p><b>Lever.</b> The gather is driven by the vanilla idle behavior
 * {@code InteractWith.of(EntityType.VILLAGER, INTERACTION_TARGET)}: it scans for the nearest
 * villager that passes the private static {@code canInteract} helper ({@code method_46959}) and, on
 * success, sets LOOK_TARGET (face) + WALK_TARGET (approach) + INTERACTION_TARGET (gossip). If we
 * force that helper to reject every candidate, the scan finds no partner and no social targets are
 * set; the idle {@code RunOne} then spends that tick on an inert no-op (identical to vanilla's
 * "no villager nearby" case) and strolls on its other idle picks. Work / sleep / POI targeting are
 * produced by entirely separate behaviors and are untouched.
 *
 * <p><b>Rotating ~{@link #VS$SHIP_SOCIAL_PERCENT}% cohort.</b> Each villager is independently rolled
 * "social" or not for the current ~{@link #VS$SOCIAL_WINDOW_TICKS}-tick window via a UUID-seeded
 * hash, so roughly that fraction is eligible at any moment and which villagers rotate between
 * windows (rather than the same villagers being permanent chatterboxes forever).
 *
 * <p><b>Two-sided gate.</b> Gating only the initiator lets an eligible villager lock onto an
 * ineligible neighbour and drag it into a visible face-up, inflating the realized gather fraction.
 * So a pairing requires BOTH the initiator and a villager candidate to be in the cohort. The
 * realized visible fraction therefore tends to sit at or a little below
 * {@link #VS$SHIP_SOCIAL_PERCENT} (sparser decks undershoot); treat the constant as a tunable, not a
 * perfectly linear knob.
 *
 * <p><b>Shared-helper note.</b> {@code method_46959} is also reached by on-ship villager-initiated
 * breeding / cat walk-up, so the non-cohort fraction is mildly damped there too. Acceptable: it is
 * rotating (averages out across windows) and never blocks breeding, only paces it. Cat/animal
 * <i>initiators</i> are untouched (the gate requires the initiator to be a {@link Villager}).
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(InteractWith.class)
public class MixinInteractWith {

    /**
     * Percent of on-ship villagers eligible to socialize in any given window. 100 = vanilla,
     * 0 = no villager socializing on ships. Two-sided gating means the visible gather fraction
     * tends to sit at or slightly below this value -- tune in-world to taste. Overworld villagers
     * are never affected.
     */
    @Unique
    private static final int VS$SHIP_SOCIAL_PERCENT = 30;

    /** Length of a social-eligibility window in ticks (~30s) -- how often cohort membership rotates. */
    @Unique
    private static final long VS$SOCIAL_WINDOW_TICKS = 600L;

    @Inject(
        method = "method_46959(Lnet/minecraft/world/entity/LivingEntity;ILjava/util/function/Predicate;Lnet/minecraft/world/entity/LivingEntity;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private static void vs_eureka$dampShipSocialize(
        final LivingEntity self, final int maxDistSqr,
        final Predicate<?> userPredicate, final LivingEntity candidate,
        final CallbackInfoReturnable<Boolean> cir) {

        // Only gate villager initiators; non-villager (e.g. cat) initiators stay vanilla.
        if (!(self instanceof Villager)) {
            return;
        }
        final Level level = self.level();
        if (level.isClientSide()) {
            return;
        }
        // SHIP GATE -- the overworld byte-identity boundary. drag_standing_mobs re-stamps this true
        // every tick for a villager standing on a deck; it is permanently false for a native
        // overworld villager (lastShipStoodOn stays null). One instanceof + a few field reads, no
        // ship-world query on the off-ship hot path.
        if (!(self instanceof IEntityDraggingInformationProvider sp)
            || !sp.getDraggingInformation().isEntityBeingDraggedByAShip()) {
            return; // OFF-SHIP -> vanilla canInteract runs unchanged.
        }
        final long window = level.getGameTime() / VS$SOCIAL_WINDOW_TICKS;
        // Initiator must be in this window's social cohort...
        if (!vs_eureka$isSocialThisWindow(self.getUUID(), window)) {
            cir.setReturnValue(false);
            return;
        }
        // ...and so must a villager partner (two-sided), so an eligible initiator can't drag an
        // ineligible neighbour into a face-up. A non-villager candidate is not gated on this side.
        if (candidate instanceof Villager && !vs_eureka$isSocialThisWindow(candidate.getUUID(), window)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Deterministic per-villager, per-window social-eligibility roll. Mixing the UUID bits with the
     * window index gives each villager an independent phase, so ~{@link #VS$SHIP_SOCIAL_PERCENT}% are
     * eligible at any moment and membership rotates between windows. Stable within a window (no
     * per-tick flicker).
     */
    @Unique
    private static boolean vs_eureka$isSocialThisWindow(final UUID id, final long window) {
        long h = id.getMostSignificantBits() ^ id.getLeastSignificantBits()
            ^ (window * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 32);
        return Math.floorMod((int) h, 100) < VS$SHIP_SOCIAL_PERCENT;
    }
}
