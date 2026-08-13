package org.valkyrienskies.eureka.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/**
 * A round for a ship's gun. Inert on its own -- it does nothing until it is loaded into a cannon.
 *
 * Stacks to 16 like a snowball rather than 64. A gun deck's magazine is meant to take up room: a broadside
 * that can be fed all afternoon out of one inventory slot is not a supply line, it is a formality.
 */
class CannonballItem(val ball: Cannonball, properties: Properties) : Item(properties) {

    /**
     * Quotes the range, not the average.
     *
     * The average would read better and mislead badly -- the damage ladder is weighted hard toward its floor
     * (see [Cannonball]), so a player told "iron averages 3.5" would reasonably expect 4 about half the time
     * and get it about a fifth of the time. The honest short version is the pair of numbers it lands between.
     */
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        adder: Consumer<Component>,
        flag: TooltipFlag
    ) {
        adder.accept(
            Component.translatable(
                "item.vs_eureka.cannonball.damage",
                ball.minBlocks,
                ball.maxBlocks
            ).withStyle(ChatFormatting.GRAY)
        )
    }
}
