package org.valkyrienskies.eureka.util

import net.minecraft.util.RandomSource

/**
 * The `"name*weight"` list format: a plain string list a user can re-weigh without a schema change.
 *
 * `"pirate/sloop*3"` draws three times as often as `"pirate/brig"`; a bare name is weight 1, so every
 * list written before weights existed keeps meaning exactly what it meant. Malformed weights fall back
 * to 1 rather than failing the entry -- the ChanceSpec rule: a config typo costs precision, never the
 * feature. Used by the pirate hull pool and the special-blueprint pool, which are the same idea wearing
 * two hats.
 */
object WeightedNames {

    /** `"name*weight"` -> name to weight; bare name -> weight 1.0. */
    fun parse(entry: String): Pair<String, Double> {
        val star = entry.lastIndexOf('*')
        if (star <= 0) return entry.trim() to 1.0
        val name = entry.substring(0, star).trim()
        val weight = entry.substring(star + 1).trim().toDoubleOrNull() ?: 1.0
        if (name.isEmpty()) return entry.trim() to 1.0
        return name to weight.coerceAtLeast(0.0)
    }

    /** One weighted draw over [entries], considering only names [valid] admits. Null when nothing is. */
    fun pick(entries: List<String>, random: RandomSource, valid: (String) -> Boolean = { true }): String? {
        val parsed = entries.map { parse(it) }.filter { it.second > 0.0 && valid(it.first) }
        if (parsed.isEmpty()) return null
        val total = parsed.sumOf { it.second }
        var roll = random.nextDouble() * total
        for ((name, weight) in parsed) {
            roll -= weight
            if (roll < 0.0) return name
        }
        return parsed.last().first
    }
}
