package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.bottle.ShipBottle
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.PlacementCheck
import org.valkyrienskies.eureka.template.ShipTemplate

/**
 * What a shipwright does: keep a captain's plans, take materials against them, and hand over ships.
 *
 * ## Plans in, materials in instalments, a ship out
 * Filing a blueprint spends the page and buys a permanent place on the shelf. Everything after that is
 * materials, and materials are the only thing a *build* spends -- so the same hull can be ordered again and
 * again by bringing the bill again. See [ShipwrightLedger] for why the shelf belongs to the player rather than
 * to a bench.
 *
 * ## Nothing is ever taken that cannot be used
 * Every path here fails before it spends. A blueprint whose template has gone missing is refused before the
 * page is consumed; a build with nowhere to go is refused with the materials still on the shelf. The player
 * can always clear the ground and ask again.
 */
object Shipwright {

    /**
     * File the blueprint in [stack] on the player's shelf, consuming the page.
     *
     * The template is checked to still exist before anything is spent. A page from another world, or one whose
     * generated structure was cleared, describes a ship nobody can build -- and finding that out after the
     * blueprint is gone would be the worst possible moment.
     */
    fun file(level: ServerLevel, player: ServerPlayer, stack: ItemStack): Boolean {
        val page = Blueprint.read(stack) ?: run {
            PathMessages.send(player, "That blueprint is blank.", PathMessages.Kind.WARN)
            return false
        }

        if (ShipTemplate.find(level, page.template) == null) {
            PathMessages.send(
                player,
                "That blueprint describes a ship this world has no record of.",
                PathMessages.Kind.ERROR
            )
            return false
        }

        val cost = LinkedHashMap<Item, Int>()
        for ((item, count) in page.census) cost[item] = (cost[item] ?: 0) + count
        if (cost.isEmpty()) {
            PathMessages.send(player, "There is nothing on that blueprint to build.", PathMessages.Kind.WARN)
            return false
        }

        val ledger = ShipwrightLedger.get(level.server)
        val refusal = ledger.file(
            player.uuid,
            ShipwrightLedger.Plans(page.shipName, page.template, cost)
        )
        if (refusal != null) {
            PathMessages.send(player, refusal, PathMessages.Kind.WARN)
            return false
        }

        stack.shrink(1)
        val library = ledger.libraryOf(player.uuid)
        PathMessages.send(
            player,
            "'${page.shipName}' is on file -- ${library.plans.size} of ${library.slots} sets of plans.",
            PathMessages.Kind.GOOD
        )
        return true
    }

    /**
     * Hand over everything in [player]'s inventory that these plans still need.
     *
     * All at once rather than stack by stack: the alternative is a player clicking through forty stacks of
     * planks, which is not gameplay. Creative pays the whole bill outright, mirroring `EurekaAssembler.apply`'s
     * `instabuild` gate -- a creative player is not testing their logistics.
     */
    fun pay(level: ServerLevel, player: ServerPlayer, plans: ShipwrightLedger.Plans): Int {
        val ledger = ShipwrightLedger.get(level.server)

        if (player.abilities.instabuild) {
            var granted = 0
            for ((item, owed) in plans.outstanding()) granted += ledger.deliver(plans, item, owed)
            if (granted > 0) announce(player, plans, granted)
            return granted
        }

        var taken = 0
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue

            val wanted = plans.outstanding(stack.item)
            if (wanted <= 0) continue

            val accepted = ledger.deliver(plans, stack.item, minOf(wanted, stack.count))
            if (accepted <= 0) continue

            stack.shrink(accepted)
            taken += accepted
        }

        if (taken > 0) announce(player, plans, taken)
        return taken
    }

    /**
     * Build the ship in the world beside [bench], **unassembled**.
     *
     * Unassembled on purpose, and it is the more useful outcome rather than the lesser one. A hull that arrives
     * as loose blocks can be walked through, altered, have its floaters retuned and its cargo loaded before it
     * is ever a ship -- and then assembled at its wheel like anything built by hand.
     *
     * ## Why it is set down clear of everything
     * A delivered hull must not TOUCH a dock, a pier, or the harbor wall. Assembly walks outward from the wheel
     * through connected blocks, so a hull resting against a jetty takes the jetty with it -- and the first the
     * player knows about it is a harbor with a hole in it, sailing away. [CLEARANCE] blocks of gap on every
     * horizontal side is what makes that impossible rather than unlikely.
     *
     * Sideways only, not below. A keel near the seabed is normal and the assembler already tells terrain from
     * a build; a pier underneath a ship is not a thing harbors have.
     */
    fun build(
        level: ServerLevel,
        player: ServerPlayer,
        plans: ShipwrightLedger.Plans,
        bench: BlockPos
    ): Boolean {
        val template = ShipTemplate.find(level, plans.template) ?: run {
            PathMessages.send(player, "The plans for that ship are missing.", PathMessages.Kind.ERROR)
            return false
        }

        val corner = findBerth(level, template.size, bench) ?: run {
            PathMessages.send(
                player,
                "No clear water beside the bench -- '${plans.shipName}' needs " +
                    "${template.size.x + CLEARANCE * 2} by ${template.size.z + CLEARANCE * 2} of open room.",
                PathMessages.Kind.WARN
            )
            return false
        }

        if (ShipTemplate.place(level, plans.template, corner) !is ShipTemplate.Placed) {
            PathMessages.send(player, "The ship could not be built.", PathMessages.Kind.ERROR)
            return false
        }

        ShipwrightLedger.get(level.server).spendMaterials(plans)
        PathMessages.send(
            player,
            "'${plans.shipName}' is built and waiting -- assemble it at its wheel.",
            PathMessages.Kind.GOOD
        )
        return true
    }

    /**
     * An unassigned Ship Bottle in [player]'s inventory, or null.
     *
     * "Unassigned" means it has not been marked for a ship. A marked bottle is already pointed at somebody's
     * hull and is about to be thrown at it; quietly filling it with a different ship would destroy that
     * intention with no way to notice. See [ShipBottle.markedHelm].
     */
    fun freeBottle(player: ServerPlayer): ItemStack? {
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !stack.`is`(EurekaItems.SHIP_BOTTLE.get())) continue
            if (ShipBottle.markedHelm(stack) == null) return stack
        }
        return null
    }

    /**
     * Hand the ship over in a bottle instead of building it.
     *
     * The materials pay for the hull; the **bottle** is what it comes in, and the player brings that. It is not
     * on the bill and never appears in the material list -- a ship's cost should not change depending on how
     * you take delivery -- but the shipwright has nothing to put a ship into without one. No heart and no eye
     * of ender: those are what a Ship Bottle is *made* of, and paying for the ingredients twice is paying
     * twice.
     *
     * The plans' template is **copied** rather than shared. Releasing a bottle forgets its template once the
     * ship is out, so a bottle pointing at the shelf's own file would destroy those plans the first time it was
     * used -- and they are meant to last forever. See [ShipTemplate.copy].
     */
    fun bottle(level: ServerLevel, player: ServerPlayer, plans: ShipwrightLedger.Plans): Boolean {
        val bottle = freeBottle(player) ?: run {
            PathMessages.send(
                player,
                "The shipwright needs an unmarked Ship Bottle to put '${plans.shipName}' into.",
                PathMessages.Kind.WARN
            )
            return false
        }

        if (ShipTemplate.find(level, plans.template) == null) {
            PathMessages.send(player, "The plans for that ship are missing.", PathMessages.Kind.ERROR)
            return false
        }

        val bottleTemplate = "bottled/${UUID.randomUUID().toString().replace("-", "")}"
        if (!ShipTemplate.copy(level, plans.template, bottleTemplate)) {
            PathMessages.send(player, "The ship would not go in the bottle.", PathMessages.Kind.ERROR)
            return false
        }

        val bottled = ShipBottle.bottleOf(bottleTemplate, plans.shipName)
        ShipwrightLedger.get(level.server).spendMaterials(plans)
        // The bottle becomes the bottled ship rather than sitting alongside it.
        bottle.shrink(1)
        if (!player.inventory.add(bottled)) player.drop(bottled, false)

        PathMessages.send(
            player,
            "The shipwright hands over '${plans.shipName}', bottled.",
            PathMessages.Kind.GOOD
        )
        return true
    }

    /**
     * The nearest spot beside [bench] where a hull of [size] fits with [CLEARANCE] to spare, or null.
     *
     * Searched outward in the four horizontal directions rather than dropped at one fixed offset, because a
     * bench sits on a dock and a dock has a side the water is on. Bounded so a refusal is a refusal -- a ship
     * appearing forty blocks away because that is where the search happened to succeed would be worse than
     * being told to clear some room.
     */
    private fun findBerth(level: ServerLevel, size: Vec3i, bench: BlockPos): BlockPos? {
        for (distance in CLEARANCE..MAX_SEARCH) {
            for (facing in HORIZONTALS) {
                val corner = when (facing) {
                    Direction.EAST -> BlockPos(bench.x + distance, bench.y, bench.z - size.z / 2)
                    Direction.WEST -> BlockPos(bench.x - distance - size.x, bench.y, bench.z - size.z / 2)
                    Direction.SOUTH -> BlockPos(bench.x - size.x / 2, bench.y, bench.z + distance)
                    else -> BlockPos(bench.x - size.x / 2, bench.y, bench.z - distance - size.z)
                }
                if (fitsClear(level, size, corner)) return corner
            }
        }
        return null
    }

    /**
     * Whether a hull of [size] at [corner] both fits and stands clear of anything solid around it.
     *
     * The hull's own footprint goes through [PlacementCheck] -- which understands that water and grass are
     * replaceable and a wall is not -- and the same test is then applied to a skirt [CLEARANCE] blocks wide on
     * each horizontal side. Checking the skirt with the identical rule is the point: whatever counts as "in the
     * way" for the ship counts as "too close" for the gap.
     */
    private fun fitsClear(level: ServerLevel, size: Vec3i, corner: BlockPos): Boolean {
        val skirt = BlockPos(corner.x - CLEARANCE, corner.y, corner.z - CLEARANCE)
        val grown = BlockPos(size.x + CLEARANCE * 2, size.y, size.z + CLEARANCE * 2)
        return PlacementCheck.isClear(level, grown, skirt)
    }

    private fun announce(player: ServerPlayer, plans: ShipwrightLedger.Plans, taken: Int) {
        if (plans.ready) {
            PathMessages.send(
                player,
                "'${plans.shipName}' is paid for -- ask for it built or bottled.",
                PathMessages.Kind.GOOD
            )
        } else {
            PathMessages.send(
                player,
                "Handed over $taken items -- '${plans.shipName}' is ${(plans.progress * 100).toInt()}% paid.",
                PathMessages.Kind.GOOD
            )
        }
    }

    /**
     * How much open room a delivered hull needs on each horizontal side.
     *
     * Two rather than one: one block of gap stops the assembly reaching a jetty, and the second is for the
     * player, who has to be able to walk round a hull to reach its wheel.
     */
    const val CLEARANCE = 2

    /** How far from the bench to look for room before giving up. */
    private const val MAX_SEARCH = 24

    private val HORIZONTALS = arrayOf(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH)
}
