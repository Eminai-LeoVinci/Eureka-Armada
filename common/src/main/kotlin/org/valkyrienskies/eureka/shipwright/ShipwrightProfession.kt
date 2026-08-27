package org.valkyrienskies.eureka.shipwright

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
 * The Shipwright: a villager profession whose workstation is a Shipwright's Bench.
 *
 * Deliberately the plainest possible sibling of [org.valkyrienskies.eureka.crew.CrewProfession]. A helm is an
 * unusual job site -- it employs a whole crew, which is why it carries a ticket count in the dozens. A bench is
 * an ordinary one: **one** shipwright, like a lectern and its librarian. Everything interesting about this
 * profession is in what it lets you commission, not in how it is claimed.
 *
 * ## Where shipwrights come from
 * Nowhere in particular, and that is the design. Nothing spawns a shipwright and nothing checks whether a
 * villager is standing in a harbor: a villager becomes one by claiming a bench, and benches only exist in
 * harbors because they cannot be crafted. See [org.valkyrienskies.eureka.block.ShipwrightsBenchBlock].
 *
 * ## Not a trader
 * No trades are registered for this profession, and none should be. A shipwright is commissioned through its
 * own menu -- blueprints in, materials in instalments, a ship out -- and a vanilla trade screen can express
 * none of that. The profession exists to mark the villager and to give it a workstation to stand at.
 *
 * As with the Crewman, everything here is vanilla API and lives in :common; the POI registration needs Fabric
 * API and lives in the loader module.
 */
object ShipwrightProfession {

    @JvmField
    val POI_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "shipwrights_bench")

    @JvmField
    val POI_KEY: ResourceKey<PoiType> = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, POI_ID)

    @JvmField
    val PROFESSION_KEY: ResourceKey<VillagerProfession> = ResourceKey.create(
        Registries.VILLAGER_PROFESSION,
        ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "shipwright")
    )

    /** One bench, one entry -- kept as a function purely to mirror `CrewProfession.helmBlocks()`. */
    @JvmStatic
    fun benchBlocks(): Array<Block> = arrayOf(EurekaBlocks.SHIPWRIGHTS_BENCH.get())

    /**
     * Register the profession. Same two-predicate arrangement as the Crewman, for the same two reasons:
     * `acquirableJobSite` is read off a villager's current profession while it looks for work, and
     * `heldJobSite` is what tells the game a claimed bench means "shipwright". Both are key comparisons that
     * never resolve the Holder, so this and the POI can be registered in either order.
     */
    @JvmStatic
    fun registerProfession() {
        Registry.register(
            BuiltInRegistries.VILLAGER_PROFESSION,
            PROFESSION_KEY,
            VillagerProfession(
                "shipwright",
                { holder: Holder<PoiType> -> holder.`is`(POI_KEY) },
                { holder: Holder<PoiType> -> holder.`is`(POI_KEY) },
                // requestedItems: a shipwright is handed materials through its menu, never off the floor.
                ImmutableSet.of(),
                // secondaryPoi: the farmer's FARMLAND. The bench is the whole job.
                ImmutableSet.of(),
                // Vanilla has no carpenter, so the toolsmith's hammering stands in -- which is the right noise
                // for someone building a hull anyway. Same borrowing the Crewman does with the fisherman's.
                SoundEvents.VILLAGER_WORK_TOOLSMITH
            )
        )
    }
}
