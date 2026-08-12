package org.valkyrienskies.eureka.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.blueprint.BlueprintPages
import java.util.function.Consumer

/**
 * A blueprint: blank until it is drafted, then a page describing one ship.
 *
 * Sneak and right-click a ship's wheel with a blank one to draft it -- the same gesture the Ship Bottle marks
 * with, told apart by what is in your hand, which is the arbitration the shared block hook was built for.
 *
 * Right-clicking a drafted page opens it. That happens entirely on the client: everything the page shows
 * travels in the item's own component, so there is no server to wait for and a blueprint in a chest across
 * the world reads exactly as fast as one in your hand.
 */
class BlueprintItem(properties: Properties) : Item(properties) {

    // Both paths open the page. useOn is overridden rather than left to fall through so that reading a
    // blueprint does not depend on what you happen to be looking at.
    override fun useOn(context: UseOnContext): InteractionResult =
        open(context.level, context.player, context.hand)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult =
        open(level, player, hand)

    private fun open(level: Level, player: Player?, hand: InteractionHand): InteractionResult {
        // Sneaking is the drafting gesture, and the block hook that handles it runs server-side only -- the
        // client sees the click pass and carries on into its own item-use path. Without this guard, drafting a
        // page would open it in the same breath.
        if (player == null || player.isSecondaryUseActive) return InteractionResult.PASS

        val page = Blueprint.read(player.getItemInHand(hand)) ?: return InteractionResult.PASS
        if (level.isClientSide) BlueprintPages.open(page)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        adder: Consumer<Component>,
        flag: TooltipFlag
    ) {
        val page = Blueprint.read(stack)
        if (page == null) {
            adder.accept(
                Component.translatable("item.vs_eureka.blueprint.blank")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
            return
        }

        adder.accept(Component.literal(page.shipName).withStyle(ChatFormatting.AQUA))
        adder.accept(
            Component.literal("${page.width} x ${page.height} x ${page.length}  -  ${page.blocks} blocks")
                .withStyle(ChatFormatting.DARK_GRAY)
        )
    }
}
