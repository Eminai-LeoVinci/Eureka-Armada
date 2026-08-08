package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

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

    /**
     * The helm holding [ship]'s articles, or null if it has none.
     *
     * Null is a real and expected answer, not a failure: a ship assembled before this feature existed has no
     * recorded station, and neither does one whose station has been mined out. Both are fixed the same way --
     * aim at a wheel and press the crew key, which claims it. See [claim].
     */
    fun stationOf(level: ServerLevel, ship: LoadedServerShip): ShipHelmBlockEntity? {
        val control = ship.getAttachment(EurekaShipControl::class.java) ?: return null
        val packed = control.crewStationPos
        if (packed == EurekaShipControl.NO_CREW_STATION) return null
        val helm = level.getBlockEntity(BlockPos.of(packed)) as? ShipHelmBlockEntity ?: return null
        // The recorded address is a position, and positions outlive the things at them. A helm that was mined
        // and replaced by a different block -- or by a NEW helm, which is a different set of articles -- must
        // not be mistaken for the one that was claimed.
        return if (helm.isCrewStation) helm else null
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
