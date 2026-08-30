package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.eureka.EurekaProperties.HEAT
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.eureka.pirate.PirateFittings
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.blockProps
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class EngineBlock : BaseEntityBlock(
    blockProps().mapColor(MapColor.STONE)
        .requiresCorrectToolForDrops()
        .strength(3.5F)
        .sound(SoundType.STONE)
        .lightLevel { state -> if (state.getValue(HEAT) > 0) state.getValue(HEAT) + 9 else 0 }
) {
    init {
        registerDefaultState(
            this.stateDefinition.any()
                .setValue(HORIZONTAL_FACING, Direction.NORTH)
                .setValue(HEAT, 0)
        )
    }

    override fun newBlockEntity(blockPos: BlockPos, state: BlockState): BlockEntity =
        EngineBlockEntity(blockPos, state)

    /**
     * Fuel in hand stokes the firebox where it stands, mirroring how a cannon takes its round: walking a
     * shovel of coal up to the engine should not detour through the screen. Anything the furnace registry
     * refuses falls through untouched, so blocks can still be placed against the engine, and the empty hand
     * keeps opening the firebox screen below.
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
        // TRY_WITH_EMPTY_HAND, not PASS -- only it continues to useWithoutItem (see CannonBlock for the
        // full account of the trap).
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND

        val engine = level.getBlockEntity(pos) as? EngineBlockEntity ?: return InteractionResult.PASS
        if (!engine.canPlaceItem(0, stack)) return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS

        val loaded = engine.load(stack, !player.hasInfiniteMaterials())
        if (loaded == 0) {
            // Full, or a different fuel already in the box. Consumed rather than passed, so the click never
            // falls through to placing the fuel block against the engine.
            return InteractionResult.CONSUME
        }

        level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.6f, 0.8f)
        return InteractionResult.CONSUME
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        blockHitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS

        // Her bunkers are behind the same fight her magazines are. An engine screen on a raider still
        // shooting at you is a chest of coal blocks with the lid off, and a generated hull carries five of
        // them stocked to sail forever. Lifts the instant the wheel goes, like every other rule aboard.
        if (PirateHelm.aboardPirateShip(level, pos)) {
            PirateHelm.denyEngines(player)
            return InteractionResult.CONSUME
        }

        val blockEntity = level.getBlockEntity(pos) as EngineBlockEntity

        player.openMenu(blockEntity)

        return InteractionResult.CONSUME
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder
            .add(HORIZONTAL_FACING)
            .add(HEAT)
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        return defaultBlockState()
            .setValue(HORIZONTAL_FACING, ctx.horizontalDirection.opposite)
    }

    // Rotate the facing with the block so the engine keeps its orientation when a ship is
    // assembled/disassembled (the base Block.rotate is identity, which left engines facing
    // their original world direction after the hull was re-oriented). Mirrors ShipHelmBlock.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState? {
        return state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING) as Direction)) as BlockState
    }

    // Keep the ship's engine count live. It was only ever captured at assembly
    // (ShipHelmBlockEntity.assemble), so an engine added or removed afterwards left the count -- and with
    // it the helm's "Top Speed" estimate, which is derived from it -- stale, while the physics used the
    // real power the engines actually reported. Balloons and floaters already track themselves this way.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level.isClientSide) return
        // onPlace also fires for same-block state changes, and this engine rewrites its own HEAT every
        // few ticks while burning -- without this guard the count would inflate on every heat step.
        // (affectNeighborsAfterRemoval below needs no such guard: it only fires on real removal.)
        if (oldState.block == this) return

        val ship = (level as ServerLevel).getLoadedShipManagingPos(pos) ?: return
        EurekaShipControl.getOrCreate(ship).engines += 1
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, isMoving: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, isMoving)

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let {
            it.engines = (it.engines - 1).coerceAtLeast(0)
        }
    }

    // Tell the engine its redstone signal may have changed, so it re-reads instead of polling all six
    // neighbours every tick. Mirrors how AnchorBlock reacts to redstone.
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        isMoving: Boolean
    ) {
        if (!level.isClientSide) {
            (level.getBlockEntity(pos) as? EngineBlockEntity)?.markRedstoneDirty()
        }
        super.neighborChanged(state, level, pos, block, orientation, isMoving)
    }

    override fun getRenderShape(blockState: BlockState): RenderShape {
        return RenderShape.MODEL
    }

    override fun getShadeBrightness(state: BlockState, level: BlockGetter, pos: BlockPos): Float {
        return if ((state.getValue(HEAT) ?: 0) > 0) {
            1.0f
        } else {
            super.getShadeBrightness(state, level, pos)
        }
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, randomSource: RandomSource) {
        val heat = state.getValue(HEAT)
        if (heat == 0) return

        val d = pos.x.toDouble() + 0.5
        val e = pos.y.toDouble()
        val f = pos.z.toDouble() + 0.5

        if (randomSource.nextDouble() < (0.04 * heat)) {
            level.playLocalSound(d, e, f, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.0f, 1.0f, false)
        }

        // Make the amount of particles based of the heat
        if (randomSource.nextDouble() > (0.2 * heat)) return

        val direction = state.getValue(HORIZONTAL_FACING)
        val axis = direction.axis
        val h = randomSource.nextDouble() * 0.6 - 0.3
        val i = if (axis === Direction.Axis.X) direction.stepX.toDouble() * 0.52 else h
        val j = randomSource.nextDouble() * 4.0 / 16.0
        val k = if (axis === Direction.Axis.Z) direction.stepZ.toDouble() * 0.52 else h
        level.addParticle(ParticleTypes.SMOKE, d + i, e + j, f + k, 0.0, 0.0, 0.0)
        level.addParticle(ParticleTypes.FLAME, d + i, e + j, f + k, 0.0, 0.0, 0.0)
    }

    override fun <T : BlockEntity?> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T> = BlockEntityTicker { level, pos, state, blockEntity ->
        if (level.isClientSide) return@BlockEntityTicker
        if (blockEntity is EngineBlockEntity) {
            blockEntity.tick()
        }
    }

    /**
     * Spill the bunker when the engine is broken -- but a raider's pays a fixed price.
     *
     * The exact shape of the cannon's magazine rule, for the same reason. An ordinary engine gives back
     * whatever is in her, like any other container. A pirate's cannot: a hull is authored with her engines
     * filled so she will sail forever, and paying a full stack of coal blocks out of each of five would
     * make one conquered sloop worth more fuel than any player would ever mine.
     *
     * So a taken engine yields a token -- two coal, whatever her slot says. While her wheel still stands
     * she gives nothing at all, and the bunker is emptied rather than dropped, which is what keeps the
     * whole-hull drop rule from having a hole in it here.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState? {
        if (!level.isClientSide) {
            val blockEntity = level.getBlockEntity(pos) as EngineBlockEntity

            fun spill(stack: ItemStack) {
                if (stack.isEmpty) return
                level.addFreshEntity(ItemEntity(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), stack))
            }

            when {
                !blockEntity.pirate -> spill(blockEntity.fuel)
                PirateHelm.dropsSuppressedAt(level, pos) -> blockEntity.fuel = ItemStack.EMPTY
                else -> {
                    blockEntity.fuel = ItemStack.EMPTY
                    spill(ItemStack(Items.COAL, PirateFittings.PRIZE_COAL))
                }
            }
        }

        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun codec() = CODEC

    companion object {
        val CODEC: MapCodec<EngineBlock> = simpleCodec { EngineBlock() }
    }
}
