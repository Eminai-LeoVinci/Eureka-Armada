package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipBearing
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.util.logger

/**
 * Which wheel holds a ship's articles, and how to find it from either end.
 *
 * A ship may carry as many helms as its builder likes. Every one of them steers, every one of them is a
 * villager job site -- and exactly ONE of them holds the crew. That is the point of the rule: bolting on a
 * second wheel must not buy a second set of berths.
 *
 * The station is claimed by the helm that assembles the ship, which is "the first helm wins" said plainly, and
 * its shipyard position is kept on [EurekaShipControl] so finding the roster is one block-entity lookup rather
 * than a walk of the hull.
 */
object CrewStations {

    private val logger by logger()

    /**
     * The helm holding [ship]'s articles, or null if it has none.
     *
     * Null is a real and expected answer, not a failure: a ship assembled before this feature existed has no
     * recorded station, and neither does one whose station has been mined out. Both are fixed the same way --
     * aim at a wheel and press the crew key, which claims it. See [claim].
     *
     * ## A miss heals instead of failing
     * The recorded address is a position, and positions can be WRONG as well as stale -- an assembly that
     * recorded its station one block off left a ship whose named wheel stood right there while every lookup
     * came back empty, and the "no station, claim what you aim at" fallback then handed the articles to a
     * blank spare. So a readable miss is treated as a bookkeeping error, not a fact: sweep the hull's own
     * chunks for its wheels, prefer the one that is flagged and named, repair the address, and answer with
     * it. An UNREADABLE address -- shipyard chunks still settling after an assembly -- returns null without
     * repairing anything, because silence is not evidence.
     */
    fun stationOf(level: ServerLevel, ship: LoadedServerShip): ShipHelmBlockEntity? {
        val control = ship.getAttachment(EurekaShipControl::class.java) ?: return null
        val packed = control.crewStationPos
        if (packed != EurekaShipControl.NO_CREW_STATION) {
            val pos = BlockPos.of(packed)
            if (!level.hasChunkAt(pos)) return null
            val helm = level.getBlockEntity(pos) as? ShipHelmBlockEntity
            // A helm that was mined and replaced by a different block -- or by a NEW helm, which is a
            // different set of articles -- must not be mistaken for the one that was claimed.
            if (helm != null && helm.isCrewStation) return helm
        }

        val wheels = helmsAboard(level, ship) ?: return null
        val chosen = wheels.firstOrNull { it.isCrewStation && it.helmName != null }
            ?: wheels.firstOrNull { it.isCrewStation }
            ?: wheels.firstOrNull { it.helmName != null }
            ?: return null
        control.crewStationPos = chosen.blockPos.asLong()
        if (!chosen.isCrewStation) {
            chosen.isCrewStation = true
            chosen.setChanged()
        }
        // One station per ship, restored in the same pass: a stale flag left on another wheel would shadow
        // this one the next time anything scores or sweeps the hull.
        for (other in wheels) {
            if (other !== chosen && other.isCrewStation) {
                other.isCrewStation = false
                other.setChanged()
            }
        }
        logger.info(
            "[crew] station healed ship=${ship.id} -> ${chosen.blockPos} ('${chosen.helmName?.string ?: "(unnamed)"}')"
        )
        return chosen
    }

    /**
     * Confirm [station] as [ship]'s one crew station: clear the flag on every other wheel aboard, and say
     * where it landed. Called from the assembly once the shipyard is readable -- the wheel flagged itself
     * before relocation ("the first helm wins"), but nothing until now unflagged the wheels that DIDN'T
     * win, and hulls carrying several old claims scored template releases against the wrong one.
     */
    fun settle(level: ServerLevel, ship: LoadedServerShip, station: BlockPos) {
        val wheels = helmsAboard(level, ship) ?: emptyList()
        for (wheel in wheels) {
            if (wheel.blockPos != station && wheel.isCrewStation) {
                wheel.isCrewStation = false
                wheel.setChanged()
            }
        }
        val holder = level.getBlockEntity(station) as? ShipHelmBlockEntity
        logger.info(
            "[crew] station ship=${ship.id} at $station holds=" +
                if (holder == null) "NOT A HELM (${level.getBlockState(station).block})"
                else "'${holder.helmName?.string ?: "(unnamed)"}'"
        )
    }

    /**
     * Every wheel aboard [ship], or null while its chunks are not readable. The [ShipGuns] chunk-walk,
     * asked for helms: a hull carries a handful at most, and this runs only on misses and at assembly.
     */
    internal fun helmsAboard(level: ServerLevel, ship: LoadedServerShip): List<ShipHelmBlockEntity>? {
        val aabb = ship.shipAABB ?: return null
        val wheels = ArrayList<ShipHelmBlockEntity>()
        val minChunkX = aabb.minX() shr 4
        val maxChunkX = aabb.maxX() shr 4
        val minChunkZ = aabb.minZ() shr 4
        val maxChunkZ = aabb.maxZ() shr 4
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                // getChunkNow, never getChunk: a ship's own chunks are loaded for as long as the ship is,
                // so a miss here means a chunk that is not part of this hull and must not be forced in.
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (blockEntity in chunk.blockEntities.values) {
                    if (blockEntity !is ShipHelmBlockEntity) continue
                    val pos = blockEntity.blockPos
                    if (pos.x < aabb.minX() || pos.x > aabb.maxX() ||
                        pos.y < aabb.minY() || pos.y > aabb.maxY() ||
                        pos.z < aabb.minZ() || pos.z > aabb.maxZ()
                    ) continue
                    wheels.add(blockEntity)
                }
            }
        }
        return wheels
    }

    /**
     * "n of total" for the wheel at [pos] on its own hull, packed for the helm menu's synced int.
     *
     * Counted per HULL rather than armada-wide, unlike the guns and holds. A wheel is a hull's control point
     * and an armada child keeps its own -- "Helm: 1/1" on a tender under tow is the true and useful answer,
     * where numbering it 7 of 12 across a whole fleet would tell a captain nothing they could act on.
     *
     * Reads through [ShipBearing] so a wheel counts from the bow like everything else, and falls back to the
     * raw sweep order on a hull with no claimed station -- which is exactly the hull where a captain is most
     * likely to be looking at wheels and deciding which to claim.
     */
    fun numberAt(level: ServerLevel, ship: LoadedServerShip, pos: BlockPos): Int {
        val wheels = helmsAboard(level, ship) ?: return 0
        val ordered = ShipBearing.flatRunOrder(ShipBearing.forwardOf(level, ship), wheels) { it.blockPos }
        val index = ordered.indexOfFirst { it.blockPos == pos }
        return ShipBearing.packNumber(index + 1, ordered.size)
    }

    /**
     * Make [helm] the crew station of the ship it is part of, taking the role from whatever held it before.
     *
     * Called when a player aims at a wheel and presses the crew key on a ship that has no station -- because
     * the original was destroyed, or because the ship predates the feature. Deliberately does NOT move the
     * roster: losing the wheel is meant to tear up the articles, so the new station starts empty and the crew
     * is picked again. Returns false when the helm is not on a ship at all, which is the one case with no ship
     * to record the claim against.
     */
    fun claim(level: ServerLevel, helm: ShipHelmBlockEntity): Boolean {
        val ship = shipOf(level, helm) ?: return false
        val control = EurekaShipControl.getOrCreate(ship)

        // Stand down the previous holder if it still exists, so a ship can never present two rosters.
        val previous = stationOf(level, ship)
        if (previous != null && previous !== helm) {
            previous.isCrewStation = false
            previous.crew.replaceAll(emptyList())
            previous.setChanged()
        }

        helm.isCrewStation = true
        helm.setChanged()
        control.crewStationPos = helm.blockPos.asLong()
        return true
    }

    /** The ship [helm] is part of. Helms live in the shipyard once assembled, so this is a plain position query. */
    fun shipOf(level: ServerLevel, helm: ShipHelmBlockEntity): LoadedServerShip? =
        level.getLoadedShipManagingPos(helm.blockPos) as? LoadedServerShip
}
