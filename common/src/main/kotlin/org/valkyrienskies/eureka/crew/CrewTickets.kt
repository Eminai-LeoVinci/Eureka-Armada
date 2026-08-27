package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * Gives a helm back the berths that vanilla's job-site code took and never returned.
 *
 * A point of interest hands out a fixed number of TICKETS -- 32 for a helm, one for a barrel -- and a villager
 * takes one when it claims the job. Two vanilla behaviours used to erase a villager's job-site memory without
 * ever putting the ticket back (see `MixinPoiCompetitorScan` and `MixinYieldJobSite`), so a helm that villagers
 * cycled past would quietly lose berths until it had none left, at which point `Occupancy.HAS_SPACE` rejects it
 * and it stops attracting anybody at all. The leak itself is fixed. **The damage is not**: ticket counts live in
 * the saved POI record, so a helm drained before that fix stays drained forever, and the only remedy was to
 * break the block and place it again.
 *
 * This is the remedy that does not cost the player their wheel. The count of taken tickets is compared against
 * the villagers who genuinely hold this helm as their job site, and the difference -- tickets held by nobody --
 * is released. It can only ever return phantom berths: a real crewman is found and their ticket left alone.
 *
 * It is also a standing safety net. Any future path that erases a job site without releasing its ticket is now
 * self-correcting rather than permanent, which matters for a POI type whose whole premise is that it employs a
 * crew rather than a shopkeeper.
 */
object CrewTickets {

    /**
     * Repair [helm]'s ticket count if it has run low. Returns how many phantom berths were handed back.
     *
     * The cheap test comes first and almost always ends it: reading the record is a section lookup, and a helm
     * with berths to spare is not the one with the problem. Only a helm that is nearly full pays for the
     * villager sweep -- which is the same helm that is about to stop working, so the cost lands exactly where
     * it buys something.
     */
    fun heal(level: ServerLevel, helm: BlockPos): Int {
        val poi = level.poiManager
        // `is` is a Kotlin keyword, so Holder.is() only reachable in backticks -- the same trap the Fabric
        // object-builder import package springs.
        val record = poi.getInChunk({ it.`is`(CrewProfession.POI_KEY) }, ChunkPos(helm), PoiManager.Occupancy.ANY)
            .filter { it.pos == helm }
            .findFirst()
            .orElse(null) ?: return 0

        val free = record.freeTickets
        if (free > LOW_WATER_MARK) return 0

        val taken = record.poiType.value().maxTickets() - free
        if (taken <= 0) return 0

        val phantom = taken - employedAt(level, helm)
        if (phantom <= 0) return 0

        var released = 0
        // release() gives back one ticket and reports whether there was one to give, so it cannot be pushed
        // below zero even if the count moved under us between the read and here.
        while (released < phantom && poi.release(helm)) released++
        return released
    }

    /**
     * How many villagers actually hold [helm] as their job site.
     *
     * The search is a WORLD-space box around where the wheel really is, because that is where the villagers
     * are -- a helm on an assembled ship is filed at shipyard coordinates but its crew walk the deck at
     * ordinary ones. What is compared is the other way round: a villager's `JOB_SITE` memory stores the POI's
     * own position, so it is matched against the shipyard address, untransformed.
     *
     * The box is generous on purpose. A villager who has wandered to the far end of a big ship still holds
     * their ticket, and counting them short would hand their berth to somebody else.
     */
    private fun employedAt(level: ServerLevel, helm: BlockPos): Int {
        val world = level.toWorldCoordinates(helm)
        val box = AABB.ofSize(world, SEARCH_SIZE, SEARCH_SIZE, SEARCH_SIZE)
        return level.getEntitiesOfClass(Villager::class.java, box) { villager ->
            villager.brain.getMemory(MemoryModuleType.JOB_SITE)
                .map { it.pos() == helm }
                .orElse(false)
        }.size
    }

    /**
     * How few free berths it takes before a helm is worth repairing.
     *
     * Not zero: a helm on its last berth is already failing to do its job for everyone but one villager, and
     * waiting for the count to hit exactly nothing would mean the sweep only ever runs on a wheel that has
     * already stopped working. Not high either -- above this, the helm is fine and the sweep is pure cost.
     */
    private const val LOW_WATER_MARK = 2

    /** Edge length of the villager search box, comfortably larger than any ship a crew walks around on. */
    private const val SEARCH_SIZE = 96.0
}
