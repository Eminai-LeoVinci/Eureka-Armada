package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm

/**
 * A Heart of the Sea offered to a helm buys one more berth.
 *
 * Lives here, apart from both callers, because vanilla routes the same gesture through two entirely different
 * paths depending on whether the player is crouching, and the two must not be allowed to drift:
 *
 *  - Standing, the block's own `useItemOn` runs and calls this.
 *  - Crouching, it does NOT. `ServerPlayerGameMode.useItemOn` skips block interaction outright whenever the
 *    player is sneaking with anything in either hand -- that is the rule which lets you place a block against
 *    a chest instead of opening it -- so the helm never hears the click. Fabric's `UseBlockCallback` fires at
 *    the HEAD of that same method, before the check, which is the only place the crouching case can be caught.
 *
 * Sneaking is the natural thing to try here, because sneaking is how the helm menu is opened; the fix is to
 * honour both rather than to teach a hand position.
 *
 * [PASS][InteractionResult.PASS] means "not this gesture" and is the caller's cue to carry on with whatever it
 * would otherwise have done -- opening the menu, sitting down, placing a block.
 */
object CrewBerths {

    /**
     * Returns [InteractionResult.PASS] if [stack] is not a Heart of the Sea, so a caller can chain this ahead
     * of its normal handling without inspecting the stack itself.
     */
    @JvmStatic
    fun offerHeart(level: Level, pos: BlockPos, player: Player, stack: ItemStack): InteractionResult {
        if (!stack.`is`(Items.HEART_OF_THE_SEA)) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS

        // Pirate gate, door 3+4 of 14: both arms of the Heart gesture funnel here (standing via
        // ShipHelmBlock.useItemOn, crouching via the UseBlockCallback in CrewRegistrationsFabric).
        // CONSUME spends the click but not the heart, and stops the fall-through opening anything.
        if (PirateHelm.gated(level.getBlockState(pos))) {
            PirateHelm.deny(serverPlayer)
            return InteractionResult.CONSUME
        }

        val cap = EurekaConfig.SERVER.crewSlotsMax
        val current = CrewData.slots(serverPlayer)
        if (current >= cap) {
            PathMessages.send(
                serverPlayer,
                "The articles are full -- $cap is the largest crew anyone commands.",
                PathMessages.Kind.ERROR
            )
            // CONSUME, not FAIL: this spends the CLICK but not the heart. FAIL would fall through and open the
            // helm menu on the same press, which reads as the heart having done something.
            return InteractionResult.CONSUME
        }

        CrewData.setSlots(serverPlayer, current + 1)
        if (!player.abilities.instabuild) stack.shrink(1)
        level.playSound(null, pos, SoundEvents.CONDUIT_ACTIVATE, SoundSource.BLOCKS, 0.6f, 1.2f)
        PathMessages.send(
            serverPlayer,
            "Another berth signed for -- you can now muster ${current + 1} crew on a ship.",
            PathMessages.Kind.GOOD
        )
        return InteractionResult.SUCCESS
    }
}
