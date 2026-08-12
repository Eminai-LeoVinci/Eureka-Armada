package org.valkyrienskies.eureka.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
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

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val player = context.player as? ServerPlayer ?: return InteractionResult.PASS
        val stack = context.itemInHand

        // The ship stands ON the clicked face, not inside the block that was clicked.
        val corner = context.clickedPos.relative(context.clickedFace)
        return if (ShipBottle.release(level, player, stack, corner)) {
            InteractionResult.SUCCESS
        } else {
            // Refusals already explained themselves in chat; consuming the click stops the arm-swing
            // suggesting something happened.
            InteractionResult.CONSUME
        }
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
