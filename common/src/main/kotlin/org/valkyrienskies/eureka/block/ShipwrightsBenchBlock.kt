package org.valkyrienskies.eureka.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.eureka.crew.CrewEggs
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession
import org.valkyrienskies.mod.common.blockProps

/**
 * The Shipwright's workstation.
 *
 * ## It is the spawn rule, and nothing else
 * A shipwright is not spawned by anything. A villager becomes one by claiming this bench, exactly as one
 * becomes a librarian by claiming a lectern -- so "shipwrights are only found at harbors" is true because the
 * bench is only *placed* at harbors, not because anything checks where a villager is standing. No custom spawn
 * logic exists, and none is wanted: the rule is the block.
 *
 * That only holds while the bench stays unobtainable. It has no recipe on purpose. The moment one can be
 * crafted, a shipwright can be manufactured in a basement and the harbor stops meaning anything -- which makes
 * the missing recipe a design decision rather than an unfinished one.
 *
 * ## Deliberately not interactive
 * Plans, materials and deliveries all go through the **villager**, not through this. A workbench that answered
 * questions would make the shipwright decorative, and the point of the profession is that a harbor without a
 * shipwright in it is a harbor that cannot build you anything. See
 * [org.valkyrienskies.eureka.shipwright.ShipwrightMenu].
 */
class ShipwrightsBenchBlock : Block(
    // Softer than a helm and axe-mineable: it is a carpenter's bench, and a player who finds one in a harbor
    // should be able to take it home even though they cannot make one.
    blockProps().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(2.5f)
) {

    /**
     * A villager egg on the bench hatches a shipwright already holding the trade.
     *
     * The "deliberately not interactive" note above still stands for everything a PLAYER can do here: this
     * answers only a creative hand holding a villager egg, and every other click falls through untouched, so
     * an empty hand still does nothing and the bench still answers no questions. See [CrewEggs] for why an
     * authoring gesture does not undo the rule that a shipwright is made by claiming a bench.
     */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (stack.item !is SpawnEggItem || !player.hasInfiniteMaterials()) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
        }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val refusal = CrewEggs.hatch(
            level as ServerLevel, pos, hitResult.direction, stack.item as SpawnEggItem, stack,
            player as? ServerPlayer, ShipwrightProfession.PROFESSION_KEY
        )
        // A zombie egg on a bench is not a mistake worth refusing -- it is somebody spawning a zombie.
        if (refusal == CrewEggs.NOT_A_VILLAGER) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
        }
        CrewEggs.tell(player as? ServerPlayer, refusal, "shipwright")
        return InteractionResult.SUCCESS
    }
}
