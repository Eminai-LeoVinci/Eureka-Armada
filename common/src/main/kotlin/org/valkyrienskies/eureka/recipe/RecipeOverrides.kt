package org.valkyrienskies.eureka.recipe

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.valkyrienskies.mod.util.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Config-driven crafting recipe overrides.
 *
 * Reads `config/vs_eureka_armada_recipes.json` (a friendly 9-slot format) and converts each entry into a
 * standard Minecraft crafting-recipe JSON. [org.valkyrienskies.mod.mixin.feature.config_recipes.MixinRecipeManager]
 * splices those into the datapack id->JSON map before `RecipeManager.apply` parses it, REPLACING the
 * matching built-in recipe (matched by id). Re-read on every recipe (re)load, so `/reload` picks up
 * edits live.
 *
 * The name is Armada's, not `vs_eureka_recipes.json`: that one belongs to Eureka Ships, and the two mods
 * ship different recipes. Since this file is only ever written when ABSENT, a shared name would mean
 * whichever mod ran first froze its recipe set in place and the other silently inherited it. Armada keeps
 * its own; Eureka Ships' file is neither read nor touched.
 *
 * - File absent  -> the bundled defaults are written out, then loaded.
 * - File present -> parsed; malformed entries are skipped (built-in recipe left intact); a wholly
 *   unparseable file logs a warning and leaves ALL built-ins untouched (file not overwritten).
 *
 * Friendly format, one entry per recipe id:
 *   "vs_eureka:engine": {
 *     "type": "shaped" | "shapeless",   // optional, default "shaped"
 *     "slots": [ s1..s9 ],              // REQUIRED, exactly 9; slot 1=top-left, 5=centre, 9=bottom-right
 *     "result": "namespace:item",       // REQUIRED
 *     "count": 1,                        // optional, default 1
 *     "group": "optional"
 *   }
 * A slot is one of:
 *   "namespace:item"  (a bare "item" assumes minecraft:)   e.g. "minecraft:stick" / "stick"
 *   "#namespace:tag"  (any item in the tag)                e.g. "#minecraft:planks"
 *   ["item_a","item_b", ...]  (interchangeable alternatives — any one works)
 *   ""  or  null      (empty slot)
 *
 * To DISABLE a built-in recipe entirely, set its value to the string "remove" (or { "remove": true }).
 * Keys beginning with "_" are ignored (used for inline notes, since JSON has no comments).
 */
object RecipeOverrides {
    private val logger by logger()
    private val CONFIG_FILE: Path = Path.of("config", "vs_eureka_armada_recipes.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var overrideCache: Map<String, JsonObject> = LinkedHashMap()
    private var removalCache: Set<String> = LinkedHashSet()

    /** Re-read the config from disk (called once per recipe reload by the mixin). */
    @JvmStatic
    fun reload() = load()

    /** id -> standard crafting-recipe JSON (to add/replace). Valid after [reload]. */
    @JvmStatic
    fun getOverrides(): Map<String, JsonObject> = overrideCache

    /** recipe ids to remove entirely. Valid after [reload]. */
    @JvmStatic
    fun getRemovals(): Set<String> = removalCache

    @JvmStatic
    fun logInfo(msg: String) = logger.info(msg)

    @JvmStatic
    fun logError(msg: String, t: Throwable) = logger.error(msg, t)

    private fun load() {
        val overrides = LinkedHashMap<String, JsonObject>()
        val removals = LinkedHashSet<String>()
        try {
            if (!Files.exists(CONFIG_FILE)) {
                CONFIG_FILE.parent?.let { Files.createDirectories(it) }
                Files.writeString(CONFIG_FILE, gson.toJson(defaultConfig()))
                logger.info("Created default recipe config at " + CONFIG_FILE.toAbsolutePath())
            }
            val root = JsonParser.parseString(Files.readString(CONFIG_FILE)).asJsonObject
            mergeMissingDefaults(root)
            for ((id, specEl) in root.entrySet()) {
                if (id.startsWith("_")) continue // inline-note key
                if (specEl.isJsonPrimitive && specEl.asString.equals("remove", ignoreCase = true)) {
                    removals.add(id); continue
                }
                if (!specEl.isJsonObject) continue
                val spec = specEl.asJsonObject
                try {
                    // Inside the try: a malformed "remove" value (object/array/null) throws from
                    // asBoolean and must skip just this entry, not abort the whole file.
                    if (spec.get("remove")?.asBoolean == true) {
                        removals.add(id); continue
                    }
                    overrides[id] = toStandardRecipe(spec)
                } catch (e: Exception) {
                    logger.warn("Skipping malformed recipe override '" + id + "': " + e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Failed to load recipe config at " + CONFIG_FILE.toAbsolutePath() +
                    " (" + e.message + "); built-in recipes unchanged."
            )
        }
        overrideCache = overrides
        removalCache = removals
    }

    /**
     * Add any recipe the defaults know about and the file on disk does not, then write it back.
     *
     * Without this, [defaultConfig] only ever reaches a player who has never launched the mod before: the
     * file is written when ABSENT and read otherwise, so every recipe added after a player's first launch
     * was invisible to them for ever. That is not a hypothetical -- a new block would ship with a recipe
     * nobody upgrading could craft, and the only fix was "delete your recipe config", which throws away
     * every tuning decision in it.
     *
     * Only ADDS. An entry already in the file is left exactly as the player left it, including a
     * deliberately retuned one, so this can never quietly undo an edit. The one thing it cannot tell apart
     * is a recipe the player DELETED to disable it -- that comes back. Setting it to `"remove"` is the
     * documented way to switch a recipe off, and unlike deletion it survives this merge and every future one.
     *
     * `_README` is refreshed rather than merged: it is documentation, not configuration, and a stale copy of
     * it is worse than none.
     */
    private fun mergeMissingDefaults(root: JsonObject) {
        val defaults = defaultConfig()
        var added = 0
        for ((id, spec) in defaults.entrySet()) {
            if (id == "_README") continue
            if (root.has(id)) continue
            root.add(id, spec)
            added++
        }

        val readme = defaults.get("_README")
        val readmeStale = readme != null && root.get("_README") != readme
        if (added == 0 && !readmeStale) return
        if (readme != null) root.add("_README", readme)

        try {
            Files.writeString(CONFIG_FILE, gson.toJson(root))
            if (added > 0) {
                logger.info(
                    "Added " + added + " new default recipe(s) to " + CONFIG_FILE.fileName +
                        "; existing entries were left untouched. Set one to \"remove\" to disable it."
                )
            }
        } catch (e: Exception) {
            // The merged recipes are already in `root`, so this run still behaves correctly -- only the
            // write-back failed, and it will be retried on the next reload.
            logger.warn("Could not write merged recipe config: " + e.message)
        }
    }

    // ---- friendly 9-slot spec -> standard crafting-recipe JSON ----

    private fun toStandardRecipe(spec: JsonObject): JsonObject {
        val type = spec.get("type")?.asString?.lowercase() ?: "shaped"
        val resultId = normalizeId(
            spec.get("result")?.asString ?: throw IllegalArgumentException("missing 'result'")
        )
        val count = spec.get("count")?.asInt ?: 1
        val group = spec.get("group")?.asString

        val slots = spec.getAsJsonArray("slots") ?: throw IllegalArgumentException("missing 'slots'")
        if (slots.size() != 9) {
            throw IllegalArgumentException("'slots' must have exactly 9 entries (got " + slots.size() + ")")
        }

        // 1.20.1 result is an item-stack object keyed by "item" (1.20.5+/1.21 switched this to "id").
        val result = JsonObject().apply {
            addProperty("item", resultId)
            addProperty("count", count)
        }

        val out = JsonObject()
        if (type == "shapeless") {
            out.addProperty("type", "minecraft:crafting_shapeless")
            val ingredients = JsonArray()
            for (i in 0 until 9) ingredients.add(slotToIngredient(slots.get(i)) ?: continue)
            if (ingredients.isEmpty) throw IllegalArgumentException("shapeless recipe has no ingredients")
            out.add("ingredients", ingredients)
        } else {
            out.addProperty("type", "minecraft:crafting_shaped")
            val key = JsonObject()
            val seen = HashMap<String, String>() // ingredient signature -> assigned char
            var next = 'A'
            val rows = JsonArray()
            for (r in 0 until 3) {
                val sb = StringBuilder(3)
                for (c in 0 until 3) {
                    val ing = slotToIngredient(slots.get(r * 3 + c))
                    if (ing == null) {
                        sb.append(' ')
                    } else {
                        val sig = ing.toString()
                        val ch = seen.getOrPut(sig) {
                            val assigned = next.toString()
                            next++
                            key.add(assigned, ing)
                            assigned
                        }
                        sb.append(ch)
                    }
                }
                rows.add(sb.toString())
            }
            out.add("pattern", rows)
            out.add("key", key)
        }
        out.add("result", result)
        if (group != null) out.addProperty("group", group)
        return out
    }

    /** slot element -> ingredient JSON ({item}/{tag}/array of those) or null if empty. */
    private fun slotToIngredient(el: JsonElement?): JsonElement? {
        if (el == null || el.isJsonNull) return null
        if (el.isJsonArray) {
            val arr = JsonArray()
            for (item in el.asJsonArray) {
                if (item.isJsonPrimitive) stringToIngredient(item.asString)?.let { arr.add(it) }
            }
            return if (arr.isEmpty) null else arr
        }
        if (el.isJsonPrimitive) return stringToIngredient(el.asString)
        return null
    }

    /**
     * 1.20.1 ingredient form is an OBJECT (like 1.21.1, unlike 1.21.2+'s bare string):
     * {"item": "minecraft:x"} for an item, {"tag": "minecraft:t"} for a tag. (Alternatives are a
     * json array of these objects, assembled by [slotToIngredient].)
     */
    private fun stringToIngredient(raw: String): JsonElement? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return JsonObject().apply {
            if (s.startsWith("#")) addProperty("tag", normalizeId(s.substring(1)))
            else addProperty("item", normalizeId(s))
        }
    }

    private fun normalizeId(s: String): String {
        val t = s.trim()
        return if (t.contains(':')) t else "minecraft:$t"
    }

    // ---- bundled defaults: Armada's recipe set, re-expressed for 1.20.1 ----
    // Eureka ships its recipes under data/vs_eureka/recipes/ (the pre-1.21 path) in the old
    // item/tag object format, so none of them load on 1.21+ and the items would be uncraftable.
    // We regenerate the recipes here so a fresh install gets craftable items; users can edit this
    // file (or drop in their own overhauls) and /reload to change them.

    private val SHIP_HELM_WOODS = listOf(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "crimson", "warped"
    )
    private val BALLOON_COLORS = listOf(
        "white", "light_gray", "gray", "black", "red", "orange", "yellow", "lime",
        "green", "light_blue", "cyan", "blue", "purple", "magenta", "pink", "brown"
    )

    private fun s(id: String): JsonElement = JsonPrimitive(id)
    private fun none(): JsonElement = JsonPrimitive("")

    /** A slot accepting any one of several items (interchangeable alternatives). */
    private fun anyOf(vararg ids: String): JsonElement =
        JsonArray().apply { ids.forEach { add(JsonPrimitive(it)) } }

    /** Any glass pane, coloured or not (the engine doesn't care which). */
    private fun anyGlassPane(): JsonElement = anyOf(
        "minecraft:glass_pane",
        "minecraft:white_stained_glass_pane", "minecraft:orange_stained_glass_pane",
        "minecraft:magenta_stained_glass_pane", "minecraft:light_blue_stained_glass_pane",
        "minecraft:yellow_stained_glass_pane", "minecraft:lime_stained_glass_pane",
        "minecraft:pink_stained_glass_pane", "minecraft:gray_stained_glass_pane",
        "minecraft:light_gray_stained_glass_pane", "minecraft:cyan_stained_glass_pane",
        "minecraft:purple_stained_glass_pane", "minecraft:blue_stained_glass_pane",
        "minecraft:brown_stained_glass_pane", "minecraft:green_stained_glass_pane",
        "minecraft:red_stained_glass_pane", "minecraft:black_stained_glass_pane"
    )

    // Single slot-writer for both recipe types. Empty slots MUST serialize as the empty string "";
    // a shaped/shapeless split here previously let padding slots serialize as a JSON boolean, which
    // made every shapeless recipe fail to build ("Unknown registry key ... minecraft:false").
    private fun recipeJson(type: String, slots: List<JsonElement>, result: String, count: Int, group: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            add("slots", JsonArray().apply { slots.forEach { add(it) } })
            addProperty("result", result)
            addProperty("count", count)
            if (group != null) addProperty("group", group)
        }

    private fun shaped(slots: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject =
        recipeJson("shaped", slots, result, count, group)

    private fun shapeless(ingredients: List<JsonElement>, result: String, count: Int, group: String? = null): JsonObject {
        // 'slots' must be exactly 9; pad the unused entries with "" (ignored for shapeless).
        val slots = ingredients.toMutableList()
        while (slots.size < 9) slots.add(none())
        return recipeJson("shapeless", slots, result, count, group)
    }

    private fun defaultConfig(): JsonObject {
        val root = JsonObject()
        root.addProperty(
            "_README",
            "Eureka Armada recipe overrides (defaults = Armada's own recipes). 9 slots: 1=top-left .. " +
                "5=centre .. 9=bottom-right. Slot = \"namespace:item\", \"#namespace:tag\", [\"a\",\"b\"] for " +
                "alternatives, or \"\" for empty. type=shaped|shapeless, plus result + count. Set a recipe to " +
                "\"remove\" to disable it. Edit then /reload. Recipes added by a mod update are appended here " +
                "automatically; entries already in this file are never rewritten, so use \"remove\" rather " +
                "than deleting a recipe you want gone -- a deleted one comes back on the next update."
        )

        // Ship helm, one per wood:  B F B   F h F   S L S
        // (B=iron bars, F=wood fence [picks the wood type — all three must match], h=heart of the sea,
        //  S=wood slab [matches the wood type], L=lodestone)
        for (w in SHIP_HELM_WOODS) {
            root.add(
                "vs_eureka:${w}_ship_helm",
                shaped(
                    listOf(
                        s("minecraft:iron_bars"), s("minecraft:${w}_fence"), s("minecraft:iron_bars"),
                        s("minecraft:${w}_fence"), s("minecraft:heart_of_the_sea"), s("minecraft:${w}_fence"),
                        s("minecraft:${w}_slab"), s("minecraft:lodestone"), s("minecraft:${w}_slab")
                    ),
                    "vs_eureka:${w}_ship_helm", 1, "ship_helm"
                )
            )
        }

        // Engine:  S S S   F R G   I I T
        // (S=smooth stone, F=blast furnace, R=lightning rod, G=any glass pane, I=iron block, T=iron trapdoor)
        // 1.21.1 has no #minecraft:lightning_rods tag (single un-oxidizable rod), so the plain item is used.
        root.add(
            "vs_eureka:engine",
            shaped(
                listOf(
                    s("minecraft:smooth_stone"), s("minecraft:smooth_stone"), s("minecraft:smooth_stone"),
                    s("minecraft:blast_furnace"), s("minecraft:lightning_rod"), anyGlassPane(),
                    s("minecraft:iron_block"), s("minecraft:iron_block"), s("minecraft:iron_trapdoor")
                ),
                "vs_eureka:engine", 1
            )
        )

        // Floater = 16:  W B W   B _ B   W B W   (W=any wooden slab, B=barrel)
        root.add(
            "vs_eureka:floater",
            shaped(
                listOf(
                    s("#minecraft:wooden_slabs"), s("minecraft:barrel"), s("#minecraft:wooden_slabs"),
                    s("minecraft:barrel"), none(), s("minecraft:barrel"),
                    s("#minecraft:wooden_slabs"), s("minecraft:barrel"), s("#minecraft:wooden_slabs")
                ),
                "vs_eureka:floater", 16
            )
        )

        // Anchor:  # i #   _ i _   i I i   (#=lead, i=iron ingot, I=iron block)
        root.add(
            "vs_eureka:anchor",
            shaped(
                listOf(
                    s("minecraft:lead"), s("minecraft:iron_ingot"), s("minecraft:lead"),
                    none(), s("minecraft:iron_ingot"), none(),
                    s("minecraft:iron_ingot"), s("minecraft:iron_block"), s("minecraft:iron_ingot")
                ),
                "vs_eureka:anchor", 1
            )
        )

        // Shipwright's Bench:  G A C   S M T   p p p
        // (G=grindstone, A=any anvil, C=crafting table, S=stonecutter, M=cartography table,
        //  T=smithing table, p=any planks)
        //
        // Every station in the grid is a station standing on the finished desk, which is what makes a recipe
        // this expensive read as assembly rather than as a toll. The anvil is the tag, so a chipped or
        // damaged one is accepted -- refusing a dented anvil for a workbench that already has one dented into
        // its top would be a strange thing to insist on.
        //
        // This is also the switch for the whole feature: set this entry to "remove" and the Shipwright's
        // Bench becomes uncraftable again, which is the behaviour it shipped with and the one that makes
        // shipwrights something you can only find rather than make.
        root.add(
            "vs_eureka:shipwrights_bench",
            shaped(
                listOf(
                    s("minecraft:grindstone"), s("#minecraft:anvil"), s("minecraft:crafting_table"),
                    s("minecraft:stonecutter"), s("minecraft:cartography_table"), s("minecraft:smithing_table"),
                    s("#minecraft:planks"), s("#minecraft:planks"), s("#minecraft:planks")
                ),
                "vs_eureka:shipwrights_bench", 1
            )
        )

        // Ballast:  # C #   C _ C   # C #   (#=stone, C=cobblestone)
        root.add(
            "vs_eureka:ballast",
            shaped(
                listOf(
                    s("minecraft:stone"), s("minecraft:cobblestone"), s("minecraft:stone"),
                    s("minecraft:cobblestone"), none(), s("minecraft:cobblestone"),
                    s("minecraft:stone"), s("minecraft:cobblestone"), s("minecraft:stone")
                ),
                "vs_eureka:ballast", 8
            )
        )

        // Balloon = 10:  L M L   M N M   L M L
        // (L=leather, M=phantom membrane, N=nether star) -- the single, Nether-Star-gated balloon recipe.
        root.add(
            "vs_eureka:balloon",
            shaped(
                listOf(
                    s("minecraft:leather"), s("minecraft:phantom_membrane"), s("minecraft:leather"),
                    s("minecraft:phantom_membrane"), s("minecraft:nether_star"), s("minecraft:phantom_membrane"),
                    s("minecraft:leather"), s("minecraft:phantom_membrane"), s("minecraft:leather")
                ),
                "vs_eureka:balloon", 10, "balloons"
            )
        )

        // Coloured balloons: shapeless  (any balloon) + that dye -> that colour.
        for (c in BALLOON_COLORS) {
            root.add(
                "vs_eureka:${c}_balloon",
                shapeless(
                    listOf(s("#vs_eureka:balloons"), s("minecraft:${c}_dye")),
                    "vs_eureka:${c}_balloon", 1, "colored_balloons"
                )
            )
        }

        // Ship Bottle:  _ E _   _ H _   _ B _
        // (E=eye of ender, H=heart of the sea, B=glass bottle) -- an Armada item rather than a classic
        // Eureka one, but it lives here so it is retunable from the same config as everything else.
        // The padding columns are stripped when the pattern is parsed, so this works in any column.
        root.add(
            "vs_eureka:ship_bottle",
            shaped(
                listOf(
                    none(), s("minecraft:ender_eye"), none(),
                    none(), s("minecraft:heart_of_the_sea"), none(),
                    none(), s("minecraft:glass_bottle"), none()
                ),
                "vs_eureka:ship_bottle", 1
            )
        )

        // Ship Blueprint: shapeless paper + lapis. Cheap on purpose -- a blueprint costs nothing to draft and
        // is worth nothing until a shipwright reads it; the price of a ship is the materials list inside.
        root.add(
            "vs_eureka:blueprint",
            shapeless(
                listOf(s("minecraft:paper"), s("minecraft:lapis_lazuli")),
                "vs_eureka:blueprint", 1
            )
        )

        // Cannonballs = 8:  N I N   I I I   N I N
        // (I=ingot in a plus, N=that metal's nugget in the corners). Eight to a batch because a gun deck
        // eats them and they only stack to 16 -- shot is meant to be bulky to keep, not tedious to make.
        //
        // Netherite has no nugget, so it takes raw gold in the corners instead. That is not a filler
        // substitution: netherite is smithed WITH gold, so gold in the corners is the material already in
        // its lineage, and it keeps the most expensive round in the game visibly the most expensive.
        //
        // Copper takes IRON nuggets, which makes it the one round in the family that is an alloy rather
        // than one metal in two grades. That is deliberate on both counts. Copper on its own is soft, and
        // a soft ball spreads against a hull instead of going through it; a little iron worked into the
        // cast is what makes the shot worth firing. And it closes the only version split left in the
        // recipe book -- copper has no nugget before the Copper Age drop, so 1.20.1 and 1.21.1 were
        // spending raw copper where 1.21.11 spent a nugget, and the same round cost a different thing
        // depending on which game you were playing. Iron nuggets exist in all three.
        for ((ball, ingot, corner) in listOf(
            Triple("copper", "minecraft:copper_ingot", "minecraft:iron_nugget"),
            Triple("iron", "minecraft:iron_ingot", "minecraft:iron_nugget"),
            Triple("gold", "minecraft:gold_ingot", "minecraft:gold_nugget"),
            Triple("netherite", "minecraft:netherite_ingot", "minecraft:raw_gold")
        )) {
            root.add(
                "vs_eureka:${ball}_cannonball",
                shaped(
                    listOf(
                        s(corner), s(ingot), s(corner),
                        s(ingot), s(ingot), s(ingot),
                        s(corner), s(ingot), s(corner)
                    ),
                    "vs_eureka:${ball}_cannonball", 8, "cannonballs"
                )
            )
        }

        // Steel = 8:  C R C   R I I   C I I
        // (I=iron ingot in a 2x2 at the bottom right, R=raw iron, C=coal or charcoal in the free corners).
        // Steel is smelted rather than mined, so its recipe is the only one in the family that is not a
        // symmetrical arrangement of one metal: iron and raw iron cooked with carbon.
        val carbon = anyOf("minecraft:coal", "minecraft:charcoal")
        root.add(
            "vs_eureka:steel_cannonball",
            shaped(
                listOf(
                    carbon, s("minecraft:raw_iron"), carbon,
                    s("minecraft:raw_iron"), s("minecraft:iron_ingot"), s("minecraft:iron_ingot"),
                    carbon, s("minecraft:iron_ingot"), s("minecraft:iron_ingot")
                ),
                "vs_eureka:steel_cannonball", 8, "cannonballs"
            )
        )

        // Charged rounds = 4:  M P P   P B B   P B B
        // (B=four of that cannonball in a 2x2 at the bottom right, mirroring the steel layout, M=that
        // metal's raw form in the upper-left, P=the charge filling the rest). Four in, four out: a charge is
        // packed into shells you already have rather than cast into new ones.
        //
        // The charged rounds share the shape and differ only in the filling, which is the whole point --
        // the recipe reads as "same round, different filling", exactly as the items do. Armor-piercing takes
        // diamonds where the others take powder: a coating rather than a charge, and priced like one.
        for ((prefix, powderId) in listOf(
            "explosive" to "minecraft:gunpowder",
            "incendiary" to "minecraft:blaze_powder",
            "armor_piercing" to "minecraft:diamond"
        )) {
            for (ball in listOf("copper", "iron", "steel", "gold", "netherite")) {
                val raw = when (ball) {
                    "copper" -> "minecraft:raw_copper"
                    // Steel is an iron alloy and has no raw form of its own.
                    "iron", "steel" -> "minecraft:raw_iron"
                    "gold" -> "minecraft:raw_gold"
                    // Scrap, not debris: the charge is packed against refined metal, and debris is the ORE --
                    // asking for it here read as a mistake to anyone who knows the smelting chain.
                    else -> "minecraft:netherite_scrap"
                }
                val shot = s("vs_eureka:${ball}_cannonball")
                val powder = s(powderId)
                root.add(
                    "vs_eureka:${prefix}_${ball}_cannonball",
                    shaped(
                        listOf(
                            s(raw), powder, powder,
                            powder, shot, shot,
                            powder, shot, shot
                        ),
                        "vs_eureka:${prefix}_${ball}_cannonball", 4, "cannonballs"
                    )
                )
            }
        }

        return root
    }
}
