package org.valkyrienskies.eureka.crew

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerTrades
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.block.Block
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaLootConfig

/**
 * What a Crewman sells and buys -- read from the loot config, not written here.
 *
 * The listings live in :common because `VillagerTrades.ItemListing`, `MerchantOffer` and `ItemCost` are all
 * vanilla. Only the registration call needs Fabric API, and that is in `CrewRegistrationsFabric`.
 *
 * ## Why the table is data now
 * It used to be five hardcoded lists, so the only question a server owner could answer was "balloons: yes or
 * no", through a pair of booleans. Every trade now lives in `crewmanTrades` as a weighted entry: re-weigh one
 * to change how often it turns up, add an id that was never in the defaults, or delete an entry to remove
 * that trade outright. Removing balloons is deleting the balloon entry -- there is no switch to find, because
 * there is no switch.
 *
 * ## Weight had to be taken away from vanilla to mean anything
 * `Villager.updateTrades` draws two listings out of a level's array and gives every one of them equal odds.
 * That is why rarity could not be expressed before, and why seventeen balloon colours had to hide inside a
 * single listing rather than be seventeen entries -- as entries they would have crowded every other Eureka
 * item out of the level AND still been equally likely as each other.
 *
 * So the array handed to vanilla is [DRAWN] copies of one listing, and the weighted roll happens inside it,
 * over the config, once per slot. Two offers, any number of candidates, and a weight that behaves.
 *
 * The roll happens when the villager's offer list is built and NOT again on restock, which is the same
 * promise the balloon lottery always made: a crewman selling magenta balloons goes on selling magenta
 * balloons. A rare trade is a trader you found, not a slot machine you pull until it pays.
 */
object CrewTrades {

    /** How many offers vanilla draws from each level's pool. */
    private const val DRAWN = 2

    /** Every balloon a 'balloon' trade can hand over, all equally likely. */
    private val COLOURS = listOf(
        EurekaBlocks.BALLOON,
        EurekaBlocks.WHITE_BALLOON, EurekaBlocks.LIGHT_GRAY_BALLOON, EurekaBlocks.GRAY_BALLOON,
        EurekaBlocks.BLACK_BALLOON, EurekaBlocks.BROWN_BALLOON, EurekaBlocks.RED_BALLOON,
        EurekaBlocks.ORANGE_BALLOON, EurekaBlocks.YELLOW_BALLOON, EurekaBlocks.LIME_BALLOON,
        EurekaBlocks.GREEN_BALLOON, EurekaBlocks.LIGHT_BLUE_BALLOON, EurekaBlocks.CYAN_BALLOON,
        EurekaBlocks.BLUE_BALLOON, EurekaBlocks.PURPLE_BALLOON, EurekaBlocks.MAGENTA_BALLOON,
        EurekaBlocks.PINK_BALLOON
    )

    /**
     * One drawn offer: a weighted roll over the level's configured trades.
     *
     * [avoid] is the small courtesy that stops the pair being the same trade twice. Offers are appended as
     * each listing is asked, so by the time the second is called the first is already in the merchant's list
     * and can be read back and rolled around. Best-effort by design: a level holding one entry has nothing
     * else to give, and leaving the slot empty would be worse than repeating it.
     */
    private class Drawn(private val rank: Int) : VillagerTrades.ItemListing {
        override fun getOffer(level: ServerLevel, trader: Entity, rng: RandomSource): MerchantOffer? {
            val pool = poolFor(rank)
            if (pool.isEmpty()) return null
            val avoid = (trader as? Villager)?.offers?.map { it.result.item }?.toSet() ?: emptySet()

            repeat(8) {
                val offer = offerOf(roll(pool, rng), rng)
                if (offer.result.item !in avoid) return offer
            }
            return offerOf(roll(pool, rng), rng)
        }
    }

    /**
     * The usable entries of one level, read live so a config edit lands on the next villager.
     *
     * An entry naming an item that does not exist is DROPPED rather than fatal -- a pack that lists another
     * mod's ingot should degrade to "that trade is absent" when the mod is not installed, not take the
     * villager's whole trade table down with it.
     */
    private fun poolFor(level: Int): List<EurekaLootConfig.TradeEntry> {
        val entries = EurekaLootConfig.LOOT.crewmanTrades[level.toString()] ?: return emptyList()
        return entries.filter { it.weight > 0.0 && (it.type == "balloon" || itemOf(it.item) != null) }
    }

    /** Weighted pick. Weights are floored at zero by [poolFor], so the total is always positive here. */
    private fun roll(pool: List<EurekaLootConfig.TradeEntry>, rng: RandomSource): EurekaLootConfig.TradeEntry {
        val total = pool.sumOf { it.weight }
        var r = rng.nextDouble() * total
        for (entry in pool) {
            r -= entry.weight
            if (r < 0.0) return entry
        }
        return pool.last()
    }

    private fun offerOf(entry: EurekaLootConfig.TradeEntry, rng: RandomSource): MerchantOffer {
        val emeralds = entry.emeralds.coerceAtLeast(1)
        val count = entry.count.coerceAtLeast(1)
        val uses = entry.maxUses.coerceAtLeast(1)
        val xp = entry.xp.coerceAtLeast(0)

        return when (entry.type) {
            "buy" -> MerchantOffer(
                ItemCost(itemOf(entry.item)!!, count), ItemStack(Items.EMERALD, emeralds),
                uses, xp, PRICE_MULTIPLIER
            )
            "balloon" -> MerchantOffer(
                ItemCost(Items.EMERALD, emeralds), ItemStack(rollBalloon(rng)),
                uses, xp, PRICE_MULTIPLIER
            )
            else -> MerchantOffer(
                ItemCost(Items.EMERALD, emeralds), ItemStack(itemOf(entry.item)!!, count),
                uses, xp, PRICE_MULTIPLIER
            )
        }
    }

    /** The one line that differs between the trees: 1.21.11 spells it Identifier. */
    private fun itemOf(id: String): Item? {
        if (id.isBlank()) return null
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)
    }

    /**
     * Which balloon a 'balloon' entry hands over: any of the seventeen, with equal chance.
     *
     * There used to be a weighted colour table here -- one dominant plain, a band of naturals, a band of
     * dyes, three you would hardly ever see -- and it was a second rarity system sitting above the one the
     * trade entries already have. Two knobs for one question is a knob too many: the entry's own weight
     * says how often a balloon comes up at all, and which colour it happens to be is not a difficulty
     * setting. A captain who wants a particular colour dyes it.
     *
     * Rolled when the villager's offer list is built and never again, so a crewman selling magenta goes on
     * selling magenta. That was always the promise and it survives the simplification.
     */
    private fun rollBalloon(rng: RandomSource): Block = COLOURS[rng.nextInt(COLOURS.size)].get()

    /**
     * The listings a Crewman of [level] can draw from.
     *
     * Two identical draws rather than one entry per trade -- see the class note. A level whose config table
     * is empty registers nothing, which is what lets a pack delete a whole rank's worth of trades and get
     * silence rather than a fallback.
     */
    @JvmStatic
    fun listings(level: Int): List<VillagerTrades.ItemListing> =
        if (poolFor(level).isEmpty()) emptyList() else List(DRAWN) { Drawn(level) }

    /** Novice through Master. */
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 5

    /** Vanilla's usual demand sensitivity; a heavily-traded offer creeps up in price and settles back. */
    private const val PRICE_MULTIPLIER = 0.05f
}
