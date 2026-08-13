package org.valkyrienskies.eureka.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
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
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.gui.cannon.CannonScreenMenu
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.util.KtContainerData

/**
 * A cannon's magazine: powder in one slot, shot in the other.
 *
 * ## Why the two are kept apart
 * A single slot would let a player load a gun with shot and no charge, or charge and no shot, and then wonder
 * why it will not fire. Two slots make the requirement visible: the gun is ready when both have something in
 * them, and a glance at the menu says which one is empty.
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

    var powder: ItemStack = ItemStack.EMPTY
    var shot: ItemStack = ItemStack.EMPTY

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

    /** Both barrels of the question: a gun with powder and no ball is as useless as the reverse. */
    val loaded: Boolean get() = !powder.isEmpty && !shot.isEmpty

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu =
        CannonScreenMenu(containerId, inventory, this)

    override fun getDefaultName(): Component = Component.translatable("gui.vs_eureka.cannon")

    override fun getItems(): NonNullList<ItemStack> = NonNullList.of(ItemStack.EMPTY, powder, shot)

    override fun setItems(list: NonNullList<ItemStack>) {
        powder = list.getOrElse(POWDER) { ItemStack.EMPTY }
        shot = list.getOrElse(SHOT) { ItemStack.EMPTY }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (!powder.isEmpty) output.store("Powder", ItemStack.CODEC, powder)
        if (!shot.isEmpty) output.store("Shot", ItemStack.CODEC, shot)
        output.putLong("ReadyAt", readyAt)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        powder = input.read("Powder", ItemStack.CODEC).orElse(ItemStack.EMPTY)
        shot = input.read("Shot", ItemStack.CODEC).orElse(ItemStack.EMPTY)
        readyAt = input.getLongOr("ReadyAt", 0L)
    }

    // region Container
    override fun getContainerSize(): Int = 2

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

    override fun isEmpty(): Boolean = powder.isEmpty && shot.isEmpty

    override fun clearContent() {
        powder = ItemStack.EMPTY
        shot = ItemStack.EMPTY
    }

    override fun getItem(slot: Int): ItemStack = when (slot) {
        POWDER -> powder
        SHOT -> shot
        else -> ItemStack.EMPTY
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        when (slot) {
            POWDER -> powder = stack
            SHOT -> shot = stack
        }
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack =
        ContainerHelper.removeItem(mutableListOf(powder, shot), slot, amount).also { setChanged() }

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
        POWDER -> stack.`is`(Items.GUNPOWDER)
        SHOT -> stack.item is CannonballItem
        else -> false
    }

    // Hoppers may feed a gun from any side but the bottom, and may not empty it. A gun deck should be
    // suppliable by machinery -- that is most of what makes a big broadside practical -- but a hopper
    // under a cannon quietly unloading it would be a trap rather than a feature.
    override fun getSlotsForFace(side: Direction): IntArray = intArrayOf(POWDER, SHOT)

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(slot, stack)

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, direction: Direction): Boolean = false
    // endregion

    companion object {
        const val POWDER = 0
        const val SHOT = 1

        /** Rounds a gun can hold, regardless of how few a player can carry in one slot. */
        const val MAGAZINE_CAPACITY = 64
    }
}
