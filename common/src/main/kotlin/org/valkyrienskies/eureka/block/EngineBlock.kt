package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
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
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.eureka.EurekaProperties.HEAT
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class EngineBlock : BaseEntityBlock(
    Properties.of().mapColor(MapColor.STONE)
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

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        blockHitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
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

    // Keep the ship's engine count live. It was only ever captured at assembly
    // (ShipHelmBlockEntity.assemble), so an engine added or removed afterwards left the count -- and with
    // it the helm's "Top Speed" estimate, which is derived from it -- stale, while the physics used the
    // real power the engines actually reported. Balloons and floaters already track themselves this way.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level.isClientSide) return
        // onPlace also fires for same-block state changes, and this engine rewrites its own HEAT every
        // few ticks while burning -- without this guard the count would inflate on every heat step.
        if (oldState.block == this) return

        val ship = (level as ServerLevel).getLoadedShipManagingPos(pos) ?: return
        EurekaShipControl.getOrCreate(ship).engines += 1
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        super.onRemove(state, level, pos, newState, isMoving)

        if (level.isClientSide) return
        // 1.21.5 moved real removal into affectNeighborsAfterRemoval, which needs no guard because it
        // only fires when the block is actually gone. Here it is still onRemove, which vanilla also calls
        // for a plain state rewrite -- so without this the burning engine's own HEAT steps would decrement
        // the ship's engine count every few ticks until it hit zero.
        if (newState.block == this) return

        (level as ServerLevel).getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let {
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
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        if (!level.isClientSide) {
            (level.getBlockEntity(pos) as? EngineBlockEntity)?.markRedstoneDirty()
        }
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
    }

    // Rotate the facing with the block so the engine keeps its orientation when a ship is
    // assembled/disassembled (the base Block.rotate is identity, which left engines facing
    // their original world direction after the hull was re-oriented). Mirrors ShipHelmBlock.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState? {
        return state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING) as Direction)) as BlockState
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

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState? {
        if (!level.isClientSide) {
            val blockEntity = level.getBlockEntity(pos) as EngineBlockEntity

            // Drop inventory
            if (!blockEntity.fuel.isEmpty) {
                level.addFreshEntity(ItemEntity(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), blockEntity.fuel))
            }
        }

        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun codec() = CODEC

    companion object {
        val CODEC: MapCodec<EngineBlock> = simpleCodec { EngineBlock() }
    }
}
