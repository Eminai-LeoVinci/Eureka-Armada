package org.valkyrienskies.eureka.bottle

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * Where every bottle-bound wheel is RIGHT NOW.
 *
 * A marked Ship Bottle used to remember its wheel by shipyard position, and a shipyard position is the
 * least durable name a ship has: capture deletes the ship, release builds a new one on a new chunk claim,
 * and every sibling bottle marked before the cycle was left holding an address into dead space. The durable
 * name is the binding UUID on the helm block entity itself, because helm NBT rides the bottle's template
 * through capture and release -- the same ride that brings the ship's name back.
 *
 * This object is the lookup from that durable name to a current position, and it is deliberately NOT
 * persisted: the helm NBT is the persistence. Every wheel that carries a binding reports its address once
 * per tick ([org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity.tick]), so the map heals itself on
 * chunk load, after a release, after a restart -- with no release-side bookkeeping anywhere, which is what
 * makes a bottle sitting in a chest keep working.
 *
 * An entry can be stale (a mined wheel stops reporting but is not erased), so a reader must verify the
 * block entity at the answer still carries the binding -- positions outlive the things at them. That check
 * lives in [ShipBottle.resolveMarkedHelm], the one reader.
 */
object BottleBindings {

    private data class Address(val dimension: ResourceKey<Level>, val pos: BlockPos)

    private val addresses = ConcurrentHashMap<UUID, Address>()

    /** Called by a bound wheel every tick. A same-address report writes nothing. */
    fun report(level: ServerLevel, binding: UUID, pos: BlockPos) {
        val current = addresses[binding]
        if (current != null && current.dimension == level.dimension() && current.pos == pos) return
        addresses[binding] = Address(level.dimension(), pos.immutable())
    }

    /**
     * The last-reported position of the wheel carrying [binding], or null if it has not reported in this
     * server's lifetime or lives in another dimension. Null reads as "not loaded anywhere nearby", which is
     * the truth: an unloaded wheel has not had the chance to say where it is.
     */
    fun find(level: ServerLevel, binding: UUID): BlockPos? {
        val address = addresses[binding] ?: return null
        if (address.dimension != level.dimension()) return null
        return address.pos
    }
}
