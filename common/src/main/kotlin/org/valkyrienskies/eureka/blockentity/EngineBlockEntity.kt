package org.valkyrienskies.eureka.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.joml.Math.lerp
import org.joml.Math.min
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties.HEAT
import org.valkyrienskies.eureka.gui.engine.EngineScreenMenu
import org.valkyrienskies.eureka.registry.FuelRegistry
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipEngines
import org.valkyrienskies.eureka.util.KtContainerData
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import kotlin.math.ceil
import kotlin.math.max

class EngineBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(EurekaBlockEntities.ENGINE.get(), pos, state),
    WorldlyContainer {

    private val ship: LoadedServerShip? get() = (this.level as ServerLevel).getLoadedShipManagingPos(this.blockPos)
    val data = KtContainerData()
    private var heatLevel by data
    private var fuelLeft by data
    private var fuelTotal by data

    /**
     * This engine's "n of total" aboard, packed through [org.valkyrienskies.eureka.ship.ShipBearing.packNumber];
     * 0 when it is not on a ship. Refreshed in [createMenu] -- open time is the one moment it is wanted, the
     * number only moves when engines are added or removed, and a whole-ship walk per tick to keep a label
     * current would be absurd.
     *
     * DECLARATION ORDER MATTERS: [data] hands out slot indices in declaration order and [EngineScreenMenu]
     * mirrors this class field for field. Append here, and append there to match.
     */
    var fittingNumber by data
    var fuel: ItemStack = ItemStack.EMPTY
    private var lastFuelValue = 1600; // coal: 1600

    /**
     * A raider's engine, not a shipwright's: this one came aboard with a pirate hull.
     *
     * The exact twin of [org.valkyrienskies.eureka.blockentity.CannonBlockEntity.pirate], stamped by the
     * same sweep at the same moment, and it buys the same two things. Her engines never run dry while her
     * wheel stands, because a raider who runs out of coal mid-chase is not a raider, she is scenery. And
     * her bunkers are not a coal mine: a template author fills every engine to the brim so the hull will
     * sail forever, and paying that out on a break would make one conquered sloop worth more coal than the
     * fight that took her.
     *
     * Never cleared, exactly as the guns are not. A prize sailed away by her new captain burns real fuel
     * she has to find -- what she can never do is be broken up for the fuel already in her.
     */
    var pirate: Boolean = false

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu {
        // Stamp this engine's number just before the menu syncs it; see [fittingNumber].
        val level = this.level
        if (level is ServerLevel) {
            val ship = level.getLoadedShipManagingPos(blockPos) as? LoadedServerShip
            fittingNumber = ship?.let { ShipEngines.numberAt(level, it, blockPos) } ?: 0
        }
        return EngineScreenMenu(containerId, inventory, this)
    }

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

    /**
     * Put this engine out, and leave it out. Called on every engine of a hull that SANK, once her blocks are
     * back in the world.
     *
     * Zeroing the heat alone is not enough and looks like it should be. A block entity ticks wherever it is:
     * a loose engine with coal still in it burns that coal, climbs back up the heat ladder, and relights --
     * `EngineBlock` derives its light level straight from the HEAT property, so a wreck on the seabed would
     * have glowed and hummed away as if nothing had happened. The fuel goes with the heat, which is also the
     * honest reading: she went down with her bunkers.
     */
    fun douse() {
        heat = 0f
        heatLevel = 0
        fuelLeft = 0
        fuelTotal = 0
        fuel = ItemStack.EMPTY
        setChanged()
    }

    fun tick() {
        if (this.level!!.isClientSide) return

        // Resolve THIS engine's managing ship + its EurekaShipControl once per tick (the ship getter walks the
        // loaded-ship index, so cache it). Three engine fields are per SHIP CATEGORY -- engineHeatGain,
        // enginePowerLinear and its cold-engine floor -- and must honor the ship's own preset; everything else
        // (heat loss, fuel, redstone) stays global on EurekaConfig.SERVER. A loose engine (not on a ship, or
        // with no control attachment) falls back to the base block, which is also what a boat reads for the
        // shared values.
        val eurekaShipControl = ship?.getAttachment(EurekaShipControl::class.java)
        val engineCfg = eurekaShipControl?.engineCfg ?: EurekaConfig.SERVER

        // Heat ceiling, derived per-tick from this ship's engineHeatGain preset so the cap tracks the same
        // per-category gain used below; was a stale construction-time field.
        val maxEffectiveFuel = 100f - engineCfg.engineHeatGain

        val isPowered = hasRedstoneSignal()
        if (EurekaConfig.SERVER.engineRedstoneBehaviorPause && isPowered) {
            // Still report the tank level while redstone-paused, so a fueled-but-idle engine keeps counting
            // toward the helm "Engine Power: X%" readout instead of dropping to 0.
            eurekaShipControl?.reportEngineFuel(fuelFraction())
            return
        }

        // A raider's bunkers are bottomless, the way her magazines are: she still has to be STOCKED --
        // an engine with an empty slot burns down and stops like any other, and an author who left one
        // empty gets a hull that limps -- but nothing is deducted, so a chase that runs long never ends
        // because the pillagers ran out of coal. The heat still climbs on the same curve, so she makes
        // her power exactly as an honest engine does.
        val bottomless = pirate && EurekaConfig.SERVER.pirateEngineInfiniteFuel && !fuel.isEmpty

        // Disable engine feeding when they are receiving a redstone signal
        if (!isPowered) {
            if (fuelLeft > 0) {

                if (EurekaConfig.SERVER.engineFuelSaving) {
                    if (heat <= maxEffectiveFuel) {
                        heat += scaleEngineHeating(engineCfg.engineHeatGain)
                        if (!bottomless) fuelLeft--
                    }
                } else {
                    if (!bottomless) fuelLeft--

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

                // Engine linear power is per SHIP CATEGORY (an airship's 500000f against a boat's 100000f), so
                // read both ends of the lerp off this ship's own preset: the hot power AND the boost threshold
                // it is scaled against have to agree, or an airship's boost would engage at a boat's engine count.
                eurekaShipControl.powerLinear += lerp(
                    engineCfg.enginePowerLinearMin,
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
    /**
     * How full this engine is, 0..1, counting both the charge already burning and the items still in the slot.
     *
     * Public because a shipwright quotes a whole ship's fuel by averaging its engines, and the helm's own
     * readout must not be the only thing that can answer "how much has it got left".
     */
    fun fuelFraction(): Double {
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
        (FuelRegistry.INSTANCE.get(fuel, level!!.fuelValues()) * EurekaConfig.SERVER.engineFuelMultiplier).toInt()


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

            // 1.21.11: Item.hasCraftingRemainingItem/craftingRemainingItem were removed (the
            // remainder is now a data component); the lava-bucket-style remainder is dropped here.
            removeItem(0, 1)
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

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (!fuel.isEmpty) {
            output.store("FuelSlot", ItemStack.CODEC, fuel)
        }
        output.putInt("FuelLeft", fuelLeft)
        output.putInt("PrevFuelTotal", fuelTotal)
        output.putFloat("Heat", heat)
        if (pirate) output.putBoolean("Pirate", true)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        fuel = input.read("FuelSlot", ItemStack.CODEC).orElse(ItemStack.EMPTY)
        fuelLeft = input.getIntOr("FuelLeft", 0)
        fuelTotal = input.getIntOr("PrevFuelTotal", 0)
        heat = input.getFloatOr("Heat", 0f)
        pirate = input.getBooleanOr("Pirate", false)
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

    override fun canTakeItemThroughFace(index: Int, stack: ItemStack, direction: Direction): Boolean {
        // Allow extraction from slot 0 (fuel slot) when the hopper is below the block entity
        val fuelValues = level?.fuelValues() ?: return false
        return index == 0 && direction == Direction.DOWN && !fuel.isEmpty &&
            FuelRegistry.INSTANCE.get(fuel, fuelValues) <= 0
    }

    override fun canPlaceItem(index: Int, stack: ItemStack): Boolean {
        val fuelValues = level?.fuelValues() ?: return false
        return index == 0 && FuelRegistry.INSTANCE.get(stack, fuelValues) > 0
    }

    /**
     * Stoke the firebox from a hand, and answer how much went in. The mirror of
     * `CannonBlockEntity.load`, for the same caller: ship-wide restocking, which walks up with a stack
     * and needs to know what it spent.
     *
     * One slot, so the rules are short: fuel only, and a slot already holding a DIFFERENT fuel refuses
     * rather than merges -- an engine mid-burn on blaze rods should not have its reserve quietly swapped
     * for planks. [consume] is false in creative, where the hand is bottomless.
     */
    fun load(stack: ItemStack, consume: Boolean): Int {
        if (stack.isEmpty || !canPlaceItem(0, stack)) return 0

        val room = when {
            fuel.isEmpty -> stack.maxStackSize
            ItemStack.isSameItemSameComponents(fuel, stack) -> stack.maxStackSize - fuel.count
            else -> 0
        }
        if (room <= 0) return 0

        val take = minOf(room, stack.count)
        if (fuel.isEmpty) fuel = stack.copyWithCount(take) else fuel.grow(take)
        if (consume) stack.shrink(take)
        setChanged()
        return take
    }

    // endregion Container Stuff

    companion object {
        private const val REDSTONE_RESYNC_INTERVAL_TICKS = 20L
    }
}
