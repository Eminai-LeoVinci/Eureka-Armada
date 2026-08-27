package org.valkyrienskies.eureka.blockentity

import org.valkyrienskies.eureka.util.nbt.*

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.server.level.ServerLevel
import net.minecraft.network.chat.Component
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.cannon.PowderCharge
import org.valkyrienskies.eureka.gui.cannon.CannonScreenMenu
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.util.KtContainerData
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * A cannon's magazine: three powder slots and one for shot.
 *
 * ## Why powder and shot are kept apart
 * A single slot would let a player load a gun with shot and no charge, or charge and no shot, and then wonder
 * why it will not fire. Separate slots make the requirement visible: the gun is ready when both kinds are
 * present, and a glance at the menu says which is missing.
 *
 * ## Why powder gets three slots
 * Because a gunner can pack one, two or three measures behind the ball (see
 * [org.valkyrienskies.eureka.cannon.PowderCharge]), and a column of three reads as the powder store for a gun
 * that thinks in threes. They are POOLED rather than one-slot-per-measure: a 3x shot takes three powder from
 * wherever they are, top down, so a magazine with two slots empty and one full still fires heavy. Tying each
 * measure to its own slot would have made a full 192 rounds of powder refuse a 3x shot for no reason a player
 * could see.
 *
 * ## It lives on the rear block only
 * A cannon is two blocks but one gun, and only [org.valkyrienskies.eureka.block.CannonPart.REAR] carries the
 * block entity -- the breech end, which is where you would load a real one. The front half forwards to it, so
 * clicking anywhere on the gun opens the same magazine. Putting a container on both halves would have given
 * one cannon two magazines and made "is it loaded?" a question with two answers.
 *
 * Contents are captured automatically by `vs$fillFromVoxelSet`, so a bottled or blueprinted ship keeps its
 * guns loaded with no extra work.
 */
class CannonBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(EurekaBlockEntities.CANNON.get(), pos, state),
    WorldlyContainer {

    val data = KtContainerData()

    /**
     * How much powder this gun packs per shot, as a [PowderCharge] ordinal.
     *
     * Delegated through [data] so it rides the menu's synced container data to the client, which is what lets
     * the breech button show the current setting without a packet of its own. An int rather than the enum
     * because that is what [net.minecraft.world.inventory.ContainerData] carries; read it through
     * [powderCharge].
     */
    var powderChargeOrdinal by data

    /** This gun's powder measure. Setting it writes the ordinal the menu syncs. */
    var powderCharge: PowderCharge
        get() = PowderCharge.of(powderChargeOrdinal)
        set(value) { powderChargeOrdinal = value.ordinal }

    /**
     * This gun's deck-and-bow-relative name ("L1 - D1"), packed through [org.valkyrienskies.eureka.cannon.GunLabels.encode];
     * 0 when it has none. Delegated through [data] like the charge, and refreshed in [createMenu] -- open
     * time is the one moment the label is wanted, the BE has no ticker, and labels shift only when guns are
     * added or removed, so computing it per open is both cheapest and always current.
     *
     * DECLARATION ORDER MATTERS: [data] hands out slot indices in declaration order, and
     * [CannonScreenMenu]'s clone mirrors this class field for field. This must stay second, after
     * [powderChargeOrdinal], as it is there.
     */
    var gunLabelCode by data

    /**
     * A MIRROR of the ELEVATION blockstate, for the menu's Angle button -- elevation itself lives on the
     * block, not here, so this is never persisted and never authoritative: the menu refreshes it every
     * broadcast while it is open, which also keeps the button honest when somebody crouch-clicks the gun
     * outside. Third slot; the declaration-order rule above applies.
     */
    var elevationIndex by data

    /**
     * CLIENT RENDER BOOKKEEPING for the pitching barrel, owned by CannonBlockEntityRenderer and
     * meaningless everywhere else: the barrel angle currently drawn (degrees above horizontal) and the
     * nanoTime it was last advanced. Never persisted, never synced -- the renderer slews the drawn angle
     * toward the ELEVATION blockstate's angle at a fixed rate, so an order visibly lays the gun. NaN
     * marks a barrel not drawn yet; the renderer snaps that straight to its target, no wiggle on load.
     */
    var barrelPitchShown: Float = Float.NaN
    var barrelPitchNanos: Long = 0L

    /** The three powder slots, top to bottom, and the shot slot. */
    var powderA: ItemStack = ItemStack.EMPTY
    var powderB: ItemStack = ItemStack.EMPTY
    var powderC: ItemStack = ItemStack.EMPTY
    var shot: ItemStack = ItemStack.EMPTY

    /**
     * A raider's gun, not a shipwright's: this cannon came aboard with a pirate hull.
     *
     * Stamped once when the site is adopted (PirateShips.adopt), persisted, and never cleared -- which is
     * the point. A pirate's guns are furniture of the fight, not salvage: they give nothing when broken,
     * no gun and no magazine, so the sixty cannons of a conquered prize cannot be mined into sixty cannons
     * in a chest. Cannons are earned from the loot tables and nowhere else.
     *
     * It says nothing about who is FIRING her. Bottomless ammunition belongs to the raiders' gunnery
     * (pirateCannonInfiniteAmmo), so a prize taken and sailed away answers her new captain on real powder
     * and real shot -- she simply can never be sold for parts.
     */
    var pirate: Boolean = false

    /** Every measure of powder aboard this gun, across all three slots. */
    val powderCount: Int get() = powderA.count + powderB.count + powderC.count

    /**
     * The game tick this gun can next be fired on.
     *
     * An absolute deadline rather than a countdown, so it needs no ticker: a gun nobody visits costs nothing,
     * and a chunk that unloads mid-reload comes back with the wait already correctly elapsed rather than
     * restarting it.
     *
     * Stored as "when it is ready" rather than "when it last fired" because the latter needs a sentinel for
     * a gun that has never fired, and `gameTime - Long.MIN_VALUE` overflows to a negative wait -- which reads
     * in game as a cannon reloading for 2147483647 seconds. A fresh gun is simply ready at tick 0.
     */
    var readyAt: Long = 0L

    /**
     * Both barrels of the question: a gun with powder and no ball is as useless as the reverse.
     *
     * Powder is measured against the SELECTED charge rather than against zero, so a gun set to 3x with two
     * measures in it reads "not loaded" -- which is the truth, since firing it would be refused.
     */
    val loaded: Boolean get() = powderCount >= powderCharge.powder && !shot.isEmpty

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu {
        // Stamp the gun's current name just before the menu syncs it; see [gunLabelCode].
        val level = this.level
        if (level is ServerLevel) {
            val ship = level.getLoadedShipManagingPos(blockPos) as? LoadedServerShip
            val label = ship?.let { GunLabels.labelAt(level, it, blockPos) }
            gunLabelCode = GunLabels.encode(label)
        }
        return CannonScreenMenu(containerId, inventory, this)
    }

    override fun getDefaultName(): Component = Component.translatable("gui.vs_eureka.cannon")

    override fun getItems(): NonNullList<ItemStack> =
        NonNullList.of(ItemStack.EMPTY, powderA, powderB, powderC, shot)

    override fun setItems(list: NonNullList<ItemStack>) {
        powderA = list.getOrElse(POWDER_A) { ItemStack.EMPTY }
        powderB = list.getOrElse(POWDER_B) { ItemStack.EMPTY }
        powderC = list.getOrElse(POWDER_C) { ItemStack.EMPTY }
        shot = list.getOrElse(SHOT) { ItemStack.EMPTY }
    }

    // Saved by NAME, not by index, which is what lets a gun built before powder slots B and C existed come
    // back loaded: its single "Powder" stack is still slot A's key, and its "Shot" is untouched. A gun that
    // has never been given a charge setting reads 0 = 1x, which is what it always effectively fired at.
    override fun saveAdditional(output: CompoundTag, provider: HolderLookup.Provider) {
        super.saveAdditional(output, provider)
        if (!powderA.isEmpty) output.store("Powder", ItemStack.CODEC, provider, powderA)
        if (!powderB.isEmpty) output.store("Powder2", ItemStack.CODEC, provider, powderB)
        if (!powderC.isEmpty) output.store("Powder3", ItemStack.CODEC, provider, powderC)
        if (!shot.isEmpty) output.store("Shot", ItemStack.CODEC, provider, shot)
        output.putLong("ReadyAt", readyAt)
        output.putInt("PowderCharge", powderChargeOrdinal)
        if (pirate) output.putBoolean("Pirate", true)
    }

    override fun loadAdditional(input: CompoundTag, provider: HolderLookup.Provider) {
        super.loadAdditional(input, provider)
        powderA = input.read("Powder", ItemStack.CODEC, provider).orElse(ItemStack.EMPTY)
        powderB = input.read("Powder2", ItemStack.CODEC, provider).orElse(ItemStack.EMPTY)
        powderC = input.read("Powder3", ItemStack.CODEC, provider).orElse(ItemStack.EMPTY)
        shot = input.read("Shot", ItemStack.CODEC, provider).orElse(ItemStack.EMPTY)
        readyAt = input.getLongOr("ReadyAt", 0L)
        powderChargeOrdinal = input.getIntOr("PowderCharge", PowderCharge.SINGLE.ordinal)
        pirate = input.getBooleanOr("Pirate", false)
    }

    /**
     * Take [amount] measures of powder, top slot first, and report whether the gun had them.
     *
     * All-or-nothing on purpose: a refused shot must not eat half a charge. The caller checks
     * [powderCount] first, so the guard here is for the case where two things fire the same gun on the
     * same tick.
     */
    fun consumePowder(amount: Int): Boolean {
        if (powderCount < amount) return false
        var left = amount
        for (slot in POWDER_A..POWDER_C) {
            if (left <= 0) break
            val stack = getItem(slot)
            val taken = minOf(left, stack.count)
            if (taken <= 0) continue
            stack.shrink(taken)
            if (stack.isEmpty) setItem(slot, ItemStack.EMPTY)
            left -= taken
        }
        return true
    }

    // region Container
    override fun getContainerSize(): Int = 4

    /**
     * The magazine holds 64 rounds even though shot only stacks to 16 in a player's hands.
     *
     * Those two limits answer different questions. Sixteen is about what a gunner can *carry* -- it is the
     * reason a magazine is a supply problem worth solving. Sixty-four is about what the gun *holds*, and a
     * cannon that had to be topped up every sixteen shots would make resupply the whole of the gameplay
     * rather than a constraint on it.
     *
     * Taking rounds back out is safe: every player-side slot still reports the item's own 16, so a stack of
     * 64 shift-clicked out lands as four stacks of 16 rather than one illegal pile.
     */
    override fun getMaxStackSize(): Int = MAGAZINE_CAPACITY

    // Both, not just the no-argument one. Container.getMaxStackSize(stack) defaults to min(container, item)
    // and is what every container-level write actually consults -- so leaving it alone let the magazine be
    // clamped back to the cannonball's own 16 by anything that set a slot rather than assigning the field.
    override fun getMaxStackSize(stack: ItemStack): Int = MAGAZINE_CAPACITY

    override fun isEmpty(): Boolean =
        powderA.isEmpty && powderB.isEmpty && powderC.isEmpty && shot.isEmpty

    override fun clearContent() {
        powderA = ItemStack.EMPTY
        powderB = ItemStack.EMPTY
        powderC = ItemStack.EMPTY
        shot = ItemStack.EMPTY
    }

    override fun getItem(slot: Int): ItemStack = when (slot) {
        POWDER_A -> powderA
        POWDER_B -> powderB
        POWDER_C -> powderC
        SHOT -> shot
        else -> ItemStack.EMPTY
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        when (slot) {
            POWDER_A -> powderA = stack
            POWDER_B -> powderB = stack
            POWDER_C -> powderC = stack
            SHOT -> shot = stack
        }
    }

    // Through a list built from the live stacks and written back, because ContainerHelper.removeItem
    // mutates the list it is handed -- assigning EMPTY into it, not into these fields.
    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val items = mutableListOf(powderA, powderB, powderC, shot)
        val taken = ContainerHelper.removeItem(items, slot, amount)
        powderA = items[POWDER_A]
        powderB = items[POWDER_B]
        powderC = items[POWDER_C]
        shot = items[SHOT]
        setChanged()
        return taken
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val taken = getItem(slot)
        setItem(slot, ItemStack.EMPTY)
        return taken
    }

    override fun setChanged() {
        level?.let { setChanged(it, worldPosition, blockState) }
    }

    override fun stillValid(player: Player): Boolean =
        if (level!!.getBlockEntity(worldPosition) !== this) false
        else player.distanceToSqr(
            worldPosition.x + 0.5, worldPosition.y + 0.5, worldPosition.z + 0.5
        ) <= 64.0

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = when (slot) {
        POWDER_A, POWDER_B, POWDER_C -> stack.`is`(Items.GUNPOWDER)
        SHOT -> stack.item is CannonballItem
        else -> false
    }

    /**
     * Load what is in a hand straight into the magazine, and answer how much went in.
     *
     * The point of the gesture is to serve a gun deck without opening six screens, so it fills the way a
     * gun crew would: powder into whichever charges have room, in order, and shot into the shot slot. A
     * round of a DIFFERENT kind than the one already loaded is refused rather than merged -- the slot holds
     * one nature of round, and quietly mixing them would change what the gun fires without saying so.
     *
     * [consume] is false in creative, where the hand is bottomless and shrinking it is wrong.
     */
    fun load(stack: ItemStack, consume: Boolean): Int {
        if (stack.isEmpty) return 0
        val slots = if (stack.`is`(Items.GUNPOWDER)) POWDER_SLOTS else SHOT_SLOTS
        var moved = 0

        for (slot in slots) {
            val left = stack.count - moved
            if (left <= 0) break
            if (!canPlaceItem(slot, stack)) continue

            val current = getItem(slot)
            val room = when {
                current.isEmpty -> MAGAZINE_CAPACITY
                ItemStack.isSameItemSameComponents(current, stack) -> MAGAZINE_CAPACITY - current.count
                else -> 0
            }
            if (room <= 0) continue

            val take = minOf(room, left)
            if (current.isEmpty) setItem(slot, stack.copyWithCount(take)) else current.grow(take)
            moved += take
        }

        if (moved > 0) {
            if (consume) stack.shrink(moved)
            setChanged()
        }
        return moved
    }

    // Hoppers may feed a gun from any side but the bottom, and may not empty it. A gun deck should be
    // suppliable by machinery -- that is most of what makes a big broadside practical -- but a hopper
    // under a cannon quietly unloading it would be a trap rather than a feature.
    override fun getSlotsForFace(side: Direction): IntArray =
        intArrayOf(POWDER_A, POWDER_B, POWDER_C, SHOT)

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(slot, stack)

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, direction: Direction): Boolean = false
    // endregion

    companion object {
        const val POWDER_A = 0
        const val POWDER_B = 1
        const val POWDER_C = 2
        const val SHOT = 3

        private val POWDER_SLOTS = intArrayOf(POWDER_A, POWDER_B, POWDER_C)
        private val SHOT_SLOTS = intArrayOf(SHOT)

        /** Rounds a gun can hold, regardless of how few a player can carry in one slot. */
        const val MAGAZINE_CAPACITY = 64
    }
}
