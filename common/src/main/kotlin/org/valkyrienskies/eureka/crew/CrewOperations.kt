package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.block.CannonPart
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.cannon.PowderCharge
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.ship.EurekaShipControl
import java.util.UUID

/**
 * The Operations tab's spine: everything a captain orders for the whole ship at once.
 *
 * Where `CrewManifest` answers questions about ONE crew member, this answers orders about the VESSEL --
 * lay out the gun crew, post the fire watch, run powder and shot to the batteries, stoke the engines --
 * each one the sum of dozens of gestures a player used to make by hand. The materials come from
 * [ShipStores] (any chest or barrel aboard), the guns from `GunLabels.labeled` (which is also the order
 * everything is served in: deck by deck from the keel up, each deck's port battery bow to stern, then its
 * starboard, then its chasers), and the authority from the same gate every manifest action uses: the
 * wheel's articles, reachable from anywhere aboard. Most gun orders take a scope -- a side and a deck --
 * and touch nothing outside it.
 *
 * ## Locks are the exception to everything bulk
 * A LOCKED berth is a captain's "do not touch": bulk assignment counts them toward the total but never
 * re-tasks them, and their gun keeps its own settings -- restocks still SUPPLY it (powder is powder, and
 * a locked gun refills with whatever round is already chambered), but nothing bulk ever changes what it
 * is set to. Everything here is written against that rule even before the manifest can set a lock, so
 * the lock feature lands as UI, not as a re-audit of every algorithm.
 */
object CrewOperations {

    /** Which battery an order addresses. On elevation orders [BOTH] reads as "all", chasers included. */
    enum class Side { PORT, STARBOARD, BOTH }

    /**
     * What an Assign order means for crew who already hold a position.
     *
     * [KEEP] never re-tasks anybody -- it makes the numbers up out of idle hands, so an order about one
     * deck can never cost another one its gunners. [REASSIGN] is the opposite promise: the WHOLE gun crew
     * comes off its guns first, wherever they stand, and the scope is dealt again from the top of the
     * articles. [RELEASE] frees the scope and posts nobody; the count is not consulted, because "let Deck
     * 2's port guns go" is not a number.
     *
     * Ordinals are the wire format; KEEP leads because it is the default and the one that cannot surprise.
     */
    enum class AssignMode { KEEP, REASSIGN, RELEASE }

    /**
     * Pushes a stores tally at one screen, along with the guns-per-deck census the deck dropdown lists.
     * Installed by the platform networking, exactly as `CrewManifest.sender` is; the default no-op keeps
     * loaders without the payload honest. The deck counts ride this payload rather than the manifest
     * because they change with the same gestures the holds do -- every ops order refreshes both at once.
     */
    @Volatile
    @JvmField
    var storesSender: (ServerPlayer, Long, ShipStores.Stores, List<Int>, Boolean) -> Boolean =
        { _, _, _, _, _ -> false }

    // region the orders



    /** Answer a screen asking what the holds hold. */
    fun requestStores(level: ServerLevel, player: ServerPlayer, helm: Long) {
        val op = gate(level, player, helm) ?: return
        pushStores(op)
    }

    /**
     * Give or lift the Fire at Will order: the gun crews lay their own guns at the nearest raider until
     * told otherwise. The order lives on the SHIP ([EurekaShipControl.fireAtWill]) rather than on the
     * captain who gave it, for the reason every duty here does -- crew work for the hull they stand on,
     * and a ship crewed by two captains is not two half-orders.
     */
    fun requestFireAtWill(level: ServerLevel, player: ServerPlayer, helm: Long, on: Boolean) {
        val op = gate(level, player, helm) ?: return
        val control = op.ship.getAttachment(EurekaShipControl::class.java)
        if (control == null) {
            PathMessages.send(player, "That hull has no wheel to give the order from.", PathMessages.Kind.ERROR)
            return
        }
        if (control.fireAtWill == on) {
            pushStores(op)
            return
        }
        control.fireAtWill = on
        control.fireAtWillTarget = 0L
        if (on) {
            ShipCrew.tellOthers(
                level, op.ship, player,
                "${ShipCrew.name(op.ship)}'s guns are free to fire.", PathMessages.Kind.WARN
            )
            PathMessages.send(player, "Fire at will -- the gun crews will lay their own.", PathMessages.Kind.GOOD)
        } else {
            PathMessages.send(player, "Cease fire.", PathMessages.Kind.GOOD)
        }
        pushStores(op)
    }

    /**
     * Work [side]'s guns on deck [layer] according to [mode], and touch nothing outside that scope.
     *
     * An order about Deck 1's port battery is never permission to unseat Deck 2 -- that is the whole point
     * of the scope, and it holds in all three modes:
     *
     * - [AssignMode.KEEP] seats gunners at EMPTY guns until [count] of the scope's guns are manned in
     *   total. Nobody already at a gun is moved, locked or not, and everybody already there counts toward
     *   the total; asking for no more than are manned changes nothing and says so.
     * - [AssignMode.REASSIGN] stands this crew's ENTIRE unlocked gun crew down first -- every deck, both
     *   sides -- and then fills the scope the same way. That is what makes "sixty to port, then sixty to
     *   starboard" mean the second order rather than both at once; a scope-only clearing would leave the
     *   port battery manned and answer the starboard order with whatever hands happened to be spare.
     * - [AssignMode.RELEASE] empties the scope and posts nobody.
     *
     * New hands come from the first unlocked berths on the articles that are not holding a gun ANYWHERE
     * and are not waiting on a remembered one -- an idle firefighter may be drafted (a duty is an order,
     * not a shackle), but a gunner on another deck is never poached off it by an order about this one, and
     * a stood-down gunner whose label is about to walk him back to his own gun is left for the reconcile
     * that is already carrying him there.
     *
     * BOTH deals alternately -- L, R, L, R -- so a half-manned ship still answers on both broadsides; a
     * single side fills bow to stern. Guns held by another captain's crew are never dealt, and LOCKED
     * berths are untouched by every mode, releases included.
     */
    fun requestAssignGunners(
        level: ServerLevel,
        player: ServerPlayer,
        helm: Long,
        count: Int,
        side: Side,
        layer: Int,
        mode: AssignMode
    ) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val scope = assignmentSeq(labeled, side, layer)
        if (scope.isEmpty()) {
            PathMessages.send(player, "No guns in ${scopeName(side, layer).lowercase()}.", PathMessages.Kind.WARN)
            return
        }

        val ledger = CrewLedger.get(level.server)

        // Both of the other modes begin by standing gunners down -- but over different ground, and the
        // difference is the whole meaning of the two words.
        //
        // Reassign is a CLEAN SLATE: every gun this crew holds anywhere comes free first, so an order to
        // man the starboard battery can draw on the hands that were serving to port. Clearing only the
        // scope was the first cut and it read as a plain bug -- sixty gunners stayed put on the left, and
        // an order for sixty on the right was answered by the five spare hands that happened to be idle.
        // Release is the scoped one: it frees what the selectors point at and nothing else.
        val released = when (mode) {
            AssignMode.KEEP -> 0
            AssignMode.REASSIGN -> standDownGunners(level, ledger, op, null)
            AssignMode.RELEASE -> standDownGunners(level, ledger, op, scope)
        }
        if (mode == AssignMode.RELEASE) {
            if (released == 0) {
                PathMessages.send(player, "No one to release from ${scopeName(side, layer).lowercase()}.", PathMessages.Kind.GOOD)
            } else {
                val who = if (released == 1) "gunner" else "gunners"
                PathMessages.send(player, "${scopeName(side, layer)}: $released $who released.", PathMessages.Kind.GOOD)
                CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
                pushStores(op)
            }
            return
        }

        // Anybody's stationed berth mans a gun -- locked, another captain's, all of them. The count the
        // captain asked for is "how many of these guns have somebody behind them", not "how many are mine".
        // Read AFTER the clearing above, so a re-deal sees the guns it just emptied.
        val held = ledger.stationedBerths().mapNotNullTo(HashSet()) { it.station }
        val manned = scope.count { it.gun.blockPos.asLong() in held }
        val total = count.coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)
        val wanted = (total - manned).coerceAtLeast(0)
        if (wanted == 0) {
            if (released > 0) {
                PathMessages.send(
                    player,
                    "${scopeName(side, layer)}: $released stood down, $manned still manned.",
                    PathMessages.Kind.GOOD
                )
                CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
                pushStores(op)
            } else {
                PathMessages.send(player, "${scopeName(side, layer)} already musters $manned gunners.", PathMessages.Kind.GOOD)
            }
            return
        }

        // Empty guns in dealing order; free hands in slot order. A berth with a station is on a gun and a
        // berth with only a LABEL is owed one -- both are spoken for, so neither is a free hand.
        val targets = ArrayDeque(scope.filter { it.gun.blockPos.asLong() !in held })
        val candidates = ArrayDeque(
            ledger.crew(op.key).sortedBy { it.slot }
                .filter { !it.locked && it.station == null && it.stationLabel == null }
        )

        var assigned = 0
        var away = 0
        var refused = 0

        while (assigned < wanted && targets.isNotEmpty() && candidates.isNotEmpty()) {
            val berth = candidates.removeFirst()
            val villager = CrewMuster.findAnywhere(level.server, berth.villager)
            if (villager == null || !villager.isAlive || villager.level() !== level) {
                // An unreachable crew member costs no gun -- the next berth takes it instead.
                away++
                continue
            }

            // The next gun somebody can actually stand at. A gun with no safe footing is consumed, not
            // retried: it stays unmanned on purpose, and the captain is told rather than left counting.
            var gun: GunLabels.Labeled? = null
            while (targets.isNotEmpty()) {
                val candidate = targets.removeFirst()
                if (GunStations.footingProblem(level, candidate.gun.blockPos) != null) {
                    refused++
                    continue
                }
                gun = candidate
                break
            }
            if (gun == null) break

            // Duty first -- setDuty wipes any station -- then the binding, then the seat.
            ledger.setDuty(berth.villager, CrewDuty.GUNNER)
            ledger.setStation(berth.villager, gun.gun.blockPos.asLong(), gun.label)
            if (GunStations.stationNow(level, berth.villager, gun.gun.blockPos)) {
                assigned++
            } else {
                ledger.clearStation(berth.villager)
                GunStations.unseat(level, berth.villager)
                refused++
            }
        }

        PathMessages.send(
            player,
            "${scopeName(side, layer)}: $assigned seated, ${manned + assigned} of ${scope.size} guns manned.",
            PathMessages.Kind.GOOD
        )
        val gripes = buildList {
            if (away > 0) add("$away couldn't be reached")
            if (refused > 0) add("$refused guns refused (footing or seat)")
            if (assigned < wanted && targets.isEmpty()) add("ran out of guns")
            if (assigned < wanted && targets.isNotEmpty() && candidates.isEmpty()) add("ran out of free hands")
        }
        if (gripes.isNotEmpty()) {
            PathMessages.send(player, gripes.joinToString(", ") + ".", PathMessages.Kind.WARN)
        }

        CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
        pushStores(op)
    }

    /**
     * Post the fire watch: [count] firefighters in total, locked ones included in the count.
     *
     * Ledger-only -- a duty survives being ashore, so no entity is required -- and the same three modes
     * the gun crew answers to, minus a scope, because a fire does not care which battery you favour:
     *
     * - [AssignMode.KEEP] leaves every hand already posted where it is and makes the difference up out of
     *   crew doing nothing at all. A gunner at his gun is somebody who already has a position, and this
     *   mode's whole promise is that it will not take him off it.
     * - [AssignMode.REASSIGN] posts from scratch: the first unlocked berths in slot order hold the duty
     *   and everyone else unlocked who held it stands down. Gunners MAY be drafted here and leave their
     *   guns (setDuty clears the binding) -- that is the long-standing rule, and now it is a choice.
     * - [AssignMode.RELEASE] stands the whole unlocked watch down.
     */
    fun requestAssignFirefighters(
        level: ServerLevel,
        player: ServerPlayer,
        helm: Long,
        count: Int,
        mode: AssignMode
    ) {
        val op = gate(level, player, helm) ?: return
        val ledger = CrewLedger.get(level.server)
        val berths = ledger.crew(op.key).sortedBy { it.slot }
        val total = count.coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)

        if (mode == AssignMode.RELEASE) {
            var released = 0
            for (berth in berths) {
                if (berth.locked || berth.duty != CrewDuty.FIREFIGHTER) continue
                ledger.setDuty(berth.villager, CrewDuty.NONE)
                released++
            }
            if (released == 0) {
                PathMessages.send(player, "No fire watch to release.", PathMessages.Kind.GOOD)
            } else {
                PathMessages.send(player, "Fire watch: $released released.", PathMessages.Kind.GOOD)
                CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
                pushStores(op)
            }
            return
        }

        val lockedWatch = berths.count { it.locked && it.duty == CrewDuty.FIREFIGHTER }

        if (mode == AssignMode.KEEP) {
            val onWatch = berths.count { it.duty == CrewDuty.FIREFIGHTER }
            var short = (total - onWatch).coerceAtLeast(0)
            if (short == 0) {
                PathMessages.send(player, "The fire watch already musters $onWatch.", PathMessages.Kind.GOOD)
                return
            }
            var added = 0
            for (berth in berths) {
                if (short == 0) break
                // Idle means idle: no duty, no gun, and no gun remembered from a ship that was packed up.
                if (berth.locked || berth.duty != CrewDuty.NONE) continue
                if (berth.station != null || berth.stationLabel != null) continue
                ledger.setDuty(berth.villager, CrewDuty.FIREFIGHTER)
                short--
                added++
            }
            PathMessages.send(player, "Fire watch: $added posted, ${onWatch + added} on watch.", PathMessages.Kind.GOOD)
            if (short > 0) {
                PathMessages.send(player, "No more idle crew to post.", PathMessages.Kind.WARN)
            }
            CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
            pushStores(op)
            return
        }

        var wanted = (total - lockedWatch).coerceAtLeast(0)

        var posted = 0
        for (berth in berths) {
            if (berth.locked) continue
            if (wanted > 0) {
                wanted--
                posted++
                if (berth.duty != CrewDuty.FIREFIGHTER) {
                    ledger.setDuty(berth.villager, CrewDuty.FIREFIGHTER)
                    GunStations.unseat(level, berth.villager)
                }
            } else if (berth.duty == CrewDuty.FIREFIGHTER) {
                ledger.setDuty(berth.villager, CrewDuty.NONE)
            }
        }

        val kept = if (lockedWatch == 0) "" else ", $lockedWatch locked kept"
        PathMessages.send(player, "Fire watch: $posted posted$kept.", PathMessages.Kind.GOOD)
        if (posted < total - lockedWatch) {
            PathMessages.send(player, "Only $posted unlocked crew to post.", PathMessages.Kind.WARN)
        }

        CrewManifest.sender(player, CrewManifest.build(level, player, op.station))
        pushStores(op)
    }

    /**
     * Run powder to every gun aboard, split evenly. Locked gunners' guns are supplied like any other --
     * powder is powder; a lock freezes settings, not logistics.
     *
     * Evenly, not bow-to-stern-until-dry, which is what this order used to do: a short supply that filled
     * the bow battery to the brim and left the stern with nothing answered a logistics order with a
     * tactical opinion. The split is planned in memory first -- fair share per pass, capped at each
     * magazine's room, leftovers re-shared until the powder or the room runs out -- and then moved once
     * per gun, forwardmost guns taking any indivisible remainder.
     */
    fun requestRestockPowder(level: ServerLevel, player: ServerPlayer, helm: Long) {
        val op = gate(level, player, helm) ?: return
        if (!storesManned(op)) return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }

        val order = labeled.filter { 3 * CannonBlockEntity.MAGAZINE_CAPACITY - it.gun.powderCount > 0 }
        if (order.isEmpty()) {
            PathMessages.send(player, "Every magazine is already full.", PathMessages.Kind.WARN)
            return
        }
        var avail = ShipStores.count(level, op.ship) { it.`is`(Items.GUNPOWDER) }
        if (avail == 0) {
            PathMessages.send(player, "No gunpowder in the holds.", PathMessages.Kind.WARN)
            return
        }

        val room = HashMap<CannonBlockEntity, Int>(order.size)
        for (named in order) room[named.gun] = 3 * CannonBlockEntity.MAGAZINE_CAPACITY - named.gun.powderCount

        val grant = HashMap<CannonBlockEntity, Int>(order.size)
        var open = order.map { it.gun }
        while (avail > 0 && open.isNotEmpty()) {
            val share = avail / open.size
            if (share == 0) {
                // Fewer grains than guns: one each to the forwardmost until it runs out.
                for (gun in open) {
                    if (avail == 0) break
                    grant.merge(gun, 1, Int::plus)
                    avail--
                }
                break
            }
            val next = ArrayList<CannonBlockEntity>(open.size)
            for (gun in open) {
                val give = minOf(share, room.getValue(gun) - (grant[gun] ?: 0))
                if (give > 0) {
                    grant.merge(gun, give, Int::plus)
                    avail -= give
                }
                if ((grant[gun] ?: 0) < room.getValue(gun)) next.add(gun)
            }
            open = next
        }

        var moved = 0
        var touched = 0
        for (named in order) {
            val quota = grant[named.gun] ?: continue
            val got = ShipStores.withdraw(level, op.ship, { it.`is`(Items.GUNPOWDER) }, quota)
            if (got == 0) break
            val stack = ItemStack(Items.GUNPOWDER, got)
            val loaded = named.gun.load(stack, true)
            if (loaded > 0) {
                moved += loaded
                touched++
            }
            if (!stack.isEmpty) {
                // The gun would not take what the holds gave (should not happen -- room was measured);
                // whatever is left goes back rather than vanishing.
                ShipStores.deposit(level, op.ship, stack)
            }
        }

        if (moved == 0) {
            PathMessages.send(player, "No gunpowder in the holds.", PathMessages.Kind.WARN)
        } else {
            PathMessages.send(player, "Distributed $moved powder over $touched guns, evenly.", PathMessages.Kind.GOOD)
        }
        pushStores(op)
    }

    /**
     * Run shot to [side]'s battery on deck [layer]: every gun in the scope filled to the brim with the
     * chosen round, in battery order.
     *
     * A gun already chambered with a DIFFERENT round has it swapped back into the holds first -- a
     * restock order means "arm the battery with THIS", not "top up whatever history left in each gun".
     * The two exceptions are both the lock rule: a locked gunner's gun refills with whatever it already
     * holds (its round is a setting, and settings are theirs), and a locked gun standing empty is left
     * exactly as its captain left it. Swap-backs that outsize the holds drop at the gun, never vanish.
     */
    fun requestRestockShot(
        level: ServerLevel,
        player: ServerPlayer,
        helm: Long,
        side: Side,
        ball: Cannonball,
        charge: CannonCharge,
        layer: Int
    ) {
        val op = gate(level, player, helm) ?: return
        if (!storesManned(op)) return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side, layer)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns in ${scopeName(side, layer).lowercase()}.", PathMessages.Kind.WARN)
            return
        }

        val ledger = CrewLedger.get(level.server)
        val lockedGuns = ledger.stationedBerths()
            .filter { it.locked }
            .mapNotNullTo(HashSet()) { it.station }
        val chosen = ItemStack(EurekaItems.cannonball(ball, charge))

        var moved = 0
        var swapped = 0
        for (named in guns) {
            val gun = named.gun
            val wantRef: ItemStack
            if (gun.blockPos.asLong() in lockedGuns) {
                if (gun.shot.isEmpty) continue
                wantRef = gun.shot.copyWithCount(1)
            } else {
                wantRef = chosen
                if (!gun.shot.isEmpty && !ItemStack.isSameItemSameComponents(gun.shot, chosen)) {
                    val out = gun.shot
                    gun.shot = ItemStack.EMPTY
                    gun.setChanged()
                    val rest = ShipStores.deposit(level, op.ship, out)
                    if (!rest.isEmpty) dropAtGun(level, op.ship, gun, rest)
                    swapped++
                }
            }

            val need = CannonBlockEntity.MAGAZINE_CAPACITY - gun.shot.count
            if (need <= 0) continue
            val got = ShipStores.withdraw(
                level, op.ship, { ItemStack.isSameItemSameComponents(it, wantRef) }, need
            )
            if (got > 0) {
                if (gun.shot.isEmpty) gun.shot = wantRef.copyWithCount(got) else gun.shot.grow(got)
                gun.setChanged()
                moved += got
            }
        }

        val swaps = if (swapped == 0) "" else " ($swapped guns re-armed)"
        if (moved == 0 && swapped == 0) {
            PathMessages.send(player, "Nothing to load -- the holds have none and the guns are full.", PathMessages.Kind.WARN)
        } else {
            PathMessages.send(player, "Distributed $moved shot$swaps.", PathMessages.Kind.GOOD)
        }
        pushStores(op)
    }

    /**
     * Lay [side]'s battery on deck [layer] to one elevation. [Side.BOTH] reads as ALL here, chasers
     * included -- "everything to twenty degrees" is an order about the ship, not about a broadside.
     *
     * Per-scope layings are independent by construction: this writes only the guns it addresses, so laying
     * Deck 1's port to +20 and then Deck 2's to +45 leaves Deck 1 exactly where it was put. LOCKED
     * gunners' guns are stepped over -- their elevation is a setting, and settings are theirs.
     */
    fun requestElevation(level: ServerLevel, player: ServerPlayer, helm: Long, side: Side, index: Int, layer: Int) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side, layer)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns in ${scopeName(side, layer).lowercase()}.", PathMessages.Kind.WARN)
            return
        }

        val clamped = index.coerceIn(0, MAX_ELEVATION)
        val ledger = CrewLedger.get(level.server)
        val lockedGuns = ledger.stationedBerths()
            .filter { it.locked }
            .mapNotNullTo(HashSet()) { it.station }

        var laid = 0
        for (named in guns) {
            if (named.gun.blockPos.asLong() in lockedGuns) continue
            layGun(level, named.gun, clamped)
            laid++
        }

        // One line, no per-gun sound: thirty lantern clanks is noise, and this is an order acknowledged.
        val degrees = EurekaProperties.elevationDegrees(clamped)
        val skipped = guns.size - laid
        val kept = if (skipped == 0) "" else " ($skipped locked kept)"
        PathMessages.send(player, "${scopeName(side, layer)} laid to ${formatDegrees(degrees)} -- $laid guns$kept.", PathMessages.Kind.GOOD)
    }

    /**
     * Set [side]'s battery on deck [layer] to one powder measure -- the bulk twin of the card's charge
     * control, and of [requestElevation] in shape: [Side.BOTH] reads as ALL, per-scope settings are
     * independent, and LOCKED gunners' guns keep the measure their captain set them.
     */
    fun requestPower(level: ServerLevel, player: ServerPlayer, helm: Long, side: Side, ordinal: Int, layer: Int) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side, layer)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns in ${scopeName(side, layer).lowercase()}.", PathMessages.Kind.WARN)
            return
        }

        val charge = PowderCharge.of(ordinal.coerceIn(0, PowderCharge.entries.size - 1))
        val ledger = CrewLedger.get(level.server)
        val lockedGuns = ledger.stationedBerths()
            .filter { it.locked }
            .mapNotNullTo(HashSet()) { it.station }

        var set = 0
        for (named in guns) {
            if (named.gun.blockPos.asLong() in lockedGuns) continue
            named.gun.powderCharge = charge
            named.gun.setChanged()
            set++
        }

        val skipped = guns.size - set
        val kept = if (skipped == 0) "" else " ($skipped locked kept)"
        PathMessages.send(player, "${scopeName(side, layer)} set to ${charge.powder}x power -- $set guns$kept.", PathMessages.Kind.GOOD)
    }

    /** Set one gunner's cannon to a powder charge, from their card. The card's own optimism confirms here. */
    fun requestGunCharge(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID, ordinal: Int) {
        val op = gate(level, player, helm) ?: return
        val (_, gun) = cardGun(op, villager) ?: return
        gun.powderCharge = PowderCharge.of(ordinal.coerceIn(0, PowderCharge.entries.size - 1))
        gun.setChanged()
        pushDetail(op, villager)
    }

    /** Lay one gunner's cannon to an elevation, from their card. */
    fun requestGunElevation(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID, index: Int) {
        val op = gate(level, player, helm) ?: return
        val (_, gun) = cardGun(op, villager) ?: return
        layGun(level, gun, index.coerceIn(0, MAX_ELEVATION))
        pushDetail(op, villager)
    }

    /**
     * Arm one gunner's cannon with a chosen round, from their card: the single-gun form of the shot
     * restock's unlocked path. A different chambered round goes back to the holds first (overflow drops
     * at the gun, never vanishes), then the gun fills with the chosen kind from the holds.
     */
    fun requestGunAmmo(
        level: ServerLevel,
        player: ServerPlayer,
        helm: Long,
        villager: UUID,
        ball: Cannonball,
        charge: CannonCharge
    ) {
        val op = gate(level, player, helm) ?: return
        val (berth, gun) = cardGun(op, villager) ?: return
        val chosen = ItemStack(EurekaItems.cannonball(ball, charge))

        if (!gun.shot.isEmpty && !ItemStack.isSameItemSameComponents(gun.shot, chosen)) {
            val out = gun.shot
            gun.shot = ItemStack.EMPTY
            gun.setChanged()
            val rest = ShipStores.deposit(level, op.ship, out)
            if (!rest.isEmpty) dropAtGun(level, op.ship, gun, rest)
        }

        val need = CannonBlockEntity.MAGAZINE_CAPACITY - gun.shot.count
        var got = 0
        if (need > 0) {
            got = ShipStores.withdraw(
                level, op.ship, { ItemStack.isSameItemSameComponents(it, chosen) }, need
            )
            if (got > 0) {
                if (gun.shot.isEmpty) gun.shot = chosen.copyWithCount(got) else gun.shot.grow(got)
                gun.setChanged()
            }
        }

        if (got == 0 && gun.shot.isEmpty) {
            PathMessages.send(player, "The holds have no ${chosen.hoverName.string} for ${berth.name}'s gun.", PathMessages.Kind.WARN)
        }
        pushDetail(op, villager)
        pushStores(op)
    }

    /**
     * Stoke every engine aboard, evenly, best fuel first.
     *
     * Every fuel kind in the holds is ranked by how long one of it burns, and spent in that order --
     * the list the fuel popup shows IS the plan. Within one kind the split is even: what the holds hold
     * divided by the engines that can take it, remainder to the first engines in the fixed engine order.
     * An engine already burning a lesser fuel keeps its kind and is topped up when that kind's turn
     * comes -- one firebox holds one fuel, and swapping a burning reserve out would waste it.
     */
    fun requestRefuel(level: ServerLevel, player: ServerPlayer, helm: Long) {
        val op = gate(level, player, helm) ?: return
        if (!storesManned(op)) return
        val engines = org.valkyrienskies.eureka.ship.ShipEngines.aboard(level, op.ship)
        if (engines.isEmpty()) {
            PathMessages.send(player, "No engines aboard.", PathMessages.Kind.ERROR)
            return
        }

        val fuels = ShipStores.tally(level, op.ship).fuels
        if (fuels.isEmpty()) {
            PathMessages.send(player, "No fuel in the holds.", PathMessages.Kind.WARN)
            return
        }

        val stoked = HashSet<Long>()
        val spent = LinkedHashMap<String, Int>()

        for (kind in fuels) {
            val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(kind.itemId)).orElse(null) ?: continue
            val ref = ItemStack(item)
            val matches = { it: ItemStack -> ItemStack.isSameItemSameComponents(it, ref) }

            while (true) {
                val eligible = engines.filter { engine ->
                    engine.canPlaceItem(0, ref) && when {
                        engine.fuel.isEmpty -> true
                        ItemStack.isSameItemSameComponents(engine.fuel, ref) -> engine.fuel.count < ref.maxStackSize
                        else -> false
                    }
                }
                if (eligible.isEmpty()) break
                val avail = ShipStores.count(level, op.ship, matches)
                if (avail == 0) break

                val share = avail / eligible.size
                val remainder = avail % eligible.size
                var movedThisPass = 0

                for ((index, engine) in eligible.withIndex()) {
                    val quota = share + if (index < remainder) 1 else 0
                    if (quota <= 0) continue
                    val got = ShipStores.withdraw(level, op.ship, matches, quota)
                    if (got == 0) break
                    val stack = ItemStack(item, got)
                    val loaded = engine.load(stack, true)
                    if (loaded > 0) {
                        movedThisPass += loaded
                        stoked.add(engine.blockPos.asLong())
                    }
                    if (!stack.isEmpty) ShipStores.deposit(level, op.ship, stack)
                }

                if (movedThisPass == 0) break
                spent.merge(ref.hoverName.string, movedThisPass, Int::plus)
            }
        }

        if (spent.isEmpty()) {
            PathMessages.send(player, "Every engine is already stoked full.", PathMessages.Kind.WARN)
        } else {
            val bill = spent.entries.joinToString(", ") { (name, n) -> "$n $name" }
            PathMessages.send(player, "Stoked ${stoked.size} engines: $bill.", PathMessages.Kind.GOOD)
        }
        pushStores(op)
    }

    // endregion

    // region lookups and helpers

    /** One order's resolved standing: the wheel, the articles, the vessel. Null means the gate refused. */
    private class Op(
        val level: ServerLevel,
        val player: ServerPlayer,
        val station: ShipHelmBlockEntity,
        val key: CrewLedger.Key,
        val ship: LoadedServerShip
    )

    /**
     * The same authority every manifest action answers to: the wheel resolves, holds articles, and the
     * captain is within reach of it or standing anywhere on its armada.
     */
    private fun gate(level: ServerLevel, player: ServerPlayer, helm: Long): Op? {
        val station = CrewManifest.stationAt(level, player, helm) ?: return null
        val key = CrewManifest.crewKey(player, station) ?: return null
        val ship = CrewStations.shipOf(level, station) ?: return null
        return Op(level, player, station, key, ship)
    }

    private fun pushStores(op: Op) {
        val decks = GunLabels.layerCounts(GunLabels.labeled(op.level, op.ship))
        val firing = op.ship.getAttachment(EurekaShipControl::class.java)?.fireAtWill ?: false
        storesSender(
            op.player, op.station.blockPos.asLong(), ShipStores.tally(op.level, op.ship), decks, firing
        )
    }

    /**
     * Whether anyone is free to run the stores, said out loud when nobody is.
     *
     * A Crewman -- the duty everyone signs on with -- is who carries cargo, and every Restock order needs
     * at least one alive and ABOARD this crew's ship right now: a captain who posts every last hand to a
     * gun or the fire watch has nobody left to run powder to them, and a crewman standing ashore is no
     * help to a ship at sea. LOCKED crewmen count in full -- a lock freezes a crew member's settings,
     * never their duty. The gunner's own card stays ungated: a man arms his own gun.
     */
    private fun storesManned(op: Op): Boolean {
        val ledger = CrewLedger.get(op.level.server)
        val manned = CrewMuster.villagersIn(op.level, op.ship.worldAABB).any { villager ->
            ledger.crewOf(villager.uuid) == op.key &&
                ledger.berthOf(villager.uuid)?.duty == CrewDuty.NONE
        }
        if (!manned) {
            PathMessages.send(
                op.player,
                "Nobody to run the stores -- every hand has a post. Crewmen carry the cargo.",
                PathMessages.Kind.ERROR
            )
        }
        return manned
    }

    /**
     * The guns an order addresses, in the order they are served: [GunLabels.labeled] filtered by side and
     * by deck. Layer [ALL_LAYERS] is every deck, exactly as [Side.BOTH] is every side.
     */
    private fun sideGuns(labeled: List<GunLabels.Labeled>, side: Side, layer: Int): List<GunLabels.Labeled> {
        val onDeck = if (layer == ALL_LAYERS) labeled else labeled.filter { it.layer == layer }
        return when (side) {
            Side.PORT -> onDeck.filter { it.label.startsWith(PORT_GROUP) }
            Side.STARBOARD -> onDeck.filter { it.label.startsWith(STARBOARD_GROUP) }
            Side.BOTH -> onDeck
        }
    }

    /**
     * Turn this crew's unlocked gunners back into free hands, and say how many. A null [scope] means every
     * gunner they have, wherever they stand -- Reassign's clean slate; a scope limits it to the guns that
     * list covers, which is Release.
     *
     * Matched by the gun a berth HOLDS and by the label it is OWED: a gunner whose ship was packed up
     * under him keeps only the label until the reconcile walks him back, and an order that clears his deck
     * plainly means him too. A berth carrying the duty but no gun at all is swept up by the clean slate,
     * since "from scratch" is a statement about the whole gun crew. `setDuty` drops the station and the
     * label together -- a duty change is an order to do something else, so there is no gun left to
     * remember -- and the seat is emptied at once rather than left for the next reconcile pass to notice.
     */
    private fun standDownGunners(
        level: ServerLevel,
        ledger: CrewLedger,
        op: Op,
        scope: List<GunLabels.Labeled>?
    ): Int {
        val positions = scope?.mapTo(HashSet()) { it.gun.blockPos.asLong() }
        val labels = scope?.mapTo(HashSet()) { it.label }
        var released = 0
        for (berth in ledger.crew(op.key)) {
            if (berth.locked) continue
            val station = berth.station
            val label = berth.stationLabel
            if (station == null && label == null && berth.duty != CrewDuty.GUNNER) continue
            if (positions != null && labels != null) {
                val here = (station != null && station in positions) || (label != null && label in labels)
                if (!here) continue
            }
            ledger.setDuty(berth.villager, CrewDuty.NONE)
            GunStations.unseat(level, berth.villager)
            released++
        }
        return released
    }

    /**
     * The DEALING order for gunner assignment. A single side is that side bow to stern; BOTH alternates
     * L, R, L, R... so both broadsides man up evenly, the longer side's tail follows, and the chasers
     * come last -- a half crew answers on both sides before anybody minds the bow gun. The deck filter
     * runs first, so "both sides of Deck 2" alternates across that one deck.
     */
    private fun assignmentSeq(labeled: List<GunLabels.Labeled>, side: Side, layer: Int): List<GunLabels.Labeled> {
        if (side != Side.BOTH) return sideGuns(labeled, side, layer)
        val onDeck = if (layer == ALL_LAYERS) labeled else labeled.filter { it.layer == layer }
        val port = onDeck.filter { it.label.startsWith(PORT_GROUP) }
        val starboard = onDeck.filter { it.label.startsWith(STARBOARD_GROUP) }
        val rest = onDeck.filterNot { it.label.startsWith(PORT_GROUP) || it.label.startsWith(STARBOARD_GROUP) }

        val dealt = ArrayList<GunLabels.Labeled>(onDeck.size)
        for (i in 0 until maxOf(port.size, starboard.size)) {
            port.getOrNull(i)?.let { dealt.add(it) }
            starboard.getOrNull(i)?.let { dealt.add(it) }
        }
        dealt.addAll(rest)
        return dealt
    }

    /**
     * How an order's scope reads back to the captain: the side, the deck, or both, said the way an
     * acknowledgement would say it. Doubles as the "no guns there" complaint via `lowercase()`.
     */
    private fun scopeName(side: Side, layer: Int): String = when (side) {
        Side.PORT -> if (layer == ALL_LAYERS) "Port battery" else "Deck $layer's port battery"
        Side.STARBOARD -> if (layer == ALL_LAYERS) "Starboard battery" else "Deck $layer's starboard battery"
        Side.BOTH -> if (layer == ALL_LAYERS) "Every gun" else "All of Deck $layer"
    }

    /**
     * The card's gun-control gate, shared by charge, elevation and ammo: the berth must be [player]'s to
     * command (CrewManifest's own authorisation), unlocked, and stationed at a cannon that still stands.
     * Refusals speak, and the no-gun case also refreshes the card so a stale one corrects itself.
     */
    private fun cardGun(op: Op, villager: UUID): Pair<CrewLedger.Berth, CannonBlockEntity>? {
        val berth = CrewManifest.berthFor(op.level, op.player, op.station, villager) ?: return null
        if (berth.locked) {
            PathMessages.send(op.player, "${berth.name} is locked -- unlock them first.", PathMessages.Kind.ERROR)
            return null
        }
        val gun = berth.station?.let { op.level.getBlockEntity(BlockPos.of(it)) as? CannonBlockEntity }
        if (gun == null) {
            PathMessages.send(op.player, "${berth.name} has no gun to lay.", PathMessages.Kind.ERROR)
            pushDetail(op, villager)
            return null
        }
        return berth to gun
    }

    private fun pushDetail(op: Op, villager: UUID) {
        CrewManifest.detailFor(op.level, op.player, op.station, villager)?.let {
            CrewManifest.detailSender(op.player, it)
        }
    }

    /**
     * Re-pose both halves of one cannon to an elevation index. The block-entity sits on the REAR block, so
     * the parts walk forward from it -- the same loop the crouch-click gesture runs, minus its sound: bulk
     * callers speak once for the battery, and the card's caller answers with the card itself.
     */
    private fun layGun(level: ServerLevel, gun: CannonBlockEntity, index: Int) {
        val state = gun.blockState
        if (state.block !is CannonBlock) return
        val facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
        for (part in CannonPart.entries) {
            val pos = gun.blockPos.relative(facing, part.ordinal)
            val there = level.getBlockState(pos)
            if (there.block !is CannonBlock) continue
            level.setBlock(pos, there.setValue(EurekaProperties.ELEVATION, index), Block.UPDATE_CLIENTS)
        }
    }

    private fun formatDegrees(degrees: Double): String =
        if (degrees > 0) "+${degrees.toInt()}°" else "${degrees.toInt()}°"

    /** What the holds would not take back lands at the gun -- visible, retrievable, never voided. */
    private fun dropAtGun(level: ServerLevel, ship: LoadedServerShip, gun: CannonBlockEntity, stack: ItemStack) {
        val world = ship.shipToWorld.transformPosition(
            Vector3d(gun.blockPos.x + 0.5, gun.blockPos.y + 1.0, gun.blockPos.z + 0.5)
        )
        val drop = ItemEntity(level, world.x, world.y, world.z, stack)
        drop.setDefaultPickUpDelay()
        level.addFreshEntity(drop)
    }

    private const val PORT_GROUP = "L"
    private const val STARBOARD_GROUP = "R"

    /** The layer argument meaning "every deck" -- the deck dropdown's All, as [Side.BOTH] is the sides'. */
    const val ALL_LAYERS = 0

    /** The top of the ELEVATION property's range: index 18, +45 degrees. */
    private const val MAX_ELEVATION = 18

    // endregion
}
