package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.VillagerTrades
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaLootConfig

/**
 * What a Crewman sells: Eureka's own hardware, plus the Heart of the Sea that buys berths.
 *
 * The listings live here in :common because `VillagerTrades.ItemListing`, `MerchantOffer` are
 * all vanilla (1.20.1 offers cost plain ItemStacks). Only the registration call needs Fabric API, and that is in `CrewRegistrationsFabric`.
 *
 * Vanilla's own `EmeraldForItems` and `ItemsForEmeralds` are package-private, so they cannot be constructed
 * from here. [Sell] and [Buy] below are the two-line replacements. Writing them beats access-widening two
 * package-private vanilla internals: the balloon lottery needs a bespoke listing regardless, so this is one
 * file rather than a file plus an access-widener entry that would have to survive every mapping bump.
 */
object CrewTrades {

    /** Emeralds in, goods out. */
    private class Sell(
        private val emeralds: Int,
        private val maxUses: Int,
        private val xp: Int,
        private val result: () -> ItemStack
    ) : VillagerTrades.ItemListing {
        override fun getOffer(trader: Entity, rng: RandomSource): MerchantOffer =
            MerchantOffer(ItemStack(Items.EMERALD, emeralds), result(), maxUses, xp, PRICE_MULTIPLIER)
    }

    /** Goods in, emeralds out -- the trades that let a crewman be levelled up in the first place. */
    private class Buy(
        private val item: ItemLike,
        private val count: Int,
        private val emeralds: Int,
        private val maxUses: Int,
        private val xp: Int
    ) : VillagerTrades.ItemListing {
        override fun getOffer(trader: Entity, rng: RandomSource): MerchantOffer =
            MerchantOffer(ItemStack(item, count), ItemStack(Items.EMERALD, emeralds), maxUses, xp, PRICE_MULTIPLIER)
    }

    /**
     * One trade slot, seventeen possible balloons, the colour decided when the offer is generated.
     *
     * Seventeen separate listings would not merely be untidy, it would not work. `Villager.updateTrades` draws
     * two listings at random from the level's pool, so seventeen balloon entries would crowd every other
     * Eureka item out of that level AND make every colour exactly as likely as every other -- the rarity could
     * not be expressed at all. One listing takes one slot and holds the lottery inside itself.
     *
     * The roll happens once, when the villager's offer list is built, and NOT again on restock. So a crewman
     * selling magenta balloons goes on selling magenta balloons: a rare colour is a trader you found, rather
     * than a slot machine you pull until it pays.
     */
    private class BalloonForEmeralds(
        private val emeralds: Int,
        private val maxUses: Int,
        private val xp: Int
    ) : VillagerTrades.ItemListing {
        override fun getOffer(trader: Entity, rng: RandomSource): MerchantOffer {
            val table = weightedBalloons()
            val total = table.sumOf { it.second }
            var roll = rng.nextInt(total)
            val block = table.first { roll -= it.second; roll < 0 }.first
            return MerchantOffer(
                ItemStack(Items.EMERALD, emeralds), ItemStack(block), maxUses, xp, PRICE_MULTIPLIER
            )
        }
    }

    /**
     * The colour table, rebuilt per offer so a config edit takes effect on the next villager rather than
     * needing a restart. Shaped after vanilla sheep: one dominant plain, a band of naturals, a band of dyes,
     * and three that you will hardly ever see.
     *
     * Weights are read defensively -- a zero or negative weight in the config would otherwise make
     * `nextInt(total)` throw on a live server.
     */
    private fun weightedBalloons(): List<Pair<Block, Int>> {
        // The lottery moved to the loot table file with the rest of the economy; still read live per
        // offer, so an edit takes effect on the next villager rather than needing a restart.
        val cfg = EurekaLootConfig.LOOT.balloonTrade
        val plain = cfg.plain.coerceAtLeast(0)
        val natural = cfg.natural.coerceAtLeast(0)
        val dyed = cfg.dyed.coerceAtLeast(0)
        val rare = cfg.rare.coerceAtLeast(0)

        val table = mutableListOf<Pair<Block, Int>>()
        if (plain > 0) table += EurekaBlocks.BALLOON.get() to plain
        if (natural > 0) {
            table += EurekaBlocks.WHITE_BALLOON.get() to natural
            table += EurekaBlocks.LIGHT_GRAY_BALLOON.get() to natural
            table += EurekaBlocks.GRAY_BALLOON.get() to natural
            table += EurekaBlocks.BLACK_BALLOON.get() to natural
            table += EurekaBlocks.BROWN_BALLOON.get() to natural
        }
        if (dyed > 0) {
            table += EurekaBlocks.RED_BALLOON.get() to dyed
            table += EurekaBlocks.ORANGE_BALLOON.get() to dyed
            table += EurekaBlocks.YELLOW_BALLOON.get() to dyed
            table += EurekaBlocks.LIME_BALLOON.get() to dyed
            table += EurekaBlocks.GREEN_BALLOON.get() to dyed
            table += EurekaBlocks.LIGHT_BLUE_BALLOON.get() to dyed
            table += EurekaBlocks.CYAN_BALLOON.get() to dyed
            table += EurekaBlocks.BLUE_BALLOON.get() to dyed
        }
        if (rare > 0) {
            table += EurekaBlocks.PURPLE_BALLOON.get() to rare
            table += EurekaBlocks.MAGENTA_BALLOON.get() to rare
            table += EurekaBlocks.PINK_BALLOON.get() to rare
        }
        // Every weight zeroed would leave nothing to roll; fall back to the plain balloon rather than throw.
        return table.ifEmpty { listOf(EurekaBlocks.BALLOON.get() to 1) }
    }

    /**
     * The listings a Crewman of [level] can draw from. Two are drawn, so every level carries at least three
     * or the pool is not a pool.
     *
     * The Heart of the Sea is Expert and Master only, on purpose: it is the currency berths are bought with,
     * and going from four crew to a full sixty-four costs sixty of them. It should be a voyage, not an errand.
     */
    @JvmStatic
    fun listings(level: Int): List<VillagerTrades.ItemListing> = when (level) {
        1 -> listOf(
            Buy(Items.KELP, 32, 1, 16, 2),
            Buy(Items.STRING, 20, 1, 16, 2),
            Sell(1, 16, 1) { ItemStack(EurekaBlocks.FLOATER.get(), 4) },
            Sell(2, 12, 1) { ItemStack(EurekaBlocks.BALLAST.get()) }
        )

        2 -> listOf(
            Buy(Items.COAL, 16, 1, 16, 5),
            Sell(12, 5, 5) { ItemStack(EurekaBlocks.ANCHOR.get()) },
            BalloonForEmeralds(6, 8, 5)
        )

        3 -> listOf(
            Buy(Items.COPPER_INGOT, 8, 1, 12, 10),
            Sell(20, 4, 10) { ItemStack(EurekaBlocks.ENGINE.get()) },
            BalloonForEmeralds(8, 6, 10)
        )

        4 -> listOf(
            Buy(Items.PRISMARINE_CRYSTALS, 6, 1, 12, 15),
            Sell(24, 3, 15) { ItemStack(EurekaBlocks.OAK_SHIP_HELM.get()) },
            Sell(32, 2, 15) { ItemStack(Items.HEART_OF_THE_SEA) }
        )

        5 -> listOf(
            Sell(24, 3, 30) { ItemStack(Items.HEART_OF_THE_SEA) },
            Sell(32, 3, 30) { ItemStack(EurekaBlocks.ENGINE.get(), 2) },
            BalloonForEmeralds(12, 4, 30)
        )

        else -> emptyList()
    }

    /** Novice through Master. */
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 5

    /** Vanilla's usual demand sensitivity; a heavily-traded offer creeps up in price and settles back. */
    private const val PRICE_MULTIPLIER = 0.05f
}
