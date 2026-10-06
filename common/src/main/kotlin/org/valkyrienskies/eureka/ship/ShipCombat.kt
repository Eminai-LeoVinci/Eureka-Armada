package org.valkyrienskies.eureka.ship

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.concurrent.ConcurrentHashMap

/**
 * When each ship was last hit by a cannonball or had fire burning on it, so other systems can tell a ship in a fight
 * from a ship at peace. Fed from CannonDamage (every punch, burst and kindle that lands on a ship) and ShipFireBurn
 * (every burn check on a ship's fire). In memory only; a restart counts as a long peace.
 */
object ShipCombat {

    private val lastHit = ConcurrentHashMap<Long, Long>()

    /** Record that the ship at [pos] (if [pos] is on one) was hit now. */
    @JvmStatic
    fun markHit(level: ServerLevel, pos: BlockPos) {
        val ship = level.getShipManagingPos(pos) ?: return
        lastHit[ship.id] = level.gameTime
    }

    /** Has [shipId] gone [ticks] game ticks without a cannon or fire hit? */
    @JvmStatic
    fun calmFor(shipId: Long, gameTime: Long, ticks: Long): Boolean {
        val hit = lastHit[shipId] ?: return true
        return gameTime - hit >= ticks
    }

    /** Server stopping. */
    @JvmStatic
    fun clear() = lastHit.clear()
}
