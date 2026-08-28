package org.valkyrienskies.eureka.crew

import com.google.common.collect.ImmutableSet
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.level.block.Block
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaMod

/**
 * The Crewman: a villager profession whose workstation is a ship helm.
 *
 * A helm is a job site like a barrel or a fletching table, with one difference that is the whole point --
 * it employs a CREW rather than a shopkeeper. That is a plain [PoiType] ticket count (see
 * `EurekaConfig.SERVER.crewmanHelmPoiTickets`), not a mechanism of our own: vanilla's MEETING point already
 * ships 32 tickets, so nothing here is novel to Minecraft, only to job sites.
 *
 * Everything in this file is vanilla API and therefore lives in :common. The two calls that genuinely need
 * Fabric API -- registering the POI (vanilla's `PoiTypes.register` is private, and also populates a private
 * state -> type map) and registering the trades -- are in `CrewRegistrationsFabric`.
 */
object CrewProfession {

    @JvmField
    val POI_ID: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "ship_helm")

    // @JvmField on these two so the villager mixins, which are Java, can write CrewProfession.POI_KEY rather
    // than CrewProfession.INSTANCE.getPOI_KEY(). They are read on hot-ish villager AI paths; a plain static
    // field read is also the cheapest thing they could be.
    @JvmField
    val POI_KEY: ResourceKey<PoiType> = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, POI_ID)

    @JvmField
    val PROFESSION_KEY: ResourceKey<VillagerProfession> = ResourceKey.create(
        Registries.VILLAGER_PROFESSION,
        ResourceLocation(EurekaMod.MOD_ID, "crewman")
    )

    /**
     * Every helm block, in one place so the POI and the `acquirable_job_site` tag cannot drift apart.
     *
     * The wheel is deliberately absent: `ship_helm_wheel` is a virtual block with no item, placed only so the
     * renderer has a blockstate to bake spinning models from. A villager cannot stand at one.
     */
    @JvmStatic
    fun helmBlocks(): Array<Block> = arrayOf(
        EurekaBlocks.OAK_SHIP_HELM.get(),
        EurekaBlocks.SPRUCE_SHIP_HELM.get(),
        EurekaBlocks.BIRCH_SHIP_HELM.get(),
        EurekaBlocks.JUNGLE_SHIP_HELM.get(),
        EurekaBlocks.ACACIA_SHIP_HELM.get(),
        EurekaBlocks.DARK_OAK_SHIP_HELM.get(),
        EurekaBlocks.CRIMSON_SHIP_HELM.get(),
        EurekaBlocks.WARPED_SHIP_HELM.get()
    )

    /**
     * Register the profession itself. Call after [EurekaBlocks.register] -- not because this code touches a
     * block (it doesn't), but so the whole crew subsystem comes up in one predictable order.
     *
     * Vanilla's four `VillagerProfession.register` overloads and `createKey` are all PRIVATE, so the record is
     * constructed by hand. That is fine: the constructor is public and this mirrors what the private helper
     * does anyway.
     *
     * The two predicates look identical and are not redundant -- they are consulted by different code:
     *
     *  - `acquirableJobSite` is read off a villager's CURRENT profession while it hunts for work. An unemployed
     *    villager's profession is `none`, whose predicate is `ALL_ACQUIRABLE_JOBS` = "is in the
     *    minecraft:acquirable_job_site tag". So it is the TAG, not this predicate, that sends an unemployed
     *    villager to a helm; this one only matters for a Crewman who has lost its helm and wants another.
     *  - `heldJobSite` is what `AssignProfessionFromJobSite` scans the entire profession registry with, to work
     *    out which profession a claimed POI grants. So THIS is what makes a helm mean "Crewman".
     *
     * Both are `holder.is(POI_KEY)` -- a key comparison that never resolves the Holder -- so the profession and
     * the POI can be registered in either order.
     */
    @JvmStatic
    fun registerProfession() {
        Registry.register(
            BuiltInRegistries.VILLAGER_PROFESSION,
            PROFESSION_KEY,
            VillagerProfession(
                "crewman",
                { holder: Holder<PoiType> -> holder.`is`(POI_KEY) },
                { holder: Holder<PoiType> -> holder.`is`(POI_KEY) },
                // requestedItems: what the villager picks up off the floor, farmer-style. A crewman hauls
                // nothing of its own.
                ImmutableSet.of(),
                // secondaryPoi: the farmer's FARMLAND. A helm is the whole job.
                ImmutableSet.of(),
                // A plain nullable SoundEvent in 1.21.11, not a Holder. The fisherman's work sound suits a
                // deckhand and matches the outfit the texture is built from.
                SoundEvents.VILLAGER_WORK_FISHERMAN
            )
        )
    }
}
