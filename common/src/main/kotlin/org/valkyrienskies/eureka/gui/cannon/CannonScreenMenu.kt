package org.valkyrienskies.eureka.gui.cannon

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.EurekaScreens
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.block.CannonPart
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.PowderCharge
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.util.KtContainerData
import org.valkyrienskies.eureka.util.inventorySlots

class CannonScreenMenu(syncId: Int, playerInv: Inventory, val blockEntity: CannonBlockEntity?) :
    AbstractContainerMenu(EurekaScreens.CANNON.get(), syncId) {

    constructor(syncId: Int, playerInv: Inventory) : this(syncId, playerInv, null)

    // The client has no block entity, so it builds its own container to hold the synced copy -- and
    // SimpleContainer.setItem runs every incoming stack through limitSize(getMaxStackSize(stack)), which
    // defaults to min(container, item) and so is 16 for cannonballs. A magazine holding forty therefore
    // arrived, was truncated to sixteen for display, and only told the truth once the stack was picked up
    // and the real one came back from the server. The gun's capacity has to be the gun's on both sides.
    private val container: Container = blockEntity ?: object : SimpleContainer(MAGAZINE_SLOTS) {
        override fun getMaxStackSize(stack: ItemStack): Int = CannonBlockEntity.MAGAZINE_CAPACITY
    }

    // Mirrors CannonBlockEntity.powderChargeOrdinal through the synced container data, which is how the
    // breech button knows what to draw without a packet of its own. Declared in the same order as the
    // block entity's own delegates, because that order IS the slot index.
    private val data = blockEntity?.data?.clone() ?: KtContainerData()
    var powderChargeOrdinal by data
    var gunLabelCode by data
    var elevationIndex by data

    /** This gun's powder measure, as the client sees it. */
    val powderCharge: PowderCharge get() = PowderCharge.of(powderChargeOrdinal)

    /** Every measure of powder aboard, summed across the three slots. Read straight off the synced slots. */
    val powderCount: Int
        get() = (CannonBlockEntity.POWDER_A..CannonBlockEntity.POWDER_C)
            .sumOf { slots[it].item.count }

    /**
     * True once the gun has a ball and enough powder FOR THE SELECTED CHARGE -- so a gun set to 3x with two
     * measures in it reads as not loaded, which is exactly what firing it will tell you.
     */
    val loaded: Boolean get() = powderCount >= powderCharge.powder && slots[CannonBlockEntity.SHOT].hasItem()

    init {
        // Each slot refuses anything it is not for, so a player cannot load shot into the powder
        // horn and then wonder why the gun will not fire.
        for (slot in CannonBlockEntity.POWDER_A..CannonBlockEntity.POWDER_C) {
            addSlot(object : Slot(container, slot, POWDER_X, powderSlotY(slot)) {
                override fun mayPlace(stack: ItemStack): Boolean = stack.`is`(Items.GUNPOWDER)

                // The gun's capacity is a fact about the gun, so powder stacks to the magazine's limit for
                // the same reason shot does -- and gunpowder's own limit is 64 anyway, so this only matters
                // if that ever changes.
                override fun getMaxStackSize(stack: ItemStack): Int = CannonBlockEntity.MAGAZINE_CAPACITY
            })
        }
        addSlot(object : Slot(container, CannonBlockEntity.SHOT, SHOT_X, SLOT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = stack.item is CannonballItem

            // Slot.getMaxStackSize(stack) is min(slot limit, item limit) by default, so shot would cap at
            // its carry limit of 16 no matter what the container said. The gun's capacity is a fact about
            // the gun, so it overrides here -- and only here, which is what keeps every player-side slot
            // honest about the item's own 16.
            override fun getMaxStackSize(stack: ItemStack): Int = CannonBlockEntity.MAGAZINE_CAPACITY
        })

        inventorySlots(::addSlot, playerInv)

        addDataSlots(data)
    }

    override fun stillValid(player: Player): Boolean = container.stillValid(player)

    /**
     * The breech button: [BUTTON_CHARGE] steps the powder measure 1x -> 2x -> 3x -> 1x.
     *
     * Server-side only, and it writes the block entity rather than the menu, so the setting belongs to the
     * GUN and survives the screen being closed. The synced data carries the new value straight back to
     * whoever has the menu open.
     */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val gun = blockEntity ?: return false
        when (id) {
            BUTTON_CHARGE -> {
                gun.powderCharge = gun.powderCharge.next
                gun.setChanged()
                return true
            }
            BUTTON_ANGLE -> {
                // The same both-halves re-pose the crouch-click gesture makes, minus its sound and
                // actionbar line -- the button's own label answering is the feedback in here.
                val level = gun.level ?: return false
                val state = gun.blockState
                if (state.block !is CannonBlock) return false
                val facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                val next = (state.getValue(EurekaProperties.ELEVATION) + 1) % 5
                for (part in CannonPart.entries) {
                    val pos = gun.blockPos.relative(facing, part.ordinal)
                    val there = level.getBlockState(pos)
                    if (there.block !is CannonBlock) continue
                    level.setBlock(pos, there.setValue(EurekaProperties.ELEVATION, next), Block.UPDATE_CLIENTS)
                }
                gun.elevationIndex = next
                return true
            }
            else -> return super.clickMenuButton(player, id)
        }
    }

    /**
     * The elevation mirror is refreshed here, every server broadcast while the menu is open, because the
     * truth lives on the BLOCK and can move without this menu hearing -- a crouch-click at the gun, or
     * the Operations tab laying the whole battery.
     */
    override fun broadcastChanges() {
        blockEntity?.let { gun ->
            val state = gun.blockState
            if (state.block is CannonBlock) {
                gun.elevationIndex = state.getValue(EurekaProperties.ELEVATION)
            }
        }
        super.broadcastChanges()
    }

    /**
     * Shift-click routes by what the item *is*, not by which slot is free.
     *
     * Returning the untouched copy rather than EMPTY is what lets vanilla's caller keep looping until the
     * stack is exhausted -- return EMPTY too early and a shift-click moves one stack and stops, which reads
     * as the menu ignoring you.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val stack = slot.item
        val original = stack.copy()

        if (index < MAGAZINE_SLOTS) {
            if (!moveItemStackTo(stack, MAGAZINE_SLOTS, slots.size, true)) return ItemStack.EMPTY
        } else {
            // Powder spreads across the whole column so a shift-click fills the gun rather than one slot.
            val moved = when {
                stack.`is`(Items.GUNPOWDER) ->
                    moveItemStackTo(stack, CannonBlockEntity.POWDER_A, CannonBlockEntity.POWDER_C + 1, false)
                stack.item is CannonballItem ->
                    moveItemStackTo(stack, CannonBlockEntity.SHOT, CannonBlockEntity.SHOT + 1, false)
                else -> return ItemStack.EMPTY
            }
            if (!moved) return ItemStack.EMPTY
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    companion object {
        const val MAGAZINE_SLOTS = 4

        /** Button id for the breech's powder-measure toggle. */
        const val BUTTON_CHARGE = 0

        /** Button id for the barrel's elevation toggle -- the crouch-click gesture, indoors. */
        const val BUTTON_ANGLE = 1

        // The magazine has to fit between the divider at y=19 and the player inventory at y=84, and three
        // 18px wells plus their gaps is most of that. The powder column sits left, the shot slot centres
        // against it, and the charge button takes the space to the right.
        const val POWDER_X = 26
        const val SHOT_X = 80
        const val SLOT_Y = 42
        const val SLOT_PITCH = 18

        /** Where powder slot [slot] sits: the column is centred on [SLOT_Y], one well above and one below. */
        fun powderSlotY(slot: Int): Int = SLOT_Y + (slot - CannonBlockEntity.POWDER_B) * SLOT_PITCH

        val factory: (syncId: Int, playerInv: Inventory) -> CannonScreenMenu = ::CannonScreenMenu
    }
}
