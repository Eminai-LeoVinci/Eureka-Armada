package org.valkyrienskies.eureka.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class BalloonBlock(properties: Properties) : Block(properties) {

    override fun fallOn(level: Level, state: BlockState, blockPos: BlockPos, entity: Entity, f: Float) {
        entity.causeFallDamage(f, 0.2f, entity.damageSources().fall())
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level.isClientSide) return
        level as ServerLevel

        // vs-core 2.5+ requires LoadedServerShip for attachment ops. Unloaded ships pick
        // up their balloon count via the assembly counter pass in ShipHelmBlockEntity.assemble().
        val ship = level.getLoadedShipManagingPos(pos) ?: return
        EurekaShipControl.getOrCreate(ship).balloons += 1
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        super.onRemove(state, level, pos, newState, isMoving)

        if (level.isClientSide) return
        level as ServerLevel

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let {
            it.balloons -= 1
        }
    }

    override fun onProjectileHit(level: Level, state: BlockState, hit: BlockHitResult, projectile: Projectile) {
        if (level.isClientSide) return

        level.destroyBlock(hit.blockPos, false)
        Direction.entries.forEach {
            val neighbor = hit.blockPos.relative(it)
            if (level.getBlockState(neighbor).block == this &&
                level.random.nextFloat() < EurekaConfig.SERVER.popSideBalloonChance
            ) {
                level.destroyBlock(neighbor, false)
            }
        }
    }
}
