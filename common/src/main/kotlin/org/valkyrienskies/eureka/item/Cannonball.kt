package org.valkyrienskies.eureka.item

import net.minecraft.util.RandomSource
import org.valkyrienskies.eureka.EurekaConfig
import kotlin.math.floor

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
 * ## The numbers live on the config
 * Every rung of every ladder reads [EurekaConfig.SERVER] live -- the "Cannonball damage" region -- so a
 * `/reload` retunes the rounds and their tooltips together. The shipped defaults keep each tier's identity:
 * copper the weak opener, **iron the sweet spot**, steel the consistent one, gold heavy but swingy (its
 * extra rungs descend through the MIDDLE of its ladder, carrying the average past steel while keeping the
 * lower floor and the longer tail), netherite the hammer. Each default ladder is tuned to a target average
 * of `guaranteed + sum(chances)`; the config descriptions carry the ranges.
 */
enum class Cannonball(
    /** The metal's name, which is also the first half of every item id built from it. */
    val metal: String
) {
    COPPER("copper"),
    IRON("iron"),
    STEEL("steel"),
    GOLD("gold"),
    NETHERITE("netherite");

    /** Blocks destroyed before a single die is thrown. Read live off the config. */
    val guaranteed: Int
        get() = when (this) {
            COPPER -> EurekaConfig.SERVER.cannonballCopperGuaranteed
            IRON -> EurekaConfig.SERVER.cannonballIronGuaranteed
            STEEL -> EurekaConfig.SERVER.cannonballSteelGuaranteed
            GOLD -> EurekaConfig.SERVER.cannonballGoldGuaranteed
            NETHERITE -> EurekaConfig.SERVER.cannonballNetheriteGuaranteed
        }.coerceAtLeast(0)

    /** Chance of one further block each, rolled independently and in order. Read live off the config. */
    val extraChances: DoubleArray
        get() = ChanceSpec.parse(
            when (this) {
                COPPER -> EurekaConfig.SERVER.cannonballCopperExtraChances
                IRON -> EurekaConfig.SERVER.cannonballIronExtraChances
                STEEL -> EurekaConfig.SERVER.cannonballSteelExtraChances
                GOLD -> EurekaConfig.SERVER.cannonballGoldExtraChances
                NETHERITE -> EurekaConfig.SERVER.cannonballNetheriteExtraChances
            }
        )

    /** How many *surviving* blocks an incendiary round of this weight sets alight. Read live off the config. */
    val incendiary: Int
        get() = when (this) {
            COPPER -> EurekaConfig.SERVER.cannonballCopperIncendiary
            IRON -> EurekaConfig.SERVER.cannonballIronIncendiary
            STEEL -> EurekaConfig.SERVER.cannonballSteelIncendiary
            GOLD -> EurekaConfig.SERVER.cannonballGoldIncendiary
            NETHERITE -> EurekaConfig.SERVER.cannonballNetheriteIncendiary
        }.coerceAtLeast(0)

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
 * The bonus numbers read the config live, like the metals' own.
 */
enum class CannonCharge(
    /** Prefixes the item id: "" for a plain round, "explosive_" for a charged one. */
    val prefix: String
) {
    PLAIN(""),

    /**
     * Gunpowder: more blocks, some of them certain.
     *
     * The chance rungs roll as a ladder of their own rather than being appended to the metal's -- appended,
     * they would inherit whatever tail that metal happened to have and make an explosive netherite round
     * wildly better than an explosive copper one, when the charge is the same amount of gunpowder either
     * way. Both halves of the bonus are config levers.
     */
    EXPLOSIVE("explosive_"),

    /**
     * Blaze powder: no extra damage at all, and that is the point.
     *
     * An incendiary round sets surviving blocks alight *after* destruction has been resolved, so the fire is
     * never a damage bonus in disguise -- it can only ever take hold on what the ball did not already break.
     * Given a free rung on the ladder as well it would just be a worse explosive round with a light show;
     * given none, it is a different weapon. How many blocks it lights is a property of the *metal*, since a
     * heavier ball carries more of the stuff -- see [Cannonball.incendiary].
     */
    INCENDIARY("incendiary_"),

    /**
     * Diamond: no extra damage per hit, and the round does not stop.
     *
     * An armor-piercing round takes [Load.impacts] bites instead of one. The first is the metal's own roll,
     * unchanged; the follow-throughs are shares **of that first roll** -- of the first, not each of the
     * last, so one lucky opening hit carries the whole chain and one poor one dooms it. Rounded half-up and
     * never below one block: a spent ball still stings. At the default 100/75/50/25 shares, netherite at
     * its best runs 12-9-6-3; copper at its best runs 4-3-2-1.
     *
     * Like blaze powder, the coating adds nothing to any single ladder -- the extra impacts are the whole
     * purchase. Appended per the ladder philosophy: a diamond is the same diamond whatever ball wears it.
     */
    ARMOR_PIERCING("armor_piercing_");

    /** Blocks added to the floor. Read live off the config; zero for everything but explosive. */
    val bonusGuaranteed: Int
        get() = if (this == EXPLOSIVE) {
            EurekaConfig.SERVER.cannonExplosiveBonusGuaranteed.coerceAtLeast(0)
        } else 0

    /** Extra independent rolls, on top of the metal's own. Empty for everything but explosive. */
    val bonusChances: DoubleArray
        get() = if (this == EXPLOSIVE) {
            ChanceSpec.parse(EurekaConfig.SERVER.cannonExplosiveBonusChances)
        } else ChanceSpec.NONE
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
     * How many times this round strikes before it is spent. One, unless it is armor-piercing -- and for an
     * armor-piercing round the count is the config's share list itself: one strike per entry.
     */
    val impacts: Int
        get() = if (charge == CannonCharge.ARMOR_PIERCING) apShares().size.coerceAtLeast(1) else 1

    /**
     * What the [ordinal]-th block strike takes (0 = the opening hit's own roll), given what that opening
     * hit rolled.
     *
     * Half rounds UP -- .50 to .99 climbs, .01 to .49 falls -- and the floor is one block always: the
     * last impact of the weariest copper ball still breaks something. Deterministic past the first hit on
     * purpose, so the chain reads as one shot losing steam rather than four separate lotteries.
     */
    fun followThrough(base: Int, ordinal: Int): Int {
        val share = apShares().getOrElse(ordinal) { return 0 }
        return floor(base * share + 0.5).toInt().coerceAtLeast(1)
    }

    /**
     * The armor-piercing strike shares, as fractions of the opening roll. Index 0 is the opening strike
     * itself -- present so the list's LENGTH is the strike count -- but never consumed as a multiplier:
     * CannonShot rolls the opening hit fresh and only asks [followThrough] from the second strike on.
     */
    private fun apShares(): DoubleArray = ChanceSpec.parse(EurekaConfig.SERVER.cannonArmorPiercingStrikePercents)

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

/**
 * The config's percent lists ("80,70,50"), parsed to fractions and memoized by the exact string -- these
 * are read on every roll and every tooltip frame, and a config only ever holds a handful of distinct
 * values. Malformed entries are dropped rather than failing the list; a blank string is a ladder with no
 * rungs. No upper clamp on purpose: "150" as a chance is simply a certain block, which is a legitimate
 * way to raise a floor without touching the guaranteed key.
 */
internal object ChanceSpec {
    val NONE = DoubleArray(0)

    private val cache = HashMap<String, DoubleArray>()

    fun parse(spec: String): DoubleArray = synchronized(cache) {
        // A config typo should not slowly fill a map that lives forever; the real key set is tiny.
        if (cache.size > 64) cache.clear()
        cache.getOrPut(spec) {
            spec.split(',', ';')
                .mapNotNull { it.trim().toDoubleOrNull() }
                .map { (it / 100.0).coerceAtLeast(0.0) }
                .toDoubleArray()
        }
    }
}
