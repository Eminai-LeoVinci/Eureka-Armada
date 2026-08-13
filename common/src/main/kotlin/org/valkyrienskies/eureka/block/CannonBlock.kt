package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
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
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.eureka.EurekaProperties.CANNON_PART
import org.valkyrienskies.eureka.util.DirectionalShape
import org.valkyrienskies.eureka.util.RotShapes
import org.valkyrienskies.mod.common.blockProps

/**
 * A ship's gun: two blocks long, laid rear-to-front like a bed.
 *
 * ## Why two, having started at three
 * The footprint follows the model, and the model got shorter. It began at 44 units -- 2.75 blocks -- where
 * two blocks would have left three quarters of a block hanging outside the gun's own footprint, at the two
 * places that most need to be clear: ahead of the muzzle, where the shot and the gunport go, and behind the
 * trail, where the crew stands. Three blocks was the honest answer to that.
 *
 * Then the wagon was pitched back 22.5 degrees and its trail shortened to suit, and the barrel trimmed to
 * match, which brought the gun to 34 units. At that length two blocks overhang by a single unit at each end
 * and three blocks waste six -- so guns could not sit closer than three blocks apart on a broadside, and
 * both end blocks looked empty. Two is now the better fit in both directions.
 *
 * It matters more than tidiness, because a cannon is **fired by right-clicking it with a torch**. Geometry
 * that hangs outside its own blocks is geometry you can see but not click, so a player aiming at the muzzle
 * would punch straight through it. One unit is small enough not to matter; twelve was not.
 *
 * The gun is 1.31 wide and 1.375 tall whatever we do, so it overhangs sideways and upward regardless. The
 * footprint only ever bought the length axis, which is the one that carries the interaction.
 *
 * ## The layout is written down once
 * [CannonPart]'s ordinal is the offset from the rear block along [HORIZONTAL_FACING], so every position is
 * `rear.relative(facing, part.ordinal)` and the rear is always `pos.relative(facing.opposite, part.ordinal)`.
 * Nothing else encodes the arrangement, which is why dropping from three blocks to two changed the enum and
 * the collision shapes and no logic at all.
 *
 * [HORIZONTAL_FACING] is the direction the muzzle points, which is the direction the player was looking --
 * *not* the `.opposite` that the helm and engine use. Those two want their faces turned toward you; a gun
 * wants to fire away from you.
 */
class CannonBlock : Block(
    blockProps().mapColor(MapColor.METAL)
        .requiresCorrectToolForDrops()
        .strength(4.0F)
        .sound(SoundType.METAL)
        // The model is nowhere near a full cube; without this the game culls the faces of whatever it is
        // stood against and you see straight through the hull behind it.
        .noOcclusion()
) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(HORIZONTAL_FACING, Direction.NORTH)
                .setValue(CANNON_PART, CannonPart.REAR)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HORIZONTAL_FACING).add(CANNON_PART)
    }

    /**
     * Claim all three blocks, or none.
     *
     * Returning null refuses the placement outright, which is what leaves the item in the player's hand
     * instead of dropping a one-third cannon into a space too small for it.
     */
    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        val facing = ctx.horizontalDirection
        val rear = ctx.clickedPos

        for (part in CannonPart.entries) {
            if (part == CannonPart.REAR) continue
            val pos = rear.relative(facing, part.ordinal)
            if (!ctx.level.getBlockState(pos).canBeReplaced(ctx)) return null
        }

        return defaultBlockState()
            .setValue(HORIZONTAL_FACING, facing)
            .setValue(CANNON_PART, CannonPart.REAR)
    }

    /** Vanilla places only the block that was clicked; the other two thirds are ours to fill in. */
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return

        val facing = state.getValue(HORIZONTAL_FACING)
        for (part in CannonPart.entries) {
            if (part == CannonPart.REAR) continue
            level.setBlock(
                pos.relative(facing, part.ordinal),
                state.setValue(CANNON_PART, part),
                Block.UPDATE_ALL
            )
        }
    }

    /**
     * Break any third and the whole gun goes.
     *
     * The block that was actually broken has already dropped its own loot by the time this runs, and the two
     * we clear here go via `setBlock` to air, which never drops. That is what makes breaking any part yield
     * exactly one cannon -- including when the cause was an explosion or a piston rather than a player.
     */
    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, isMoving: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, isMoving)

        // Clearing the other two re-enters this method for each of them. Block instances are singletons and
        // this only ever runs on the server thread, so a plain flag is enough to stop the recursion.
        if (dismantling) return
        dismantling = true
        try {
            val facing = state.getValue(HORIZONTAL_FACING)
            val rear = pos.relative(facing.opposite, state.getValue(CANNON_PART).ordinal)

            for (part in CannonPart.entries) {
                val other = rear.relative(facing, part.ordinal)
                if (other == pos) continue

                // Only clear a block that is genuinely part of THIS gun. Two cannons parked nose to tail
                // otherwise take each other with them.
                val there = level.getBlockState(other)
                if (there.block !== this) continue
                if (there.getValue(CANNON_PART) != part) continue
                if (there.getValue(HORIZONTAL_FACING) != facing) continue

                level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
            }
        } finally {
            dismantling = false
        }
    }

    private var dismantling = false

    /**
     * Keep the muzzle pointing where it was pointing when a ship is assembled or disassembled.
     *
     * The base implementation is identity, which on a three-block structure is worse than merely cosmetic:
     * the parts are moved to their rotated positions regardless, so a stale facing leaves each third looking
     * for its neighbours along the wrong axis and the gun falls apart the first time anything touches it.
     */
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(HORIZONTAL_FACING)))

    override fun getShape(
        state: BlockState,
        getter: BlockGetter,
        pos: BlockPos,
        ctx: CollisionContext
    ): VoxelShape = when (state.getValue(CANNON_PART)) {
        CannonPart.REAR -> REAR_SHAPE[state.getValue(HORIZONTAL_FACING)]
        CannonPart.FRONT -> FRONT_SHAPE[state.getValue(HORIZONTAL_FACING)]
    }

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun codec(): MapCodec<CannonBlock> = CODEC

    companion object {
        val CODEC: MapCodec<CannonBlock> = simpleCodec { CannonBlock() }

        // Authored facing NORTH and rotated by DirectionalShape, matching how the blockstate rotates the
        // models. Each is the part's own geometry clamped into its block: the rear carries wheels, carriage
        // and breech and is close to solid, while the front is only barrel, so there is walkable air under
        // a run-out gun rather than an invisible wall.
        private val REAR_SHAPE = DirectionalShape(RotShapes.box(0.0, 0.0, 0.0, 16.0, 15.0, 16.0))
        private val FRONT_SHAPE = DirectionalShape(RotShapes.box(3.0, 6.0, 0.0, 13.0, 16.0, 16.0))
    }
}
