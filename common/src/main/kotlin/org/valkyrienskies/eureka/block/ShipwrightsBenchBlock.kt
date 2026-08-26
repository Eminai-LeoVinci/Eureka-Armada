package org.valkyrienskies.eureka.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
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
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.eureka.EurekaProperties.BENCH_PART
import org.valkyrienskies.eureka.crew.CrewEggs
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession
import org.valkyrienskies.mod.common.blockProps

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
    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, isMoving: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, isMoving)

        if (dismantling) return
        dismantling = true
        try {
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
        } finally {
            dismantling = false
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
     * The model tops out at 30 of a possible 32, so the upper course is 14 high rather than a full block --
     * enough that the desk cannot be walked onto, without a phantom two pixels of ceiling above the anvil.
     */
    override fun getShape(state: BlockState, getter: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        if (state.getValue(BENCH_PART).up == 0) LOWER_SHAPE else UPPER_SHAPE

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    /**
     * A villager egg on the bench hatches a shipwright already holding the trade.
     *
     * The "deliberately not interactive" note above still stands for everything a PLAYER can do here: this
     * answers only a creative hand holding a villager egg, and every other click falls through untouched, so
     * an empty hand still does nothing and the bench still answers no questions. Any of the six parts will do
     * -- the desk reads as one object to whoever is holding the egg. See [CrewEggs] for why an authoring
     * gesture does not undo the rule that a shipwright is made by claiming a bench.
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

    private companion object {
        val LOWER_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        val UPPER_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 14.0, 16.0)
    }
}
