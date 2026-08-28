package org.valkyrienskies.eureka

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.imifou.jsonschema.module.addon.annotation.JsonSchema

/**
 * Everything a pirate ship's containers can hold, and how the crewman's balloon lottery weighs its
 * colours -- the whole loot economy in one user-editable file, `config/vs_eureka_armada_loottable.json`.
 *
 * ## The shape of a roll
 * A container gets `rollsMin..rollsMax` rolls. Each roll is ONE weighted draw from the merged pool of
 * every table that container ENABLES: an entry's `weight` is its share of the pool (unweighted entries
 * count as [DEFAULT_WEIGHT]), so the listed rarity ladder -- cannons 3 up to hearts 40 -- is exactly the
 * relative story, whatever it happens to sum to. A `table`-type entry hands its draw into another table:
 * that is how the chest pool reaches the vanilla-goods table at weight 50 and the weapons rack at 12
 * without those tables being enabled on the container at all.
 *
 * ## Editable the way the assembler whitelist is
 * Every table is a plain list the user can extend, trim or re-weigh. An entry without a weight is as
 * likely as every other unweighted entry -- add thirty more foods and each one simply gets rarer, which
 * is the intended arithmetic. The four entry types beyond a plain `item`:
 *  - `pool`   -- one uniform pick from `items` (the seventeen balloons; the nine helm woods)
 *  - `table`  -- delegate the draw to another named table
 *  - `cannonball` -- the two-stage ammunition roll: weighted metal, then an `effectChance` shot at a
 *    weighted effect (explosive / incendiary / armor_piercing)
 *  - `blueprint`  -- blank drafting pages, with a `specialChance` that the roll is instead ONE
 *    pre-drafted ship from `blueprintTemplatePool`
 * Weapons-rack entries may carry `enchantRandomly` (vanilla's own loot-enchant routine, end-city style,
 * `treasureAllowed` opening the treasure-and-curse list) or a fixed `enchantments` list (the Lure rod).
 */
object EurekaLootConfig {

    /** The live singleton, populated by [EurekaLootLoader] before item registration. */
    @JvmField
    val LOOT = Loot()

    /** An entry with no weight of its own draws at this weight. */
    const val DEFAULT_WEIGHT = 10.0

    class Loot {
        @JsonSchema(
            description = "Per container type: how many rolls it gets, and which tables feed its pool. " +
                "single_chest / double_chest / barrel are the three kinds a hull can carry; a double " +
                "chest rolls ONCE as one big container, not twice as two small ones. Note the chest " +
                "defaults reach the common and weapons tables THROUGH the eureka table's own sub-table " +
                "entries (weights 50 and 12) -- enabling a table here adds its entries to the pool " +
                "directly, on top of any sub-table route."
        )
        var containers: LinkedHashMap<String, ContainerRules> = linkedMapOf(
            "single_chest" to ContainerRules(5, 16, linkedMapOf(
                "eureka" to true, "common" to false, "weapons" to false, "food" to false
            )),
            "double_chest" to ContainerRules(9, 28, linkedMapOf(
                "eureka" to true, "common" to false, "weapons" to false, "food" to false
            )),
            "barrel" to ContainerRules(8, 20, linkedMapOf(
                "eureka" to false, "common" to false, "weapons" to false, "food" to true
            ))
        )

        @JsonSchema(
            description = "The tables themselves. Add, remove or re-weigh entries freely; new tables " +
                "become real the moment a container (or a table-type entry) names them."
        )
        var tables: LinkedHashMap<String, MutableList<LootEntry>> = linkedMapOf(
            "eureka" to eurekaTable(),
            "common" to commonTable(),
            "food" to foodTable(),
            "weapons" to weaponsTable()
        )

        @JsonSchema(
            description = "The crewman's balloon trade lottery, moved here from the main config: the " +
                "relative chance of the plain balloon, each natural colour, each common dye, and each " +
                "rare dye. At 100/12/5/1 plain comes up about half the time and pink about one time in " +
                "two hundred."
        )
        var balloonTrade: BalloonTrade = BalloonTrade()

        @JsonSchema(
            description = "Where a SPECIAL blueprint roll draws its ship from -- template names under " +
                "data/vs_eureka/structure/, weighted like pirateHulls (name*weight, bare name = 1)."
        )
        var blueprintTemplatePool: List<String> = listOf("pirate/large1")

        @JsonSchema(
            description = "The display name a special pre-drafted blueprint carries, so the find reads " +
                "as the prize it is."
        )
        var specialBlueprintName: String = "Captain's Commission"
    }

    class ContainerRules() {
        @JsonSchema(description = "Fewest rolls this container gets.")
        var rollsMin = 3

        @JsonSchema(description = "Most rolls this container gets.")
        var rollsMax = 5

        @JsonSchema(description = "Table name -> whether its entries join this container's pool.")
        var tables: LinkedHashMap<String, Boolean> = linkedMapOf()

        constructor(min: Int, max: Int, enabled: LinkedHashMap<String, Boolean>) : this() {
            rollsMin = min
            rollsMax = max
            tables = enabled
        }
    }

    /**
     * One line of a table. Only the fields an entry actually uses are written to the file -- a plain
     * item at default weight is just `{"item": "...", "countMin": .., "countMax": ..}`.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    class LootEntry() {
        @JsonSchema(description = "item (default) | pool | table | cannonball | blueprint.")
        var type: String = "item"

        @JsonSchema(description = "The item id, for type item.")
        var item: String? = null

        @JsonSchema(description = "The uniform candidate list, for type pool.")
        var items: List<String>? = null

        @JsonSchema(description = "The table the draw delegates into, for type table.")
        var table: String? = null

        @JsonSchema(description = "This entry's share of the pool. Absent = 10.")
        var weight: Double = DEFAULT_WEIGHT

        @JsonSchema(description = "Smallest stack the roll yields.")
        var countMin = 1

        @JsonSchema(description = "Largest stack the roll yields.")
        var countMax = 1

        // -- cannonball --
        @JsonSchema(description = "cannonball only: metal -> weight (copper/iron/steel/gold/netherite).")
        var metalWeights: LinkedHashMap<String, Double>? = null

        @JsonSchema(description = "cannonball only: chance (0-1) the rolled shot carries an effect at all.")
        var effectChance: Double = 0.0

        @JsonSchema(description = "cannonball only: effect -> weight (explosive/incendiary/armor_piercing).")
        var effectWeights: LinkedHashMap<String, Double>? = null

        // -- blueprint --
        @JsonSchema(description = "blueprint only: chance (0-1) this roll is ONE special pre-drafted ship.")
        var specialChance: Double = 0.0

        // -- enchanting --
        @JsonSchema(description = "Run vanilla's random loot enchant over the item (books become enchanted books).")
        var enchantRandomly: Boolean = false

        @JsonSchema(description = "Lowest enchanting power for enchantRandomly.")
        var enchantLevelMin = 20

        @JsonSchema(description = "Highest enchanting power for enchantRandomly.")
        var enchantLevelMax = 30

        @JsonSchema(description = "enchantRandomly may draw treasure enchantments and curses.")
        var treasureAllowed: Boolean = false

        @JsonSchema(description = "Fixed enchantments applied outright, e.g. the Lure III rod.")
        var enchantments: List<EnchantSpec>? = null

        constructor(build: LootEntry.() -> Unit) : this() {
            build(this)
        }
    }

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    class EnchantSpec() {
        var id: String = ""
        var level: Int = 1

        constructor(id: String, level: Int) : this() {
            this.id = id
            this.level = level
        }
    }

    class BalloonTrade {
        var plain = 100
        var natural = 12
        var dyed = 5
        var rare = 3
    }

    // region default tables

    private fun entry(build: LootEntry.() -> Unit) = LootEntry(build)

    private fun item(id: String, weight: Double? = null, min: Int = 1, max: Int = 1) = entry {
        item = id
        weight?.let { this.weight = it }
        countMin = min
        countMax = max
    }

    private fun eurekaTable(): MutableList<LootEntry> = mutableListOf(
        item("vs_eureka:cannon", weight = 2.0),
        entry {
            type = "pool"; weight = 5.0; countMin = 3; countMax = 18
            items = listOf(
                "vs_eureka:balloon",
                "vs_eureka:white_balloon", "vs_eureka:light_gray_balloon", "vs_eureka:gray_balloon",
                "vs_eureka:black_balloon", "vs_eureka:brown_balloon", "vs_eureka:red_balloon",
                "vs_eureka:orange_balloon", "vs_eureka:yellow_balloon", "vs_eureka:lime_balloon",
                "vs_eureka:green_balloon", "vs_eureka:light_blue_balloon", "vs_eureka:cyan_balloon",
                "vs_eureka:blue_balloon", "vs_eureka:purple_balloon", "vs_eureka:magenta_balloon",
                "vs_eureka:pink_balloon"
            )
        },
        item("vs_eureka:ship_bottle", weight = 5.0, min = 1, max = 4),
        entry {
            type = "pool"; weight = 10.0
            items = listOf(
                "vs_eureka:oak_ship_helm", "vs_eureka:spruce_ship_helm", "vs_eureka:birch_ship_helm",
                "vs_eureka:pale_oak_ship_helm", "vs_eureka:jungle_ship_helm", "vs_eureka:acacia_ship_helm",
                "vs_eureka:dark_oak_ship_helm", "vs_eureka:crimson_ship_helm", "vs_eureka:warped_ship_helm"
            )
        },
        entry {
            type = "cannonball"; weight = 17.5; countMin = 1; countMax = 18
            metalWeights = linkedMapOf(
                "copper" to 40.0, "iron" to 30.0, "steel" to 15.0, "gold" to 10.0, "netherite" to 5.0
            )
            effectChance = 0.15
            effectWeights = linkedMapOf("explosive" to 5.0, "incendiary" to 3.0, "armor_piercing" to 2.0)
        },
        item("vs_eureka:engine", weight = 12.5, min = 1, max = 5),
        entry { type = "blueprint"; weight = 15.0; countMin = 1; countMax = 8; specialChance = 0.2 },
        entry {
            type = "pool"; weight = 30.0; countMin = 1; countMax = 28
            items = listOf("vs_eureka:floater", "vs_eureka:ballast")
        },
        item("minecraft:heart_of_the_sea", weight = 10.0, min = 1, max = 3),
        entry { type = "table"; table = "common"; weight = 70.0 },
        entry { type = "table"; table = "weapons"; weight = 8.0 }
    )

    private fun commonTable(): MutableList<LootEntry> {
        val list = mutableListOf<LootEntry>()
        for (wood in listOf(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"
        )) {
            list.add(item("minecraft:${wood}_planks", min = 4, max = 32))
        }
        for (log in listOf(
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
            "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"
        )) {
            list.add(item("minecraft:$log", min = 2, max = 16))
        }
        list.add(item("minecraft:torch", min = 4, max = 24))
        list.add(item("minecraft:iron_ingot", min = 1, max = 8))
        list.add(item("minecraft:copper_ingot", min = 1, max = 8))
        list.add(item("minecraft:gold_ingot", min = 1, max = 8))
        list.add(item("minecraft:diamond", min = 1, max = 4))
        list.add(item("minecraft:emerald", min = 1, max = 4))
        list.add(item("minecraft:amethyst_shard", min = 1, max = 4))
        list.add(item("minecraft:gunpowder", min = 1, max = 48))
        list.add(item("minecraft:coal", min = 1, max = 52))
        list.add(item("minecraft:bucket", min = 1, max = 4))
        list.add(item("minecraft:string", min = 1, max = 16))
        return list
    }

    private fun foodTable(): MutableList<LootEntry> = mutableListOf(
        item("minecraft:milk_bucket"),
        item("minecraft:wheat", min = 1, max = 12),
        item("minecraft:bread", min = 1, max = 6),
        item("minecraft:cod", min = 1, max = 8),
        item("minecraft:cooked_cod", min = 1, max = 8),
        item("minecraft:salmon", min = 1, max = 8),
        item("minecraft:cooked_salmon", min = 1, max = 8),
        item("minecraft:beef", min = 1, max = 6),
        item("minecraft:cooked_beef", min = 1, max = 6),
        item("minecraft:porkchop", min = 1, max = 6),
        item("minecraft:cooked_porkchop", min = 1, max = 6),
        item("minecraft:mutton", min = 1, max = 6),
        item("minecraft:cooked_mutton", min = 1, max = 6),
        item("minecraft:chicken", min = 1, max = 6),
        item("minecraft:cooked_chicken", min = 1, max = 6),
        item("minecraft:apple", min = 1, max = 8),
        item("minecraft:carrot", min = 1, max = 8),
        item("minecraft:potato", min = 1, max = 8),
        item("minecraft:baked_potato", min = 1, max = 8),
        item("minecraft:beetroot", min = 1, max = 6),
        item("minecraft:melon_slice", min = 1, max = 8),
        item("minecraft:sweet_berries", min = 1, max = 8),
        item("minecraft:dried_kelp", min = 1, max = 16),
        item("minecraft:cookie", min = 1, max = 8),
        item("minecraft:pumpkin", min = 1, max = 3),
        item("minecraft:pumpkin_pie", min = 1, max = 4),
        item("minecraft:mushroom_stew"),
        item("minecraft:beetroot_soup"),
        item("minecraft:rabbit_stew"),
        item("minecraft:bowl", min = 1, max = 4),
        item("minecraft:glass_bottle", min = 1, max = 4),
        item("minecraft:honey_bottle"),
        item("minecraft:egg", min = 1, max = 4),
        item("minecraft:wheat_seeds", min = 1, max = 8),
        item("minecraft:pumpkin_seeds", min = 1, max = 8),
        item("minecraft:melon_seeds", min = 1, max = 8),
        item("minecraft:beetroot_seeds", min = 1, max = 8),
        // The three special finds, at the user's own odds.
        item("minecraft:enchanted_golden_apple", weight = 3.0),
        item("minecraft:golden_apple", weight = 10.0, min = 1, max = 2),
        item("minecraft:golden_carrot", weight = 15.0, min = 1, max = 4)
    )

    private fun weaponsTable(): MutableList<LootEntry> {
        fun armed(id: String, weight: Double, levelMin: Int, levelMax: Int, treasure: Boolean) = entry {
            item = id; this.weight = weight
            enchantRandomly = true
            enchantLevelMin = levelMin
            enchantLevelMax = levelMax
            treasureAllowed = treasure
        }
        return mutableListOf(
            // End-city flavour: diamond, heavily enchanted, treasure allowed (curses included).
            armed("minecraft:diamond_sword", 10.0, 20, 39, true),
            armed("minecraft:diamond_axe", 8.0, 20, 39, true),
            armed("minecraft:diamond_pickaxe", 8.0, 20, 39, true),
            armed("minecraft:diamond_shovel", 6.0, 20, 39, true),
            armed("minecraft:diamond_helmet", 6.0, 20, 39, true),
            armed("minecraft:diamond_chestplate", 6.0, 20, 39, true),
            armed("minecraft:diamond_leggings", 6.0, 20, 39, true),
            armed("minecraft:diamond_boots", 6.0, 20, 39, true),
            // The working tier, enchanted lighter.
            armed("minecraft:iron_sword", 12.0, 15, 25, false),
            armed("minecraft:iron_helmet", 10.0, 15, 25, false),
            armed("minecraft:iron_chestplate", 10.0, 15, 25, false),
            armed("minecraft:iron_leggings", 10.0, 15, 25, false),
            armed("minecraft:iron_boots", 10.0, 15, 25, false),
            armed("minecraft:bow", 10.0, 20, 30, true),
            armed("minecraft:crossbow", 10.0, 20, 30, true),
            armed("minecraft:trident", 3.0, 20, 39, true),
            // Ancient-city flavour: the book (becomes an enchanted book, treasure list open).
            armed("minecraft:book", 12.0, 20, 30, true),
            // The enchanted fishing rod, Lure III fixed.
            entry {
                item = "minecraft:fishing_rod"; weight = 6.0
                enchantments = listOf(EnchantSpec("minecraft:lure", 3))
            },
            item("minecraft:diamond_horse_armor", weight = 4.0)
        )
    }

    // endregion
}
