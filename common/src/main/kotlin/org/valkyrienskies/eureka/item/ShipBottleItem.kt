package org.valkyrienskies.eureka.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.EurekaEntities
import org.valkyrienskies.eureka.bottle.ShipBottle
import org.valkyrienskies.eureka.bottle.ThrownShipBottle
import org.valkyrienskies.eureka.path.PathMessages
import java.util.function.Consumer

/**
 * An empty Ship Bottle.
 *
 * Two gestures, in this order:
 *
 * 1. **Sneak** and right-click a ship's wheel to *mark* it. Handled by the loader-side block hook, since the
 *    click lands on a block.
 * 2. **Stand** and right-click to *throw*. The bottle arcs out, lands, and sets off for the wheel it was
 *    marked with.
 *
 * Splitting it that way is the whole point of the item: a bottle that swallowed the ship the instant you
 * touched the wheel would be over before the player saw it. Marked, you can walk to shore and take the ship
 * from somewhere you would rather be standing when it disappears.
 *
 * ## Why the throw refuses while sneaking
 * Not taste -- necessity. Fabric's block hook runs server-side, but the client sees it pass and carries on into
 * its own item-use path, so one sneaking right-click on a wheel arrives here as *two* interactions: the mark,
 * and then a use. Without this guard the bottle marks the wheel and immediately throws itself at it, and the
 * player never gets to walk away.
 */
class ShipBottleItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult =
        hurl(context.level, context.player, context.hand)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult =
        hurl(level, player, hand)

    private fun hurl(level: Level, player: Player?, hand: InteractionHand): InteractionResult {
        // See the class note: sneaking is the marking gesture, and the mark arrives here as a second click.
        if (player == null || player.isSecondaryUseActive) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val stack = serverPlayer.getItemInHand(hand)

        val helm = ShipBottle.markedHelm(stack) ?: run {
            PathMessages.send(
                serverPlayer,
                "Sneak and right-click a ship's wheel to mark it, then throw the bottle.",
                PathMessages.Kind.WARN
            )
            // Consumed rather than passed: the hint has been given, and swinging the arm as well would suggest
            // something was thrown.
            return InteractionResult.CONSUME
        }

        // Answered before the bottle leaves the hand, never after. A throw at a ship that is not there would
        // arc off, land, and spend a minute flying at nothing before it came back with an apology.
        val wheel = ShipBottle.helmWorldPos(serverLevel, helm)
        val name = ShipBottle.shipNameOf(stack) ?: "That ship"
        if (wheel == null) {
            PathMessages.send(serverPlayer, "$name is not loaded anywhere nearby.", PathMessages.Kind.WARN)
            return InteractionResult.CONSUME
        }
        val range = wheel.distanceTo(serverPlayer.position())
        if (range > ShipBottle.CAPTURE_RANGE) {
            PathMessages.send(
                serverPlayer,
                "$name is ${range.toInt()} blocks away -- a bottle only reaches ${ShipBottle.CAPTURE_RANGE.toInt()}.",
                PathMessages.Kind.WARN
            )
            return InteractionResult.CONSUME
        }

        val bottle = ThrownShipBottle(EurekaEntities.THROWN_BOTTLE.get(), serverLevel)
        bottle.launch(serverPlayer, stack, helm)
        serverLevel.addFreshEntity(bottle)

        serverLevel.playSound(
            null, serverPlayer.x, serverPlayer.y, serverPlayer.z,
            SoundEvents.ENDER_EYE_LAUNCH, SoundSource.PLAYERS, 0.5f, 0.4f
        )

        // Spent even in creative. The bottle in the air IS this stack -- it comes back to the inventory whether
        // it caught a ship or not, so letting creative keep the original would hand out a second one.
        stack.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        adder: Consumer<Component>,
        flag: TooltipFlag
    ) {
        val marked = ShipBottle.shipNameOf(stack)
        if (marked != null) {
            adder.accept(Component.literal(marked).withStyle(ChatFormatting.AQUA))
        } else {
            adder.accept(
                Component.translatable("item.vs_eureka.ship_bottle.unmarked")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
        }
    }
}
