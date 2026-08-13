package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.ShipManifest
import org.valkyrienskies.eureka.template.ShipTemplate

/**
 * Talking to a shipwright: every interaction the profession has.
 *
 * Handing over a blueprint or a Heart of the Sea is done by holding it out; everything else happens in the
 * screen. Deliberately all on the villager rather than on its bench -- a workbench that answered questions
 * would make the shipwright decorative, and the point of the profession is that a harbor without one cannot
 * build you anything.
 */
object ShipwrightTalk {

    /**
     * Right-clicking a shipwright. Returns true if the click was ours and should go no further.
     *
     * Claiming the click matters as much as handling it: a shipwright has no trades, so anything we pass on
     * reaches vanilla's trade screen, finds nothing to sell, and the villager shakes its head at the player.
     */
    fun interact(level: ServerLevel, player: ServerPlayer, villager: Villager, stack: ItemStack): Boolean {
        if (!isShipwright(villager)) return false

        if (stack.`is`(EurekaItems.BLUEPRINT.get())) {
            Shipwright.file(level, player, stack)
            return true
        }

        if (stack.`is`(Items.HEART_OF_THE_SEA)) {
            buyShelfSpace(level, player, villager, stack)
            return true
        }

        openShelf(level, player, villager)
        return true
    }

    fun isShipwright(villager: Villager): Boolean =
        villager.villagerData.profession().`is`(ShipwrightProfession.PROFESSION_KEY)

    /** Send this player their whole shelf, as the screen draws it. */
    fun openShelf(level: ServerLevel, player: ServerPlayer, villager: Villager) {
        val shelf = shelfFor(level, player, villager)
        sender?.invoke(player, shelf)
    }

    fun shelfFor(level: ServerLevel, player: ServerPlayer, villager: Villager): ShipwrightMenu.Shelf =
        ShipwrightMenu.snapshot(
            ledger = ShipwrightLedger.get(level.server),
            owner = player.uuid,
            villager = villager.id,
            hasFreeBottle = Shipwright.freeBottle(player) != null,
            detail = { template -> detailOf(level, template) }
        )

    /**
     * The size, weight and speed of a template, recomputed rather than stored.
     *
     * Costs one pass over the block list per set of plans when a screen opens, which is nothing beside the
     * alternative: a second record of each ship that can drift from the template it describes.
     */
    private fun detailOf(level: ServerLevel, template: String): ShipwrightMenu.Detail? {
        val found = ShipTemplate.find(level, template) ?: return null
        val manifest = ShipManifest.of(found)
        return ShipwrightMenu.Detail(
            width = manifest.width,
            height = manifest.height,
            length = manifest.length,
            blocks = manifest.blocks,
            mass = manifest.mass,
            topSpeed = manifest.topSpeed,
            profile = manifest.profile.name
        )
    }

    /**
     * Act on what the screen asked for, then answer with a fresh shelf.
     *
     * Every action re-sends the snapshot, including the ones that fail. That is what keeps the screen honest
     * without polling: whatever the server now believes is what the player is looking at.
     */
    fun act(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        action: ShipwrightMenu.Action,
        shipName: String
    ) {
        val ledger = ShipwrightLedger.get(level.server)
        val plans = ledger.plansFor(player.uuid, shipName)

        if (plans == null) {
            PathMessages.send(player, "Those plans are no longer on file.", PathMessages.Kind.WARN)
            openShelf(level, player, villager)
            return
        }

        when (action) {
            ShipwrightMenu.Action.PAY -> Shipwright.pay(level, player, plans)
            ShipwrightMenu.Action.DELETE -> {
                if (ledger.delete(player.uuid, shipName)) {
                    PathMessages.send(
                        player,
                        "The shipwright discards the plans for '$shipName'.",
                        PathMessages.Kind.WARN
                    )
                }
            }
            ShipwrightMenu.Action.BUILD -> {
                if (!plans.ready) {
                    PathMessages.send(player, "'$shipName' is not paid for yet.", PathMessages.Kind.WARN)
                } else {
                    Shipwright.build(level, player, plans, yard(villager))
                }
            }
            ShipwrightMenu.Action.BOTTLE -> {
                if (!plans.ready) {
                    PathMessages.send(player, "'$shipName' is not paid for yet.", PathMessages.Kind.WARN)
                } else {
                    Shipwright.bottle(level, player, plans)
                }
            }
        }

        openShelf(level, player, villager)
    }

    /**
     * Where a ship this shipwright builds is set down.
     *
     * The **bench**, not the villager -- a bench sits on a dock with the water in front of it, which is exactly
     * where a hull wants to go, whereas the villager could be anywhere it happens to have wandered. Falls back
     * to the villager only if it has somehow lost its workstation.
     */
    private fun yard(villager: Villager): BlockPos {
        val site: GlobalPos? = villager.brain.getMemory(MemoryModuleType.JOB_SITE).orElse(null)
        return site?.pos() ?: villager.blockPosition()
    }

    private fun buyShelfSpace(
        level: ServerLevel,
        player: ServerPlayer,
        villager: Villager,
        stack: ItemStack
    ) {
        val ledger = ShipwrightLedger.get(level.server)
        if (!ledger.buySlot(player.uuid)) {
            PathMessages.send(
                player,
                "Your shelf is already as large as anyone's gets " +
                    "(${EurekaConfig.SERVER.shipwrightSlotsMax}).",
                PathMessages.Kind.ERROR
            )
            return
        }

        if (!player.abilities.instabuild) stack.shrink(1)
        level.playSound(
            null, villager.blockPosition(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.NEUTRAL, 0.6f, 1.2f
        )
        PathMessages.send(
            player,
            "Room for another set of plans -- ${ledger.libraryOf(player.uuid).slots} in all.",
            PathMessages.Kind.GOOD
        )
        openShelf(level, player, villager)
    }

    /**
     * Installed by the loader's networking layer, which owns packets. Same indirection as `PathMessages.sender`.
     */
    @Volatile
    @JvmStatic
    var sender: ((ServerPlayer, ShipwrightMenu.Shelf) -> Unit)? = null

    /** Unused today; kept so the ledger's per-player keying reads the same everywhere. */
    fun ownerOf(player: ServerPlayer): UUID = player.uuid
}
