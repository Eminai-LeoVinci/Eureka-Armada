package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.bottle.ShipBottle

/**
 * Sneak and right-click a ship's wheel to take a reading of the ship: with an empty Ship Bottle, that marks it
 * for capture; with a blank blueprint, it drafts a page.
 *
 * Crouching is what separates this from opening the helm menu, which is a standing right-click. It shares that
 * gesture with the Heart of the Sea offering, so this handler is registered FIRST and claims the click when the
 * hand holds one of ours -- otherwise the two would race and the winner would depend on registration order
 * rather than on what the player is holding. Which of ours it is decides what happens, for the same reason.
 */
object BottleRegistrationsFabric {

    fun register() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (level.isClientSide) return@register InteractionResult.PASS
            if (!player.isSecondaryUseActive) return@register InteractionResult.PASS

            val stack = player.getItemInHand(hand)
            val bottling = stack.`is`(EurekaItems.SHIP_BOTTLE.get())
            // A drafted page is not a blank one: re-drafting over a blueprint you already own would throw the
            // ship it describes away, and the gesture gives no warning that it is about to.
            val drafting = stack.`is`(EurekaItems.BLUEPRINT.get()) && Blueprint.read(stack) == null
            if (!bottling && !drafting) return@register InteractionResult.PASS

            val pos = hit.blockPos
            if (level.getBlockState(pos).block !is ShipHelmBlock) return@register InteractionResult.PASS

            val serverLevel = level as? ServerLevel ?: return@register InteractionResult.PASS
            val serverPlayer = player as? ServerPlayer ?: return@register InteractionResult.PASS

            // Whether this wheel steers an assembled ship at all is the common module's business either way,
            // since VS2's ship lookups are Kotlin extensions that only resolve there.
            if (bottling) {
                // Marks the wheel; the ship does not move yet. Throwing the bottle is what takes it -- see
                // ShipBottleItem.
                ShipBottle.mark(serverLevel, serverPlayer, pos, stack)
            } else {
                // Reads the ship onto the page and leaves it exactly where it is.
                val page = Blueprint.draft(serverLevel, serverPlayer, pos)
                if (page != null) {
                    stack.shrink(1)
                    if (!serverPlayer.inventory.add(page)) serverPlayer.drop(page, false)
                }
            }

            // Claimed either way: a refusal has already explained itself in chat, and passing the click on
            // would open the helm menu on top of the message.
            InteractionResult.SUCCESS
        }
    }
}
