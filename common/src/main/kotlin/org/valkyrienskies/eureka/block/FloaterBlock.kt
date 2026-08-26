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
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getShipObjectManagingPos

class FloaterBlock : Block(
    Properties.of().mapColor(MapColor.WOOD)
        .sound(SoundType.WOOL).strength(1.5f, 3.0f).requiresCorrectToolForDrops()
) {
    init {
        registerDefaultState(defaultBlockState().setValue(POWER, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(POWER)
    }

    @OptIn(GameTickOnly::class)
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

        val ship = level.getLoadedShipManagingPos(pos) ?: level.getShipManagingPos(pos) ?: return
        EurekaShipControl.deferUntilLoaded(ship) { it.floaters += floaterPower }
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)

        if (level as? ServerLevel == null) return

        val signal = level.getBestNeighborSignal(pos)
        val currentPower = state.getValue(POWER)

        val ship = level.getLoadedShipManagingPos(pos) ?: level.getShipManagingPos(pos)
        if(ship != null) EurekaShipControl.deferUntilLoaded(ship) { it.floaters += (currentPower - signal) }

        level.setBlock(pos, state.setValue(POWER, signal), 2)
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        super.onRemove(state, level, pos, newState, isMoving)

        if (level.isClientSide) return
        level as ServerLevel

        // The same-block guard the placement path needs, and on this version the removal path needs it
        // too. 1.21.5 split real removal off into affectNeighborsAfterRemoval, which fires only when the
        // block is genuinely gone; here it is still onRemove, which vanilla also calls for a plain state
        // rewrite (which is why RedStoneWireBlock guards it the same way). Ungated, the POWER rewrite in
        // neighborChanged ran BOTH this and onPlace on top of the delta neighborChanged had already
        // applied, so every redstone edge moved the ship's floater count by twice what it should.
        if (newState.block == this) return

        val floaterPower = 15 - state.getValue(POWER)

        val ship = level.getLoadedShipManagingPos(pos) ?: level.getShipManagingPos(pos) ?: return
        EurekaShipControl.deferUntilLoaded(ship) { it.floaters -= floaterPower }
    }
}
