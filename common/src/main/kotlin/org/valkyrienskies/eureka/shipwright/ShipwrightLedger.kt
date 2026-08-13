package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
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
     * [cost] is fixed when the page is filed; [delivered] is the only mutable part. No amount of paying can
     * change what is being paid for, which is what makes an instalment safe to interrupt.
     */
    class Plans(
        val shipName: String,
        /** The template the page named. Shared with every copy of that blueprint. */
        val template: String,
        val cost: Map<Item, Int>,
        val delivered: MutableMap<Item, Int> = HashMap()
    ) {
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

    /** One captain's shelf. */
    class Library(var slots: Int) {
        val plans = LinkedHashMap<String, Plans>()
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
    fun spendMaterials(plans: Plans) {
        plans.delivered.clear()
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
                row.put(COST_KEY, writeTally(plans.cost))
                row.put(DELIVERED_KEY, writeTally(plans.delivered))
                shelf.add(row)
            }
            entry.put(PLANS_KEY, shelf)
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
        private const val SHIP_NAME_KEY = "ship_name"
        private const val TEMPLATE_KEY = "template"
        private const val COST_KEY = "cost"
        private const val DELIVERED_KEY = "delivered"
        private const val ITEM_KEY = "item"
        private const val COUNT_KEY = "count"

        private val TYPE: SavedDataType<ShipwrightLedger> = SavedDataType(
            SAVED_DATA_ID,
            { ShipwrightLedger() },
            CompoundTag.CODEC.xmap({ load(it) }, { it.saveToTag(CompoundTag()) }),
            DataFixTypes.LEVEL
        )

        /** The world's one library, on the overworld's storage whichever level asks. */
        fun get(server: MinecraftServer): ShipwrightLedger =
            server.overworld().dataStorage.computeIfAbsent(TYPE)

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

                    val plans = Plans(shipName, template, readTally(row, COST_KEY))
                    plans.delivered.putAll(readTally(row, DELIVERED_KEY))
                    library.plans[shipName] = plans
                }
                ledger.libraries[owner] = library
            }
            return ledger
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
