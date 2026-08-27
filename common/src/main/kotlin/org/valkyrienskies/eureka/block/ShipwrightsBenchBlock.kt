package org.valkyrienskies.eureka.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.eureka.EurekaProperties.BENCH_PART
import org.valkyrienskies.eureka.crew.CrewEggs
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession
import org.valkyrienskies.mod.common.blockProps
import org.valkyrienskies.mod.common.executeIf

/**
 * The Shipwright's workstation: a desk three blocks wide, one deep and two tall.
 *
 * ## It is the spawn rule, and nothing else
 * A shipwright is not spawned by anything. A villager becomes one by claiming this bench, exactly as one
 * becomes a librarian by claiming a lectern -- so no custom spawn logic exists, and none is wanted: the rule
 * is the block. What has changed is where benches come from. They used to have no recipe at all, so the only
 * ones in the world were the ones placed in harbors, and "shipwrights are only found at harbors" was true by
 * construction. That reading is now a **config decision instead of a hard rule**: the recipe lives in
 * `config/vs_eureka_armada_recipes.json` like every other, and setting `vs_eureka:shipwrights_bench` to
 * `"remove"` there restores the old behaviour exactly. Harbors do not exist yet, and an unobtainable
 * workstation in the meantime is a profession no survival player can ever meet.
 *
 * ## Deliberately not interactive
 * Plans, materials and deliveries all go through the **villager**, not through this. A workbench that answered
 * questions would make the shipwright decorative, and the point of the profession is that a harbor without a
 * shipwright in it is a harbor that cannot build you anything. See
 * [org.valkyrienskies.eureka.shipwright.ShipwrightMenu].
 *
 * ## Six blocks, one desk
 * The footprint follows the model, which is 3.01 blocks wide, 1.48 deep and 1.88 tall. Width and height land
 * on whole blocks; depth does not, so the bench claims **one** block of depth and overhangs it by about three
 * pixels into the wall behind and four and a half in front. That is the cannon's arrangement and the cannon's
 * caveat with it: geometry outside a block is geometry you can see but not click, which is why the depth was
 * not rounded up to two. Four and a half pixels of desk lip is not something a player aims at; a whole anvil
 * would have been.
 *
 * [BenchPart] carries the layout and this file only ever asks it for offsets -- see there for why the middle
 * of the bottom row is special.
 */
class ShipwrightsBenchBlock : Block(
    // Axe OR pickaxe, and it now demands one: the desk is half joinery and half anvil, and having become
    // craftable it should cost a tool to take back. Both tags list it; either is the correct tool.
    blockProps().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(2.5f)
        .requiresCorrectToolForDrops()
        // The model is nowhere near a full cube. Without this the game culls the faces of whatever the desk
        // is stood against and you see straight through the wall behind it.
        .noOcclusion()
) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BENCH_PART, BenchPart.ANCHOR)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HORIZONTAL_FACING).add(BENCH_PART)
    }

    /** Where [part] sits, given the block the player clicked and the way the desk faces. */
    private fun posOf(anchor: BlockPos, facing: Direction, part: BenchPart): BlockPos =
        anchor.relative(facing.clockWise, part.across).above(part.up)

    /** The anchor, given any part of the desk. The exact inverse of [posOf]. */
    private fun anchorOf(pos: BlockPos, state: BlockState): BlockPos {
        val part = state.getValue(BENCH_PART)
        return pos.relative(state.getValue(HORIZONTAL_FACING).clockWise, -part.across).below(part.up)
    }

    /**
     * Claim all six blocks, or none of them.
     *
     * Returning null refuses the placement outright, which leaves the item in the player's hand instead of
     * dropping a third of a desk into a space too small for it.
     */
    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        // .opposite, like the helm and the engine: the front of a desk turns toward whoever placed it. A
        // cannon is the odd one out there, because a gun wants to point away from you.
        val facing = ctx.horizontalDirection.opposite
        val anchor = ctx.clickedPos

        for (part in BenchPart.entries) {
            if (part == BenchPart.ANCHOR) continue
            if (!ctx.level.getBlockState(posOf(anchor, facing, part)).canBeReplaced(ctx)) return null
        }

        return defaultBlockState()
            .setValue(HORIZONTAL_FACING, facing)
            .setValue(BENCH_PART, BenchPart.ANCHOR)
    }

    /** Vanilla places only the block that was clicked; the other five are ours to fill in. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return

        val facing = state.getValue(HORIZONTAL_FACING)
        for (part in BenchPart.entries) {
            if (part == BenchPart.ANCHOR) continue
            level.setBlock(posOf(pos, facing, part), state.setValue(BENCH_PART, part), UPDATE_ALL)
        }
    }

    /**
     * Break any one of the six and the whole desk goes.
     *
     * The block that was actually broken has already dropped its own loot by the time this runs, and
     * everything cleared here goes via `setBlock` to air, which never drops. That is what makes breaking any
     * part yield exactly one bench -- including when the cause was an explosion or a piston rather than a
     * player. Copied from [CannonBlock], including the re-entrancy flag: clearing the other five re-enters
     * this method once per block, and a plain flag is enough because block instances are singletons and this
     * only ever runs on the server thread.
     */
    // 1.21.1: the removal hook is onRemove (fires on any replacement, both sides); the modern
    // affectNeighborsAfterRemoval fires only on true server-side removal, so that is re-created here:
    // same-block state changes fall through to super alone, and the body runs server-side only.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (state.`is`(newState.block)) {
            super.onRemove(state, level, pos, newState, isMoving)
            return
        }
        super.onRemove(state, level, pos, newState, isMoving)
        if (level !is ServerLevel) return

        // Deferred ONE TICK on 1.21.1: ship assembly and disassembly relocate a multiblock part by part
        // through raw chunk writes, and each part's removal lands here looking exactly like a player
        // break. Clearing the siblings immediately gutted the structure mid-relocation -- only the
        // first-moved block of every desk survived a disassembly. One tick later the relocation has
        // finished: a desk that MOVED has no parts left here to clear, and one genuinely broken still
        // does. (1.21.11 needs none of this: its removal hook never fires for chunk-level writes.)
        val server = level.server
        val at = server.tickCount
        server.executeIf({ server.tickCount > at }) {
            if (dismantling) return@executeIf
            dismantling = true
            try {
                dismantleSiblings(state, level, pos)
            } finally {
                dismantling = false
            }
        }
    }

    private fun dismantleSiblings(state: BlockState, level: ServerLevel, pos: BlockPos) {
        run {
            val facing = state.getValue(HORIZONTAL_FACING)
            val anchor = anchorOf(pos, state)

            for (part in BenchPart.entries) {
                val other = posOf(anchor, facing, part)
                if (other == pos) continue

                // Only clear a block that is genuinely part of THIS desk. Two benches placed back to back
                // otherwise take each other with them.
                val there = level.getBlockState(other)
                if (there.block !== this) continue
                if (there.getValue(BENCH_PART) != part) continue
                if (there.getValue(HORIZONTAL_FACING) != facing) continue

                // SUPPRESS_DROPS because the part that was actually broken has already dropped the bench, and
                // KNOWN_SHAPE because this also runs during bulk work -- assembling, bottling, a ship being
                // deleted -- where a neighbour update knocks whatever was resting against the desk loose and
                // drops that too. The cannon needs a third flag here for its magazine; a bench holds nothing,
                // so it does not.
                level.setBlock(
                    other,
                    Blocks.AIR.defaultBlockState(),
                    UPDATE_CLIENTS or UPDATE_KNOWN_SHAPE or UPDATE_SUPPRESS_DROPS
                )
            }
        }
    }

    private var dismantling = false

    /**
     * Keep the desk facing where it faced when a ship is assembled or disassembled.
     *
     * The base implementation is identity, which on a six-block structure is worse than merely cosmetic: the
     * parts are moved to their rotated positions regardless, so a stale facing leaves every block looking for
     * its neighbours along the wrong axis and the desk falls apart the first time anything touches it.
     */
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(HORIZONTAL_FACING)))

    /**
     * The bottom row is a solid desk; the top row is the tools standing on it.
     *
     * The top row is 14 high rather than a full block -- the model tops out at 30 of a possible 32, so this
     * is the tools without a phantom two pixels of ceiling above the anvil -- with ONE exception.
     *
     * [BenchPart.LEFT_UPPER] has no shape at all. That is the smithing-table end, where the desk carries
     * only the hand saw, and it is the one span of worktop clear enough to stand on; the anvil and the
     * stonecutter fill their columns and should still stop you. So a player can step up at that end and
     * nowhere else, which is the point -- this is a foothold, not a walkway along the whole bench. The
     * cost is local and small: the hand saw above that block cannot be clicked, and the desk beneath it
     * still can.
     */
    override fun getShape(state: BlockState, getter: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape {
        val part = state.getValue(BENCH_PART)
        return when {
            part.up == 0 -> LOWER_SHAPE
            part == BenchPart.LEFT_UPPER -> Shapes.empty()
            else -> UPPER_SHAPE
        }
    }

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    /**
     * A villager egg on the bench hatches a shipwright already holding the trade.
     *
     * The "deliberately not interactive" note above still stands for everything a PLAYER can do here: this
     * answers only a creative hand holding a villager egg, and every other click falls through untouched, so
     * an empty hand still does nothing and the bench still answers no questions. Any of the parts will do --
     * the desk reads as one object to whoever is holding the egg. See [CrewEggs] for why an authoring
     * gesture does not undo the rule that a shipwright is made by claiming a bench.
     */
    // 1.21.1 returns ItemInteractionResult where the modern branch returns InteractionResult; the legacy
    // body below is kept verbatim (computing modern-style results) and this override maps them across.
    // TRY_WITH_EMPTY_HAND has no 1.21.1 constant -- the body returns PASS there, and PASS maps to
    // PASS_TO_DEFAULT_BLOCK_INTERACTION, which is the same fall-through to useWithoutItem.
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult
    ): ItemInteractionResult = when (useItemOnLegacy(stack, state, level, pos, player, hand, hitResult)) {
        InteractionResult.SUCCESS -> ItemInteractionResult.SUCCESS
        InteractionResult.CONSUME -> ItemInteractionResult.CONSUME
        InteractionResult.FAIL -> ItemInteractionResult.FAIL
        else -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
    }

    private fun useItemOnLegacy(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult
    ): InteractionResult {
        if (stack.item !is SpawnEggItem || !player.hasInfiniteMaterials()) {
            return InteractionResult.PASS // 1.21.1: the wrapper maps PASS to the default fall-through super would take
        }
        if (level.isClientSide) return InteractionResult.SUCCESS

        val refusal = CrewEggs.hatch(
            level as ServerLevel, pos, hitResult.direction, stack.item as SpawnEggItem, stack,
            player as? ServerPlayer, ShipwrightProfession.PROFESSION_KEY
        )
        // A zombie egg on a bench is not a mistake worth refusing -- it is somebody spawning a zombie.
        if (refusal == CrewEggs.NOT_A_VILLAGER) {
            return InteractionResult.PASS // 1.21.1: the wrapper maps PASS to the default fall-through super would take
        }
        CrewEggs.tell(player as? ServerPlayer, refusal, "shipwright")
        return InteractionResult.SUCCESS
    }

    private companion object {
        val LOWER_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        val UPPER_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 14.0, 16.0)
    }
}
