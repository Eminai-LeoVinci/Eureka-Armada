package org.valkyrienskies.eureka.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.redstone.Orientation
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.blockProps
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class FloaterBlock : Block(
    blockProps().mapColor(MapColor.WOOD)
        .sound(SoundType.WOOL).strength(1.5f, 3.0f).requiresCorrectToolForDrops()
) {
    init {
        registerDefaultState(defaultBlockState().setValue(POWER, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(POWER)
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level.isClientSide) return
        level as ServerLevel

        // onPlace also fires for same-block state changes, and neighborChanged below rewrites this
        // block's own POWER on every redstone edge. Without this guard that rewrite re-counted the
        // floater on top of the delta neighborChanged had already applied: powering a floater netted
        // zero, but UN-powering it added a second full-strength floater, so a lever wired to a floater
        // inflated the ship's buoyancy by 15 per off-cycle, permanently.
        if (oldState.block == this) return

        val floaterPower = 15 - state.getValue(POWER)

        val ship = level.getLoadedShipManagingPos(pos) ?: return
        EurekaShipControl.getOrCreate(ship).floaters += floaterPower
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, orientation, isMoving)

        if (level as? ServerLevel == null) return

        val signal = level.getBestNeighborSignal(pos)
        val currentPower = state.getValue(POWER)

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let {
            it.floaters += (currentPower - signal)
        }

        level.setBlock(pos, state.setValue(POWER, signal), 2)
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, isMoving: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, isMoving)

        val floaterPower = 15 - state.getValue(POWER)

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let {
            it.floaters -= floaterPower
        }
    }
}
