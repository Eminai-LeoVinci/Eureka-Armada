package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import com.mojang.serialization.DynamicOps
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.tags.TagKey
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaMod

/**
 * Every set of plans a captain owns, and what has been paid toward each.
 *
 * ## The library belongs to the player, not to the bench
 * A blueprint filed at one bench is on file at every bench in the world. A captain who took a pillager's ship
 * off them in the northern sea should be able to have it rebuilt at a harbor in the south -- being made to
 * sail back to one specific workshop is bookkeeping, not gameplay, and it would quietly punish the exact
 * behaviour the feature is meant to reward.
 *
 * So this is keyed by player, then by **ship name**. The name is the identity: filing plans for a ship you
 * already have plans for is refused rather than silently making a second copy, which is also why entries can
 * be deleted but never renamed. A renameable key is a key that can collide with an existing one, and the only
 * answers to that are to refuse the rename or to overwrite somebody's ship.
 *
 * ## Plans are permanent; materials are not
 * Filing a blueprint is a one-way purchase: the page is consumed and the plans stay forever. **Building** from
 * them spends only the materials, so a hull can be built again and again by bringing the bill again. That is
 * the whole shape of the feature -- a survival-legal schematic system rather than a one-shot order desk.
 *
 * ## Slots
 * Three to begin with, up to [EurekaConfig.Server.shipwrightSlotsMax] bought one at a time with Hearts of the
 * Sea -- deliberately the same currency and the same ceiling as crew berths, because it is the same kind of
 * decision and a player who has learned one has learned the other.
 */
class ShipwrightLedger : SavedData() {

    /**
     * One set of plans, and the pile of materials sitting against it.
     *
     * [baseCost] is fixed when the page is filed and never changes again. What a captain actually owes is
     * [cost] -- the base seen through whatever [alteration] they have set -- and [delivered] is the only
     * other mutable part. No amount of PAYING can change what is being paid for, which is what makes an
     * instalment safe to interrupt; changing the plans themselves is a deliberate act with its own door.
     */
    class Plans(
        val shipName: String,
        /** The template the page named. Shared with every copy of that blueprint. */
        val template: String,
        /** The census as filed. Frozen forever: the alteration is a lens over it, never an edit to it. */
        val baseCost: Map<Item, Int>,
        val delivered: MutableMap<Item, Int> = HashMap(),
        /** What the captain has changed about these plans. [Alteration.NONE] until they change something. */
        var alteration: Alteration = Alteration.NONE,
        /**
         * ANY rows only: what was actually handed in, in the order it arrived, keyed by the row it paid into.
         *
         * Order is the whole point. "Any slab" has to be built out of a mixture, and the captain's delivery
         * order is what decides which slab lands where -- so this is a LIST per row and a LinkedHashMap of
         * them, and every step of the NBT round-trip has to preserve that or a hull built after a restart
         * comes out shuffled.
         */
        val deliveries: MutableMap<Item, MutableList<ItemRun>> = LinkedHashMap()
    ) {
        /** One hand-in: this many of this item, at this point in the queue. */
        class ItemRun(val item: Item, var count: Int)

        // The bill is read several times per progress call and once per screen row, and applying an
        // alteration walks the whole census -- so the answer is kept until the alteration itself changes.
        private var costCache: Map<Item, Int>? = null
        private var costStamp: Alteration? = null

        /** What is owed, with the captain's changes applied. Everything below reads this, never [baseCost]. */
        val cost: Map<Item, Int>
            get() {
                val stamp = alteration
                val cached = costCache
                if (cached != null && costStamp === stamp) return cached
                val fresh = stamp.applyTo(baseCost)
                costCache = fresh
                costStamp = stamp
                return fresh
            }
        /** How much of [item] is still owed. Never negative -- overpaying is not a debt. */
        fun outstanding(item: Item): Int = maxOf(0, (cost[item] ?: 0) - (delivered[item] ?: 0))

        /** Everything still owed, in the blueprint's own order so the list does not reshuffle as it is paid. */
        fun outstanding(): Map<Item, Int> = cost.keys.associateWith { outstanding(it) }.filterValues { it > 0 }

        val ready: Boolean get() = outstanding().isEmpty()

        /** 0..1 by total item count rather than by kind -- one plank short is not "half done". */
        val progress: Float
            get() {
                val total = cost.values.sum()
                if (total <= 0) return 1.0f
                return cost.keys.sumOf { minOf(cost[it] ?: 0, delivered[it] ?: 0) }.toFloat() / total.toFloat()
            }
    }

    /**
     * A repair in progress on one assembled ship.
     *
     * Deliberately a **separate pot** from that ship's build bill. They answer different questions -- "what
     * does this hull cost to make" against "what does this hull need to be whole again" -- and letting them
     * draw on each other would mean materials brought for a repair silently paying down a build, or worse.
     *
     * Keyed by the ship's slug rather than the plans' name, because the thing being repaired is a particular
     * hull. Two ships built from one page are two repairs.
     *
     * [plansName] is the dropdown's answer, kept here so a choice survives closing the screen.
     */
    class RepairBill(
        val shipSlug: String,
        val plansName: String,
        /** What the assessment asked for, before this job's own swaps. */
        val baseCost: Map<Item, Int>,
        val delivered: MutableMap<Item, Int> = HashMap(),
        /**
         * Swaps set for THIS JOB, which is not the same thing as swaps set on the plans. The plans describe
         * the design; a repair bill describes one afternoon's work on one hull, and a captain who is out of
         * birch today has not changed their mind about what the ship is.
         */
        var alteration: Alteration = Alteration.NONE,
        val deliveries: MutableMap<Item, MutableList<Plans.ItemRun>> = LinkedHashMap()
    ) {
        private var costCache: Map<Item, Int>? = null
        private var costStamp: Alteration? = null

        val cost: Map<Item, Int>
            get() {
                val stamp = alteration
                val cached = costCache
                if (cached != null && costStamp === stamp) return cached
                val fresh = stamp.applyTo(baseCost)
                costCache = fresh
                costStamp = stamp
                return fresh
            }
        fun outstanding(item: Item): Int = maxOf(0, (cost[item] ?: 0) - (delivered[item] ?: 0))
        fun outstanding(): Map<Item, Int> = cost.keys.associateWith { outstanding(it) }.filterValues { it > 0 }
        val ready: Boolean get() = outstanding().isEmpty()

        val progress: Float
            get() {
                val total = cost.values.sum()
                if (total <= 0) return 1.0f
                return cost.keys.sumOf { minOf(cost[it] ?: 0, delivered[it] ?: 0) }.toFloat() / total.toFloat()
            }
    }

    /**
     * A ship that has been broken up, waiting to be carried away.
     *
     * Durable, unlike an [Alteration]: this is not a preference that can be re-expressed, it is the entire
     * material substance of a ship the captain no longer has. Losing it on relog would be losing the ship.
     *
     * Hull and cargo are kept apart because they answer different questions -- "what was this built from"
     * and "what was in it" -- and a captain rebuilding wants the first without wading through the second.
     */
    class Salvage(val shipName: String) {
        val hull = LinkedHashMap<Item, Int>()
        val cargo = LinkedHashMap<Item, Int>()

        /** Stacks a count cannot describe. Kept verbatim; see [ShipSalvage]. */
        val keepsakes = ArrayList<ItemStack>()

        val empty: Boolean get() = hull.isEmpty() && cargo.isEmpty() && keepsakes.isEmpty()

        fun tab(cargoSide: Boolean): MutableMap<Item, Int> = if (cargoSide) cargo else hull
    }

    /** One captain's shelf, whatever they have in for repair, and whatever they have broken up. */
    class Library(var slots: Int) {
        val plans = LinkedHashMap<String, Plans>()
        val repairs = LinkedHashMap<String, RepairBill>()
        val salvage = LinkedHashMap<String, Salvage>()
    }

    fun repairFor(player: UUID, shipSlug: String): RepairBill? = libraries[player]?.repairs?.get(shipSlug)

    fun salvageFor(player: UUID): Collection<Salvage> = libraries[player]?.salvage?.values ?: emptyList()

    fun salvagePile(player: UUID, shipName: String): Salvage? = libraries[player]?.salvage?.get(shipName)

    /**
     * File a survey as a claimable pile.
     *
     * Two ships of the same name MERGE rather than replace. Replacing would delete the first pile outright,
     * and a name collision is not a reason to destroy a ship someone has already paid for with a ship.
     */
    fun recordSalvage(player: UUID, survey: ShipSalvage.Survey) {
        val library = libraryOf(player)
        val pile = library.salvage.getOrPut(survey.shipName) { Salvage(survey.shipName) }
        for ((item, count) in survey.hull) pile.hull[item] = (pile.hull[item] ?: 0) + count
        for ((item, count) in survey.cargo) pile.cargo[item] = (pile.cargo[item] ?: 0) + count
        pile.keepsakes.addAll(survey.keepsakes)
        setDirty()
    }

    /**
     * Take [count] of [item] off a pile, and answer how many actually came off.
     *
     * The caller decides what to do with them; this only ever removes what it says it removed, so a claim
     * that cannot be carried can put the difference straight back by not asking for it in the first place.
     */
    fun takeSalvage(player: UUID, pile: Salvage, cargoSide: Boolean, item: Item, count: Int): Int {
        val tab = pile.tab(cargoSide)
        val had = tab[item] ?: 0
        val taken = minOf(had, count)
        if (taken <= 0) return 0
        if (taken >= had) tab.remove(item) else tab[item] = had - taken
        retire(player, pile)
        setDirty()
        return taken
    }

    /** Take one keepsake off the pile, or null if that index has already gone. */
    fun takeKeepsake(player: UUID, pile: Salvage, index: Int): ItemStack? {
        val stack = pile.keepsakes.getOrNull(index) ?: return null
        pile.keepsakes.removeAt(index)
        retire(player, pile)
        setDirty()
        return stack
    }

    /** Throw ONE keepsake away. Same two-press rule as a counted row, and just as final. */
    fun dismissKeepsake(player: UUID, pile: Salvage, index: Int) {
        if (index !in pile.keepsakes.indices) return
        pile.keepsakes.removeAt(index)
        retire(player, pile)
        setDirty()
    }

    /** Throw a row away outright. The captain asked twice; nothing is handed back. */
    fun dismissSalvage(player: UUID, pile: Salvage, cargoSide: Boolean, item: Item) {
        pile.tab(cargoSide).remove(item)
        retire(player, pile)
        setDirty()
    }

    /**
     * Throw a whole tab away, and answer how many kinds went.
     *
     * The count is returned so the message can say what was actually discarded -- "the tab was already
     * empty" and "four thousand planks are gone" must not read the same.
     */
    fun dismissTab(player: UUID, pile: Salvage, cargoSide: Boolean): Int {
        val tab = pile.tab(cargoSide)
        val kinds = tab.size
        tab.clear()
        retire(player, pile)
        setDirty()
        return kinds
    }

    /** The same for the keepsakes, which are a list rather than a tally. */
    fun dismissAllKeepsakes(player: UUID, pile: Salvage): Int {
        val kinds = pile.keepsakes.size
        pile.keepsakes.clear()
        retire(player, pile)
        setDirty()
        return kinds
    }

    /** An emptied pile stops being a thing you can open. */
    private fun retire(player: UUID, pile: Salvage) {
        if (pile.empty) libraries[player]?.salvage?.remove(pile.shipName)
    }

    /**
     * Start or restate a repair on [shipSlug] against [plansName], costing [cost].
     *
     * Materials already handed over survive a re-quote **against the same plans** -- a hull that took more
     * damage while its captain was away fetching timber should not lose the timber they already brought. A
     * different set of plans is a different job and starts clean.
     */
    fun quoteRepair(player: UUID, shipSlug: String, plansName: String, cost: Map<Item, Int>): RepairBill {
        val library = libraryOf(player)
        val existing = library.repairs[shipSlug]
        val bill = RepairBill(shipSlug, plansName, cost)
        if (existing != null && existing.plansName == plansName) {
            for ((item, count) in existing.delivered) {
                // Never carry over more than the new quote asks for; the surplus would read as a credit that
                // silently vanishes on the next re-quote.
                bill.delivered[item] = minOf(count, cost[item] ?: 0)
            }
        }
        library.repairs[shipSlug] = bill
        setDirty()
        return bill
    }

    fun deliverRepair(bill: RepairBill, item: Item, count: Int): Int {
        val taken = minOf(count, bill.outstanding(item))
        if (taken <= 0) return 0
        bill.delivered[item] = (bill.delivered[item] ?: 0) + taken
        setDirty()
        return taken
    }

    /**
     * Materials actually built into the hull come OUT of the pot. What was handed over and not spent stays
     * against the bill -- a partial repair that ran dry keeps the surplus for the next pass, and the next
     * re-quote carries it forward exactly as an interrupted instalment would be.
     */
    fun spendRepair(bill: RepairBill, consumed: Map<Item, Int>) {
        if (consumed.isEmpty()) return
        for ((item, count) in consumed) {
            val left = (bill.delivered[item] ?: 0) - count
            if (left > 0) bill.delivered[item] = left else bill.delivered.remove(item)
        }
        setDirty()
    }

    /** Called once a repair has been written into the hull. */
    fun closeRepair(player: UUID, shipSlug: String) {
        if (libraryOf(player).repairs.remove(shipSlug) != null) setDirty()
    }

    private val libraries = HashMap<UUID, Library>()

    fun libraryOf(player: UUID): Library =
        libraries.getOrPut(player) { Library(EurekaConfig.SERVER.shipwrightSlotsStart) }

    fun plansFor(player: UUID, shipName: String): Plans? = libraries[player]?.plans?.get(shipName)

    fun allPlans(player: UUID): List<Plans> = libraryOf(player).plans.values.toList()

    /** Why filing a blueprint was refused, or null if it was accepted. */
    fun file(player: UUID, plans: Plans): String? {
        val library = libraryOf(player)
        if (library.plans.containsKey(plans.shipName)) {
            return "You already hold plans for '${plans.shipName}'."
        }
        if (library.plans.size >= library.slots) {
            return "Your shelf is full at ${library.slots} sets of plans -- delete one, " +
                "or offer a Heart of the Sea for another."
        }
        library.plans[plans.shipName] = plans
        setDirty()
        return null
    }

    fun delete(player: UUID, shipName: String): Boolean {
        val removed = libraryOf(player).plans.remove(shipName) != null
        if (removed) setDirty()
        return removed
    }

    /**
     * Hand [count] of [item] toward [plans], returning how many were actually taken.
     *
     * Never takes more than is owed. A player emptying an inventory into a nearly-finished hull keeps the
     * remainder, which is the difference between paying a bill and being robbed by one.
     */
    fun deliver(plans: Plans, item: Item, count: Int): Int {
        val taken = minOf(count, plans.outstanding(item))
        if (taken <= 0) return 0
        plans.delivered[item] = (plans.delivered[item] ?: 0) + taken
        setDirty()
        return taken
    }

    /** Called once a built ship has been handed over. The plans stay; only the materials are spent. */
    /**
     * The pile is spent and the plans go back to what the page says.
     *
     * Both doors out of a set of plans come through here -- Build, and Bottle on the branch where the
     * bottle is actually handed over -- which is exactly where an alteration should end. A captain who
     * turned up without a bottle keeps their work, because that path refuses long before this line.
     */
    fun spendMaterials(plans: Plans) {
        plans.delivered.clear()
        plans.deliveries.clear()
        plans.alteration = Alteration.NONE
        setDirty()
    }

    /** Buy one more slot. False if already at the ceiling. */
    fun buySlot(player: UUID): Boolean {
        val library = libraryOf(player)
        if (library.slots >= EurekaConfig.SERVER.shipwrightSlotsMax) return false
        library.slots++
        setDirty()
        return true
    }

    fun saveToTag(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((owner, library) in libraries) {
            val entry = CompoundTag()
            entry.putString(OWNER_KEY, owner.toString())
            entry.putInt(SLOTS_KEY, library.slots)

            val shelf = ListTag()
            for (plans in library.plans.values) {
                val row = CompoundTag()
                row.putString(SHIP_NAME_KEY, plans.shipName)
                row.putString(TEMPLATE_KEY, plans.template)
                // The BASE, never the altered bill. Writing what is currently owed would bake the
                // alteration into the census and then apply it again on load, compounding a little more
                // every save until the ship costs nothing at all.
                row.put(BASE_COST_KEY, writeTally(plans.baseCost))
                row.put(DELIVERED_KEY, writeTally(plans.delivered))
                shelf.add(row)
            }
            entry.put(PLANS_KEY, shelf)

            val jobs = ListTag()
            for (bill in library.repairs.values) {
                val row = CompoundTag()
                row.putString(SHIP_SLUG_KEY, bill.shipSlug)
                row.putString(PLANS_NAME_KEY, bill.plansName)
                row.put(BASE_COST_KEY, writeTally(bill.baseCost))
                row.put(DELIVERED_KEY, writeTally(bill.delivered))
                jobs.add(row)
            }
            entry.put(REPAIRS_KEY, jobs)

            val piles = ListTag()
            for (pile in library.salvage.values) {
                val row = CompoundTag()
                row.putString(SHIP_NAME_KEY, pile.shipName)
                row.put(HULL_KEY, writeTally(pile.hull))
                row.put(CARGO_KEY, writeTally(pile.cargo))
                row.put(KEEPSAKES_KEY, writeStacks(pile.keepsakes))
                piles.add(row)
            }
            entry.put(SALVAGE_KEY, piles)
            list.add(entry)
        }
        tag.put(LIBRARIES_KEY, list)
        return tag
    }

    private fun writeTally(tally: Map<Item, Int>): ListTag {
        val list = ListTag()
        for ((item, count) in tally) {
            val row = CompoundTag()
            row.putString(ITEM_KEY, BuiltInRegistries.ITEM.getKey(item).toString())
            row.putInt(COUNT_KEY, count)
            list.add(row)
        }
        return list
    }

    companion object {
        private val SAVED_DATA_ID = "${EurekaMod.MOD_ID}_shipwright_library"

        private const val LIBRARIES_KEY = "libraries"
        private const val OWNER_KEY = "owner"
        private const val SLOTS_KEY = "slots"
        private const val PLANS_KEY = "plans"
        private const val REPAIRS_KEY = "repairs"
        private const val SHIP_SLUG_KEY = "ship_slug"
        private const val PLANS_NAME_KEY = "plans_name"
        private const val SHIP_NAME_KEY = "ship_name"
        private const val TEMPLATE_KEY = "template"
        private const val COST_KEY = "cost"
        private const val BASE_COST_KEY = "base_cost"
        private const val DELIVERED_KEY = "delivered"
        private const val ITEM_KEY = "item"
        private const val COUNT_KEY = "count"
        private const val SALVAGE_KEY = "salvage"
        private const val HULL_KEY = "hull"
        private const val CARGO_KEY = "cargo"
        private const val KEEPSAKES_KEY = "keepsakes"

        /** The registries, so a keepsake can be written down. Set by [get]; see [stackOps]. */
        @Volatile
        private var registries: HolderLookup.Provider? = null

        private val TYPE: SavedDataType<ShipwrightLedger> = SavedDataType(
            SAVED_DATA_ID,
            { ShipwrightLedger() },
            CompoundTag.CODEC.xmap({ load(it) }, { it.saveToTag(CompoundTag()) }),
            DataFixTypes.LEVEL
        )

        /** The world's one library, on the overworld's storage whichever level asks. */
        fun get(server: MinecraftServer): ShipwrightLedger {
            // Before computeIfAbsent, because computeIfAbsent is what runs load().
            registries = server.registryAccess()
            return server.overworld().dataStorage.computeIfAbsent(TYPE)
        }

        /** A hand-edited or truncated id drops one shelf rather than the whole book. */
        private fun parseUuid(raw: String): UUID? =
            if (raw.isEmpty()) null else try {
                UUID.fromString(raw)
            } catch (ex: IllegalArgumentException) {
                null
            }

        fun load(tag: CompoundTag): ShipwrightLedger {
            val ledger = ShipwrightLedger()
            val list = tag.getList(LIBRARIES_KEY).orElse(ListTag())
            for (element in list) {
                val entry = element as? CompoundTag ?: continue
                val owner = parseUuid(entry.getString(OWNER_KEY).orElse("")) ?: continue
                val library = Library(entry.getIntOr(SLOTS_KEY, EurekaConfig.SERVER.shipwrightSlotsStart))

                val shelf = entry.getList(PLANS_KEY).orElse(ListTag())
                for (i in 0 until shelf.size) {
                    val row = shelf.getCompound(i).orElse(null) ?: continue
                    val shipName = row.getString(SHIP_NAME_KEY).orElse("")
                    val template = row.getString(TEMPLATE_KEY).orElse("")
                    if (shipName.isEmpty() || template.isEmpty()) continue

                    // An older world wrote its census under "cost" and had no alterations, so that list
                    // IS the base cost. That fallback is the whole migration.
                    val base = readTally(row, BASE_COST_KEY).ifEmpty { readTally(row, COST_KEY) }
                    // The alteration is NOT read back, because it is never written. A captain's changes
                    // to a set of plans last as long as the session they were made in: long enough to go and
                    // fetch materials, not long enough to come back a week later to a ship that is quietly
                    // missing its lanterns because of something you did on a Tuesday.
                    val plans = Plans(shipName, template, base)
                    plans.delivered.putAll(readTally(row, DELIVERED_KEY))
                    library.plans[shipName] = plans
                }

                val jobs = entry.getList(REPAIRS_KEY).orElse(ListTag())
                for (i in 0 until jobs.size) {
                    val row = jobs.getCompound(i).orElse(null) ?: continue
                    val slug = row.getString(SHIP_SLUG_KEY).orElse("")
                    val plansName = row.getString(PLANS_NAME_KEY).orElse("")
                    if (slug.isEmpty() || plansName.isEmpty()) continue

                    val billBase = readTally(row, BASE_COST_KEY).ifEmpty { readTally(row, COST_KEY) }
                    val bill = RepairBill(slug, plansName, billBase)
                    bill.delivered.putAll(readTally(row, DELIVERED_KEY))
                    library.repairs[slug] = bill
                }
                val piles = entry.getList(SALVAGE_KEY).orElse(ListTag())
                for (i in 0 until piles.size) {
                    val row = piles.getCompound(i).orElse(null) ?: continue
                    val name = row.getString(SHIP_NAME_KEY).orElse("")
                    if (name.isEmpty()) continue
                    val pile = Salvage(name)
                    pile.hull.putAll(readTally(row, HULL_KEY))
                    pile.cargo.putAll(readTally(row, CARGO_KEY))
                    pile.keepsakes.addAll(readStacks(row, KEEPSAKES_KEY))
                    // A pile that read back empty is a pile of nothing; it would open onto a blank page.
                    if (!pile.empty) library.salvage[name] = pile
                }

                ledger.libraries[owner] = library
            }
            return ledger
        }

        /**
         * Keepsakes go through [ItemStack.CODEC], which needs the registries to write an enchantment down.
         *
         * The saved-data codec has no registry access of its own, so [get] stashes the server's before it
         * asks for the ledger -- which is the call that triggers the load. Without them a keepsake would
         * silently fail to encode and an enchanted tool would be lost by the save it was meant to survive,
         * so a missing provider drops the keepsake list rather than writing a broken one.
         */
        private fun stackOps(): DynamicOps<Tag>? =
            registries?.let { RegistryOps.create(NbtOps.INSTANCE, it) }

        private fun writeStacks(stacks: List<ItemStack>): ListTag {
            val list = ListTag()
            val ops = stackOps() ?: return list
            for (stack in stacks) {
                ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent { list.add(it) }
            }
            return list
        }

        private fun readStacks(entry: CompoundTag, key: String): List<ItemStack> {
            val stacks = ArrayList<ItemStack>()
            val ops = stackOps() ?: return stacks
            val rows = entry.getList(key).orElse(ListTag())
            for (element in rows) {
                ItemStack.CODEC.parse(ops, element).result().ifPresent { stacks.add(it) }
            }
            return stacks
        }

        private fun readTally(entry: CompoundTag, key: String): Map<Item, Int> {
            val tally = LinkedHashMap<Item, Int>()
            val rows = entry.getList(key).orElse(ListTag())
            for (i in 0 until rows.size) {
                val row = rows.getCompound(i).orElse(null) ?: continue
                val id = row.getString(ITEM_KEY).orElse(null) ?: continue
                // An item from a mod that has since been removed drops out of the bill rather than blocking
                // the build forever. The alternative is a ship nobody can ever finish paying for.
                val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null) ?: continue
                tally[item] = row.getIntOr(COUNT_KEY, 0)
            }
            return tally
        }
    }
}
