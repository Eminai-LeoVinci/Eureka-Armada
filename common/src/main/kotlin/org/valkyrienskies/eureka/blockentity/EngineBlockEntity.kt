package org.valkyrienskies.eureka.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.StackedContents
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.StackedContentsCompatible
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Math.lerp
import org.joml.Math.min
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties.HEAT
import org.valkyrienskies.eureka.gui.engine.EngineScreenMenu
import org.valkyrienskies.eureka.registry.FuelRegistry
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.KtContainerData
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import kotlin.math.ceil
import kotlin.math.max

class EngineBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(EurekaBlockEntities.ENGINE.get(), pos, state),
    StackedContentsCompatible,
    WorldlyContainer {

    private val ship: LoadedServerShip? get() = (this.level as ServerLevel).getLoadedShipManagingPos(this.blockPos)
    val data = KtContainerData()
    private var heatLevel by data
    private var fuelLeft by data
    private var fuelTotal by data
    var fuel: ItemStack = ItemStack.EMPTY
    private var lastFuelValue = 1600; // coal: 1600

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu =
        EngineScreenMenu(containerId, inventory, this)

    override fun getDefaultName(): Component = Component.translatable("gui.vs_eureka.engine")

    override fun getItems(): NonNullList<ItemStack> = NonNullList.of(fuel)

    override fun setItems(nonNullList: NonNullList<ItemStack>) {
        if (nonNullList.isNotEmpty()) {
            fuel = nonNullList.first()
        } else {
            fuel = ItemStack.EMPTY
        }
    }

    // Redstone signal, refreshed on neighbour changes rather than polled. hasNeighborSignal reads all
    // six neighbouring block states, and it did so for every engine on every ship every tick even
    // though the answer only changes when a neighbour does. EngineBlock.neighborChanged marks it
    // stale; the periodic re-read is a safety net for anything that powers the block without sending
    // a neighbour update (and re-syncs after load, assembly or a shipyard relocation).
    private var redstoneSignal = false
    private var redstoneCheckedAtTick = Long.MIN_VALUE

    fun markRedstoneDirty() {
        redstoneCheckedAtTick = Long.MIN_VALUE
    }

    private fun hasRedstoneSignal(): Boolean {
        val level = this.level!!
        val gameTime = level.gameTime
        if (gameTime - redstoneCheckedAtTick >= REDSTONE_RESYNC_INTERVAL_TICKS) {
            redstoneSignal = level.hasNeighborSignal(blockPos)
            redstoneCheckedAtTick = gameTime
        }
        return redstoneSignal
    }

    private var heat = 0f
    fun tick() {
        if (this.level!!.isClientSide) return

        // Resolve THIS engine's managing ship + its EurekaShipControl once per tick (the ship getter walks the
        // loaded-ship index, so cache it). Two engine fields are MODE-affected -- engineHeatGain and
        // enginePowerLinear -- and must honor the per-ship vanilla/advanced preset rather than the global
        // ADVANCED defaults; everything else (heat loss, fuel, redstone) stays global on EurekaConfig.SERVER.
        // A loose engine (not on a ship / control attachment absent) falls back to EurekaConfig.SERVER, which
        // is the ADVANCED preset, so unmanaged engines behave exactly as before.
        val eurekaShipControl = ship?.getAttachment(EurekaShipControl::class.java)
        val engineCfg = eurekaShipControl?.engineCfg ?: EurekaConfig.SERVER

        // Heat ceiling, derived per-tick from this ship's engineHeatGain preset (mode-affected) so the cap
        // tracks the same vanilla/advanced gain used below; was a stale construction-time field.
        val maxEffectiveFuel = 100f - engineCfg.engineHeatGain

        val isPowered = hasRedstoneSignal()
        if (EurekaConfig.SERVER.engineRedstoneBehaviorPause && isPowered) {
            // Still report the tank level while redstone-paused, so a fueled-but-idle engine keeps counting
            // toward the helm "Engine Power: X%" readout instead of dropping to 0.
            eurekaShipControl?.reportEngineFuel(fuelFraction())
            return
        }

        // Disable engine feeding when they are receiving a redstone signal
        if (!isPowered) {
            if (fuelLeft > 0) {

                if (EurekaConfig.SERVER.engineFuelSaving) {
                    if (heat <= maxEffectiveFuel) {
                        heat += scaleEngineHeating(engineCfg.engineHeatGain)
                        fuelLeft--
                    }
                } else {
                    fuelLeft--

                    if (heat <= maxEffectiveFuel) {
                        heat += scaleEngineHeating(engineCfg.engineHeatGain)
                    }
                }

                // Refill while burning
                if (!fuel.isEmpty && lastFuelValue <= EurekaConfig.SERVER.engineMinCapacity - fuelLeft) {
                    consumeFuel()
                }
            } else if (!fuel.isEmpty) {
                consumeFuel()
            }
        }

        val prevHeatLevel = heatLevel
        heatLevel = min(ceil(heat * 4f / 100f).toInt(), 4)
        if (prevHeatLevel != heatLevel) {
            level!!.setBlock(blockPos, this.blockState.setValue(HEAT, heatLevel), 11)
        }

        if (heat > 0) {
            // eurekaShipControl was resolved once at the top of tick(); reuse it here.
            if (eurekaShipControl != null) {
                // Avoid fluctuations in speed
                var effectiveHeat = 1f
                if (heat < maxEffectiveFuel) {
                    effectiveHeat = heat / 100f
                }

                // enginePowerLinear is mode-affected (vanilla 500000f vs advanced 100000f): use this ship's
                // preset so a vanilla ship makes 833d445-strength force AND its boost threshold scales against
                // the same 500000f (boost at ~3 engines), restoring the real pre-overhaul engine feel.
                eurekaShipControl.powerLinear += lerp(
                    EurekaConfig.SERVER.enginePowerLinearMin,
                    engineCfg.enginePowerLinear,
                    effectiveHeat,
                )

                eurekaShipControl.powerAngular += lerp(
                    EurekaConfig.SERVER.enginePowerAngularMin,
                    EurekaConfig.SERVER.enginePowerAngular,
                    effectiveHeat,
                )

                heat -= eurekaShipControl.consumed
            }

            heat = max(heat - scaleEngineCooling(EurekaConfig.SERVER.engineHeatLoss), 0f)
        }

        // Report this engine's fuel level (reservoir + burning charge) to its ship for the helm
        // "Engine Power: X%" tank readout. See [fuelFraction] -- a full slot reads ~100% and falls monotonically
        // as fuel burns, so the aggregated readout no longer bounces up and down.
        eurekaShipControl?.reportEngineFuel(fuelFraction())
    }

    /**
     * This engine's fuel level as a fraction (0..1) of a full fuel slot: the unburned items still in the slot
     * (the reservoir) plus the currently-burning charge, over the slot's max stack. Counts the reservoir per
     * the helm "tank %" definition, and is monotonic as fuel burns (no per-item sawtooth), so the aggregated
     * "Engine Power" readout drifts down smoothly instead of fluctuating. 0 when the engine is completely dry.
     */
    private fun fuelFraction(): Double {
        // Burn-ticks per item for whatever fuel this engine holds; falls back to the last-consumed value once
        // the slot has emptied into the burning buffer.
        val burnPerItem = if (!fuel.isEmpty) getScaledFuel() else lastFuelValue
        if (burnPerItem <= 0) return 0.0
        val maxStack = if (!fuel.isEmpty) fuel.maxStackSize else 64
        val bufferItems = fuelLeft.toDouble() / burnPerItem   // currently-burning charge, in item-equivalents
        val reservoirItems = fuel.count.toDouble()            // unburned items still in the slot
        return ((bufferItems + reservoirItems) / maxStack).coerceIn(0.0, 1.0)
    }

    fun isBurning(): Boolean = fuelLeft > 0

    /**
     * Get fuel value from the item type stored in the engine.
     *
     * @return scaled fuel ticks.
     */
    private fun getScaledFuel(): Int =
        (FuelRegistry.INSTANCE.get(fuel) * EurekaConfig.SERVER.engineFuelMultiplier).toInt()


    /**
     * Absorb one fuel item into the engine.
     */
    private fun consumeFuel() {

        lastFuelValue = getScaledFuel()

        if (lastFuelValue > 0) {
            if (fuelLeft > 0 && lastFuelValue > EurekaConfig.SERVER.engineMinCapacity - fuelLeft) {
                return
            }

            fuelLeft += lastFuelValue
            fuelTotal = max(lastFuelValue, EurekaConfig.SERVER.engineMinCapacity)

            // Handle items like lava buckets
            if (fuel.item.hasCraftingRemainingItem()) {
                fuel = ItemStack(fuel.item.craftingRemainingItem!!, 1)
            } else {
                removeItem(0, 1)
            }
            setChanged()
        }
    }

    /**
     * Scale given heating [value] based on current heat.
     *
     * @return the scaled value.
     */
    private fun scaleEngineHeating(value: Float): Float =
        (100 * EurekaConfig.SERVER.engineHeatChangeExponent - this.heat * EurekaConfig.SERVER.engineHeatChangeExponent + 1f) * value

    /**
     * Scale given cooling [value] based on current heat.
     *
     * @return the scaled value.
     */
    private fun scaleEngineCooling(value: Float): Float =
        (this.heat * EurekaConfig.SERVER.engineHeatChangeExponent + 1f) * value

    override fun saveAdditional(tag: CompoundTag, provider: HolderLookup.Provider) {
        if (!fuel.isEmpty) {
            tag.put("FuelSlot", fuel.save(provider))
        }
        tag.putInt("FuelLeft", fuelLeft)
        tag.putInt("PrevFuelTotal", fuelTotal)
        tag.putFloat("Heat", heat)
        super.saveAdditional(tag, provider)
    }

    override fun loadAdditional(compoundTag: CompoundTag, provider: HolderLookup.Provider) {
        fuel = ItemStack.parseOptional(provider, compoundTag.getCompound("FuelSlot"))
        fuelLeft = compoundTag.getInt("FuelLeft")
        fuelTotal = compoundTag.getInt("PrevFuelTotal")
        heat = compoundTag.getFloat("Heat")
        super.loadAdditional(compoundTag, provider)
    }

    // region Container Stuff
    override fun clearContent() {
        fuel = ItemStack.EMPTY
    }

    override fun getContainerSize(): Int = 1

    override fun isEmpty(): Boolean = fuel.isEmpty

    override fun getItem(slot: Int): ItemStack = if (slot == 0) fuel else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        return ContainerHelper.removeItem(listOf(fuel), slot, amount)
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        if (slot == 0) fuel = ItemStack.EMPTY
        return ItemStack.EMPTY
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot == 0) fuel = stack
    }

    override fun setChanged() {
        val level = this.level
        if (level != null) {
            setChanged(level, this.worldPosition, this.blockState);
        }
    }

    override fun stillValid(player: Player): Boolean {
        return if (level!!.getBlockEntity(worldPosition) !== this) {
            false
        } else player.distanceToSqr(
            worldPosition.x.toDouble() + 0.5, worldPosition.y.toDouble() + 0.5, worldPosition.z.toDouble() + 0.5
        ) <= 64.0
    }

    override fun getSlotsForFace(side: Direction): IntArray = intArrayOf(0)

    override fun canPlaceItemThroughFace(index: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(index, itemStack)

    override fun canTakeItemThroughFace(index: Int, stack: ItemStack, direction: Direction): Boolean =
        // Allow extraction from slot 0 (fuel slot) when the hopper is below the block entity
        index == 0 && direction == Direction.DOWN && !fuel.isEmpty && FuelRegistry.INSTANCE.get(fuel) <= 0

    override fun canPlaceItem(index: Int, stack: ItemStack): Boolean =
        index == 0 && FuelRegistry.INSTANCE.get(stack) > 0

    override fun fillStackedContents(helper: StackedContents) = helper.accountStack(fuel)
    // endregion Container Stuff

    companion object {
        private const val REDSTONE_RESYNC_INTERVAL_TICKS = 20L
    }
}
