package org.valkyrienskies.eureka.shipwright

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.crew.packedItems
import org.valkyrienskies.eureka.crew.writePacked
import org.valkyrienskies.eureka.path.PathMessages

/**
 * Carrying a broken-up ship away, a pack at a time.
 *
 * ## Nothing is ever dropped
 * A claim stops the moment the captain's pack is full and leaves the rest on the pile. That is the rule the
 * whole shipwright keeps -- materials handed back into a full pack stay credited on the bench rather than
 * landing on the floor -- and it matters more here than anywhere else, because the pile IS a ship. Items on
 * the ground despawn; a pile in the ledger does not.
 *
 * So every hand-over follows the same shape: ask the inventory to take a stack, see how much it actually
 * took, and remove exactly that much from the pile. There is no step at which a count exists in neither
 * place.
 *
 * ## Auto-Shulker
 * A ship is tens of thousands of blocks and a pack is thirty-six slots, so claiming a hull by hand is a
 * dozen round trips. With **Use Shulkers** ticked, every EMPTY shulker box in the captain's pack is filled
 * to its twenty-seven slots, largest pile first, before the loose hand-over begins -- turning a dozen trips
 * into one. Boxes are written through their packed-contents tag, in place, so colour,
 * name, and slot survive untouched.
 *
 * A shulker box is never packed INTO a shulker box. The component would accept it; the block would not,
 * and a box that cannot be placed is a box whose contents cannot be got at.
 */
object Salvaging {

    /** A shulker box holds twenty-seven stacks, and its contents component will happily hold more. */
    private const val BOX_SLOTS = 27

    /**
     * Claim everything on one tab.
     *
     * Largest piles first: they are the ones that need the room, and a captain who runs out of space
     * halfway would rather be left holding the odds and ends than four thousand planks.
     */
    fun claimAll(
        ledger: ShipwrightLedger,
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        cargoSide: Boolean,
        useShulkers: Boolean
    ) {
        if (useShulkers) packBoxes(ledger, player, pile, cargoSide)

        // Every row is tried, not just rows up to the first one that stalls. A pack with two slots left
        // cannot take four thousand planks, but it can take the anchor and the bell -- and stopping at the
        // planks would hand back nothing and look exactly like a button that does not work.
        val order = pile.tab(cargoSide).entries.sortedByDescending { it.value }.map { it.key }
        var carried = 0
        for (item in order) {
            carried += hand(ledger, player, pile, cargoSide, item)
        }
        report(player, pile, cargoSide, carried)
    }

    /** Claim one row, for a captain who wants the anchor chain and not the four thousand planks. */
    fun claimOne(
        ledger: ShipwrightLedger,
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        cargoSide: Boolean,
        item: Item,
        useShulkers: Boolean
    ) {
        if (useShulkers) packBoxes(ledger, player, pile, cargoSide, only = item)
        val carried = hand(ledger, player, pile, cargoSide, item)
        report(player, pile, cargoSide, carried)
    }

    /**
     * Hand over the keepsakes, whole.
     *
     * Walked backwards so removing one does not renumber the ones still to come.
     */
    fun claimKeepsakes(ledger: ShipwrightLedger, player: ServerPlayer, pile: ShipwrightLedger.Salvage) {
        var stuck = 0
        for (index in pile.keepsakes.indices.reversed()) {
            val stack = pile.keepsakes[index].copy()
            if (!player.inventory.add(stack)) {
                // Partially added stacks are put back at what remains, so nothing is duplicated and
                // nothing is lost -- the pile holds exactly what the pack did not take.
                if (stack.count < pile.keepsakes[index].count) pile.keepsakes[index] = stack
                stuck++
                continue
            }
            ledger.takeKeepsake(player.uuid, pile, index)
        }
        ledger.setDirty()
        if (stuck > 0) {
            PathMessages.send(
                player,
                "Your pack is full -- $stuck kept items stay with the salvage.",
                PathMessages.Kind.WARN
            )
        }
    }

    /**
     * Hand over ONE keepsake, whole.
     *
     * All or nothing per stack: a shulker box holding a ship's worth of coal cannot be split across two
     * trips, so if it will not fit it stays exactly where it is.
     */
    fun claimKeepsake(
        ledger: ShipwrightLedger,
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        index: Int
    ) {
        val kept = pile.keepsakes.getOrNull(index) ?: return
        val stack = kept.copy()
        if (!player.inventory.add(stack)) {
            PathMessages.send(
                player,
                "No room in your pack for that one -- it stays with the salvage.",
                PathMessages.Kind.WARN
            )
            return
        }
        ledger.takeKeepsake(player.uuid, pile, index)
        PathMessages.send(player, "${kept.hoverName.string} carried aboard.", PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS)
    }

    /**
     * Move as much of [item] into the pack as will go, and answer how many actually went.
     *
     * The pile is only ever debited by what the inventory ACCEPTED, which is the difference in the stack's
     * own count across the call -- never by what was offered. Anything else is a way to delete a ship.
     *
     * A stack that moves nothing ends the run for this item: the pack is out of room for it, and offering
     * the same stack again would loop forever.
     */
    private fun hand(
        ledger: ShipwrightLedger,
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        cargoSide: Boolean,
        item: Item
    ): Int {
        var carried = 0
        while (true) {
            val left = pile.tab(cargoSide)[item] ?: return carried
            if (left <= 0) return carried

            val offered = minOf(left, item.maxStackSize)

            // COUNTED, not reported. Asking the inventory how much it took means trusting it to shrink the
            // stack it was handed, and a hand-over that over-reports here debits a ship out of the ledger
            // and puts nothing in the pack -- the pile is the only copy. Counting the pack before and after
            // cannot be wrong about that, and costs one pass over thirty-six slots.
            val before = countIn(player, item)
            val stack = ItemStack(item, offered)
            player.inventory.add(stack)
            val given = (countIn(player, item) - before).coerceIn(0, offered)

            if (given > 0) {
                ledger.takeSalvage(player.uuid, pile, cargoSide, item, given)
                carried += given
            }
            // Two independent reasons to stop, because either one alone has a way of being wrong: nothing
            // moved, or the pack handed some of the stack back.
            if (given <= 0 || stack.count > 0) return carried
        }
    }

    /** How much of [item] the pack holds right now, armour and offhand included -- this is a delta. */
    private fun countIn(player: ServerPlayer, item: Item): Int {
        var total = 0
        val pack = player.inventory
        for (slot in 0 until pack.containerSize) {
            val stack = pack.getItem(slot)
            if (stack.item == item) total += stack.count
        }
        return total
    }

    /**
     * Fill every empty shulker box in the pack from the pile.
     *
     * Only boxes standing alone in a slot are used: a stack of two boxes has one set of contents between
     * them, so filling it would put one ship's worth of timber into a stack the game will happily let the
     * captain split in half.
     */
    private fun packBoxes(
        ledger: ShipwrightLedger,
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        cargoSide: Boolean,
        only: Item? = null
    ) {
        val tab = pile.tab(cargoSide)

        for (slot in 0 until player.inventory.containerSize) {
            if (tab.isEmpty()) return
            val box = player.inventory.getItem(slot)
            if (box.count != 1 || !isShulkerBox(box)) continue
            // An empty box and a box with no contents component are the same thing; both are fair game.
            if (packedItems(box)?.any { !it.isEmpty } == true) continue

            // Chosen and packed FIRST, debited only once the box has actually been written. Debiting as we
            // went would mean anything that failed between the two was a ship deleted from the ledger and
            // nowhere else -- the pile is the only copy.
            val packed = ArrayList<ItemStack>()
            val taken = LinkedHashMap<Item, Int>()
            while (packed.size < BOX_SLOTS) {
                val next = tab.entries
                    .filter { it.value - (taken[it.key] ?: 0) > 0 && (only == null || it.key == only) }
                    .filterNot { isShulkerBox(ItemStack(it.key)) }
                    .maxByOrNull { it.value - (taken[it.key] ?: 0) } ?: break

                val available = next.value - (taken[next.key] ?: 0)
                val take = minOf(available, next.key.maxStackSize)
                packed.add(ItemStack(next.key, take))
                taken[next.key] = (taken[next.key] ?: 0) + take
            }
            if (packed.isEmpty()) continue

            writePacked(box, packed)
            for ((item, count) in taken) {
                ledger.takeSalvage(player.uuid, pile, cargoSide, item, count)
            }
        }

        // The box was rewritten in place rather than replaced, so the slot itself never changed hands. Tell
        // the client explicitly instead of trusting it to notice.
        player.containerMenu.broadcastChanges()
    }

    /**
     * Say what moved and what did not.
     *
     * Both halves are spoken. "Nothing happened" and "everything you asked for happened" used to look
     * identical from the captain's side -- a silent success and a silent refusal are the same screen.
     */
    private fun report(
        player: ServerPlayer,
        pile: ShipwrightLedger.Salvage,
        cargoSide: Boolean,
        carried: Int
    ) {
        val left = pile.tab(cargoSide).values.sum()
        when {
            carried > 0 && left > 0 -> PathMessages.send(
                player,
                "Carried $carried aboard -- $left still waiting with the salvage.",
                PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS
            )
            carried > 0 -> PathMessages.send(player, "Carried $carried aboard.", PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS)
            left > 0 -> PathMessages.send(
                player,
                "No room in your pack -- all $left are still with the salvage.",
                PathMessages.Kind.WARN
            )
        }
    }
    // 1.21.1 has no #minecraft:shulker_boxes item tag (it arrives later); the block class is the test.
    private fun isShulkerBox(stack: ItemStack): Boolean = Block.byItem(stack.item) is ShulkerBoxBlock
}
