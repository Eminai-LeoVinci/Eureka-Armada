package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.CrewBerths
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.DirectionalShape
import org.valkyrienskies.eureka.util.RotShapes
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

class ShipHelmBlock(properties: Properties, val woodType: IWoodType) : BaseEntityBlock(properties) {
    val HELM_BASE = RotShapes.box(2.0, 0.0, 2.0, 14.0, 2.0, 14.0)
    val HELM_POLE = RotShapes.box(4.0, 2.0, 5.0, 12.0, 13.0, 13.0)

    val HELM_SHAPE = DirectionalShape(RotShapes.or(HELM_BASE, HELM_POLE))

    init {
        registerDefaultState(
            this.stateDefinition.any()
                .setValue(HORIZONTAL_FACING, Direction.NORTH)
                .setValue(EurekaProperties.MARK, HelmMark.NORMAL)
        )
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level.isClientSide) return
        level as ServerLevel

        val ship = level.getLoadedShipManagingPos(pos) ?: return
        EurekaShipControl.getOrCreate(ship).helms += 1
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, isMoving: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, isMoving)

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let { control ->

            if (control.helms <= 1 && control.seatedPlayer?.vehicle?.type == ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE) {
                control.seatedPlayer!!.unRide()
                control.seatedPlayer = null
            }

            control.helms -= 1
        }
    }

    /**
     * A Heart of the Sea offered to the wheel buys one more berth.
     *
     * This overrides [useItemOn] rather than [useWithoutItem] because vanilla only routes to the latter when
     * the player's hand is empty. Anything [CrewBerths] passes on falls straight through to `super`, whose
     * default body returns `TRY_WITH_EMPTY_HAND` -- and it is exactly that fall-through which keeps an
     * empty-handed click opening the menu or sitting the player down. Returning `PASS` here instead would
     * quietly break both.
     *
     * This is only HALF the gesture: vanilla never calls a block at all when the player is crouching with a
     * full hand, so the crouching case is caught by a `UseBlockCallback` in `CrewRegistrationsFabric` and both
     * arms meet in [CrewBerths.offerHeart]. See there for why it is split that way.
     *
     * Deliberately works on an UNASSEMBLED helm: berths are a fact about the captain rather than about a hull.
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
        val berth = CrewBerths.offerHeart(level, pos, player, stack)
        if (berth != InteractionResult.PASS) return berth
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        blockHitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val blockEntity = level.getBlockEntity(pos) as ShipHelmBlockEntity

        return if (player.isSecondaryUseActive) {
            player.openMenu(blockEntity)
            InteractionResult.CONSUME
        } else if (level.getLoadedShipManagingPos(pos) == null) {
            player.displayClientMessage(Component.translatable("info.vs_eureka.sneak_to_open_helm"), true)
            InteractionResult.CONSUME
        } else if (blockEntity.sit(player)) {
            InteractionResult.CONSUME
        } else InteractionResult.PASS
    }

    override fun getRenderShape(blockState: BlockState): RenderShape {
        return RenderShape.MODEL
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        return defaultBlockState()
            .setValue(HORIZONTAL_FACING, ctx.horizontalDirection.opposite)
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HORIZONTAL_FACING, EurekaProperties.MARK)
    }

    override fun newBlockEntity(blockPos: BlockPos, state: BlockState): BlockEntity {
        return ShipHelmBlockEntity(blockPos, state)
    }

    override fun getShape(
        blockState: BlockState,
        blockGetter: BlockGetter,
        blockPos: BlockPos,
        collisionContext: CollisionContext
    ): VoxelShape {
        return HELM_SHAPE[blockState.getValue(HORIZONTAL_FACING)]
    }

    override fun useShapeForLightOcclusion(blockState: BlockState): Boolean {
        return true
    }

    override fun isPathfindable(blockState: BlockState, pathComputationType: PathComputationType): Boolean {
        return false
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState? {
        return state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING) as Direction)) as BlockState
    }

    override fun <T : BlockEntity?> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T> = BlockEntityTicker { level, pos, state, blockEntity ->
        if (level.isClientSide) return@BlockEntityTicker
        if (blockEntity is ShipHelmBlockEntity) {
            blockEntity.tick()
        }
    }

    override fun codec() = CODEC

    companion object {
        val CODEC: MapCodec<ShipHelmBlock> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                propertiesCodec(),
                WoodType.CODEC.fieldOf("wood_type").forGetter { it.woodType as WoodType }
            ).apply(instance, ::ShipHelmBlock)
        }
    }
}
