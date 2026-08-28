package org.valkyrienskies.eureka.crew

import org.valkyrienskies.eureka.util.nbt.*
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.entity.npc.VillagerType
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A crew member kept in writing, for when the villager themselves cannot be found.
 *
 * Mustering would rather move the REAL villager -- that keeps their identity, their memories and anything
 * another mod has attached to them. But a crew member can be somewhere no amount of searching will reach: an
 * unloaded chunk, a dimension nobody is standing in, or simply dead. A crew that quietly shrank every time
 * somebody wandered out of render distance would not be a crew, so the articles carry enough to rebuild them.
 *
 * What is stored is the villager's whole entity NBT, not a chosen list of fields. Trades, level, experience,
 * profession, biome type, custom name, gossip and inventory all come back because none of them were ever
 * singled out -- and anything a future version of the game or another mod adds comes back too, without this
 * file having to learn about it.
 */
object CrewSnapshot {

    /**
     * Everything needed to rebuild [villager] later, or null if they cannot be written down.
     *
     * Taken opportunistically whenever a crew member is in hand, so the copy on file is rarely far behind the
     * real one. It is a fallback, not a mirror: the villager is always preferred while they exist.
     */
    fun capture(villager: Villager): CompoundTag? =
        try {
            val output = CompoundTag()
            villager.saveWithoutId(output)
            output.also { writePapers(it, villager) }
        } catch (ex: Exception) {
            null
        }

    /**
     * The few facts about a crew member the manifest needs in order to list somebody who is not here.
     *
     * Written alongside the entity NBT rather than read back out of it. The game is free to change how it
     * spells a villager's profession on disk between versions, and a screen that guessed at those key names
     * would show a blank row the first time it guessed wrong -- whereas these keys are ours, so what is written
     * is exactly what is read. [restore] ignores them, because an unknown field is simply a field the entity
     * loader is not looking for.
     */
    data class Papers(val profession: String, val type: String, val level: Int, val xp: Int)

    private fun writePapers(tag: CompoundTag, villager: Villager) {
        val data = villager.villagerData
        val papers = CompoundTag()
        papers.putString(PROFESSION_KEY, idOf(data.profession, NO_PROFESSION))
        papers.putString(TYPE_KEY, idOf(data.type, DEFAULT_TYPE))
        papers.putInt(LEVEL_KEY, data.level)
        papers.putInt(XP_KEY, villager.villagerXp)
        tag.put(PAPERS_KEY, papers)
    }

    /** What [tag] says about a crew member, or null for a copy written before papers existed. */
    fun papers(tag: CompoundTag?): Papers? {
        val papers = tag?.getCompoundOpt(PAPERS_KEY)?.orElse(null) ?: return null
        return Papers(
            profession = papers.getStringOpt(PROFESSION_KEY).orElse(NO_PROFESSION),
            type = papers.getStringOpt(TYPE_KEY).orElse(DEFAULT_TYPE),
            level = papers.getIntOpt(LEVEL_KEY).orElse(0),
            xp = papers.getIntOpt(XP_KEY).orElse(0)
        )
    }

    // 1.21.1: VillagerData hands out the objects directly (the modern branch wraps them in Holders),
    // so the id comes off the registry instead of the holder's key.
    private fun idOf(profession: VillagerProfession, fallback: String): String =
        BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession)?.toString() ?: fallback

    private fun idOf(type: VillagerType, fallback: String): String =
        BuiltInRegistries.VILLAGER_TYPE.getKey(type)?.toString() ?: fallback

    /** Namespaced so it cannot collide with anything the entity itself writes. */
    private const val PAPERS_KEY = "vs_eureka:papers"
    private const val PROFESSION_KEY = "profession"
    private const val TYPE_KEY = "type"
    private const val LEVEL_KEY = "level"
    private const val XP_KEY = "xp"

    const val NO_PROFESSION = "minecraft:none"
    const val DEFAULT_TYPE = "minecraft:plains"

    /**
     * Rebuild a crew member from [tag] and put them on the deck at [at].
     *
     * Given a NEW uuid on purpose. The original may still exist in a chunk nobody has loaded, and two entities
     * sharing a uuid is the one outcome worse than a missing crew member -- so the copy is a new individual
     * and the old id is tombstoned (see [CrewLedger.tombstone]) to be removed the moment it turns up. The
     * caller re-files the berth under the new id.
     *
     * Position and motion are overwritten after the load rather than trusted from the tag: the snapshot
     * records where they were standing when it was taken, which is exactly where they must NOT reappear.
     */
    fun restore(level: ServerLevel, tag: CompoundTag, at: Vec3): Villager? =
        try {
            val villager = EntityType.VILLAGER.create(level)
            if (villager == null) null else {
                villager.load(tag)
                villager.uuid = UUID.randomUUID()
                villager.setPos(at.x, at.y, at.z)
                villager.deltaMovement = Vec3.ZERO
                villager.fallDistance = 0.0f
                if (level.addFreshEntity(villager)) villager else null
            }
        } catch (ex: Exception) {
            null
        }
}
