package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.ShipManifest
import org.valkyrienskies.eureka.template.ShipTemplate

/**
 * Talking to a shipwright: every interaction the profession has.
 *
 * Handing over a blueprint or a Heart of the Sea is done by holding it out; everything else happens in the
 * screen. Deliberately all on the villager rather than on its bench -- a workbench that answered questions
 * would make the shipwright decorative, and the point of the profession is that a harbor without one cannot
 * build you anything.
 */
object ShipwrightTalk {

    /**
     * Right-clicking a shipwright. Returns true if the click was ours and should go no further.
     *
     * Claiming the click matters as much as handling it: a shipwright has no trades, so anything we pass on
     * reaches vanilla's trade screen, finds nothing to sell, and the villager shakes its head at the player.
     */
    fun interact(level: ServerLevel, player: ServerPlayer, villager: Villager, stack: ItemStack): Boolean {
        if (!isShipwright(villager)) return false

        if (stack.`is`(EurekaItems.BLUEPRINT.get())) {
            Shipwright.file(level, player, stack)
            return true
        }

        if (stack.`is`(Items.HEART_OF_THE_SEA)) {
            buyShelfSpace(level, player, villager, stack)
            return true
        }

        openShelf(level, player, villager)
        return true
    }

    fun isShipwright(villager: Villager): Boolean =
        villager.villagerData.profession().`is`(ShipwrightProfession.PROFESSION_KEY)

    /** Send this player their whole shelf, as the screen draws it. */
    fun openShelf(level: ServerLevel, player: ServerPlayer, villager: Villager) {
        val shelf = shelfFor(level, player, villager)
        sender?.invoke(player, shelf)
    }

    fun shelfFor(level: ServerLevel, player: ServerPlayer, villager: Villager): ShipwrightMenu.Shelf {
        val shelf = ShipwrightMenu.snapshot(
            ledger = ShipwrightLedger.get(level.server),
            owner = player.uuid,
            villager = villager.id,
            hasFreeBottle = Shipwright.freeBottle(player) != null,
            detail = { template -> detailOf(level, template) }
        )
        return ShipwrightMenu.Shelf(
            shelf.villager, shelf.slots, shelf.hasFreeBottle, shelf.rows,
            vesselsFor(level, player, villager)
        )
    }

    /**
     * Every hull the shipwright can see, with its repair state.
     *
     * The dropdown's answer is remembered on the repair bill; when there is none yet the shipwright guesses,
     * quotes against its guess, and the player sees a pre-filled dropdown they can change.
     */
    private fun vesselsFor(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager
    ): List<ShipwrightMenu.Vessel> {
        val ledger = ShipwrightLedger.get(level.server)
        val bench = yard(villager)

        return ShipwrightYard.visible(level, bench).mapNotNull { (ship, isChild) ->
            // Tight block bounds, the same measure a template uses -- see ShipRepair.bounds.
            val hull = ShipRepair.bounds(level, ship) ?: return@mapNotNull null
            val slug = ship.slug ?: return@mapNotNull null

            // The captain's choice if they have made one, otherwise the shipwright's guess.
            val chosen = ledger.repairFor(player.uuid, slug)?.plansName
                ?: ShipwrightYard.guessPlans(level, player.uuid, ship)?.shipName

            val plans = chosen?.let { ledger.plansFor(player.uuid, it) }
            val assessment = plans?.let { ShipRepair.assess(level, ship, it) }

            // Re-quoted on EVERY look, not only when no bill exists yet.
            //
            // This is what keeps a hull's damage current, and it has to be unconditional: a finished repair
            // leaves a bill behind whose cost is empty, so "quote only if there is no bill" would find that
            // empty bill forever and report a ship as sound however much of it was later blown off. Materials
            // already handed over survive the re-quote -- see ShipwrightLedger.quoteRepair.
            //
            // Doing it here rather than off a block-update hook is deliberate. A hook would fire for every
            // block placed or broken on every ship in the world, to answer a question nobody is asking until
            // they open this book. Reassessing when the book opens gives the same answer at the only moment it
            // can be seen.
            val bill = if (plans != null && assessment != null) {
                ledger.quoteRepair(player.uuid, slug, plans.shipName, assessment.missing)
            } else {
                null
            }

            ShipwrightMenu.Vessel(
                slug = slug,
                width = hull.width,
                height = hull.height,
                length = hull.length,
                blocks = hull.blocks,
                mass = ship.inertiaData.mass,
                fuel = ShipwrightYard.fuelOf(level, ship),
                child = isChild,
                plansName = chosen,
                match = assessment?.match ?: 0f,
                refusal = assessment?.refusal,
                repairs = bill?.cost?.map { (item, needed) ->
                    ShipwrightMenu.Material(item, needed, bill.delivered[item] ?: 0)
                } ?: emptyList()
            )
        }
    }

    /**
     * The size, weight and speed of a template, recomputed rather than stored.
     *
     * Costs one pass over the block list per set of plans when a screen opens, which is nothing beside the
     * alternative: a second record of each ship that can drift from the template it describes.
     */
    private fun detailOf(level: ServerLevel, template: String): ShipwrightMenu.Detail? {
        val found = ShipTemplate.find(level, template) ?: return null
        val manifest = ShipManifest.of(found)
        return ShipwrightMenu.Detail(
            width = manifest.width,
            height = manifest.height,
            length = manifest.length,
            blocks = manifest.blocks,
            mass = manifest.mass,
            topSpeed = manifest.topSpeed,
            profile = manifest.profile.name
        )
    }

    /**
     * Act on what the screen asked for, then answer with a fresh shelf.
     *
     * Every action re-sends the snapshot, including the ones that fail. That is what keeps the screen honest
     * without polling: whatever the server now believes is what the player is looking at.
     */
    fun act(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        action: ShipwrightMenu.Action,
        shipName: String,
        argument: String = ""
    ) {
        val ledger = ShipwrightLedger.get(level.server)

        // The yard actions name a HULL rather than a set of plans, so they resolve differently and are handled
        // before the shelf lookup below would fail on a slug it has never heard of.
        if (action == ShipwrightMenu.Action.SELECT ||
            action == ShipwrightMenu.Action.PAY_REPAIR ||
            action == ShipwrightMenu.Action.REPAIR
        ) {
            yardAction(level, player, villager, action, shipName, argument)
            openShelf(level, player, villager)
            return
        }

        val plans = ledger.plansFor(player.uuid, shipName)

        if (plans == null) {
            PathMessages.send(player, "Those plans are no longer on file.", PathMessages.Kind.WARN)
            openShelf(level, player, villager)
            return
        }

        when (action) {
            ShipwrightMenu.Action.PAY -> Shipwright.pay(level, player, plans)
            ShipwrightMenu.Action.DELETE -> {
                if (ledger.delete(player.uuid, shipName)) {
                    PathMessages.send(
                        player,
                        "The shipwright discards the plans for '$shipName'.",
                        PathMessages.Kind.WARN
                    )
                }
            }
            ShipwrightMenu.Action.BUILD -> {
                if (!plans.ready) {
                    PathMessages.send(player, "'$shipName' is not paid for yet.", PathMessages.Kind.WARN)
                } else {
                    Shipwright.build(level, player, plans, yard(villager))
                }
            }
            ShipwrightMenu.Action.BOTTLE -> {
                if (!plans.ready) {
                    PathMessages.send(player, "'$shipName' is not paid for yet.", PathMessages.Kind.WARN)
                } else {
                    Shipwright.bottle(level, player, plans)
                }
            }
            // Handled above, before the shelf lookup -- these name a hull, not a set of plans.
            else -> Unit
        }

        openShelf(level, player, villager)
    }

    /**
     * The three actions that act on a hull in the water rather than on a page.
     *
     * [slug] names the ship; for [ShipwrightMenu.Action.SELECT], [argument] names the plans chosen in the
     * dropdown.
     */
    private fun yardAction(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        action: ShipwrightMenu.Action,
        slug: String,
        argument: String
    ) {
        val bench = yard(villager)
        // Re-resolved from what the shipwright can see rather than trusting the slug on the wire, so a client
        // cannot ask about a hull on the other side of the world.
        val ship = ShipwrightYard.visible(level, bench).firstOrNull { it.first.slug == slug }?.first ?: run {
            PathMessages.send(player, "That ship is no longer in the yard.", PathMessages.Kind.WARN)
            return
        }

        val ledger = ShipwrightLedger.get(level.server)
        val chosen = when (action) {
            ShipwrightMenu.Action.SELECT -> argument
            else -> ledger.repairFor(player.uuid, slug)?.plansName
        }
        val plans = chosen?.let { ledger.plansFor(player.uuid, it) } ?: run {
            PathMessages.send(player, "Choose which plans to mend it against.", PathMessages.Kind.WARN)
            return
        }

        when (action) {
            ShipwrightMenu.Action.SELECT -> {
                val assessment = ShipRepair.assess(level, ship, plans)
                if (assessment == null) {
                    PathMessages.send(player, "The plans for that ship are missing.", PathMessages.Kind.ERROR)
                    return
                }
                // Quoted even when refused, so the card can show the player exactly why rather than only that
                // the button is dead.
                ledger.quoteRepair(player.uuid, slug, plans.shipName, assessment.missing)
                assessment.refusal?.let { PathMessages.send(player, it, PathMessages.Kind.WARN) }
            }

            ShipwrightMenu.Action.PAY_REPAIR -> {
                val bill = ledger.repairFor(player.uuid, slug) ?: return
                payRepair(level, player, bill)
            }

            ShipwrightMenu.Action.REPAIR -> ShipwrightYard.repair(level, player, ship, plans)

            else -> Unit
        }
    }

    /**
     * Take everything the repair still needs out of [player]'s inventory.
     *
     * The same rules as paying for a build: all at once, never more than is owed, and creative settles the
     * whole bill outright.
     */
    private fun payRepair(level: ServerLevel, player: ServerPlayer, bill: ShipwrightLedger.RepairBill) {
        val ledger = ShipwrightLedger.get(level.server)

        if (player.abilities.instabuild) {
            for ((item, owed) in bill.outstanding()) ledger.deliverRepair(bill, item, owed)
            PathMessages.send(player, "The shipwright has all it needs.", PathMessages.Kind.GOOD)
            return
        }

        var taken = 0
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val wanted = bill.outstanding(stack.item)
            if (wanted <= 0) continue

            val accepted = ledger.deliverRepair(bill, stack.item, minOf(wanted, stack.count))
            if (accepted <= 0) continue
            stack.shrink(accepted)
            taken += accepted
        }

        if (taken == 0) {
            PathMessages.send(player, "Nothing on you that it needs.", PathMessages.Kind.WARN)
        } else if (bill.ready) {
            PathMessages.send(player, "The shipwright has all it needs.", PathMessages.Kind.GOOD)
        } else {
            PathMessages.send(
                player,
                "Handed over $taken items -- ${(bill.progress * 100).toInt()}% of the repair.",
                PathMessages.Kind.GOOD
            )
        }
    }

    /**
     * Where a ship this shipwright builds is set down.
     *
     * The **bench**, not the villager -- a bench sits on a dock with the water in front of it, which is exactly
     * where a hull wants to go, whereas the villager could be anywhere it happens to have wandered. Falls back
     * to the villager only if it has somehow lost its workstation.
     */
    private fun yard(villager: Villager): BlockPos {
        val site: GlobalPos? = villager.brain.getMemory(MemoryModuleType.JOB_SITE).orElse(null)
        return site?.pos() ?: villager.blockPosition()
    }

    private fun buyShelfSpace(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        stack: ItemStack
    ) {
        val ledger = ShipwrightLedger.get(level.server)
        if (!ledger.buySlot(player.uuid)) {
            PathMessages.send(
                player,
                "Your shelf is already as large as anyone's gets " +
                    "(${EurekaConfig.SERVER.shipwrightSlotsMax}).",
                PathMessages.Kind.ERROR
            )
            return
        }

        if (!player.abilities.instabuild) stack.shrink(1)
        level.playSound(
            null, villager.blockPosition(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.NEUTRAL, 0.6f, 1.2f
        )
        PathMessages.send(
            player,
            "Room for another set of plans -- ${ledger.libraryOf(player.uuid).slots} in all.",
            PathMessages.Kind.GOOD
        )
        openShelf(level, player, villager)
    }

    /**
     * Installed by the loader's networking layer, which owns packets. Same indirection as `PathMessages.sender`.
     */
    @Volatile
    @JvmStatic
    var sender: ((ServerPlayer, ShipwrightMenu.Shelf) -> Unit)? = null

    /** Unused today; kept so the ledger's per-player keying reads the same everywhere. */
    fun ownerOf(player: ServerPlayer): UUID = player.uuid
}
