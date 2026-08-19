package org.valkyrienskies.eureka.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.pirate.PirateHelm;
import org.valkyrienskies.eureka.util.ShipFireBurn;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Fire aboard a ship burns away what it is standing on, and nothing else.
 *
 * <p>A ship is a confined wooden box that a player cannot rebuild while it is moving, and vanilla fire is
 * built to consume a forest: it lights every neighbour it can reach and leaves fresh fire behind as it
 * goes. Left alone on a hull that is one unlucky incendiary round away from open flame, that is not a
 * hazard, it is a delete button. So a fire on a ship still costs you hull -- the block it caught on burns
 * through and leaves a hole -- but it can never turn into a fire somewhere else.
 *
 * <p>Three things have to be true for that, and vanilla's {@code FireBlock.tick} does all three in
 * separate places:
 *
 * <ol>
 *   <li>it must not ignite the blocks around it -- the spread loop, gated here by forcing the ignite odds
 *       of every candidate to zero;</li>
 *   <li>it must burn only what it is attached to, not all six neighbours -- {@code checkBurnOut};</li>
 *   <li>the block it consumes must be <em>gone</em>, not replaced with fire, which is how a contained burn
 *       quietly turns back into a spreading one.</li>
 * </ol>
 *
 * <p>Everything else about fire is left alone: it still ages, still burns entities standing in it, still
 * dies in the rain, and still goes out on its own once it has nothing left to hold onto. Fire ashore is
 * untouched -- the gate is the fire's own position belonging to a ship. Set {@code shipFireSpreads} to put
 * vanilla behaviour back.
 *
 * <p>Ordering note: this only matters because ship fire ticks at all, which it did not before VS2 started
 * measuring {@code fire_spread_radius_around_player} from a ship block's world position.
 */
@Mixin(FireBlock.class)
public class MixinShipFireContainment {

    /**
     * Wake the fire up when its block is actually due, rather than whenever the next 30-40 tick beat
     * happens to fall -- otherwise the shortest burns all round up to the same couple of seconds and the
     * configured floor stops meaning anything. Only ever shortens the wait, and only once a deadline
     * exists (it is drawn later in the very first tick), so a fire can never be made to linger by this.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FireBlock;getFireTickDelay"
                + "(Lnet/minecraft/util/RandomSource;)I"
        )
    )
    private int vs_eureka$lookAgainWhenTheBlockIsDue(
        final RandomSource random, final Operation<Integer> getFireTickDelay,
        @Local(argsOnly = true) final ServerLevel level,
        @Local(argsOnly = true) final BlockPos firePos) {

        final int vanilla = getFireTickDelay.call(random);
        if (!vs_eureka$contained(level, firePos)) {
            return vanilla;
        }
        // Overdue means this very tick is the one that burns it, so there is nothing left to hurry for.
        // Clamping a negative remainder up to 1 instead would put the fire on an every-tick schedule for
        // as long as it failed to consume anything -- ageing it out in a couple of seconds.
        final long remaining = ShipFireBurn.INSTANCE.ticksRemaining(level, firePos);
        if (remaining <= 0L) {
            return vanilla;
        }
        return (int) Math.min((long) vanilla, remaining);
    }

    /**
     * The spread loop asks each candidate position how readily it catches. Zero for every one of them is
     * the whole of "does not spread": the loop's own {@code igniteOdds > 0} guard then skips the block
     * before any roll is made, so nothing else about the pass has to be second-guessed.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FireBlock;getIgniteOdds"
                + "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I"
        )
    )
    private int vs_eureka$neverSpreadFromAShip(
        final FireBlock self, final LevelReader reader, final BlockPos candidate,
        final Operation<Integer> getIgniteOdds,
        @Local(argsOnly = true) final ServerLevel level,
        @Local(argsOnly = true) final BlockPos firePos) {

        if (vs_eureka$contained(level, firePos)) {
            return 0;
        }
        return getIgniteOdds.call(self, reader, candidate);
    }

    /**
     * Vanilla runs this for all six neighbours. On a ship only the blocks the fire is actually attached to
     * are allowed to burn, which is what keeps a deck fire to a hole in the deck instead of the beam
     * beside it, the wall behind it and the ceiling above it.
     *
     * <p>The timing is ours too. Vanilla rolls against a flammability table every fire tick, which for
     * planks averages well over a minute and has no upper bound at all; a fire that cannot spread and takes
     * an unknowable time to do its one job is not a threat a crew can respond to. So the roll is replaced by
     * a deadline drawn once per fire (see {@link ShipFireBurn}), and vanilla's own call is then made with a
     * chance of 1 -- {@code random.nextInt(1)} is always 0, so it burns if and only if the block is
     * flammable at all. That keeps vanilla's flammability table as the authority on *what* can burn while
     * taking over *when*.
     */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FireBlock;checkBurnOut"
                + "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;I"
                + "Lnet/minecraft/util/RandomSource;I)V"
        )
    )
    private void vs_eureka$burnOnlyWhatItIsAttachedTo(
        final FireBlock self, final Level level, final BlockPos neighbour, final int chance,
        final RandomSource random, final int age, final Operation<Void> checkBurnOut,
        @Local(argsOnly = true) final BlockPos firePos,
        @Local(argsOnly = true) final BlockState fireState) {

        if (!vs_eureka$contained(level, firePos)) {
            checkBurnOut.call(self, level, neighbour, chance, random, age);
            return;
        }
        if (!vs_eureka$attachedTo(fireState, firePos, neighbour)
            || !(level instanceof ServerLevel serverLevel)
            || !ShipFireBurn.INSTANCE.burnedThrough(serverLevel, firePos)) {
            return;
        }
        // Time is up: burn it if it can burn at all.
        //
        // Only an actual burn clears the deadline. The six neighbours are offered in a fixed order with
        // the space below the fire third, and a fire counts as attached to that space whether or not
        // anything is in it -- so for a fire clinging to a wall the first candidate to reach this point is
        // routinely air. Clearing the clock there would restart it before the wall the fire is really on
        // ever got its turn, every time, and the fire would age out having consumed nothing. A blockstate
        // that changed is the only proof that something burned.
        final BlockState before = level.getBlockState(neighbour);
        checkBurnOut.call(self, level, neighbour, 1, random, age);
        if (level.getBlockState(neighbour) != before) {
            ShipFireBurn.INSTANCE.forget(serverLevel, firePos);
        }
    }

    /**
     * A pirate's wheel never burns -- at sea or ashore. Helms are registered flammable, and a generated
     * pirate hull is ordinary world blocks until it assembles, so a deck fire walking the hull would
     * otherwise consume the conquest objective with nobody even aboard. Zero burn odds is the one
     * interception that covers BOTH of vanilla's consumption branches (replace-with-fire and removeBlock):
     * {@code random.nextInt(chance) < 0} is never true, so the wheel chars in place and outlives the fire.
     * The predicate is PIRATE-only; a TAKEN or ordinary wheel burns exactly as before.
     */
    @WrapOperation(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FireBlock;getBurnOdds"
                + "(Lnet/minecraft/world/level/block/state/BlockState;)I"
        )
    )
    private int vs_eureka$aPirateWheelNeverBurns(
        final FireBlock self, final BlockState state, final Operation<Integer> getBurnOdds) {

        if (PirateHelm.inviolable(state)) {
            return 0;
        }
        return getBurnOdds.call(self, state);
    }

    /**
     * When a block burns out, vanilla sometimes puts fire in its place instead of leaving a hole. On a
     * ship that is spreading by another name -- the new fire is attached to whatever held the old block up
     * and starts eating that -- so the block simply goes.
     */
    @WrapOperation(
        method = "checkBurnOut",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock"
                + "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean vs_eureka$leaveAHoleNotAFire(
        final Level level, final BlockPos burned, final BlockState fire, final int flags,
        final Operation<Boolean> setBlock) {

        if (vs_eureka$contained(level, burned)) {
            return level.removeBlock(burned, false);
        }
        return setBlock.call(level, burned, fire, flags);
    }

    /** Whether this fire is one we are keeping on a leash. */
    private static boolean vs_eureka$contained(final Level level, final BlockPos firePos) {
        return !EurekaConfig.SERVER.getShipFireSpreads()
            && VSGameUtilsKt.getShipManagingPos(level, firePos) != null;
    }

    /**
     * Whether {@code target} is a block this fire is actually sitting on or clinging to.
     *
     * <p>Fire records the faces it caught on in its own blockstate, which is exactly the question being
     * asked here -- so a fire on a wall consumes the wall, and a fire on a deck consumes the deck, with no
     * separate notion of "attached" to keep in step with vanilla's. The block below is always included:
     * a fire resting on top of something sets no face property at all.
     */
    private static boolean vs_eureka$attachedTo(final BlockState fire, final BlockPos firePos,
        final BlockPos target) {

        if (target.equals(firePos.below())) {
            return true;
        }
        for (final Map.Entry<Direction, BooleanProperty> face : PipeBlock.PROPERTY_BY_DIRECTION.entrySet()) {
            final BooleanProperty caught = face.getValue();
            // Fire has no DOWN property; hasProperty covers that without a special case.
            if (fire.hasProperty(caught) && fire.getValue(caught)
                && target.equals(firePos.relative(face.getKey()))) {
                return true;
            }
        }
        return false;
    }
}
