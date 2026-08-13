package org.valkyrienskies.eureka.item

import net.minecraft.util.RandomSource

/**
 * What a given shot is made of, and how much of a hull it takes with it.
 *
 * ## The damage is a ladder, not a range
 * [guaranteed] blocks always go. Every entry in [extraChances] is then rolled **independently** for one more
 * block, so a round is not a uniform roll between a minimum and a maximum -- it is heavily weighted toward
 * the low end, and the top of its range is rare. Iron's 2-5 lands on 2 or 3 most of the time and reaches 5
 * about once in eight shots.
 *
 * That shape is the whole balance. It means a heavier ball raises the *floor* far more reliably than it
 * raises the ceiling, so netherite is not "gold but luckier" -- it is a gun that always hurts.
 *
 * ## The tiers
 * Copper is the weak opener. **Iron is the sweet spot**: cheap, and its floor of 2 is enough to matter.
 * Gold is heavy but swingy -- the widest spread in the game, and the only one that can disappoint at 2 after
 * you paid gold for it. Netherite is the hammer.
 *
 * Steel sits between iron and gold by design (higher average than iron, tighter spread than gold) and is
 * deliberately absent until there is a steel ingot to make it from.
 */
enum class Cannonball(
    val itemName: String,
    /** Blocks destroyed before a single die is thrown. */
    val guaranteed: Int,
    /** Chance of one further block each, rolled independently and in order. */
    val extraChances: DoubleArray,
    /** How many *surviving* blocks an incendiary round of this weight sets alight. */
    val incendiary: Int
) {
    COPPER("copper_cannonball", 1, doubleArrayOf(0.75, 0.25), 2),
    IRON("iron_cannonball", 2, doubleArrayOf(0.75, 0.50, 0.25), 3),
    GOLD("gold_cannonball", 2, doubleArrayOf(0.90, 0.80, 0.70, 0.40, 0.20, 0.10), 5),
    NETHERITE("netherite_cannonball", 6, doubleArrayOf(0.80, 0.70, 0.60, 0.40, 0.20, 0.10), 6);

    /** The worst this round can do -- and the most likely thing it will do. */
    val minBlocks: Int get() = guaranteed

    /** The best it can do, which it will hardly ever do. */
    val maxBlocks: Int get() = guaranteed + extraChances.size

    /**
     * Roll this round's damage.
     *
     * Every chance is rolled even after one fails, rather than stopping at the first miss. Stopping early
     * would quietly multiply the probabilities together and make the upper half of every range far rarer
     * than the table says -- netherite's 12 would go from one shot in a hundred to one in several thousand.
     */
    fun roll(random: RandomSource): Int {
        var blocks = guaranteed
        for (chance in extraChances) {
            if (random.nextDouble() < chance) blocks++
        }
        return blocks
    }
}
