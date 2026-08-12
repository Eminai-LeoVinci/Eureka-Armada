package org.valkyrienskies.eureka.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.item.component.TooltipDisplay
import org.valkyrienskies.eureka.bottle.ShipBottle
import java.util.function.Consumer

/**
 * A ship in a bottle.
 *
 * Right-clicking a block lets it out with its keel on the face that was clicked. Eventually the bottle will be
 * thrown and fly like an eye of ender, and this direct placement becomes the last step of that arc rather than
 * the whole interaction -- but the arc is presentation, and everything that can actually go wrong (no room, a
 * missing template, a hull that will not assemble) goes wrong here.
 */
class BottledShipItem(properties: Properties) : Item(properties) {

    // The ship stands ON the clicked face, not inside the block that was clicked.
    override fun useOn(context: UseOnContext): InteractionResult =
        letOut(context.level, context.player, context.hand, context.clickedPos.relative(context.clickedFace))

    /**
     * Reached when the click hit no block at all -- which is what aiming at open sea usually means, since water
     * is invisible to the ordinary raycast and the seabed is often out of range.
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult =
        letOut(level, player, hand, null)

    private fun letOut(level: Level, player: Player?, hand: InteractionHand, onLand: BlockPos?): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val stack = serverPlayer.getItemInHand(hand)

        // Water wins wherever it is in the way. The ordinary raycast passes straight through it, so a click
        // aimed at the sea arrives here either as a hit on the seabed far below or as no hit at all -- and
        // dropping a ship on the seabed is not what the player asked for. Re-casting with SOURCE_ONLY, the way
        // buckets do, is what makes the surface clickable.
        val wet = getPlayerPOVHitResult(level, serverPlayer, ClipContext.Fluid.SOURCE_ONLY)
        val onWater = wet.type == HitResult.Type.BLOCK && !level.getFluidState(wet.blockPos).isEmpty

        val released = when {
            onWater -> ShipBottle.releaseOnWater(serverLevel, serverPlayer, stack, wet.blockPos)
            onLand != null -> ShipBottle.release(serverLevel, serverPlayer, stack, onLand)
            else -> return InteractionResult.PASS
        }

        // Refusals already explained themselves in chat; consuming the click stops the arm-swing suggesting
        // something happened.
        return if (released) InteractionResult.SUCCESS else InteractionResult.CONSUME
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
