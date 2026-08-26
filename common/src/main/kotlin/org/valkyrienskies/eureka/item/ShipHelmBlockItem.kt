package org.valkyrienskies.eureka.item

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmScreenMenu

/**
 * A ship's wheel in the hand: place it as any block, or SNEAK and right-click to read it.
 *
 * The wheel is the one block in this mod that carries an identity around with it -- its name becomes the
 * name of the next hull it assembles, and the crews bound to it come with it. Both were invisible until it
 * was on the ground: naming meant an anvil or an axe's durability spent breaking and replacing it, and the
 * crews it kept could not be read at all. Sneaking opens the helm's own interface against the STACK, where
 * everything a ship would answer is greyed and the two things a wheel answers for itself are live: its name,
 * and the articles.
 *
 * ## Why an item class rather than the shared block hook
 * [org.valkyrienskies.eureka.fabric.BottleRegistrationsFabric] and friends arbitrate sneak+right-click by
 * what is in hand, which is the right shape when the gesture is aimed at a wheel. This one is not aimed at
 * anything -- a captain reading the wheel in their hand may be standing in a field -- and `UseBlockCallback`
 * never fires without a block under the crosshair. It is also server-only in every existing handler, so the
 * client would predict the placement it is about to be told did not happen and flicker a ghost wheel.
 *
 * Overriding both [useOn] and [use], as `BlueprintItem` does, covers block and air alike and runs on both
 * sides, so the gesture reads the same wherever it is pointed.
 */
class ShipHelmBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    // Aimed at a block. Sneaking reads the wheel; anything else is an ordinary placement, so vanilla's own
    // placement path -- state, collision, the pirate veto in getStateForPlacement -- is left entirely alone.
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player
        if (player != null && player.isSecondaryUseActive) {
            return read(context.level, player, context.hand)
        }
        return super.useOn(context)
    }

    // Aimed at nothing. There is no placement to fall through to, but the gesture has to mean the same thing
    // pointed at the sky as pointed at the ground.
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSecondaryUseActive) return read(level, player, hand)
        return super.use(level, player, hand)
    }

    /**
     * Open the helm interface with no wheel behind it.
     *
     * The menu takes a nullable block entity already, and null is what tells it -- and, through one synced
     * slot, the screen -- that there is no ship to answer for. Everything ship-shaped is refused server-side
     * by that same null, so this opens the most locked-down version of the screen there is.
     */
    private fun read(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer) {
            val title = player.getItemInHand(hand).hoverName
            player.openMenu(SimpleMenuProvider({ id, inv, _ -> ShipHelmScreenMenu(id, inv, null) }, title))
        }
        return InteractionResult.SUCCESS
    }
}
