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
 * everything is served in: port bow to stern, then starboard, then the chasers), and the authority from
 * the same gate every manifest action uses: the wheel's articles, reachable from anywhere aboard.
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
     * Pushes a stores tally at one screen. Installed by the platform networking, exactly as
     * `CrewManifest.sender` is; the default no-op keeps loaders without the payload honest.
     */
    @Volatile
    @JvmField
    var storesSender: (ServerPlayer, Long, ShipStores.Stores) -> Boolean = { _, _, _ -> false }

    // region the orders

    /** Answer a screen asking what the holds hold. */
    fun requestStores(level: ServerLevel, player: ServerPlayer, helm: Long) {
        val op = gate(level, player, helm) ?: return
        pushStores(op)
    }

    /**
     * Lay out the gun crew: [count] gunners in total, distributed over [side]'s guns.
     *
     * From scratch, on purpose: every UNLOCKED gunner of this crew leaves their gun first, and the new
     * layout is dealt to the first berths on the articles -- slot order, any current duty, firefighters
     * included. The count is the size of the whole gun crew, locked gunners included: ask for six with
     * two locked and four more are seated. Asking for fewer than the locked count seats nobody new and
     * stands every unlocked gunner down, which is exactly what "from scratch" promises.
     *
     * BOTH deals alternately -- L1, R1, L2, R2... -- so a half-manned ship still answers on both
     * broadsides; a single side fills bow to stern. Locked gunners' guns and guns held by another
     * captain's crew are never dealt.
     */
    fun requestAssignGunners(level: ServerLevel, player: ServerPlayer, helm: Long, count: Int, side: Side) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }

        val ledger = CrewLedger.get(level.server)
        val berths = ledger.crew(op.key).sortedBy { it.slot }
        val total = count.coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)

        val lockedGunners = berths.filter { it.locked && it.duty == CrewDuty.GUNNER }
        val lockedGuns = lockedGunners.mapNotNullTo(HashSet()) { it.station }
        val foreignHeld = ledger.stationedBerths()
            .filter { ledger.crewOf(it.villager) != op.key }
            .mapNotNullTo(HashSet()) { it.station }

        // From scratch: the whole unlocked gun crew leaves their seats before the new hand is dealt.
        for (berth in berths) {
            if (berth.locked || berth.duty != CrewDuty.GUNNER) continue
            ledger.clearStation(berth.villager)
            GunStations.unseat(level, berth.villager)
        }

        val targets = ArrayDeque(
            assignmentSeq(labeled, side).filter {
                val packed = it.gun.blockPos.asLong()
                packed !in lockedGuns && packed !in foreignHeld
            }
        )

        val wanted = (total - lockedGunners.size).coerceAtLeast(0)
        var assigned = 0
        var away = 0
        var refused = 0
        val seated = HashSet<UUID>()

        for (berth in berths) {
            if (assigned >= wanted || targets.isEmpty()) break
            if (berth.locked) continue

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
                seated.add(berth.villager)
            } else {
                ledger.clearStation(berth.villager)
                GunStations.unseat(level, berth.villager)
                refused++
            }
        }

        // The other half of "from scratch": an unlocked GUNNER this deal did not seat is not a gunner.
        for (berth in ledger.crew(op.key)) {
            if (berth.locked || berth.duty != CrewDuty.GUNNER) continue
            if (berth.villager in seated) continue
            ledger.setDuty(berth.villager, CrewDuty.NONE)
            GunStations.unseat(level, berth.villager)
        }

        val kept = if (lockedGunners.isEmpty()) "" else ", ${lockedGunners.size} locked kept"
        PathMessages.send(player, "Gun crew: $assigned seated$kept.", PathMessages.Kind.GOOD)
        val gripes = buildList {
            if (away > 0) add("$away couldn't be reached")
            if (refused > 0) add("$refused guns refused (footing or seat)")
            if (assigned < wanted && targets.isEmpty()) add("ran out of guns")
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
     * Run powder to [side]'s battery: every gun's three charge slots filled to the brim, bow to stern,
     * from the holds. Locked gunners' guns are supplied like any other -- powder is powder; a lock
     * freezes settings, not logistics. Stops, and says where, the moment the holds run dry.
     */
    fun requestRestockPowder(level: ServerLevel, player: ServerPlayer, helm: Long, side: Side) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns on that side.", PathMessages.Kind.WARN)
            return
        }

        var moved = 0
        var dryAt: String? = null
        outer@ for (named in guns) {
            val gun = named.gun
            var need = 3 * CannonBlockEntity.MAGAZINE_CAPACITY - gun.powderCount
            while (need > 0) {
                val got = ShipStores.withdraw(level, op.ship, { it.`is`(Items.GUNPOWDER) }, need)
                if (got == 0) {
                    dryAt = named.label
                    break@outer
                }
                val stack = ItemStack(Items.GUNPOWDER, got)
                val loaded = gun.load(stack, true)
                moved += loaded
                need -= loaded
                if (!stack.isEmpty) {
                    // The gun would not take what the holds gave (should not happen -- need was measured);
                    // whatever is left goes back rather than vanishing.
                    ShipStores.deposit(level, op.ship, stack)
                    break
                }
                if (loaded == 0) break
            }
        }

        if (moved == 0 && dryAt != null) {
            PathMessages.send(player, "No gunpowder in the holds.", PathMessages.Kind.WARN)
        } else {
            PathMessages.send(player, "Distributed $moved powder.", PathMessages.Kind.GOOD)
            if (dryAt != null) {
                PathMessages.send(player, "Holds ran dry at $dryAt.", PathMessages.Kind.WARN)
            }
        }
        pushStores(op)
    }

    /**
     * Run shot to [side]'s battery: every gun filled to the brim with the chosen round, in battery order.
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
        charge: CannonCharge
    ) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns on that side.", PathMessages.Kind.WARN)
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
     * Lay [side]'s battery to one elevation. [Side.BOTH] reads as ALL here, chasers included -- "everything
     * to twenty-two and a half" is an order about the ship, not about a broadside.
     *
     * Per-side layings are independent by construction: this writes only the guns it addresses, so laying
     * port to +22.5 and then starboard to +45 leaves port exactly where it was put. LOCKED gunners' guns
     * are stepped over -- their elevation is a setting, and settings are theirs.
     */
    fun requestElevation(level: ServerLevel, player: ServerPlayer, helm: Long, side: Side, index: Int) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns on that side.", PathMessages.Kind.WARN)
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
        val battery = when (side) {
            Side.PORT -> "Port battery"
            Side.STARBOARD -> "Starboard battery"
            Side.BOTH -> "Every gun"
        }
        val skipped = guns.size - laid
        val kept = if (skipped == 0) "" else " ($skipped locked kept)"
        PathMessages.send(player, "$battery laid to ${formatDegrees(degrees)} -- $laid guns$kept.", PathMessages.Kind.GOOD)
    }

    /**
     * Set [side]'s battery to one powder measure -- the bulk twin of the card's charge control, and of
     * [requestElevation] in shape: [Side.BOTH] reads as ALL, per-side settings are independent, and LOCKED
     * gunners' guns keep the measure their captain set them.
     */
    fun requestPower(level: ServerLevel, player: ServerPlayer, helm: Long, side: Side, ordinal: Int) {
        val op = gate(level, player, helm) ?: return
        val labeled = GunLabels.labeled(level, op.ship)
        if (labeled.isEmpty()) {
            PathMessages.send(player, "No bow to number the guns from -- the ship needs a crew-station wheel.", PathMessages.Kind.ERROR)
            return
        }
        val guns = sideGuns(labeled, side)
        if (guns.isEmpty()) {
            PathMessages.send(player, "No guns on that side.", PathMessages.Kind.WARN)
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

        val battery = when (side) {
            Side.PORT -> "Port battery"
            Side.STARBOARD -> "Starboard battery"
            Side.BOTH -> "Every gun"
        }
        val skipped = guns.size - set
        val kept = if (skipped == 0) "" else " ($skipped locked kept)"
        PathMessages.send(player, "$battery set to ${charge.powder}x power -- $set guns$kept.", PathMessages.Kind.GOOD)
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
        storesSender(op.player, op.station.blockPos.asLong(), ShipStores.tally(op.level, op.ship))
    }

    /** The guns an order addresses, in the order they are served: [GunLabels.labeled] filtered by side. */
    private fun sideGuns(labeled: List<GunLabels.Labeled>, side: Side): List<GunLabels.Labeled> =
        when (side) {
            Side.PORT -> labeled.filter { it.label.startsWith(PORT_GROUP) }
            Side.STARBOARD -> labeled.filter { it.label.startsWith(STARBOARD_GROUP) }
            Side.BOTH -> labeled
        }

    /**
     * The DEALING order for gunner assignment. A single side is that side bow to stern; BOTH alternates
     * L1, R1, L2, R2... so both broadsides man up evenly, the longer side's tail follows, and the
     * chasers come last -- a half crew answers on both sides before anybody minds the bow gun.
     */
    private fun assignmentSeq(labeled: List<GunLabels.Labeled>, side: Side): List<GunLabels.Labeled> {
        if (side != Side.BOTH) return sideGuns(labeled, side)
        val port = labeled.filter { it.label.startsWith(PORT_GROUP) }
        val starboard = labeled.filter { it.label.startsWith(STARBOARD_GROUP) }
        val rest = labeled.filterNot { it.label.startsWith(PORT_GROUP) || it.label.startsWith(STARBOARD_GROUP) }

        val dealt = ArrayList<GunLabels.Labeled>(labeled.size)
        for (i in 0 until maxOf(port.size, starboard.size)) {
            port.getOrNull(i)?.let { dealt.add(it) }
            starboard.getOrNull(i)?.let { dealt.add(it) }
        }
        dealt.addAll(rest)
        return dealt
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

    /** The top of the ELEVATION property's range: index 4, +45 degrees. */
    private const val MAX_ELEVATION = 4

    // endregion
}
