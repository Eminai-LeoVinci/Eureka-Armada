package org.valkyrienskies.eureka.fabric

import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import org.slf4j.LoggerFactory
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.fabric.mixin.MixinRandomSpreadAccess
import org.valkyrienskies.eureka.fabric.mixin.MixinSinglePoolElementAccess
import org.valkyrienskies.eureka.fabric.mixin.MixinStructurePlacementAccess
import org.valkyrienskies.eureka.fabric.mixin.MixinStructureSetAccess
import org.valkyrienskies.eureka.fabric.mixin.MixinTemplatePoolAccess
import org.valkyrienskies.eureka.util.WeightedNames

/**
 * Writes the pirate-ship worldgen config over the loaded datapack registries, once, as the server starts.
 *
 * How often raiders generate and which hulls they draw from are both vanilla WORLDGEN data, which normally
 * means a datapack override is the only way to change them -- an unreasonable ask for two numbers a server
 * owner will want to tune. The JSON under data/vs_eureka/worldgen stays the shipped default and is what a
 * fresh install generates with; this reads the config afterwards and, where it disagrees, wins.
 *
 * SERVER_STARTING is the moment for it. The dynamic registries are already built by then (they are loaded
 * with the world stem, before the server object exists), and no level has been created yet, so nothing has
 * had a chance to generate a chunk. Both targets are read live rather than precomputed -- a random-spread
 * placement consults its grid on every query, and a pool draws from its expanded list on every pick -- so
 * one write before the first chunk is all it takes.
 *
 * Session-scoped by nature, which is the honest behaviour: the registries are rebuilt per world load, so
 * these values are re-read every launch and nothing is baked into the save. Chunks ALREADY generated keep
 * the ships they were generated with; only new ground answers to a changed number.
 *
 * Every failure here is survivable and says so in the log rather than throwing: worst case the datapack
 * values stand, which are the ones the mod shipped with.
 */
object PirateWorldgen {

    private val LOGGER = LoggerFactory.getLogger("vs_eureka")

    private const val STRUCTURE_SET = "vs_eureka:pirate_ships"
    private const val TEMPLATE_POOL = "vs_eureka:pirate/ships"

    /** Vanilla's own ceiling on a pool weight -- its JSON codec is intRange(1, 150). */
    private const val MAX_WEIGHT = 150

    fun apply(server: MinecraftServer) {
        try {
            val enabled = EurekaConfig.SERVER.pirateShipsEnabled
            applyPlacement(server, enabled)
            if (enabled) applyHullMix(server)
        } catch (e: Throwable) {
            LOGGER.error("Could not apply pirate worldgen config; the datapack values stand.", e)
        }
    }

    /**
     * Whether raiders generate at all, and how far apart they sit when they do.
     *
     * The OFF switch is the reason this reads the enable flag rather than only the rarity numbers.
     * pirateShipsEnabled used to stop the runtime machinery alone -- adoption, zones, wake-up, pursuit --
     * and left the hulls themselves generating, because placement is datapack data and no code of ours runs
     * when one is placed. A server owner who turned pirates off still got pillager ships in every ocean,
     * their pillagers included, and had no way to stop them short of writing a datapack.
     *
     * Emptying the structure SET is what closes it, and the choice is load-bearing rather than incidental.
     * Zeroing the placement's frequency also stops generation and was tried first, but it leaves the
     * structure attached to a placement -- so "/locate structure vs_eureka:pirate_ship" still finds one to
     * search and then misses every candidate instead of hitting the first, pinning the server thread hard
     * enough to need killing. Empty the set and findNearestMapStructure has no placement to work from at
     * all, so locate answers instantly from its own first line. See MixinStructureSetAccess.
     *
     * Turning it back on simply means not emptying it: the registries are rebuilt from the datapack every
     * world load, so nothing is baked into the save either way. What the switch cannot do is remove ships
     * from ground that has ALREADY generated -- those chunks are written, and it only governs new ones.
     *
     * Separation is clamped BELOW spacing rather than merely validated, because vanilla divides by the
     * difference -- getPotentialStructureChunk calls nextInt(spacing - separation), which throws on a
     * non-positive bound. A config typo there would crash chunk generation, so it is corrected here.
     */
    private fun applyPlacement(server: MinecraftServer, enabled: Boolean) {
        val set = lookup(server, Registries.STRUCTURE_SET, STRUCTURE_SET) ?: run {
            LOGGER.warn("Structure set {} is missing; pirate worldgen config ignored.", STRUCTURE_SET)
            return
        }
        val placement = set.placement()

        if (!enabled) {
            // Detach the structure from its placement. Order matters only in that this is the one that also
            // fixes /locate; the frequency below is belt and braces for a set we somehow failed to empty.
            (set as MixinStructureSetAccess).vs_eureka_setStructures(emptyList<Any>())
            (placement as MixinStructurePlacementAccess).vs_eureka_setFrequency(0.0f)

            // Read it back rather than trusting the write. A silent failure here is not a quiet no-op --
            // it is the server-pinning /locate described in MixinStructureSetAccess -- so it must be
            // loud enough to act on.
            if (set.structures().isNotEmpty()) {
                LOGGER.error(
                    "Could not switch pirate worldgen off: {} still carries {} structure(s). " +
                        "Pirate ships will keep generating, and /locate for them may hang the server.",
                    STRUCTURE_SET, set.structures().size
                )
                return
            }
            LOGGER.info(
                "pirateShipsEnabled is off: no new pirate ships will generate. " +
                    "Ships in already-generated chunks stay where they are."
            )
            return
        }

        if (placement !is RandomSpreadStructurePlacement) {
            LOGGER.warn("Structure set {} is not random-spread; pirate rarity config ignored.", STRUCTURE_SET)
            return
        }

        val spacing = EurekaConfig.SERVER.pirateShipSpacing.coerceAtLeast(1)
        val separation = EurekaConfig.SERVER.pirateShipSeparation.coerceIn(0, spacing - 1)
        if (separation != EurekaConfig.SERVER.pirateShipSeparation) {
            LOGGER.warn(
                "pirateShipSeparation {} is not below pirateShipSpacing {}; using {} instead.",
                EurekaConfig.SERVER.pirateShipSeparation, spacing, separation
            )
        }

        val access = placement as MixinRandomSpreadAccess
        if (access.vs_eureka_getSpacing() == spacing && access.vs_eureka_getSeparation() == separation) return
        access.vs_eureka_setSpacing(spacing)
        access.vs_eureka_setSeparation(separation)
        LOGGER.info("Pirate ship rarity set from config: spacing {}, separation {} (chunks).", spacing, separation)
    }

    /**
     * Which hulls raiders are drawn from, and in what proportion -- the same pirateHulls list the regen
     * command already obeys, so the two can no longer drift apart.
     *
     * Re-weights the elements the pool ALREADY holds rather than building new ones: a hull has to exist as
     * a .nbt and be listed in the pool JSON before it can generate at all, and constructing pool elements
     * by hand differs enough between versions to be worth avoiding. A hull the config does not mention
     * keeps the weight the datapack gave it, so a partial list is not a silent deletion, and weight 0
     * excludes a hull outright.
     *
     * Refuses to empty the pool: a pool with nothing in it is not a quiet no-op, it is a crash the next
     * time the jigsaw draws from it.
     */
    private fun applyHullMix(server: MinecraftServer) {
        val pool = lookup(server, Registries.TEMPLATE_POOL, TEMPLATE_POOL) ?: run {
            LOGGER.warn("Template pool {} is missing; pirateHulls weights ignored.", TEMPLATE_POOL)
            return
        }
        val access = pool as MixinTemplatePoolAccess
        val templates = access.vs_eureka_getTemplates()
        if (templates.isEmpty()) return

        // The expanded list, folded back into element -> weight in first-seen order.
        val datapack = LinkedHashMap<StructurePoolElement, Int>()
        for (element in templates) datapack[element] = (datapack[element] ?: 0) + 1

        val configured = EurekaConfig.SERVER.pirateHulls
            .map { WeightedNames.parse(it) }
            .associate { (name, weight) -> bare(name) to weight }

        val wanted = LinkedHashMap<StructurePoolElement, Int>()
        var matched = 0
        for ((element, datapackWeight) in datapack) {
            val name = nameOf(element)
            val weight = if (name != null && configured.containsKey(name)) {
                matched++
                configured.getValue(name).toInt()
            } else {
                datapackWeight
            }
            wanted[element] = weight.coerceIn(0, MAX_WEIGHT)
        }

        if (matched == 0) {
            LOGGER.warn(
                "No pirateHulls entry names a hull in {}; the datapack weights stand. " +
                    "Names must match the pool's template ids, e.g. pirate/pilpirsmall1.",
                TEMPLATE_POOL
            )
            return
        }
        if (wanted.values.all { it == 0 }) {
            LOGGER.warn("pirateHulls would leave no hull able to generate; the datapack weights stand.")
            return
        }
        if (wanted == datapack) return

        templates.clear()
        for ((element, weight) in wanted) repeat(weight) { templates.add(element) }
        // The largest-piece cache was measured against the old list.
        access.vs_eureka_setMaxSize(Int.MIN_VALUE)

        LOGGER.info(
            "Pirate hull mix set from config: {}.",
            wanted.entries.joinToString { (element, weight) -> (nameOf(element) ?: "?") + " x" + weight }
        )
    }

    /** The element's template id, namespace stripped, or null for a piece not loaded from a file. */
    private fun nameOf(element: StructurePoolElement): String? {
        if (element !is SinglePoolElement) return null
        val template = (element as MixinSinglePoolElementAccess).vs_eureka_getTemplate().left()
        return if (template.isPresent) bare(template.get().toString()) else null
    }

    /** "vs_eureka:pirate/brig" and "pirate/brig" are the same hull to a config author. */
    private fun bare(id: String): String = id.substringAfter(':')

    /**
     * One entry out of a dynamic registry by its id.
     *
     * Walked rather than looked up by key so that no ResourceLocation has to be built -- the class is
     * spelled differently across the versions this file is shared with, and a pirate registry holds only a
     * handful of entries. The key accessor itself is one of those spellings: location() here, identifier()
     * on 1.21.11.
     */
    private fun <T : Any> lookup(server: MinecraftServer, registry: ResourceKey<Registry<T>>, id: String): T? {
        val entries = server.registryAccess().lookupOrThrow(registry).listElements().iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key().location().toString() == id) return entry.value()
        }
        return null
    }
}
