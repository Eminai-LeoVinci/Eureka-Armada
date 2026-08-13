package org.valkyrienskies.eureka.item

import net.minecraft.util.RandomSource

/**
 * What a round is made of.
 *
 * ## The damage is a ladder, not a range
 * [guaranteed] blocks always go. Every entry in [extraChances] is then rolled **independently** for one more
 * block, so a round is not a uniform roll between a minimum and a maximum -- it is heavily weighted toward
 * the low end, and the top of its range is rare. Iron's 2-5 lands on 2 or 3 most of the time and reaches 5
 * about once in eight shots.
 *
 * That shape is the whole balance. A heavier ball raises the *floor* far more reliably than it raises the
 * ceiling, so netherite is not "gold but luckier" -- it is a gun that always hurts.
 *
 * ## The tiers
 * Copper is the weak opener. **Iron is the sweet spot**: cheap, and a floor of 2 is enough to matter. Steel
 * is the consistent one. Gold is heavy but swingy. Netherite is the hammer.
 */
enum class Cannonball(
    /** The metal's name, which is also the first half of every item id built from it. */
    val metal: String,
    /** Blocks destroyed before a single die is thrown. */
    val guaranteed: Int,
    /** Chance of one further block each, rolled independently and in order. */
    val extraChances: DoubleArray,
    /** How many *surviving* blocks an incendiary round of this weight sets alight. */
    val incendiary: Int
) {
    // Each ladder is tuned to a target average, which is `guaranteed + extraChances.sum()`. Keep that sum
    // intact when adjusting a rung, or the tier quietly changes power while its printed range stays put.
    //
    //                                                                    range   avg
    COPPER("copper", 1, doubleArrayOf(0.75, 0.50, 0.25), 2), //           1-4     2.5
    IRON("iron", 2, doubleArrayOf(0.80, 0.70, 0.50), 3), //               2-5     4.0
    STEEL("steel", 3, doubleArrayOf(0.80, 0.60, 0.40, 0.20), 4), //       3-7     5.0
    // Gold's extra rungs sit in the MIDDLE of its ladder, not at the end. Anchored at 90% for the first
    // block and 10% for the ninth, the descent between them is what carries the average to 6 -- so gold
    // out-averages steel while keeping the lower floor and the longer tail. Steel is what you fire when you
    // need a hole; gold is what you fire when you want one.
    GOLD("gold", 2, doubleArrayOf(0.90, 0.85, 0.75, 0.65, 0.50, 0.25, 0.10), 5), // 2-9  6.0
    NETHERITE("netherite", 6, doubleArrayOf(0.80, 0.70, 0.60, 0.40, 0.20, 0.10), 6); // 6-12 8.8

    /** The raw form of this metal, which every charged variant is bound with. */
    val rawMaterial: String
        get() = when (this) {
            COPPER -> "minecraft:raw_copper"
            IRON, STEEL -> "minecraft:raw_iron"
            GOLD -> "minecraft:raw_gold"
            // Netherite has no raw form; ancient debris is the thing it is dug out of.
            NETHERITE -> "minecraft:ancient_debris"
        }
}

/**
 * What is packed behind the ball.
 *
 * A charge does **not** simply extend the metal's ladder. Adding rungs to the end of an existing ladder
 * would raise the ceiling and leave the floor alone, which is the opposite of what a bursting charge does --
 * so the bonus is split: part of it is guaranteed, and the rest is its own short ladder rolled alongside.
 */
enum class CannonCharge(
    /** Prefixes the item id: "" for a plain round, "explosive_" for a charged one. */
    val prefix: String,
    /** Blocks added to the floor. */
    val bonusGuaranteed: Int,
    /** Extra independent rolls, on top of the metal's own. */
    val bonusChances: DoubleArray
) {
    PLAIN("", 0, doubleArrayOf()),

    /**
     * Gunpowder: four more blocks in total, two of them certain.
     *
     * The remaining two roll at 60% and 30% as a ladder of their own rather than being appended to the
     * metal's -- appended, they would inherit whatever tail that metal happened to have and make an
     * explosive netherite round wildly better than an explosive copper one, when the charge is the same
     * amount of gunpowder either way.
     */
    EXPLOSIVE("explosive_", 2, doubleArrayOf(0.60, 0.30)),

    /**
     * Blaze powder: no extra damage at all, and that is the point.
     *
     * An incendiary round sets surviving blocks alight *after* destruction has been resolved, so the fire is
     * never a damage bonus in disguise -- it can only ever take hold on what the ball did not already break.
     * Given a free rung on the ladder as well it would just be a worse explosive round with a light show;
     * given none, it is a different weapon. How many blocks it lights is a property of the *metal*, since a
     * heavier ball carries more of the stuff -- see [Cannonball.incendiary].
     */
    INCENDIARY("incendiary_", 0, doubleArrayOf());
}

/** A round as it actually exists: a metal, and whatever is packed behind it. */
class Load(val ball: Cannonball, val charge: CannonCharge) {

    /** The worst this round can do -- and the most likely thing it will do. */
    val minBlocks: Int get() = ball.guaranteed + charge.bonusGuaranteed

    /** The best it can do, which it will hardly ever do. */
    val maxBlocks: Int get() = minBlocks + ball.extraChances.size + charge.bonusChances.size

    /** How many blocks the charge is worth at most, for a tooltip that has to justify the extra cost. */
    val chargeBonus: Int get() = charge.bonusGuaranteed + charge.bonusChances.size

    /** How many surviving blocks this round sets alight, or 0 if it starts no fires. */
    val incendiaryBlocks: Int get() = if (charge == CannonCharge.INCENDIARY) ball.incendiary else 0

    /**
     * Roll this round's damage.
     *
     * Every chance is rolled even after one fails, rather than stopping at the first miss. Stopping early
     * would quietly multiply the probabilities together and make the upper half of every range far rarer
     * than the tables say -- netherite's 12 would go from one shot in a hundred to one in several thousand.
     */
    fun roll(random: RandomSource): Int {
        var blocks = minBlocks
        for (chance in ball.extraChances) if (random.nextDouble() < chance) blocks++
        for (chance in charge.bonusChances) if (random.nextDouble() < chance) blocks++
        return blocks
    }

    /** The item id this load is sold as. */
    val itemName: String get() = "${charge.prefix}${ball.metal}_cannonball"
}
