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
import org.valkyrienskies.eureka.template.ShipManifest
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

        // A page that was drawn up from altered plans comes back altered. Without this the openness dies on
        // the page: the census would file as a fixed bill for whatever the representative happened to be,
        // and a captain who deliberately said "any slab" would be handed a plan demanding dark oak ones.
        val swaps = LinkedHashMap<Item, Alteration.Swap>()
        for (row in page.census) {
            if (!row.any) continue
            val family = MaterialFamilies.familyOf(row.item) ?: continue
            swaps[row.item] = Alteration.Any(family, row.item)
        }
        val alteration = if (swaps.isEmpty()) Alteration.NONE else Alteration(swaps = swaps)

        val ledger = ShipwrightLedger.get(level.server)

        // Filed BESIDE plans of the same name rather than refused. A captain holding two pages of one design
        // -- an original and a variant off it -- was told to go away and rename one before either could be
        // filed, which is a demand to solve the shelf's bookkeeping by hand. The shelf can count.
        val filedAs = freeName(ledger, player, page.shipName)
        val refusal = ledger.file(
            player.uuid,
            ShipwrightLedger.Plans(filedAs, page.template, cost, alteration = alteration)
        )
        if (refusal != null) {
            PathMessages.send(player, refusal, PathMessages.Kind.WARN)
            return false
        }

        stack.shrink(1)
        val library = ledger.libraryOf(player.uuid)
        // Said out loud when the shelf had to pick a different name, because a captain who filed
        // "Battleship Blue" and later cannot find it should not have to work out where it went.
        val told = if (filedAs == page.shipName) "'$filedAs' is on file"
        else "'${page.shipName}' is on file as '$filedAs'"
        PathMessages.send(
            player,
            "$told -- ${library.plans.size} of ${library.slots} sets of plans.",
            PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_PLANS
        )
        return true
    }

    /**
     * File the altered plans as a second, independent set, leaving the original alone.
     *
     * ## Metadata, not a new template
     * The variant keeps pointing at the SAME template and carries the alteration beside it. Baking a
     * second .nbt for every saved variant would leak one file per variant into the world folder forever,
     * because deleting a set of plans has no hook that could clean it up -- and there is nothing a baked
     * copy would buy here, since the shelf can read an alteration perfectly well.
     *
     * A page handed to another player is the opposite case and bakes; see [takeBlueprint].
     *
     * Materials already paid toward the original stay owed on the original. The variant starts empty,
     * because it is a different order for a different ship.
     */
    fun saveAsNew(
        level: ServerLevel,
        player: ServerPlayer,
        plans: ShipwrightLedger.Plans,
        name: String? = null
    ): String? {
        if (plans.alteration.isEmpty) return "There is nothing altered about those plans to save."

        val ledger = ShipwrightLedger.get(level.server)
        val chosen = name?.takeIf { it.isNotBlank() } ?: variantName(ledger, player, plans.shipName)
        val refusal = ledger.file(
            player.uuid,
            ShipwrightLedger.Plans(chosen, plans.template, plans.baseCost, alteration = plans.alteration)
        )
        if (refusal != null) return refusal

        // The variant now holds the changes, so the original goes back to what the page describes. Two
        // sets of plans that both claim to be "the altered one" is the state nobody can reason about.
        plans.alteration = Alteration.NONE
        plans.deliveries.clear()

        PathMessages.send(player, "Filed '$chosen' beside the original.", PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_PLANS)
        return null
    }

    /**
     * Bake the alteration into a template of its own and hand back a page describing it.
     *
     * ## This one bakes, and has to
     * A page is read on the CLIENT, off the item's own component, possibly on another server entirely --
     * it cannot reach into a ledger to find out what its owner had altered. And `BlueprintCopyRecipe`
     * copies that component byte for byte on the understanding that a template never changes once written.
     * So the page is given a real template of its own, which makes it exactly what it claims to be:
     * indistinguishable from a drafted one, copyable, and correct wherever the file travels.
     *
     * The census comes back out of [Blueprint.draftFromTemplate] recomputed from the baked template, so the
     * page's bill already reflects every exclusion and swap without any of it being written twice.
     */
    fun takeBlueprint(level: ServerLevel, player: ServerPlayer, plans: ShipwrightLedger.Plans): Boolean {
        if (plans.alteration.isEmpty) {
            PathMessages.send(player, "Those plans are unaltered -- copy the page instead.", PathMessages.Kind.WARN)
            return false
        }
        val blank = blankBlueprint(player) ?: run {
            PathMessages.send(player, "You need a blank blueprint to draw that up.", PathMessages.Kind.WARN)
            return false
        }

        val baked = "blueprint/${UUID.randomUUID().toString().replace("-", "")}"
        if (!ShipTemplate.copy(level, plans.template, baked)) {
            PathMessages.send(player, "The plans could not be copied.", PathMessages.Kind.ERROR)
            return false
        }
        if (!ShipAlterations.rewrite(level, baked, plans.alteration, plans.deliveries)) {
            ShipTemplate.delete(level, baked)
            PathMessages.send(player, "The alterations could not be applied.", PathMessages.Kind.ERROR)
            return false
        }

        // The ANY rows, by the material the plans started from. rewrite() has resolved each one to its
        // representative -- which for an Any row IS that same material -- so the baked census names exactly
        // these items, and flagging them by key lines up row for row.
        val anyItems = plans.alteration.swaps.entries
            .filter { it.value is Alteration.Any }
            .map { it.key }
            .toSet()

        // Named apart from the plans it came off. Only altered plans can be drawn up at all -- the guard at
        // the top of this method says so -- so a page taken here is BY DEFINITION not the original, and
        // handing back a second thing with the original's name on it is how a captain ends up rebuilding
        // the wrong ship. Same suffix saveAsNew uses, for the same reason.
        val pageName = variantName(ShipwrightLedger.get(level.server), player, plans.shipName)

        val page = Blueprint.draftFromTemplate(level, baked, pageName, anyItems) ?: run {
            ShipTemplate.delete(level, baked)
            PathMessages.send(player, "The page could not be drawn up.", PathMessages.Kind.ERROR)
            return false
        }

        // Spent only once the template is on disk and the page is in hand -- the same order every other
        // door here works in, so a refusal never costs the player their blank.
        blank.shrink(1)
        if (!player.inventory.add(page)) {
            // Nowhere to put it. Nothing is ever dropped on the floor here, so the blank goes back, the
            // baked template is scrapped, and the captain is told to make room -- the state they were in
            // a moment ago, exactly.
            blank.grow(1)
            ShipTemplate.delete(level, baked)
            PathMessages.send(player, "No room in your pack for the page.", PathMessages.Kind.WARN)
            return false
        }
        PathMessages.send(player, "Drawn up: '${plans.shipName}', as altered.", PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_ALTERATIONS)
        return true
    }

    /** A blank blueprint in [player]'s pack, or null. The [freeBottle] pattern, for the other page. */
    fun blankBlueprint(player: ServerPlayer): ItemStack? {
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty || !stack.`is`(EurekaItems.BLUEPRINT.get())) continue
            if (Blueprint.read(stack) == null) return stack
        }
        return null
    }

    /** '<name> (altered)', then '(altered 2)' and so on -- whatever the shelf does not already hold. */
    /** [base] if the shelf has room for it, else the first "base (2)", "base (3)" that is free. */
    private fun freeName(ledger: ShipwrightLedger, player: ServerPlayer, base: String): String {
        val held = ledger.libraryOf(player.uuid).plans.keys
        if (base !in held) return base
        var n = 2
        while ("$base ($n)" in held) n++
        return "$base ($n)"
    }

    private fun variantName(ledger: ShipwrightLedger, player: ServerPlayer, base: String): String {
        val held = ledger.libraryOf(player.uuid).plans.keys
        val first = "$base (altered)"
        if (first !in held) return first
        var n = 2
        while ("$base (altered $n)" in held) n++
        return "$base (altered $n)"
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

        // The yard is paid before it takes a single plank. Everything below this block is the old
        // materials-only handover, unchanged; this is the gate in front of it.
        //
        // Re-quoted here rather than trusted off the wire -- the screen's figure was worked out when the
        // book opened, and the world's rates could have been reloaded since. Same rule as the dismantle.
        var settled: List<YardFee.Line> = emptyList()
        if (!plans.feePaid) {
            val fee = YardFee.quoteBuild(blocksOf(level, plans))
            val short = YardFee.shortfall(player, fee)
            if (short.isNotEmpty()) {
                // Nothing changes hands: not the fee, and not one plank of the materials. A yard holding a
                // hull's worth of timber against an unpaid invoice is the half-state this ordering exists
                // to prevent.
                PathMessages.send(
                    player,
                    "Building '${plans.shipName}' costs " + YardFee.describe(fee) +
                        " up front. You are short " + YardFee.describe(short) + ".",
                    PathMessages.Kind.WARN
                )
                return 0
            }
            YardFee.take(player, fee)
            plans.feePaid = true
            ledger.setDirty()
            settled = fee
        }

        if (player.abilities.instabuild) {
            var granted = 0
            for ((item, owed) in plans.outstanding()) granted += ledger.deliver(plans, item, owed)
            if (granted > 0 || settled.isNotEmpty()) announce(player, plans, granted, settled)
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

        // Said even when nothing was taken, if the fee just was: money changed hands, so silence is not an
        // honest answer. Without a fee this stays as it was and a fruitless press says nothing.
        if (taken > 0 || settled.isNotEmpty()) announce(player, plans, taken, settled)
        return taken
    }

    /**
     * The block count a build fee is quoted against: the plans as DRAWN.
     *
     * The same measure the card prints beside the fee (`ShipwrightTalk.detailOf` reads it from the same
     * manifest), so the number a captain is charged for and the number they are shown can never disagree.
     * Missing plans quote nothing rather than throwing -- `build` refuses on the same absence a moment later
     * with a message that actually explains itself.
     */
    private fun blocksOf(level: ServerLevel, plans: ShipwrightLedger.Plans): Int {
        val template = ShipTemplate.find(level, plans.template) ?: return 0
        return ShipManifest.of(template).blocks
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

        if (!lay(level, player, plans, corner)) return false

        ShipwrightLedger.get(level.server).spendMaterials(plans)
        PathMessages.send(
            player,
            "'${plans.shipName}' is built and waiting -- assemble it at its wheel.",
            PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_DELIVERY
        )
        return true
    }

    /**
     * Put the hull in the water -- the plans as filed, or the plans as the captain altered them.
     *
     * ## Unaltered plans take the old road exactly
     * A ship nobody has changed is placed straight from its own template, with no copy, no temporary file
     * and no new way to fail. The whole apparatus below exists for the altered case and costs the ordinary
     * one nothing, which matters because the ordinary one is almost all of them.
     *
     * ## Why the copy is not optional
     * `ShipTemplate.place` hands out the structure manager's CACHED template. Rewriting that would change
     * the plans themselves -- and a template is shared by every copy of a blueprint page, so the damage
     * would show up on a ship built by somebody who never altered anything. The copy is thrown away the
     * moment the hull is standing, by DELETE rather than `forget`: forgetting only drops the in-memory
     * copy and the file stays on disk forever, one per build.
     */
    private fun lay(
        level: ServerLevel,
        player: ServerPlayer,
        plans: ShipwrightLedger.Plans,
        corner: BlockPos
    ): Boolean {
        if (plans.alteration.isEmpty) {
            if (ShipTemplate.place(level, plans.template, corner) is ShipTemplate.Placed) return true
            PathMessages.send(player, "The ship could not be built.", PathMessages.Kind.ERROR)
            return false
        }

        val working = "altered/${UUID.randomUUID().toString().replace("-", "")}"
        try {
            if (!ShipTemplate.copy(level, plans.template, working)) {
                PathMessages.send(player, "The altered plans could not be drawn up.", PathMessages.Kind.ERROR)
                return false
            }
            if (!ShipAlterations.rewrite(level, working, plans.alteration, plans.deliveries)) {
                PathMessages.send(player, "The alterations could not be applied.", PathMessages.Kind.ERROR)
                return false
            }
            if (ShipTemplate.place(level, working, corner) !is ShipTemplate.Placed) {
                PathMessages.send(player, "The ship could not be built.", PathMessages.Kind.ERROR)
                return false
            }
            return true
        } finally {
            // Whether it went up or fell over on the way, the working copy is scrap either way. delete
            // drops it from the manager AND takes the .nbt off disk; forget would only do the first, and
            // leave one file per build in the world folder forever.
            ShipTemplate.delete(level, working)
        }
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

        // The bottle holds the ship as ORDERED, not as drawn. Without this a captain who struck the
        // decor off a design and bottled it would find every carpet back the moment they let it out.
        if (!ShipAlterations.rewrite(level, bottleTemplate, plans.alteration, plans.deliveries)) {
            ShipTemplate.delete(level, bottleTemplate)
            PathMessages.send(player, "The alterations could not be applied.", PathMessages.Kind.ERROR)
            return false
        }

        // Named apart from the plans when those plans are altered, exactly as a blueprint taken off them is.
        // A bottle and a page are the same promise in two shapes -- "this design, as you changed it" -- and
        // a captain holding both should be able to tell either from the original at a glance.
        val bottleName = if (plans.alteration.isEmpty) plans.shipName
        else variantName(ShipwrightLedger.get(level.server), player, plans.shipName)

        val bottled = ShipBottle.bottleOf(bottleTemplate, bottleName)
        ShipwrightLedger.get(level.server).spendMaterials(plans)
        // The bottle becomes the bottled ship rather than sitting alongside it.
        bottle.shrink(1)
        if (!player.inventory.add(bottled)) player.drop(bottled, false)

        PathMessages.send(
            player,
            "The shipwright hands over '$bottleName', bottled.",
            PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_DELIVERY
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

    /**
     * One line for one press of Give Materials, whatever happened in it.
     *
     * [settled] is the fee taken by THIS press, empty when it was already paid or the yard builds free. It
     * leads the sentence when there is one, because a captain watching emeralds leave their pack should be
     * told so first and in the same breath as what the emeralds bought.
     */
    private fun announce(
        player: ServerPlayer,
        plans: ShipwrightLedger.Plans,
        taken: Int,
        settled: List<YardFee.Line>
    ) {
        val progress = "${(plans.progress * 100).toInt()}% paid"
        val message = if (settled.isEmpty()) {
            // Word for word what the yard has always said when no money is involved.
            if (plans.ready) "'${plans.shipName}' is paid for -- ask for it built or bottled."
            else "Handed over $taken items -- '${plans.shipName}' is $progress."
        } else {
            val paid = "The yard takes " + YardFee.describe(settled) + " -- "
            when {
                plans.ready -> paid + "'${plans.shipName}' is paid for in full, ask for it built or bottled."
                // The fee is in and the captain arrived with nothing the hull needs. Rare, but it is the
                // one case where the old code's silence would have swallowed a payment.
                taken <= 0 -> paid + "nothing on you that it needs yet."
                else -> paid + "handed over $taken items, '${plans.shipName}' is $progress."
            }
        }
        PathMessages.send(player, message, PathMessages.Kind.GOOD, PathMessages.Topic.SHIPWRIGHT_MATERIALS)
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
