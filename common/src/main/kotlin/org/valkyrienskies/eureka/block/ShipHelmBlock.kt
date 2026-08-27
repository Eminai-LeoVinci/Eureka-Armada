package org.valkyrienskies.eureka.block

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
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
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.Explosion
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.function.BiConsumer
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.CrewBerths
import org.valkyrienskies.eureka.crew.CrewEggs
import org.valkyrienskies.eureka.crew.CrewProfession
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.pirate.PirateShips
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

        // onPlace also fires for same-block STATE changes -- the engine guards this against its own HEAT
        // steps, and the helm needs it for its MARK flips: every crew wipe (PIRATE -> TAKEN) and respawn
        // (TAKEN -> PIRATE) was quietly incrementing the count, so a conquered wheel broke to helms=1 and
        // the conquest freeze released itself as "claimed" the same second it opened.
        if (oldState.block == this) return

        val ship = level.getLoadedShipManagingPos(pos) ?: return
        val control = EurekaShipControl.getOrCreate(ship)
        control.helms += 1
        // A marked wheel makes the hull a raider, which is all the damage repercussions need to know to
        // pick her thresholds. Counted here rather than looked up, because the physics thread reads it.
        if (state.getValue(EurekaProperties.MARK) != HelmMark.NORMAL) control.pirateHelms += 1
    }

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

        // A pirate-marked wheel vanishing MIGHT be the conquest -- or just assembly relocating the block.
        // The manager owns telling those apart (the relocated wheel reports back within a tick or two; a
        // broken one never does), so this only reports the disappearance.
        val wasMarked = state.getValue(EurekaProperties.MARK) != HelmMark.NORMAL
        if (wasMarked) {
            PirateShips.helmVanished(level, pos)
        }

        level.getLoadedShipManagingPos(pos)?.getAttachment<EurekaShipControl>()?.let { control ->

            if (control.helms <= 1 && control.seatedPlayer?.vehicle?.type == ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE) {
                control.seatedPlayer!!.unRide()
                control.seatedPlayer = null
            }

            control.helms -= 1
            // The moment a raider's wheel breaks she stops being a raider, which is exactly the moment a
            // conquest happens -- the prize is on the player thresholds from here, repairs and all.
            if (wasMarked) control.pirateHelms = (control.pirateHelms - 1).coerceAtLeast(0)
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
        val berth = CrewBerths.offerHeart(level, pos, player, stack)
        if (berth != InteractionResult.PASS) return berth

        // A villager egg on the wheel hatches a crewman already holding the trade -- the same authoring
        // gesture the shipwright's bench answers, and gated the same way. It sets a TRADE and not a berth:
        // signing somebody onto the articles is Sneak+C and costs a Heart, exactly as it does for a
        // villager who wandered up and claimed the wheel on their own.
        if (stack.item is SpawnEggItem && player.hasInfiniteMaterials() && !PirateHelm.gated(state)) {
            if (level.isClientSide) return InteractionResult.SUCCESS
            val refusal = CrewEggs.hatch(
                level as ServerLevel, pos, hitResult.direction, stack.item as SpawnEggItem, stack,
                player as? ServerPlayer, CrewProfession.PROFESSION_KEY
            )
            if (refusal != CrewEggs.NOT_A_VILLAGER) {
                CrewEggs.tell(player as? ServerPlayer, refusal, "crewman")
                return InteractionResult.SUCCESS
            }
        }
        return InteractionResult.PASS // 1.21.1: the wrapper maps PASS to the default fall-through super would take
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        blockHitResult: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        // Pirate gate, door 1+2 of 14: the menu and the seat. See PirateHelm for the census.
        if (PirateHelm.gated(state)) {
            PirateHelm.deny(player)
            return InteractionResult.CONSUME
        }
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

    /**
     * A PIRATE wheel cannot be mined -- by anyone, with anything. Zero progress is what makes the block
     * FEEL inviolable (no cracks, no swing progress) rather than snapping back after the fact. This is the
     * break half of the pirate gate; the interaction half is in [useWithoutItem]. The server consults this
     * once per break attempt, which is where the refusal message comes from; TAKEN falls through and breaks
     * normally, because breaking a dead pirate's wheel is the conquest.
     */
    override fun getDestroyProgress(
        state: BlockState,
        player: Player,
        level: BlockGetter,
        pos: BlockPos
    ): Float {
        if (PirateHelm.inviolable(state)) {
            // How many defenders remain, when this wheel keeps records and the server is asking.
            val remaining = (level as? ServerLevel)
                ?.let { server -> (server.getBlockEntity(pos) as? ShipHelmBlockEntity)?.let { PirateShips.livingCrew(server, it) } }
                ?: -1
            PirateHelm.denyBreak(player, remaining)
            return 0.0f
        }
        return super.getDestroyProgress(state, player, level, pos)
    }

    /**
     * A pirate's wheel is never an item, black OR white -- not by silk touch, not by any loot path. One
     * override here beats conditions in nine per-wood loot JSONs, and it also covers destroyBlock(drop=true)
     * and explosion drops in a single place. The white wheel BREAKS (that is the conquest) but pays nothing:
     * the prize is the ship under your feet, not a free helm off its corpse -- first cut dropped one and the
     * user sent it back.
     */
    override fun getDrops(state: BlockState, params: LootParams.Builder): MutableList<ItemStack> {
        if (PirateHelm.gated(state)) return mutableListOf()
        return super.getDrops(state, params)
    }

    /**
     * Explosions cannot pop a PIRATE wheel either -- a creeper on deck or a lucky TNT raft must not hand
     * out the conquest for free. Cannon fire is guarded separately in CannonDamage (it removes blocks
     * directly, never through the explosion path), and deck fires in MixinShipFireContainment.
     */
    override fun onExplosionHit(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        explosion: Explosion,
        dropConsumer: BiConsumer<ItemStack, BlockPos>
    ) {
        if (PirateHelm.inviolable(state)) return
        super.onExplosionHit(state, level, pos, explosion, dropConsumer)
    }

    override fun getRenderShape(blockState: BlockState): RenderShape {
        return RenderShape.MODEL
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState? {
        // Pirate gate, placement door: a ship that answers to a pirate wheel -- black OR white -- takes no
        // other wheel. A helm planted beside the pirate's would hand over the controls (and, later, the
        // conquest) without the fight; breaking theirs is the only way in. Server-side only: the client's
        // guess gets corrected by the refused placement.
        val level = ctx.level
        if (level is ServerLevel) {
            val ship = level.getLoadedShipManagingPos(ctx.clickedPos) as? LoadedServerShip
            if (ship != null && PirateHelm.shipHasPirateWheel(level, ship)) {
                (ctx.player as? ServerPlayer)?.let {
                    PathMessages.send(
                        it,
                        "This ship answers to its pirate wheel -- break it before placing your own.",
                        PathMessages.Kind.ERROR
                    )
                }
                return null
            }
            // The dormant twin of the check above: a wheel seated on a sleeping pirate's deck from range
            // would ride into the assembled ship as a working helm. Boarding wakes the ship the tick a
            // boot touches it, but a block can be placed without ever touching.
            if (ship == null && PirateShips.nearDormantPirateHull(level, ctx.clickedPos)) {
                (ctx.player as? ServerPlayer)?.let {
                    PathMessages.send(
                        it,
                        "Too close to a pirate hull to seat a wheel -- conquer the ship first.",
                        PathMessages.Kind.ERROR
                    )
                }
                return null
            }
        }
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
