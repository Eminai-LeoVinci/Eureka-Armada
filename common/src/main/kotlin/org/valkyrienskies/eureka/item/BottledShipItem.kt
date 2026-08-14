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
import java.util.function.Consumer

/**
 * A ship in a bottle.
 *
 * Thrown, not placed. It arcs out about twenty blocks, falls, and lets its ship out wherever it comes to rest
 * -- afloat if that was water, keel on the ground if it was not. Throwing rather than placing is what lets you
 * launch a ship from a jetty into water you could never have reached at arm's length, which is most water.
 *
 * The flight is [ThrownShipBottle]; everything that can actually go wrong (no room, a missing template, a hull
 * that will not assemble) goes wrong at the landing, and a refusal sends the bottle back to the thrower with
 * the ship still inside it.
 */
class BottledShipItem(properties: Properties) : Item(properties) {

    // Both paths throw. useOn is overridden rather than left to fall through so that a click which happens to
    // land on a nearby block throws exactly like a click at open sky, instead of doing nothing.
    override fun useOn(context: UseOnContext): InteractionResult =
        hurl(context.level, context.player, context.hand)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult =
        hurl(level, player, hand)

    private fun hurl(level: Level, player: Player?, hand: InteractionHand): InteractionResult {
        if (player == null) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val stack = serverPlayer.getItemInHand(hand)

        // No ship in it means no template on disk -- a bottle from another world, or one whose generated
        // structure was cleared. Nothing to throw.
        if (ShipBottle.templateOf(stack) == null) return InteractionResult.CONSUME

        val bottle = ThrownShipBottle(EurekaEntities.THROWN_BOTTLE.get(), serverLevel)
        bottle.launch(serverPlayer, stack, null)
        serverLevel.addFreshEntity(bottle)

        serverLevel.playSound(
            null, serverPlayer.x, serverPlayer.y, serverPlayer.z,
            SoundEvents.ENDER_EYE_LAUNCH, SoundSource.PLAYERS, 0.5f, 0.4f
        )

        // One bottle, one ship -- and spent by replacing the slot, or creative keeps it and releases the same
        // hull again for nothing; see spendOne.
        ShipBottle.spendOne(serverPlayer, hand, stack)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        adder: Consumer<Component>,
        flag: TooltipFlag
    ) {
        val name = ShipBottle.shipNameOf(stack)
        if (name != null) {
            adder.accept(Component.literal(name).withStyle(ChatFormatting.AQUA))
        } else {
            adder.accept(
                Component.translatable("item.vs_eureka.bottled_ship.empty")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
        }
    }
}
