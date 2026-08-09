package org.valkyrienskies.eureka.crew

import net.minecraft.util.RandomSource
import org.valkyrienskies.mod.util.logger

/**
 * What a crew is called when nobody has called it anything.
 *
 * A crew has to have a name before it can have a ledger entry, because the name is half of the key those
 * entries are filed under. Requiring the captain to type one first would mean the very first thing signing
 * somebody on does is refuse, so a blank wheel names itself on its first recruit instead.
 *
 * The shape is fixed on purpose: "Motley Crew" and then one word. Two crews are told apart by the third word
 * alone, which is short enough to say out loud and long enough not to collide in practice, and the constant
 * prefix makes an auto-named crew instantly recognisable as one nobody has bothered to name yet.
 *
 * ## The word pool
 * The third word is drawn from vs-core's OWN noun list -- the same 4968 words its generated ship slugs come
 * from, so "Motley Crew Drake" sits beside `drake-hay-rock` as though they were always meant to. That list is
 * `nounlist.txt` at the ROOT of the vs-core impl jar, which is a resource rather than an API: nothing promises
 * it will be there after a version bump, and a root-level name is exactly the sort of thing that gets moved or
 * shadowed by another jar. So it is read once, defensively, and [FALLBACK] takes over if it is missing or
 * unreadable. Naming a crew is not something that may fail.
 */
object CrewNameGenerator {

    /** The half of the name that never varies, and what makes an auto-named crew recognisable as one. */
    const val PREFIX = "Motley Crew"

    /**
     * A name for a crew that has none, avoiding anything [taken] already answers to.
     *
     * The retry loop exists because the key a crew is filed under is (captain, name, variant): handing out a
     * name this captain already uses for a wheel of the same wood would not make a second crew, it would
     * silently merge two. A few attempts is plenty against a pool this size; if they all collide -- which
     * needs either an absurd number of crews or the fallback list -- the counter suffix guarantees a distinct
     * answer rather than looping forever.
     */
    fun generate(rng: RandomSource, taken: (String) -> Boolean): String {
        val words = pool
        repeat(ATTEMPTS) {
            val candidate = "$PREFIX ${words[rng.nextInt(words.size)].replaceFirstChar { c -> c.uppercase() }}"
            if (!taken(candidate)) return candidate
        }
        var n = 2
        while (true) {
            val candidate = "$PREFIX ${words[rng.nextInt(words.size)].replaceFirstChar { c -> c.uppercase() }} $n"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    /**
     * Whether [slug] looks like a SHIP name vs-core made up, rather than one a player chose.
     *
     * vs-core names a new ship by joining three words from [pool] with dashes -- `drake-hay-rock`,
     * `revenant-creation-editing`. Testing all three against the pool makes this precise rather than a guess
     * about shape: a player would have to type three dash-joined dictionary nouns in lower case to be mistaken
     * for one, and the only cost if they do is that Keep Name declines to remember that particular name.
     *
     * This exists because Keep Name reads the ship's slug on a timer. Without it, a hull that came up with a
     * generated name would overwrite the name the captain had chosen, and one failed re-apply would erase the
     * memory for good -- which is exactly how it failed the first time.
     */
    fun looksGenerated(slug: String): Boolean {
        val parts = slug.split('-')
        // AT LEAST three, not exactly three: the list contains hyphenated nouns ("arm-rest", "t-shirt"), so a
        // three-word name can arrive with more than three dash-separated pieces -- `arm-rest-cabana-fanny` is
        // one vs-core actually produced. Requiring exactly three let those through to be remembered as though
        // a player had chosen them.
        if (parts.size < GENERATED_WORDS) return false
        if (parts.any { it.isEmpty() || it.any { c -> c !in 'a'..'z' } }) return false
        val words = pool
        return parts.all { it in words }
    }

    /**
     * The noun pool, read once on first use.
     *
     * `by lazy` rather than a load at mod init: this runs the first time anybody signs a villager on, which is
     * long after the classpath has settled, and a world that never crews a ship never pays for it.
     */
    private val pool: List<String> by lazy { readNouns() ?: FALLBACK }

    private fun readNouns(): List<String>? =
        try {
            CrewNameGenerator::class.java.classLoader.getResourceAsStream(NOUN_RESOURCE)?.use { stream ->
                val words = stream.bufferedReader().lineSequence()
                    .map { it.trim() }
                    // Single ASCII words only. The list carries a handful of entries ("ATM", "T-shirt") that
                    // read oddly as a crew name, and anything with a space would break the fixed three-word
                    // shape this whole scheme is recognised by.
                    .filter { it.length in MIN_WORD..MAX_WORD && it.all { c -> c in 'a'..'z' } }
                    .toList()
                words.takeIf { it.size >= MIN_POOL }
            }
        } catch (ex: Exception) {
            logger.warn("Could not read {} for crew names, using the built-in list: {}", NOUN_RESOURCE, ex.message)
            null
        }

    private const val NOUN_RESOURCE = "nounlist.txt"
    private const val ATTEMPTS = 24

    /** How many words vs-core joins to name a ship. */
    private const val GENERATED_WORDS = 3
    private const val MIN_WORD = 3
    private const val MAX_WORD = 12

    /** Below this the resource is not the list we expected, and the fallback is the safer answer. */
    private const val MIN_POOL = 64

    /**
     * Enough words to keep names distinct if vs-core's list ever moves, chosen to sound like they belong on a
     * ship's articles. Never the primary source -- see the class note.
     */
    private val FALLBACK = listOf(
        "anchor", "barrel", "beacon", "bilge", "bosun", "cannon", "capstan", "cutlass", "compass", "cove",
        "current", "dagger", "doubloon", "driftwood", "fathom", "flotsam", "galley", "gangway", "grog",
        "harbour", "hawser", "headwind", "keel", "kraken", "lantern", "lagoon", "leeward", "mainsail",
        "marlin", "mizzen", "monsoon", "mooring", "nautilus", "octant", "outrigger", "parrot", "pennant",
        "quarterdeck", "rigging", "rudder", "salvage", "sextant", "shanty", "shoal", "spinnaker", "sprit",
        "squall", "stern", "tackle", "tempest", "tiller", "topsail", "trawler", "trident", "typhoon",
        "undertow", "wharf", "windlass", "yardarm", "zephyr", "albatross", "barnacle", "brigantine", "cutter",
        "dinghy", "frigate", "galleon", "schooner", "sloop", "trawl"
    )

    private val logger by logger()
}
