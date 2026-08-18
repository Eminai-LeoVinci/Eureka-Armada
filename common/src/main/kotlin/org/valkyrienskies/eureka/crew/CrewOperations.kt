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
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.path.PathMessages
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
     * Pushes a stores tally at one screen, along with the guns-per-deck census the deck dropdown lists.
     * Installed by the platform networking, exactly as `CrewManifest.sender` is; the default no-op keeps
     * loaders without the payload honest. The deck counts ride this payload rather than the manifest
     * because they change with the same gestures the holds do -- every ops order refreshes both at once.
     */
    @Volatile
    @JvmField
    var storesSender: (ServerPlayer, Long, ShipStores.Stores, List<Int>) -> Boolean = { _, _, _, _ -> false }

    // region the orders

    /** Answer a screen asking what the holds hold. */
    fun requestStores(level: ServerLevel, player: ServerPlayer, helm: Long) {
        val op = gate(level, player, helm) ?: return
        pushStores(op)
    }

    /**
     * Man [side]'s guns on deck [layer]: seat gunners at EMPTY guns in that scope until [count] of them
     * are manned in total, and touch nothing else.
     *
     * Fill-only, on purpose -- the opposite of the whole-battery re-deal this order used to be. An order
     * about Deck 1's port battery is not permission to unseat Deck 2: gunners already at their guns stay
     * exactly where they are, locked or not, and count toward the total. Asking for no more than are
     * already manned changes nothing and says so. New hands come from the first unlocked berths on the
     * articles that are not holding a gun ANYWHERE and are not waiting on a remembered one -- an idle
     * firefighter may be drafted (a duty is an order, not a shackle), but a gunner on another deck is
     * never poached off it by an order about this one, and a stood-down gunner whose label is about to
     * walk him back to his own gun is left for the reconcile that is already carrying him there.
     *
     * BOTH deals alternately -- L, R, L, R -- so a half-manned ship still answers on both broadsides; a
     * single side fills bow to stern. Guns held by another captain's crew are never dealt.
     */
    fun requestAssignGunners(level: ServerLevel, player: ServerPlayer, helm: Long, count: Int, side: Side, layer: Int) {
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
        // Anybody's stationed berth mans a gun -- locked, another captain's, all of them. The count the
        // captain asked for is "how many of these guns have somebody behind them", not "how many are mine".
        val held = ledger.stationedBerths().mapNotNullTo(HashSet()) { it.station }
        val manned = scope.count { it.gun.blockPos.asLong() in held }
        val total = count.coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)
        val wanted = (total - manned).coerceAtLeast(0)
        if (wanted == 0) {
            PathMessages.send(player, "${scopeName(side, layer)} already musters $manned gunners.", PathMessages.Kind.GOOD)
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
     * Ledger-only -- a duty survives being ashore, so no entity is required -- and the same from-scratch
     * shape as the gun crew: the first unlocked berths in slot order hold the duty, everyone else
     * unlocked who held it stands down. Gunners drafted to the watch leave their guns (setDuty clears
     * the binding); the reverse draft is the gun-crew order's business.
     */
    fun requestAssignFirefighters(level: ServerLevel, player: ServerPlayer, helm: Long, count: Int) {
        val op = gate(level, player, helm) ?: return
        val ledger = CrewLedger.get(level.server)
        val berths = ledger.crew(op.key).sortedBy { it.slot }
        val total = count.coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)

        val lockedWatch = berths.count { it.locked && it.duty == CrewDuty.FIREFIGHTER }
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
     * included -- "everything to twenty-two and a half" is an order about the ship, not about a broadside.
     *
     * Per-scope layings are independent by construction: this writes only the guns it addresses, so laying
     * Deck 1's port to +22.5 and then Deck 2's to +45 leaves Deck 1 exactly where it was put. LOCKED
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
        storesSender(op.player, op.station.blockPos.asLong(), ShipStores.tally(op.level, op.ship), decks)
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
        if (degrees > 0) "+%.1f°".format(degrees) else "%.1f°".format(degrees)

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

    /** The top of the ELEVATION property's range: index 4, +45 degrees. */
    private const val MAX_ELEVATION = 4

    // endregion
}
