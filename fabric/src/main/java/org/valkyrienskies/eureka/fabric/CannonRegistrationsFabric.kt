package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.cannon.CannonFire

/**
 * The one cannon gesture the block itself can never see: **crouching with flint and steel in hand**.
 *
 * Vanilla does not call a block's `useItemOn` at all when a crouching player has a full hand -- it goes
 * straight to using the item, which for flint and steel means setting the gun alight. A cannon is iron and oak
 * and does not burn, and a fire block on the barrel would be in the way of the next shot besides.
 *
 * So this claims that one combination and **fires the gun**, exactly as a standing click would. Flint and
 * steel means fire whatever your stance -- making it elevate instead would be a second, invisible rule keyed
 * on a crouch nobody was told about.
 *
 * Everything else passes straight through, deliberately: crouch-placing a block against a gun has to keep
 * working, so only the igniter is intercepted.
 */
object CannonRegistrationsFabric {

    fun register() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (level.isClientSide) return@register InteractionResult.PASS
            if (!player.isSecondaryUseActive) return@register InteractionResult.PASS

            val stack = player.getItemInHand(hand)
            if (!CannonFire.isIgniter(stack.item)) return@register InteractionResult.PASS
            if (level.getBlockState(hit.blockPos).block !is CannonBlock) return@register InteractionResult.PASS

            val serverLevel = level as? ServerLevel ?: return@register InteractionResult.PASS
            CannonFire.strike(serverLevel, hit.blockPos, player as? ServerPlayer, stack, hand)

            // Claimed either way, so the flint never gets its turn and no fire is placed on the gun.
            InteractionResult.SUCCESS
        }
    }
}
