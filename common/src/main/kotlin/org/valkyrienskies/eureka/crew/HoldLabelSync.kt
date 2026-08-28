package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.util.logger

/**
 * Telling the client which numbered box it just opened.
 *
 * The number is derived server-side from the whole ship's geometry, so the client cannot work it out for
 * itself -- it does not have the hull, let alone the bow. One small push at open time is the whole channel:
 * a label never changes while a chest is open, so there is nothing to keep in step afterwards.
 *
 * [sender] is the same volatile-hook pattern `PathMessages.sender` uses, and for the same reason: :common
 * has no packets.
 */
object HoldLabelSync {

    private val logger by logger()

    /** player, containerId, label, tag mask. Installed by the loader's networking layer. */
    @Volatile
    @JvmStatic
    var sender: ((ServerPlayer, Int, String, Int) -> Unit)? = null

    /**
     * Work out what to call the box (or boxes) behind [container] and tell [player].
     *
     * A double chest is two numbered halves under one screen, so it reads as "Chest 2 - D3 + Chest 3 - D3":
     * they are genuinely two boxes, each tagged on its own, and collapsing them to one number would make the
     * screen disagree with the restock messages. The tag mask is the UNION, because the screen is showing
     * what is reachable through this window.
     *
     * Silent for anything not aboard an assembled ship, which is the rule for the whole feature: a box on
     * land is just a box.
     */
    fun push(level: ServerLevel, player: ServerPlayer, containerId: Int, container: Container) {
        val send = sender ?: return
        val holds = HoldRetag.holdsOf(container)
        if (holds.isEmpty()) return

        // A box on land is not numbered, and that is the rule rather than a fault -- so this is silent.
        val ship = level.getLoadedShipManagingPos(holds.first().blockPos) as? LoadedServerShip ?: return

        val labelled = HoldLabels.labeled(level, ship)
        if (labelled.isEmpty()) {
            // Aboard a ship and STILL unnamed has exactly one cause worth telling apart from "the label is
            // broken": no crew-station wheel, so there is no bow to number from. Said once per opening,
            // because the alternative is a captain staring at a blank corner with nothing to go on.
            logger.info(
                "[holds] chest at {} is aboard ship {} but the ship has no crew-station wheel, so there is " +
                    "no bow to number from -- claim a wheel with Sneak+C and the numbers appear",
                holds.first().blockPos.toShortString(), ship.id
            )
            return
        }

        val names = ArrayList<String>(2)
        var tags = 0
        for (hold in holds) {
            labelled.firstOrNull { it.hold.blockPos == hold.blockPos }?.let { names.add(it.label) }
            tags = tags or HoldTags.toMask(HoldTags.tagsOf(hold))
        }
        if (names.isEmpty()) return

        send(player, containerId, names.joinToString(" + "), tags)
    }
}
