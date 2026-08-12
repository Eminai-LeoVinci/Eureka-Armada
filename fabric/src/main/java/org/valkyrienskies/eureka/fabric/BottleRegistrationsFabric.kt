package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.bottle.ShipBottle

/**
 * Sneak and right-click a ship's wheel with an empty Ship Bottle to mark it for capture.
 *
 * Crouching is what separates this from opening the helm menu, which is a standing right-click. It shares that
 * gesture with the Heart of the Sea offering, so this handler is registered FIRST and claims the click when the
 * hand holds a bottle -- otherwise the two would race and the winner would depend on registration order rather
 * than on what the player is holding.
 *
 * Blueprints will take the same gesture when they arrive, distinguished the same way: by the item in hand.
 */
object BottleRegistrationsFabric {

    fun register() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (level.isClientSide) return@register InteractionResult.PASS
            if (!player.isSecondaryUseActive) return@register InteractionResult.PASS

            val stack = player.getItemInHand(hand)
            if (!stack.`is`(EurekaItems.SHIP_BOTTLE.get())) return@register InteractionResult.PASS

            val pos = hit.blockPos
            if (level.getBlockState(pos).block !is ShipHelmBlock) return@register InteractionResult.PASS

            val serverLevel = level as? ServerLevel ?: return@register InteractionResult.PASS
            val serverPlayer = player as? ServerPlayer ?: return@register InteractionResult.PASS

            // Marks the wheel; the ship does not move yet. Throwing the bottle is what takes it -- see
            // ShipBottleItem. Whether this wheel steers an assembled ship at all is ShipBottle's business, since
            // VS2's ship lookups are Kotlin extensions that only resolve in the common module.
            ShipBottle.mark(serverLevel, serverPlayer, pos, stack)

            // Claimed either way: a refusal has already explained itself in chat, and passing the click on
            // would open the helm menu on top of the message.
            InteractionResult.SUCCESS
        }
    }
}
