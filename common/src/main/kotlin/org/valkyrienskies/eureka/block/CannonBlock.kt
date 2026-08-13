package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.EurekaProperties.CANNON_PART
import org.valkyrienskies.eureka.EurekaProperties.ELEVATION
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.CannonFire
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
 * It matters more than tidiness, because a cannon is **fired by right-clicking it**. Geometry that hangs
 * outside its own blocks is geometry you can see but not click, so a player aiming at the muzzle would punch
 * straight through it. One unit is small enough not to matter; twelve was not.
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
class CannonBlock : BaseEntityBlock(
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
                .setValue(ELEVATION, EurekaProperties.ELEVATION_LEVEL)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HORIZONTAL_FACING).add(CANNON_PART).add(ELEVATION)
    }

    /**
     * Claim both blocks, or neither.
     *
     * Returning null refuses the placement outright, which is what leaves the item in the player's hand
     * instead of dropping half a cannon into a space too small for it.
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

    /** Vanilla places only the block that was clicked; the other half is ours to fill in. */
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
     * Break either half and the whole gun goes.
     *
     * The block that was actually broken has already dropped its own loot by the time this runs, and anything
     * we clear here goes via `setBlock` to air, which never drops. That is what makes breaking either half yield
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

    /** Only the breech end carries the magazine; see [CannonBlockEntity]. */
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(CANNON_PART) == CannonPart.REAR) CannonBlockEntity(pos, state) else null

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /**
     * Strike a spark on the gun and it goes off.
     *
     * Flint and steel fires a cannon from any stance -- no crouch, no modifier, just the tool against the gun.
     * The crouching case cannot arrive here, because vanilla never calls a block when a crouching player has a
     * full hand; `CannonRegistrationsFabric` catches that one and fires it identically, which also stops the
     * flint doing what it would otherwise do and setting the gun alight.
     */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        // An empty hand belongs to useWithoutItem -- the magazine, or the elevation if crouching.
        //
        // It has to be TRY_WITH_EMPTY_HAND and not PASS. Only TRY_WITH_EMPTY_HAND continues to
        // useWithoutItem; PASS ends the interaction there and the block is never asked again. Returning PASS
        // here is what silently killed both empty-hand gestures at once -- a right-click on a cannon simply
        // did nothing, with no error to show for it.
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND

        // Anything else in hand does whatever it normally does, so a block can still be placed against a gun
        // rather than every click being swallowed by it.
        if (!CannonFire.isIgniter(stack.item)) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        CannonFire.strike(level as ServerLevel, pos, player as? ServerPlayer, stack, hand)
        return InteractionResult.CONSUME
    }

    /**
     * Empty-handed: crouching lays the barrel, standing opens the magazine.
     *
     * Either half of the gun answers. The front block has no block entity of its own, so it walks back to the
     * rear rather than doing nothing: a player clicking the barrel means the same gun as one clicking the
     * trail, and a menu that opened from one end only would read as a bug every time.
     */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        val facing = state.getValue(HORIZONTAL_FACING)
        val rear = pos.relative(facing.opposite, state.getValue(CANNON_PART).ordinal)

        if (player.isSecondaryUseActive) {
            elevate(level, rear, facing, state.getValue(ELEVATION), player)
            return InteractionResult.CONSUME
        }

        val magazine = level.getBlockEntity(rear) as? CannonBlockEntity ?: return InteractionResult.PASS
        player.openMenu(magazine)
        return InteractionResult.CONSUME
    }

    /**
     * Lay the barrel one step, wrapping from the top back to the bottom.
     *
     * Both halves are written, because the elevation is a fact about the gun rather than about a block and
     * the two models have to agree -- a barrel that elevated in the front block while the breech stayed level
     * would tear the gun in half visually.
     */
    /** Lay the barrel from anywhere on the gun. Public so the crouch-with-flint hook can reach it. */
    fun elevateFrom(level: Level, clicked: BlockPos, state: BlockState, player: Player) {
        val facing = state.getValue(HORIZONTAL_FACING)
        val rear = clicked.relative(facing.opposite, state.getValue(CANNON_PART).ordinal)
        elevate(level, rear, facing, state.getValue(ELEVATION), player)
    }

    private fun elevate(level: Level, rear: BlockPos, facing: Direction, from: Int, player: Player) {
        val next = (from + 1) % (ELEVATION.possibleValues.size)

        for (part in CannonPart.entries) {
            val pos = rear.relative(facing, part.ordinal)
            val there = level.getBlockState(pos)
            if (there.block !== this) continue
            // UPDATE_CLIENTS only: this is a cosmetic re-pose, and asking for neighbour updates would rattle
            // whatever is resting against the gun every time somebody adjusted the aim.
            level.setBlock(pos, there.setValue(ELEVATION, next), Block.UPDATE_CLIENTS)
        }

        val degrees = EurekaProperties.elevationDegrees(next)
        player.displayClientMessage(
            Component.translatable(
                "info.vs_eureka.cannon_elevation",
                if (degrees > 0) "+%.1f".format(degrees) else "%.1f".format(degrees)
            ),
            true
        )
        level.playSound(null, rear, SoundEvents.LANTERN_PLACE, SoundSource.BLOCKS, 0.7f, 1.4f)
    }

    /** Spill the powder and shot when the gun is broken, like any other container. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide && state.getValue(CANNON_PART) == CannonPart.REAR) {
            (level.getBlockEntity(pos) as? CannonBlockEntity)?.let { Containers.dropContents(level, pos, it) }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

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
