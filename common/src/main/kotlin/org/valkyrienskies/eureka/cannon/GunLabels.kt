package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.crew.CrewStations

/**
 * The bow-relative names of a ship's guns: `L1 L2 ...` down the port side, `R1 ...` down starboard, both
 * numbered from the bow; `F1 ...` across the bow and `B1 ...` across the stern, both read port to starboard,
 * the way you would count them standing behind the gun line and facing forward.
 *
 * ## The group is the gun's FACING, not its position
 * A gun bolted on the centreline firing to port is a port gun -- it fires with the port broadside, and
 * calling it anything else would name it after where the carpenter stood rather than what the gunner does.
 * So: muzzle pointing where the bow points is F, astern is B, and the two beams are L and R.
 *
 * ## Where "forward" comes from
 * The crew-station helm's facing, read in SHIPYARD space -- the same space every cannon's own facing lives
 * in, so the comparison is one enum equality with no transform. A helm block faces its wheel, so the bow is
 * its `HORIZONTAL_FACING.opposite`, exactly the seat-direction rule the cruise course uses. No crew-station
 * helm means no bow, and a ship with no bow has unnamed guns: the manifest shows nothing to station anyone
 * at until a wheel claims the articles.
 *
 * ## Labels are derived, never stored per gun
 * Deterministic from geometry, so the same hull always deals the same names -- across relogs, across
 * disassemble/reassemble, across a bottle cycle -- which is what lets a label serve as the durable half of a
 * gunner's station binding while shipyard addresses get re-dealt underneath it. The trade is visible and
 * accepted: adding or removing a gun renumbers everything behind it on its side.
 *
 * Welded armada children are labeled against the flagship's forward DIRECTION applied in their own shipyard
 * axes -- correct whenever the hulls' shipyard spaces are aligned, possibly rotated names otherwise. The
 * station binding itself is by position, so a sideways label there is cosmetic.
 */
object GunLabels {

    class Labeled(val gun: CannonBlockEntity, val label: String)

    /** The bow, as a shipyard-space direction, or null for a ship with no crew-station helm. */
    fun forwardOf(level: ServerLevel, ship: LoadedServerShip): Direction? {
        val helm = CrewStations.stationOf(level, ship) ?: return null
        return helm.blockState.getValue(HORIZONTAL_FACING).opposite
    }

    /**
     * Every gun aboard [ship] (armada included), named, in reading order: the port battery bow to stern,
     * then starboard, then the bow chasers, then the stern chasers. Empty when there is no bow to name from.
     */
    fun labeled(level: ServerLevel, ship: LoadedServerShip): List<Labeled> {
        val forward = forwardOf(level, ship) ?: return emptyList()
        val starboard = forward.clockWise
        val guns = ShipGuns.aboard(level, ship)
        if (guns.isEmpty()) return emptyList()

        val port = ArrayList<CannonBlockEntity>()
        val stbd = ArrayList<CannonBlockEntity>()
        val bow = ArrayList<CannonBlockEntity>()
        val stern = ArrayList<CannonBlockEntity>()
        for (gun in guns) {
            when (gun.blockState.getValue(HORIZONTAL_FACING)) {
                forward -> bow
                forward.opposite -> stern
                forward.counterClockWise -> port
                else -> stbd
            }.add(gun)
        }

        // Bow-most first for the broadsides; port-most first for the chasers. The remaining axes break ties
        // so the order is total and the names can never shuffle between two calls on the same hull.
        val toBow = compareByDescending<CannonBlockEntity> { it.blockPos.dot(forward) }
            .thenBy { it.blockPos.dot(starboard) }
            .thenBy { it.blockPos.y }
        val toStarboard = compareBy<CannonBlockEntity> { it.blockPos.dot(starboard) }
            .thenByDescending { it.blockPos.dot(forward) }
            .thenBy { it.blockPos.y }

        val out = ArrayList<Labeled>(guns.size)
        fun emit(group: ArrayList<CannonBlockEntity>, comparator: Comparator<CannonBlockEntity>, letter: Char) {
            group.sortWith(comparator)
            for ((index, gun) in group.withIndex()) out.add(Labeled(gun, "$letter${index + 1}"))
        }
        emit(port, toBow, 'L')
        emit(stbd, toBow, 'R')
        emit(bow, toStarboard, 'F')
        emit(stern, toStarboard, 'B')
        return out
    }

    /** The gun answering to [label] on [ship], or null. */
    fun byLabel(level: ServerLevel, ship: LoadedServerShip, label: String): CannonBlockEntity? =
        labeled(level, ship).firstOrNull { it.label == label }?.gun

    /** The label of the gun whose breech sits at [pos], or null when it has no name (or no longer exists). */
    fun labelAt(level: ServerLevel, ship: LoadedServerShip, pos: BlockPos): String? =
        labeled(level, ship).firstOrNull { it.gun.blockPos == pos }?.label

    private fun BlockPos.dot(direction: Direction): Int {
        val unit = direction.unitVec3i
        return x * unit.x + y * unit.y + z * unit.z
    }

    // region label <-> int packing, for ContainerData slots (the cannon menu's synced ints)

    private const val GROUPS = "LRFB"

    /** "L1" -> 101, "R5" -> 205, "B12" -> 412; 0 for null/none/unparseable. Inverse of [decode]. */
    fun encode(label: String?): Int {
        if (label.isNullOrEmpty()) return 0
        val group = GROUPS.indexOf(label[0])
        if (group < 0) return 0
        val number = label.drop(1).toIntOrNull() ?: return 0
        if (number < 1 || number > 99) return 0
        return (group + 1) * 100 + number
    }

    /** 101 -> "L1"; null for 0 or anything [encode] cannot have produced. */
    fun decode(code: Int): String? {
        if (code <= 0) return null
        val group = code / 100 - 1
        val number = code % 100
        if (group !in GROUPS.indices || number < 1) return null
        return "${GROUPS[group]}$number"
    }

    // endregion
}
