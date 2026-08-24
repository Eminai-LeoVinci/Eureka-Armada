package org.valkyrienskies.eureka.shipwright

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig

/**
 * What a shipwright charges to break a ship up.
 *
 * Scrapping a raft should be free and scrapping a first-rate should be a decision, so the fee is by the
 * SIZE of the hull: one unit per whole multiple of [EurekaConfig.Server.shipwrightDismantleFeeBlocks], which
 * at the default 1000 makes a 999-block ship free and a 19,879-block ship cost 19. The assembly ceiling is
 * 50,000, so the most any ship can ever cost is 50 of whatever the yard takes.
 *
 * ## Counted off the hull as it stands, not off her papers
 * The count is [ShipRepair.bounds] -- a real walk of the shipyard, the same measure the repair assessment
 * and the templates use -- and never the plans, never `assembledBlocks`, never a manifest. A ship shot half
 * to pieces IS half a ship, and quoting her at the size she was launched at would charge a captain for
 * planks that are already at the bottom of the sea.
 *
 * Cargo is not hull and is not counted. The coal in her engines and the wheat in her hold are counted by
 * weight nowhere and handed straight back on the claim list anyway; billing for them would be charging a
 * captain to be given their own stores.
 *
 * ## Paid out of pockets
 * Not out of the ship's holds, though [org.valkyrienskies.eureka.crew.ShipStores] would make that easy.
 * Taking the fee out of a hull that is about to be broken up means taking it out of the claim list the
 * captain is about to be handed -- money in one pocket and out of the other, with a scary confirmation in
 * between.
 */
object DismantleFee {

    /** One item and the number of it owed. */
    class Line(val item: Item, val count: Int)

    /**
     * The bill for a hull of [blocks] blocks. Empty when the yard charges nothing, when the hull is under
     * the free threshold, or when the configured item does not exist.
     *
     * Two separate numbers, because they answer two separate questions. `feeFreeBelow` is where a shipwright
     * stops waving the job through; `feeBlocks` is what a unit of work is worth after that. They default to
     * the same 1000 so the first unit is also the first charge, which is the intuitive reading -- but a
     * world that sets the free line to 0 is saying "everything costs something", and a 30-block raft would
     * otherwise owe nothing at all by the unit maths. So past the free line the bill is at least ONE unit.
     *
     * It never rounds UP: 1278 blocks is one unit, not two. A captain should not pay for a thousand planks
     * they do not have.
     */
    fun quote(blocks: Int): List<Line> {
        val cfg = EurekaConfig.SERVER
        val per = cfg.shipwrightDismantleFeeBlocks
        if (per <= 0 || blocks <= 0) return emptyList()
        if (blocks < cfg.shipwrightDismantleFeeFreeBelow) return emptyList()
        val units = (blocks / per).coerceAtLeast(1)

        val lines = ArrayList<Line>(2)
        itemOf(cfg.shipwrightDismantleFeeItem)?.let { item ->
            val each = cfg.shipwrightDismantleFeeCount
            if (each > 0) lines.add(Line(item, units * each))
        }
        itemOf(cfg.shipwrightDismantleFeeItem2)?.let { item ->
            val each = cfg.shipwrightDismantleFeeCount2
            if (each > 0) lines.add(Line(item, units * each))
        }
        return lines
    }

    /** The bill for [ship] as she stands right now. Null bounds -- shipyard chunks unreadable -- is free. */
    fun quote(level: ServerLevel, ship: LoadedServerShip): List<Line> =
        quote(ShipRepair.bounds(level, ship)?.blocks ?: 0)

    /**
     * What [player] is short, line by line. Empty means they can pay.
     *
     * Creative pays nothing: a captain who can spawn the emeralds is not being asked a question by being
     * charged for them, only made to go and fetch them.
     */
    fun shortfall(player: ServerPlayer, fee: List<Line>): List<Line> {
        if (fee.isEmpty() || player.abilities.instabuild) return emptyList()
        val short = ArrayList<Line>(fee.size)
        for (line in fee) {
            val held = countIn(player.inventory, line.item)
            if (held < line.count) short.add(Line(line.item, line.count - held))
        }
        return short
    }

    /**
     * Take the fee out of [player]'s inventory. Call only behind an empty [shortfall]; this takes what it
     * finds and does not check first.
     */
    fun take(player: ServerPlayer, fee: List<Line>) {
        if (fee.isEmpty() || player.abilities.instabuild) return
        val inventory = player.inventory
        val changed = ArrayList<Int>()

        for (line in fee) {
            var remaining = line.count
            for (slot in 0 until inventory.containerSize) {
                if (remaining <= 0) break
                val stack = inventory.getItem(slot)
                if (stack.isEmpty || !stack.`is`(line.item)) continue
                val take = minOf(remaining, stack.count)
                stack.shrink(take)
                remaining -= take
                changed.add(slot)
            }
        }

        if (changed.isEmpty()) return
        // The shipwright's book is open, and it is not containerId 0. `broadcastChanges` emits a
        // containerId-0 slot sync, which the client DROPS for non-hotbar slots while another menu is up --
        // leaving a ghost stack of emeralds already spent. A direct per-slot player-inventory packet lands
        // whatever container is open. Same trap, and the same answer, as CrewSummon and EurekaAssembler.
        player.inventoryMenu.broadcastChanges()
        for (slot in changed.distinct()) {
            player.connection.send(ClientboundSetPlayerInventoryPacket(slot, inventory.getItem(slot)))
        }
    }

    /** "19 Emerald", or "19 Emerald and 4 Diamond" -- for the refusal, which has to say what is owed. */
    fun describe(fee: List<Line>): String = fee.joinToString(" and ") { line ->
        line.count.toString() + " " + ItemStack(line.item).hoverName.string
    }

    private fun countIn(inventory: Inventory, item: Item): Int {
        var found = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.`is`(item)) found += stack.count
        }
        return found
    }

    private fun itemOf(id: String): Item? {
        if (id.isBlank()) return null
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)
    }
}
