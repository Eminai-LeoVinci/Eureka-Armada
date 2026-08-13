package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.cannon.CannonFire

/**
 * Sneak and right-click a cannon with flint and steel to fire it.
 *
 * ## Why this cannot live on the block
 * Vanilla does not call a block's `useItemOn` **at all** when the player is crouching with something in hand
 * -- it goes straight to using the item, which for flint and steel means setting the gun alight. That is the
 * same trap the Heart of the Sea gesture hit (see `ShipHelmBlock.useItemOn`), and the same answer: catch it
 * here, before the item ever gets its turn.
 *
 * Claiming the click is therefore not tidiness, it is the feature: a cannon is iron and oak and does not burn,
 * so the spark has to light the charge and leave no fire block sitting on the barrel.
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
            val serverPlayer = player as? ServerPlayer

            val refusal = CannonFire.fire(serverLevel, hit.blockPos, serverPlayer)
            if (refusal != null) {
                // A gun that will not fire says why, and costs nothing to ask. Spending durability on a
                // refusal would punish a player for checking whether a cannon was loaded.
                serverPlayer?.displayClientMessage(refusal, true)
            } else if (serverPlayer != null && !serverPlayer.hasInfiniteMaterials()) {
                stack.hurtAndBreak(1, serverPlayer, hand)
            }

            // Claimed either way, so the flint never gets its turn and no fire is placed on the gun.
            InteractionResult.SUCCESS
        }
    }
}
