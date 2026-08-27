package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Item
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
        attend(level, player, villager)
        val shelf = shelfFor(level, player, villager)
        sender?.invoke(player, shelf)
    }

    /**
     * One shipwright, currently serving one captain.
     *
     * Held by villager id rather than by a reference, so an unloaded chunk drops the hold instead of
     * pinning an entity that no longer exists.
     */
    private class Attention(val player: UUID, var until: Long)

    private val attending = HashMap<Int, Attention>()

    /** Five minutes of held attention, refreshed by every action. The failsafe for a client that vanishes. */
    private const val ATTENTION_TICKS = 20L * 60L * 5L

    /** Twice the reach the actions themselves check, so the hold outlives a step backwards. */
    private const val ATTENTION_RANGE_SQR = 144.0

    /**
     * Keep the shipwright's attention on [player] while their book is open.
     *
     * Every action re-checks that the villager is still within reach, so one that strolled off mid-browse
     * would start refusing Dismiss and Claim -- from the captain's side, a menu that stopped working for
     * no stated reason. A shopkeeper does not wander away from the counter mid-sale.
     *
     * This is a HOLD, not a freeze: the brain keeps running, he keeps looking at you, he can still be hurt
     * and still be talked to. Only the walk target is taken away, which is the same light measure a
     * stationed gunner gets in [org.valkyrienskies.eureka.crew.GunStations] and for the same reason -- a
     * navigation that is never given a destination never builds a path.
     */
    private fun attend(level: ServerLevel, player: ServerPlayer, villager: Villager) {
        attending[villager.id] = Attention(player.uuid, level.gameTime + ATTENTION_TICKS)
    }

    /** The captain closed the book. Let him get back to his day. */
    fun dismissAttention(villager: Villager) {
        attending.remove(villager.id)
    }

    /**
     * Hold every attending shipwright still, and let go of the ones whose captain has gone.
     *
     * The deadline is the failsafe: a client that closes without a word, crashes, or logs out must not
     * leave a villager rooted to a spot forever.
     */
    fun tick(level: ServerLevel) {
        if (attending.isEmpty()) return
        val gone = ArrayList<Int>()
        for ((id, attention) in attending) {
            val villager = level.getEntity(id) as? Villager
            // A villager in another dimension belongs to another level's tick, and one that has unloaded
            // is not ours to hold. Neither is a reason to drop the hold; only time and distance are.
            if (villager == null) {
                if (level.gameTime > attention.until) gone.add(id)
                continue
            }
            val captain = level.server.playerList.getPlayer(attention.player)
            if (captain == null || level.gameTime > attention.until ||
                villager.distanceToSqr(captain) > ATTENTION_RANGE_SQR
            ) {
                gone.add(id)
                continue
            }
            villager.lookControl.setLookAt(captain, 30.0f, 30.0f)
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            if (!villager.navigation.isDone) villager.navigation.stop()
        }
        for (id in gone) attending.remove(id)
    }

    fun shelfFor(level: ServerLevel, player: ServerPlayer, villager: Villager): ShipwrightMenu.Shelf {
        reconcileOrphans(level, player)
        val shelf = ShipwrightMenu.snapshot(
            ledger = ShipwrightLedger.get(level.server),
            owner = player.uuid,
            villager = villager.id,
            hasFreeBottle = Shipwright.freeBottle(player) != null,
            detail = { template -> detailOf(level, template) }
        )
        return ShipwrightMenu.Shelf(
            shelf.villager, shelf.slots, shelf.hasFreeBottle, shelf.rows,
            vesselsFor(level, player, villager),
            repairEnabled = EurekaConfig.SERVER.shipwrightRepair,
            partialRepair = EurekaConfig.SERVER.shipwrightPartialRepair,
            hasBlankBlueprint = Shipwright.blankBlueprint(player) != null,
            excludeEnabled = EurekaConfig.SERVER.shipwrightExclude,
            swapEnabled = EurekaConfig.SERVER.shipwrightSwapMaterials,
            swapFoundational = EurekaConfig.SERVER.shipwrightSwapFoundational,
            salvage = pilesFor(level, player),
            dismantleEnabled = EurekaConfig.SERVER.shipwrightDismantle
        )
    }

    /**
     * The captain's broken-up ships, as claim lists.
     *
     * Sorted largest first within a tab so the rows that matter are the ones on screen; a hull is four
     * thousand planks and a hundred other things, and the hundred are not what the captain came for.
     */
    private fun pilesFor(level: ServerLevel, player: ServerPlayer): List<ShipwrightMenu.Pile> {
        val ledger = ShipwrightLedger.get(level.server)
        return ledger.salvageFor(player.uuid).map { pile ->
            ShipwrightMenu.Pile(
                shipName = pile.shipName,
                hull = pile.hull.entries.sortedByDescending { it.value }
                    .map { ShipwrightMenu.Material(it.key, it.value, 0) },
                cargo = pile.cargo.entries.sortedByDescending { it.value }
                    .map { ShipwrightMenu.Material(it.key, it.value, 0) },
                keepsakes = pile.keepsakes.mapIndexed { index, stack ->
                    ShipwrightMenu.Keepsake(
                        index, stack.item, stack.count, ShipSalvage.describe(stack)
                    )
                }
            )
        }
    }

    /**
     * Hand back anything credited to a row that is no longer on the bill.
     *
     * An alteration lasts a session, and materials paid into an "Any slabs" row are credited under the
     * family rather than under the slab that was handed over. So a captain who sets a swap, pays into it,
     * and then logs out comes back to a bill that no longer has that row -- and their materials sitting
     * against a line nothing reads. Not lost, exactly, but not theirs either.
     *
     * The shelf opening is the one moment they are demonstrably standing in front of the shipwright, which
     * is the only moment a refund can actually reach them. Nothing is dropped: what will not fit stays
     * credited, exactly as every other refund here behaves.
     */
    private fun reconcileOrphans(level: ServerLevel, player: ServerPlayer) {
        val ledger = ShipwrightLedger.get(level.server)
        for (plans in ledger.allPlans(player.uuid)) {
            if (plans.delivered.keys.all { it in plans.cost }) continue
            refund(level, player, plans) { /* the bill already moved; this only settles up */ }
        }
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
        // With repair off there is no Yard page, so assessing every hull in range would be work nobody
        // can ever see.
        if (!EurekaConfig.SERVER.shipwrightRepair) return emptyList()

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
                } ?: emptyList(),
                // Quoted off the walk that was just done for the repair assessment, so the fee costs nothing
                // extra to work out and can never disagree with the block count printed beside it.
                fee = YardFee.quoteDismantle(hull.blocks).map { ShipwrightMenu.Material(it.item, it.count, 0) }
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
        argument: String = "",
        argument2: String = ""
    ) {
        val ledger = ShipwrightLedger.get(level.server)

        // Closing the book names nothing at all, so it resolves before any lookup could refuse it.
        if (action == ShipwrightMenu.Action.CLOSED) {
            dismissAttention(villager)
            return
        }

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

        // Dismantling names a hull; claiming names a pile. Neither is a set of plans, so both resolve here
        // rather than falling through the plans lookup below and being refused as a missing blueprint.
        if (action == ShipwrightMenu.Action.DISMANTLE) {
            dismantle(level, player, villager, shipName)
            openShelf(level, player, villager)
            return
        }
        if (action == ShipwrightMenu.Action.CLAIM_ALL ||
            action == ShipwrightMenu.Action.CLAIM_ONE ||
            action == ShipwrightMenu.Action.SALVAGE_DISMISS ||
            action == ShipwrightMenu.Action.SALVAGE_DISMISS_ALL
        ) {
            salvageAction(level, player, action, shipName, argument, argument2)
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
                        PathMessages.Kind.WARN, PathMessages.Topic.SHIPWRIGHT_PLANS
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
            ShipwrightMenu.Action.EXCLUDE_CATEGORY -> excludeCategory(player, plans, argument)
            ShipwrightMenu.Action.EXCLUDE_ITEM -> excludeItem(player, plans, argument)
            ShipwrightMenu.Action.SWAP -> swap(player, plans, argument, argument2)
            ShipwrightMenu.Action.RESET_ALTERATION -> {
                if (!plans.alteration.isEmpty) {
                    refund(level, player, plans) { plans.alteration = Alteration.NONE }
                    PathMessages.send(player, "The plans are as drawn again.", PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_ALTERATIONS)
                }
            }
            ShipwrightMenu.Action.SAVE_AS_NEW -> {
                val refusal = Shipwright.saveAsNew(level, player, plans, argument)
                if (refusal != null) PathMessages.send(player, refusal, PathMessages.Kind.WARN)
            }
            ShipwrightMenu.Action.TAKE_BLUEPRINT -> Shipwright.takeBlueprint(level, player, plans)
            // Handled above, before the shelf lookup -- these name a hull, not a set of plans.
            else -> Unit
        }
        ledger.setDirty()

        openShelf(level, player, villager)
    }

    // region altering the plans

    /**
     * Every change to an alteration goes through here, and every one of them can hand materials back.
     *
     * A captain who has already paid forty birch planks toward a row and then strikes that row off is owed
     * forty birch planks. The alternative -- keeping them against a line that no longer exists, or quietly
     * voiding them -- is the kind of small theft that makes people stop trusting a menu. Unlike a repair
     * re-quote, where the world changed underfoot, this change was the captain's own click.
     *
     * Whatever will not fit stays credited against the plans rather than being dropped or voided, so a full
     * pack costs the captain nothing but a second click once they have made room.
     */
    private fun refund(
        level: ServerLevel,
        player: ServerPlayer,
        plans: ShipwrightLedger.Plans,
        change: () -> Unit
    ): Boolean {
        val before = HashMap(plans.delivered)
        change()

        val owed = ArrayList<ItemStack>()
        for ((item, had) in before) {
            val room = plans.cost[item] ?: 0
            if (had <= room) continue
            var over = had - room
            plans.delivered[item] = room
            while (over > 0) {
                val stack = ItemStack(item, minOf(over, item.defaultMaxStackSize))
                owed.add(stack)
                over -= stack.count
            }
        }
        if (owed.isEmpty()) return true

        // Creative hands nothing back because it was never taken -- `Shipwright.pay` grants the bill
        // outright there rather than emptying a pack.
        if (player.abilities.instabuild) return true

        var stuck = 0
        for (stack in owed) {
            if (player.inventory.add(stack)) continue
            // No room. The materials stay CREDITED against the plans rather than being dropped or voided,
            // so the captain has lost nothing: clear a slot, change the row again, and they come across.
            // Nothing in this menu ever puts items on the ground.
            plans.delivered[stack.item] = (plans.delivered[stack.item] ?: 0) + stack.count
            stuck += stack.count
        }
        if (stuck > 0) {
            PathMessages.send(
                player,
                "Your pack is full -- $stuck materials stay on the bench.",
                PathMessages.Kind.WARN, PathMessages.Topic.SHIPWRIGHT_ALTERATIONS
            )
        }
        return true
    }

    private fun excludeCategory(player: ServerPlayer, plans: ShipwrightLedger.Plans, argument: String) {
        val category = MaterialFamilies.Category.entries.firstOrNull { it.name == argument } ?: return
        if (category == MaterialFamilies.Category.FOUNDATIONAL) return
        if (!EurekaConfig.SERVER.shipwrightExclude) return

        val categories = plans.alteration.excludedCategories.toMutableSet()
        if (!categories.remove(category)) categories.add(category)
        plans.alteration = Alteration(categories, plans.alteration.excludedItems, plans.alteration.swaps)
    }

    private fun excludeItem(player: ServerPlayer, plans: ShipwrightLedger.Plans, argument: String) {
        if (!EurekaConfig.SERVER.shipwrightExclude) return
        val item = itemOf(argument) ?: return
        // Structure is never optional, whatever the client asked for. The screen greys these rows, but a
        // screen is not a gate.
        if (MaterialFamilies.categoryOf(item) == MaterialFamilies.Category.FOUNDATIONAL) {
            PathMessages.send(player, "That is part of the hull -- it cannot be left off.", PathMessages.Kind.WARN)
            return
        }

        val items = plans.alteration.excludedItems.toMutableSet()
        if (!items.remove(item)) items.add(item)
        plans.alteration = Alteration(plans.alteration.excludedCategories, items, plans.alteration.swaps)
    }

    /**
     * Set, change or clear one row's swap.
     *
     * [to] is a plain item id, "*<family id>" for an Any row, or blank to put the row back. Everything is
     * re-checked here: that swapping is on at all, that structure may be swapped if this row is structure,
     * and above all that the replacement is genuinely of the same kind. A client that offers a bad swap is
     * a client that gets refused.
     */
    private fun swap(player: ServerPlayer, plans: ShipwrightLedger.Plans, from: String, to: String) {
        if (!EurekaConfig.SERVER.shipwrightSwapMaterials) return
        val original = itemOf(from) ?: return
        if (MaterialFamilies.categoryOf(original) == MaterialFamilies.Category.FOUNDATIONAL &&
            !EurekaConfig.SERVER.shipwrightSwapFoundational
        ) {
            PathMessages.send(player, "This shipwright will not re-material the hull itself.", PathMessages.Kind.WARN)
            return
        }

        val swaps = LinkedHashMap(plans.alteration.swaps)
        when {
            to.isBlank() -> swaps.remove(original)
            to.startsWith("*") -> {
                val family = MaterialFamilies.familyOf(original) ?: return
                if (family.location().toString() != to.substring(1)) return
                val members = MaterialFamilies.replacementsFor(original)
                swaps[original] = Alteration.Any(family, members.first())
            }
            else -> {
                val chosen = itemOf(to) ?: return
                if (!MaterialFamilies.interchangeable(original, chosen)) {
                    PathMessages.send(player, "That is not the same kind of thing.", PathMessages.Kind.WARN)
                    return
                }
                swaps[original] = Alteration.Fixed(chosen)
            }
        }
        plans.alteration =
            Alteration(plans.alteration.excludedCategories, plans.alteration.excludedItems, swaps)
    }

    private fun itemOf(id: String): Item? =
        if (id.isBlank()) null
        else BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)

    // endregion

    /**
     * The three actions that act on a hull in the water rather than on a page.
     *
     * [slug] names the ship; for [ShipwrightMenu.Action.SELECT], [argument] names the plans chosen in the
     * dropdown.
     */
    /**
     * Break a hull up into a claim list.
     *
     * The hull is re-resolved from what the SHIPWRIGHT can see, not from the slug on the wire, exactly as
     * [yardAction] does -- this is the most destructive thing in the menu, and a client that could name any
     * ship in the world could delete any ship in the world.
     */
    private fun dismantle(level: ServerLevel, player: ServerPlayer, villager: Villager, slug: String) {
        if (!EurekaConfig.SERVER.shipwrightDismantle) {
            PathMessages.send(player, "This shipwright does not break ships up.", PathMessages.Kind.WARN)
            return
        }
        val ship = ShipwrightYard.visible(level, yard(villager)).firstOrNull { it.first.slug == slug }?.first
            ?: run {
                PathMessages.send(player, "That ship is no longer in the yard.", PathMessages.Kind.WARN)
                return
            }

        // Re-quoted here rather than trusted off the wire, and off the hull as she stands THIS instant --
        // the screen's figure was worked out when the book opened, and a broadside since would have made it
        // a lie in the captain's favour.
        val fee = YardFee.quoteDismantle(level, ship)
        val short = YardFee.shortfall(player, fee)
        if (short.isNotEmpty()) {
            PathMessages.send(
                player,
                "Breaking her up costs " + YardFee.describe(fee) +
                    ". You are short " + YardFee.describe(short) + ".",
                PathMessages.Kind.WARN
            )
            return
        }

        // Taken only once the hull is actually gone. Dismantle can still refuse -- an empty ship has nothing
        // to salvage -- and charging for a job that did not happen is the one failure a captain would not
        // forgive.
        if (ShipSalvage.dismantle(level, player, ship) == null) return
        YardFee.take(player, fee)
        if (fee.isNotEmpty()) {
            PathMessages.send(player, "The yard takes " + YardFee.describe(fee) + ".", PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_DISMANTLE)
        }
    }

    /**
     * Claim off a pile, or throw a row away.
     *
     * [argument] names the tab -- "hull", "cargo", or "keep" -- and [argument2] one row, or blank for all
     * of it. Everything is re-checked against the ledger: the pile, the tab, and the row.
     */
    private fun salvageAction(
        level: ServerLevel,
        player: ServerPlayer,
        action: ShipwrightMenu.Action,
        shipName: String,
        argument: String,
        argument2: String
    ) {
        val ledger = ShipwrightLedger.get(level.server)
        val pile = ledger.salvagePile(player.uuid, shipName) ?: run {
            PathMessages.send(player, "That salvage has already been carried off.", PathMessages.Kind.WARN)
            return
        }

        val useShulkers = argument.endsWith("+box")
        val tab = argument.removeSuffix("+box")
        if (tab == "keep") {
            // Keepsakes are addressed by position, not by item: two shulker boxes are the same item and
            // the entire reason they are kept whole is that their contents differ.
            val index = argument2.toIntOrNull()
            when {
                action == ShipwrightMenu.Action.SALVAGE_DISMISS_ALL -> {
                    val kinds = ledger.dismissAllKeepsakes(player.uuid, pile)
                    PathMessages.send(
                        player,
                        if (kinds > 0) "$kinds kept items thrown back into the sea."
                        else "There was nothing left on that list.",
                        if (kinds > 0) PathMessages.Kind.WARN else PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS
                    )
                }
                action == ShipwrightMenu.Action.CLAIM_ALL -> Salvaging.claimKeepsakes(ledger, player, pile)
                index == null -> PathMessages.send(
                    player, "The shipwright could not tell which one that was.", PathMessages.Kind.WARN
                )
                action == ShipwrightMenu.Action.SALVAGE_DISMISS -> {
                    val name = pile.keepsakes.getOrNull(index)?.hoverName?.string
                    ledger.dismissKeepsake(player.uuid, pile, index)
                    PathMessages.send(
                        player,
                        if (name != null) "$name thrown back into the sea."
                        else "There was nothing left to throw away.",
                        if (name != null) PathMessages.Kind.WARN else PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS
                    )
                }
                else -> Salvaging.claimKeepsake(ledger, player, pile, index)
            }
            return
        }
        val cargoSide = tab == "cargo"

        if (action == ShipwrightMenu.Action.SALVAGE_DISMISS_ALL) {
            val kinds = ledger.dismissTab(player.uuid, pile, cargoSide)
            PathMessages.send(
                player,
                if (kinds > 0) "$kinds kinds thrown back into the sea."
                else "There was nothing left on that list.",
                if (kinds > 0) PathMessages.Kind.WARN else PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS
            )
            return
        }

        if (action == ShipwrightMenu.Action.CLAIM_ALL) {
            Salvaging.claimAll(ledger, player, pile, cargoSide, useShulkers)
            return
        }

        // Both remaining actions name ONE row, so a row that cannot be resolved is a refusal rather than a
        // shrug. Doing nothing quietly is how a broken Dismiss looks exactly like a working one.
        val item = argument2.takeIf { it.isNotEmpty() }
            ?.let { BuiltInRegistries.ITEM.getOptional(Identifier.parse(it)).orElse(null) }
        if (item == null) {
            PathMessages.send(
                player, "The shipwright could not tell which row that was.", PathMessages.Kind.WARN
            )
            return
        }

        if (action == ShipwrightMenu.Action.SALVAGE_DISMISS) {
            val had = pile.tab(cargoSide)[item] ?: 0
            ledger.dismissSalvage(player.uuid, pile, cargoSide, item)
            val name = ItemStack(item).hoverName.string
            PathMessages.send(
                player,
                if (had > 0) "$had $name thrown back into the sea."
                else "There was none of that left to throw away.",
                if (had > 0) PathMessages.Kind.WARN else PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_CLAIMS
            )
        } else {
            Salvaging.claimOne(ledger, player, pile, cargoSide, item, useShulkers)
        }
    }

    private fun yardAction(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        action: ShipwrightMenu.Action,
        slug: String,
        argument: String
    ) {
        if (!EurekaConfig.SERVER.shipwrightRepair) {
            PathMessages.send(player, "This shipwright takes no repair work.", PathMessages.Kind.WARN)
            return
        }

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
            PathMessages.send(player, "The shipwright has all it needs.", PathMessages.Kind.GOOD, PathMessages.Topic.REPAIR_PROGRESS)
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
            PathMessages.send(player, "Nothing on you that it needs.", PathMessages.Kind.WARN, PathMessages.Topic.REPAIR_PROGRESS)
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
            PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_PLANS
        )
        // Deliberately NOT openShelf: an offering is a gesture, not a visit. The chat line and the conduit
        // chime already say it worked, and the screen popping over the player's view every time they feed
        // the shipwright a heart read as a misclick.
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
