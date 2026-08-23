package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import java.util.UUID

/**
 * Calling a crew to a ship, and what it costs to move them.
 *
 * ## Why there is a price at all
 * A crew is a minted id now rather than a name on a wheel, so a captain can point any wheel at any of their
 * crews and have them appear. Free, that is a teleport with no downside: every crew in the world is always
 * exactly where you want it, and the ship you left them on stops meaning anything.
 *
 * So moving a crew costs an ender pearl a head, and MOVING them is the only thing that costs. Calling the crew
 * a wheel already keeps is free however often it happens -- which is what makes ordinary sailing free, and
 * reassembling a ship free, and letting a bottled ship out free. All three are the same case underneath: the
 * wheel is asking for the crew it already had.
 *
 * ## Where the pearls come from
 * The ship's chests and barrels first, the captain's pockets second. A ship is a place with stores in it, and
 * a captain who has provisioned one should not have to carry the fare in their own inventory -- but a small
 * boat with no hold at all should still be able to take a crew, so the pockets are the fallback rather than
 * the rule.
 *
 * ## Nothing is half-done
 * The bill is drawn up in full before a single pearl moves, and a captain who cannot pay it is told how many
 * short they are and charged nothing. A crew that arrived four-fifths complete would be a crew silently split
 * across two ships with no way to see it had happened.
 */
object CrewSummon {

    /** Whether a stack is the fare. */
    fun isFare(stack: ItemStack): Boolean = stack.`is`(Items.ENDER_PEARL)

    /**
     * What passage would cost, and where it would be found.
     *
     * [fromHold] and [fromPack] are what is actually THERE, not what would be taken. Their sum against [cost]
     * is the whole affordability question, and keeping them apart is what lets a refusal say which pocket came
     * up empty rather than just that one did.
     */
    class Bill(val cost: Int, val fromHold: Int, val fromPack: Int) {
        val short: Int get() = (cost - fromHold - fromPack).coerceAtLeast(0)
        val free: Boolean get() = cost <= 0
        val affordable: Boolean get() = short == 0
    }

    /**
     * Draw up the bill for bringing [crewId] aboard [ship].
     *
     * Free when the wheel already keeps this crew -- see the class note -- and free in creative, or when the
     * fare is configured to nothing.
     */
    fun bill(
        level: ServerLevel,
        captain: ServerPlayer,
        ship: LoadedServerShip,
        station: ShipHelmBlockEntity,
        crewId: UUID
    ): Bill {
        val ledger = CrewLedger.get(level.server)
        val heads = ledger.berths(crewId).size
        val fare = EurekaConfig.SERVER.crewPassagePearls
        val owed = when {
            heads == 0 -> 0
            fare <= 0 -> 0
            captain.abilities.instabuild -> 0
            // Through bindingFor, so a wheel that has not adopted its crew id yet is not charged for calling
            // the very crew it has been carrying all along.
            ledger.bindingFor(station, captain.uuid) == crewId -> 0
            else -> heads * fare
        }
        if (owed <= 0) return Bill(0, 0, 0)
        return Bill(owed, ShipStores.count(level, ship, ::isFare), countIn(captain.inventory))
    }

    /**
     * Take the fare -- holds first, pockets second -- and answer how much was actually found.
     *
     * Call only behind [Bill.affordable]. This takes what it can and reports it, exactly as
     * [ShipStores.withdraw] does, and it is the caller's business not to have asked for more than is there.
     */
    fun pay(level: ServerLevel, captain: ServerPlayer, ship: LoadedServerShip, bill: Bill): Int {
        if (bill.free) return 0
        var paid = ShipStores.withdraw(level, ship, ::isFare, bill.cost)
        if (paid >= bill.cost) return paid

        val inventory = captain.inventory
        val changed = ArrayList<Int>()
        var remaining = bill.cost - paid
        for (slot in 0 until inventory.containerSize) {
            if (remaining <= 0) break
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || !isFare(stack)) continue
            val take = minOf(remaining, stack.count)
            stack.shrink(take)
            remaining -= take
            paid += take
            changed.add(slot)
        }
        if (changed.isNotEmpty()) {
            // The helm menu is open, and it is not containerId 0. `broadcastChanges` emits a containerId-0
            // slot sync, which the client DROPS for non-hotbar slots while another menu is up -- leaving a
            // ghost stack of pearls the captain has already spent. A direct per-slot player-inventory packet
            // is applied whatever container is open. Same trap, and the same answer, as EurekaAssembler.
            captain.inventoryMenu.broadcastChanges()
            for (slot in changed) {
                captain.connection.send(ClientboundSetPlayerInventoryPacket(slot, inventory.getItem(slot)))
            }
        }
        return paid
    }

    /**
     * Bring [crewId] aboard [ship], charging passage and standing the outgoing crew down.
     *
     * Returns null when they came, or the reason they did not -- so the caller decides how to say it, and an
     * assembly can carry on regardless while a button press reports the refusal.
     *
     * Every gate that can refuse runs before anything is spent or moved, so a refusal leaves the ship exactly
     * as it found it.
     */
    fun bring(
        level: ServerLevel,
        captain: ServerPlayer,
        ship: LoadedServerShip,
        station: ShipHelmBlockEntity,
        crewId: UUID,
        stationPos: Long
    ): String? {
        val ledger = CrewLedger.get(level.server)
        val crew = ledger.crew(crewId) ?: return "Those articles have been closed -- that crew is no more."
        if (crew.captain != captain.uuid) return "${crew.name} answer to another captain."
        if (crew.berths.isEmpty()) return "${crew.name} have nobody signed on."

        val berthLimit = CrewData.slots(captain)
        if (crew.berths.size > berthLimit) {
            return "${crew.name} muster ${crew.berths.size}, and you have $berthLimit berths. " +
                "Offer Hearts of the Sea at a wheel to make room."
        }

        val bill = bill(level, captain, ship, station, crewId)
        if (!bill.affordable) {
            return "Passage for ${crew.name} is ${bill.cost} ender ${plural(bill.cost, "pearl")} -- " +
                "${bill.fromHold} in the holds, ${bill.fromPack} in your pockets, ${bill.short} short."
        }

        // The crew standing on this deck now, if they are a DIFFERENT crew of this captain's. Taken off before
        // the new hands arrive, so two crews are never aboard at once, and the outgoing one keeps every berth,
        // duty and post for whenever they are called again.
        val outgoing = ledger.bindingFor(station, captain.uuid)?.takeIf { it != crewId }
        if (outgoing != null) {
            val posts = CrewMuster.postsOf(level, ship, BlockPos.of(stationPos))
            // sweepHull = false: this ship is not going anywhere, and the wider sweep would take a guest crew
            // and another captain's hands off the deck along with the one being relieved.
            CrewMuster.standDownShip(level, ship.id, ship.worldAABB, listOf(outgoing), posts, sweepHull = false)
        }

        pay(level, captain, ship, bill)
        station.bindCrew(captain.uuid, crewId)
        CrewMuster.muster(level, captain, ship, crewId, berthLimit, stationPos)
        return null
    }

    private fun countIn(inventory: Inventory): Int {
        var total = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && isFare(stack)) total += stack.count
        }
        return total
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}
