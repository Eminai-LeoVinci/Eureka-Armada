package org.valkyrienskies.eureka.gui.cannon

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.valkyrienskies.eureka.EurekaScreens
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.util.inventorySlots

class CannonScreenMenu(syncId: Int, playerInv: Inventory, val blockEntity: CannonBlockEntity?) :
    AbstractContainerMenu(EurekaScreens.CANNON.get(), syncId) {

    constructor(syncId: Int, playerInv: Inventory) : this(syncId, playerInv, null)

    private val container: Container = blockEntity ?: SimpleContainer(MAGAZINE_SLOTS)

    /** True once the gun has both halves of a shot in it. Read straight off the synced slots. */
    val loaded: Boolean get() = slots[CannonBlockEntity.POWDER].hasItem() && slots[CannonBlockEntity.SHOT].hasItem()

    init {
        // Each slot refuses anything it is not for, so a player cannot load shot into the powder
        // horn and then wonder why the gun will not fire.
        addSlot(object : Slot(container, CannonBlockEntity.POWDER, POWDER_X, SLOT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = stack.`is`(Items.GUNPOWDER)
        })
        addSlot(object : Slot(container, CannonBlockEntity.SHOT, SHOT_X, SLOT_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = stack.item is CannonballItem
        })

        inventorySlots(::addSlot, playerInv)
    }

    override fun stillValid(player: Player): Boolean = container.stillValid(player)

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
            val target = when {
                stack.`is`(Items.GUNPOWDER) -> CannonBlockEntity.POWDER
                stack.item is CannonballItem -> CannonBlockEntity.SHOT
                else -> return ItemStack.EMPTY
            }
            if (!moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY
        }

        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    companion object {
        const val MAGAZINE_SLOTS = 2

        // Symmetric about the panel's centre line at x = 88.
        const val POWDER_X = 62
        const val SHOT_X = 98
        const val SLOT_Y = 35

        val factory: (syncId: Int, playerInv: Inventory) -> CannonScreenMenu = ::CannonScreenMenu
    }
}
