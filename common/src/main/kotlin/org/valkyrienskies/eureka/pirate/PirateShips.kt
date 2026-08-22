package org.valkyrienskies.eureka.pirate

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.raid.Raider
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.template.PlacementCheck
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.follow.FollowGeometry
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.follow.ShipFollows
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipIntegrity
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.eureka.util.WeightedNames
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.assembly.ShipAssembler as VSShipAssembler
import org.valkyrienskies.mod.util.logger
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Every generated pirate ship's keeper: the berth ledger, the proximity zones, the wake-up, the pursuit.
 *
 * ## How a generated ship reaches this class at all
 * Worldgen is pure datapack -- no code runs when a pirate ship is placed -- so the ship must introduce
 * itself. The PIRATE-marked helm block entity reports here every tick it is loaded (the same "no lifecycle
 * hook fires at the new address, so report every tick" reasoning as BottleBindings), and the first report
 * from a wheel with no berth key ADOPTS it: a [PirateStore.Berth] is written for the site, the pillagers
 * standing the deck are pinned to it, and the wheel is given its key. Adoption is idempotent by
 * construction -- it is keyed on the null berth field.
 *
 * ## Runtime vs persisted
 * Everything in this object is rebuildable from the world and dies with the server ([reset]); the durable
 * facts live in [PirateStore] (sites, long deadlines) and on the helm itself (papers). A restart mid-anything
 * therefore recovers: the helm reports, the berth is looked up, and the machine re-enters a sane state.
 */
object PirateShips {

    private val logger by logger()

    /** What a pirate wheel said about itself this tick. Refreshed continuously; stale entries are ignored. */
    class Report(
        val helmPos: BlockPos,
        val shipId: Long?,
        val lastSeen: Long,
        val helm: ShipHelmBlockEntity
    )

    /** An arm order given: the ship is assembling, and the chase begins when its wheel reports back. */
    private class Arming(val leaderId: Long?, val playerId: UUID?, val deadline: Long)

    /**
     * A wakened pirate's whole life after the countdown: pursue while the follow holds, LINGER assembled
     * for a window after it breaks -- during which a trespasser in the zone resumes the pursuit at once,
     * no countdown -- then stand down, disassemble, and retire, which is what hands the berth back to the
     * proximity scan and its full 15-second ceremony.
     */
    class PirateChase(
        val berthId: Long,
        var leaderId: Long?,
        var lingerUntil: Long,
        /** When this chase was created -- the newborn grace below is measured from here. */
        val bornAt: Long,
        var standingDown: Boolean = false,
        var nextDisassembleTry: Long = 0L,
        /** When this hull first found something in the way of its own stand-down. 0 while unobstructed. */
        var clearingSince: Long = 0L,
        /** The guns' bookkeeping, riding the chase so both go quiet by the same door. See PirateGunnery. */
        val gunnery: PirateGunnery.State = PirateGunnery.State()
    )

    /** A hull crossing a zone's line with somebody in its influence -- the only thing that wakes a pirate. */
    private class Trespass(val ship: LoadedServerShip, val players: List<ServerPlayer>)

    // berthId -> latest self-report / countdown deadline / arm order. All game-thread only.
    private val reports = HashMap<Long, Report>()
    private val countdowns = HashMap<Long, Long>()
    private val arming = HashMap<Long, Arming>()

    /** pirate shipId -> its chase. */
    private val chases = HashMap<Long, PirateChase>()

    /** berthId -> the deadline for its vanished wheel to report back before the vanishing is a conquest. */
    private val vanished = HashMap<Long, Long>()

    // -1 = never, and the guard tests for it explicitly. NOT Long.MIN_VALUE: `now - MIN_VALUE` overflows
    // negative, which made the cooldown check refuse every wake-up the manager ever attempted -- the
    // countdown expired, nothing assembled, and the zone quietly re-armed. Found in the first field test.
    private var lastAssemblyAt = -1L
    private var tickCount = 0L

    // region reporting + adoption

    /**
     * A pirate wheel says where it is. Called from the helm block entity's tick for any MARK != NORMAL.
     *
     * First contact with an unadopted wheel (null berth key) creates its berth. The berth is keyed by the
     * wheel's position at adoption -- for a freshly generated ship that is its world position, which IS the
     * spawn site. The same key goes onto the wheel, so however far the ship later sails (the wheel's address
     * changes to the shipyard at assembly), its reports still land on the same berth.
     */
    fun report(level: ServerLevel, pos: BlockPos, helm: ShipHelmBlockEntity) {
        if (!EurekaConfig.SERVER.pirateShipsEnabled) return

        val shipId = level.getLoadedShipManagingPos(pos)?.id

        var berthId = helm.pirateBerth
        if (berthId == null) {
            berthId = adopt(level, pos, helm, shipId)
            if (berthId == null) return
        }
        reports[berthId] = Report(pos, shipId, level.gameTime, helm)
        // A report is life: whatever vanished, it was a relocation, not a break.
        vanished.remove(berthId)

        // Remember where she IS, in the world, for the ring to be drawn at once she stops reporting. A
        // wheel's own address is a shipyard one the moment she assembles, so it is the transformed centre
        // that is worth keeping -- see PirateStore.Berth.lastPos.
        PirateStore.get(level).berth(berthId)?.let { berth ->
            val centre = helmWorldCentre(level, pos)
            val here = BlockPos.containing(centre.x, centre.y, centre.z).asLong()
            if (berth.lastPos != here) {
                berth.lastPos = here
                PirateStore.get(level).markDirty()
            }
        }

        // A world copied without its SavedData, or a store lost to a crash: re-create the site record so
        // the wheel is not left reporting into the void forever.
        val store = PirateStore.get(level)
        if (store.berth(berthId) == null) {
            val size = helm.pirateTemplate?.let { ShipTemplate.find(level, it)?.size }
            store.putBerth(
                berthId,
                PirateStore.Berth(
                    originPos = berthId,
                    templateId = helm.pirateTemplate ?: "pirate/large1",
                    sizeX = size?.x ?: 16, sizeY = size?.y ?: 16, sizeZ = size?.z ?: 16
                )
            )
        }
    }

    private fun adopt(level: ServerLevel, pos: BlockPos, helm: ShipHelmBlockEntity, shipId: Long?): Long? {
        val templateId = helm.pirateTemplate ?: return null // a set-mark'd player helm has no papers: not ours
        val berthId = pos.asLong()
        val size = ShipTemplate.find(level, templateId)?.size

        PirateStore.get(level).putBerth(
            berthId,
            PirateStore.Berth(
                originPos = berthId,
                templateId = templateId,
                sizeX = size?.x ?: 16, sizeY = size?.y ?: 16, sizeZ = size?.z ?: 16
            )
        )

        // Pin the crew. Persistence is already stamped in the template, but the leash cannot be: setHomeTo
        // needs a world position that does not exist until the ship does. 1.21.11 persists home_pos/_radius,
        // so this survives saves. The tag is what later stages recognise the crew by.
        val half = ((size?.x ?: 16) + (size?.z ?: 16)) / 4.0 + 4.0
        val box = AABB(
            pos.x - half, pos.y - 4.0, pos.z - half,
            pos.x + half, pos.y + (size?.y ?: 16) + 4.0, pos.z + half
        )
        val crew = level.getEntitiesOfClass(Raider::class.java, box) { it.isAlive }
        for (raider in crew) {
            raider.setPersistenceRequired()
            raider.setHomeTo(pos, (half + 4.0).toInt())
            raider.addTag(CREW_TAG)
            raider.getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = EurekaConfig.SERVER.pirateCrewSightRange
            // Combat outfit on the spot -- the crew watch re-applies it every pass, but a hull generated
            // right beside a player should shoot like a pirate from its first second, not its twentieth.
            PirateCombat.outfit(level, raider)
        }
        helm.pirateCrewUuids = crew.map { it.uuid }
        helm.pirateBerth = berthId
        helm.setChanged()

        // The holds: rolled fresh, per ship, at the one hook BOTH placement paths funnel through --
        // jigsaw worldgen runs no code at all, and regeneration places without jigsaw, but every new
        // hull's wheel adopts on its first loaded tick.
        //
        // A box sized for the WHOLE HULL, not the crew bubble above: the wheel can sit anywhere in a
        // design, and on a large ship with the wheel aft every chest forward of it fell outside the
        // crew box and shipped empty. PirateLoot sweeps by chunk, so the wider box is free.
        // Generous in EVERY direction, including DOWN. The wheel sits on the top deck, so a box that
        // reached only eight blocks below it filled the chests around the helm and missed every barrel
        // stowed in the hold -- 26 containers stocked, 23 barrels untouched, in the first field test.
        // The sweep is per-chunk, so a box twice the size of the ship costs the same as a tight one.
        val reach = max(size?.x ?: 64, size?.z ?: 64).toDouble() + 8.0
        val rise = (size?.y ?: 64).toDouble() + 8.0
        PirateLoot.stock(
            level,
            AABB(
                pos.x - reach, pos.y - rise, pos.z - reach,
                pos.x + reach, pos.y + rise, pos.z + reach
            )
        )

        // Every gun aboard becomes a raider's gun: bottomless while her crew serve her, and worth nothing to
        // a salvager ever after. Stamped at the same hook the holds are rolled at, and for the same reason --
        // jigsaw worldgen runs no code and regeneration places without jigsaw, but every new hull's wheel
        // adopts on its first loaded tick. Idempotent, the stamp being a boolean.
        val guns = PirateGuns.stampAll(
            level,
            AABB(
                pos.x - reach, pos.y - rise, pos.z - reach,
                pos.x + reach, pos.y + rise, pos.z + reach
            )
        )

        logger.info(
            "[pirates] adopted berth at {} ({}, {} crew, {} guns)", pos, templateId, crew.size, guns
        )
        return berthId
    }

    // endregion

    // region tick

    /** Advance every pirate site in [level]. Called once per server world tick, beside ShipFollows. */
    fun tick(level: ServerLevel) {
        val cfg = EurekaConfig.SERVER
        if (!cfg.pirateShipsEnabled) return
        tickCount++
        val now = level.gameTime

        // Boarders, EVERY tick: a body on the hull assembles the ship now, not on the next scan. The whole
        // point is denying the hand reaching for the wheel -- a scan cadence is a window, and windows get
        // used. Cheap regardless: bounding-box rejects fire long before any block is read.
        tickBoarders(level, now)

        // Proximity scan: does anyone stand inside a dormant ship's zone? Cheap (players x berths), but
        // there is no reason to ask more than twice a second.
        if (tickCount % 10 == 0L) scanZones(level, now)

        // Countdowns run every tick: the PROMPT message is a single-slot channel that expires a quarter
        // second after its last refresh, so keeping it alive IS the per-tick work.
        if (countdowns.isNotEmpty()) tickCountdowns(level, now)

        // Arm orders whose ship has come up: bind the chase.
        if (arming.isNotEmpty()) tickArming(level, now)

        // An assembled pirate nobody is managing -- a relog mid-pursuit (every chase is runtime-only), or a
        // crash mid-anything. Adopt it as a chase in the hunting state: if its old quarry is still nearby it
        // resumes the pursuit; if not, the hunt fails into the circle and the ship stands itself down.
        if (tickCount % 20 == 0L) adoptAwake(level, now)

        // The wakened ships' whole lifecycle: pursue, linger, stand down, retire.
        if (chases.isNotEmpty()) tickChases(level, now)

        // The crews: who is still standing on every loaded pirate deck, the TAKEN flip when the last one
        // falls, and the respawn that turns the wheel black again.
        if (tickCount % 20 == 0L) tickCrew(level, now)

        // Vanished wheels past their grace: the conquest begins.
        if (vanished.isNotEmpty()) tickVanished(level, now)

        // Conquest windows: claimed ships released to their new captain, expired ones cut loose to founder.
        tickFrozen(level, now)

        // Conquered sites past their dawn: a fresh ship out of the pool.
        if (tickCount % 100 == 0L) tickRegen(level)

        // Feed the debug wireframe, when someone asked for it. Keyed by the plain dimension identifier --
        // the same string the client-side renderer derives from its own level, with no VS2 format between.
        if (publishZones) {
            publishedZones[level.dimension().identifier().toString()] = zones(level)
        } else if (publishedZones.isNotEmpty()) {
            publishedZones.clear()
        }

        // Same again for the cannon engage-range wireframe: every chasing pirate's sphere, plus -- dev
        // convenience -- one around whatever armed ship each player is standing on, so the gunnery bench
        // ("/vs pirate aim") can be read against the same picture the AI uses.
        if (PirateGunnery.publishRanges) {
            PirateGunnery.publish(level.dimension().identifier().toString(), rangeSpheres(level))
        } else {
            PirateGunnery.clearPublished()
        }
    }

    /** Is this loaded ship one of ours mid-chase? PirateGunnery asks so pirates never shell each other. */
    fun isPirate(shipId: Long): Boolean = shipId in chases

    /**
     * Would tearing this hull down right now try to put blocks above the world's ceiling?
     *
     * The unfill relocates every voxel to its world position and declines outright if any of them lands
     * outside the build range -- which reads, from the deck, as a stand-down that never happens. Measured
     * from the ship's own box through its live transform rather than from `worldAABB`, which this manager
     * has already caught being seconds stale on a hull that assembled this session.
     */
    private fun tooHighToDisassemble(level: ServerLevel, ship: LoadedServerShip): Boolean {
        val box = ship.shipAABB ?: return false
        val corner = Vector3d()
        var top = Double.NEGATIVE_INFINITY
        // The eight corners: a heeled hull's highest point is whichever corner the roll happens to raise.
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            corner.set(
                if (dx == 0) box.minX().toDouble() else box.maxX() + 1.0,
                if (dy == 0) box.minY().toDouble() else box.maxY() + 1.0,
                if (dz == 0) box.minZ().toDouble() else box.maxZ() + 1.0
            )
            ship.shipToWorld.transformPosition(corner)
            if (corner.y > top) top = corner.y
        }
        return top >= level.maxY - DISASSEMBLE_CEILING_MARGIN
    }

    /**
     * Is anything solid standing where this hull's blocks would land?
     *
     * Measured over her live world box -- the eight corners of the shipyard box through the transform --
     * which is deliberately GENEROUS: a box is bigger than the hull inside it, so a ship hovering close
     * over a rooftop reads as obstructed and rises a little further than she strictly had to. That is the
     * right way to be wrong here, because being wrong the other way welds a hull into a building and
     * leaves a raider that can never assemble again.
     *
     * Air, fluids, and anything a block can simply be placed into (grass, snow, seagrass) are not in the
     * way. The sweep answers on the FIRST solid it meets, so the expensive case -- walking the whole box
     * -- is the CLEAR one, which happens once and is followed immediately by the disassembly.
     */
    private fun obstructed(level: ServerLevel, ship: LoadedServerShip): Boolean {
        val box = ship.shipAABB ?: return false
        val corner = Vector3d()
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            corner.set(
                if (dx == 0) box.minX().toDouble() else box.maxX() + 1.0,
                if (dy == 0) box.minY().toDouble() else box.maxY() + 1.0,
                if (dz == 0) box.minZ().toDouble() else box.maxZ() + 1.0
            )
            ship.shipToWorld.transformPosition(corner)
            if (corner.x < minX) minX = corner.x
            if (corner.y < minY) minY = corner.y
            if (corner.z < minZ) minZ = corner.z
            if (corner.x > maxX) maxX = corner.x
            if (corner.y > maxY) maxY = corner.y
            if (corner.z > maxZ) maxZ = corner.z
        }

        val cursor = BlockPos.MutableBlockPos()
        val loX = Math.floor(minX).toInt()
        val hiX = Math.floor(maxX).toInt()
        val loZ = Math.floor(minZ).toInt()
        val hiZ = Math.floor(maxZ).toInt()
        val loY = Math.floor(minY).toInt().coerceAtLeast(level.minY)
        val hiY = Math.floor(maxY).toInt().coerceAtMost(level.maxY - 1)

        for (x in loX..hiX) {
            for (z in loZ..hiZ) {
                // A column outside the loaded world is not something to force in just to ask about it.
                if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) continue
                for (y in loY..hiY) {
                    val state = level.getBlockState(cursor.set(x, y, z))
                    if (!state.isAir && state.fluidState.isEmpty && !state.canBeReplaced()) return true
                }
            }
        }
        return false
    }

    private fun rangeSpheres(level: ServerLevel): List<PirateGunnery.Sphere> {
        val radius = EurekaConfig.SERVER.pirateCannonEngageRange
        val world = level.shipObjectWorld
        val spheres = ArrayList<PirateGunnery.Sphere>()
        val covered = HashSet<Long>()
        for (shipId in chases.keys) {
            val ship = world.loadedShips.getById(shipId) ?: continue
            if (ship.chunkClaimDimension != level.dimensionId) continue
            val centre = PirateGunnery.shipCentre(ship) ?: continue
            if (covered.add(shipId)) spheres.add(PirateGunnery.Sphere(centre.x, centre.y, centre.z, radius))
        }
        for (player in level.players()) {
            val shipId = ShipCrew.standingOn(player) ?: continue
            if (shipId in covered) continue
            val ship = world.loadedShips.getById(shipId) ?: continue
            if (ship.chunkClaimDimension != level.dimensionId) continue
            if (ShipGuns.count(level, ship) == 0) continue
            val centre = PirateGunnery.shipCentre(ship) ?: continue
            if (covered.add(shipId)) spheres.add(PirateGunnery.Sphere(centre.x, centre.y, centre.z, radius))
        }
        return spheres
    }

    private fun scanZones(level: ServerLevel, now: Long) {
        val store = PirateStore.get(level)
        for ((berthId, report) in reports) {
            if (now - report.lastSeen > STALE_TICKS) continue
            val berth = store.berth(berthId) ?: continue
            if (berth.state != PirateStore.BERTHED) continue
            if (report.shipId != null) continue // already awake (or conquered-in-place); nothing to arm
            if (berthId in arming) continue
            // A TAKEN wheel is a dead crew's wheel: the ship stays quiet until they respawn or it is
            // conquered. A ghost ship rising to give crewless chase reads as a bug, not a haunting.
            if (report.helm.blockState.getValue(EurekaProperties.MARK) != HelmMark.PIRATE) continue

            val zone = zoneOf(level, berthId, report) ?: continue

            if (trespasserIn(level, zone) == null) {
                countdowns.remove(berthId)
                continue
            }
            countdowns.putIfAbsent(berthId, now + EurekaConfig.SERVER.pirateCountdownSeconds * 20L)
        }
    }

    /**
     * The boarder's rule, run every tick: a body touching a dormant hull assembles the ship THIS tick. The
     * fifteen-second countdown is for a captain deciding whether to sail on; someone already climbing the
     * rail is past every warning -- and more to the point, past none of the mischief a still-dormant hull
     * allows, so the window between boot and wake is kept as close to zero as the tick allows. Boarding
     * bypasses the global assembly cooldown outright (it still WINDS it, spacing later zone wakes); the
     * hard cap alone can refuse, being a performance rail rather than a pacing one.
     */
    private fun tickBoarders(level: ServerLevel, now: Long) {
        val store = PirateStore.get(level)
        for ((berthId, report) in reports) {
            if (now - report.lastSeen > STALE_TICKS) continue
            if (report.shipId != null || berthId in arming) continue
            val berth = store.berth(berthId) ?: continue
            if (berth.state != PirateStore.BERTHED) continue
            if (report.helm.blockState.getValue(EurekaProperties.MARK) != HelmMark.PIRATE) continue

            val boarder = level.players().firstOrNull {
                it.isAlive && !it.isSpectator && touchingHull(level, it, report, berth)
            } ?: continue

            countdowns.remove(berthId)
            arm(
                level, berthId, report,
                ShipCrew.standingOn(boarder), boarder.uuid, now,
                bypassCooldown = true
            )
        }
    }

    /**
     * Whether [player]'s body overlaps a solid block of the dormant hull around [report]'s wheel -- a boot
     * on the deck, a hand on the rail. The region is a generous box around the wheel (the wheel may sit
     * anywhere in its template), and fluids do not count: swimming beside the hull is not touching it.
     */
    private fun touchingHull(
        level: ServerLevel,
        player: ServerPlayer,
        report: Report,
        berth: PirateStore.Berth
    ): Boolean {
        val half = max(berth.sizeX, berth.sizeZ) + 2.0
        val hx = report.helmPos.x + 0.5
        val hy = report.helmPos.y.toDouble()
        val hz = report.helmPos.z + 0.5
        if (player.x < hx - half || player.x > hx + half) return false
        if (player.z < hz - half || player.z > hz + half) return false
        if (player.y < hy - berth.sizeY - 2.0 || player.y > hy + berth.sizeY + 2.0) return false

        val box = player.boundingBox.inflate(CONTACT_MARGIN)
        val min = BlockPos.containing(box.minX, box.minY, box.minZ)
        val maxPos = BlockPos.containing(box.maxX, box.maxY, box.maxZ)
        val cursor = BlockPos.MutableBlockPos()
        for (x in min.x..maxPos.x) {
            for (y in min.y..maxPos.y) {
                for (z in min.z..maxPos.z) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (!state.isAir && state.fluidState.isEmpty) return true
                }
            }
        }
        return false
    }

    /**
     * What the zone counts as trespassing -- and it is deliberately SHIP-shaped: a hull crossing the line
     * with a player inside that hull's influence border. The bow can cross while its captain stands at the
     * stern well outside the sphere; the pair is the provocation, and the trespassing ship is the one the
     * pirate binds to, so a wake always has something worth chasing. A swimmer or a rowed vanilla boat
     * rates no broadside -- pirates hunt prizes, not paddlers (the user's rule, replacing an earlier
     * players-count-too version that woke ships with nothing to pursue).
     *
     * The influence test is the bottle's: point inside the hull's world box grown by [INFLUENCE_MARGIN],
     * VS2's own per-face default -- the same server-side stand-in for the client influence border that
     * carries a thrown bottle with a moving deck.
     */
    private fun trespasserIn(level: ServerLevel, zone: Zone): Trespass? {
        val players = level.players().filter { it.isAlive && !it.isSpectator }
        if (players.isEmpty()) return null

        val dimension = level.dimensionId
        var best: Trespass? = null
        var bestDistSq = Double.MAX_VALUE
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            // A pirate does not provoke a pirate: a boarded chase ship sailing through a sibling's zone
            // should not wake the whole coast.
            if (ship.id in chases) continue
            val box = ship.worldAABB
            if (!zone.touches(box)) continue
            val aboard = players.filter { withinInfluence(it, box) }
            if (aboard.isEmpty()) continue
            val cx = (box.minX() + box.maxX()) * 0.5 - zone.x
            val cy = (box.minY() + box.maxY()) * 0.5 - zone.y
            val cz = (box.minZ() + box.maxZ()) * 0.5 - zone.z
            val distSq = cx * cx + cy * cy + cz * cz
            if (distSq < bestDistSq) {
                best = Trespass(ship, aboard)
                bestDistSq = distSq
            }
        }
        return best
    }

    private fun withinInfluence(player: ServerPlayer, box: org.joml.primitives.AABBdc): Boolean =
        player.x >= box.minX() - INFLUENCE_MARGIN && player.x <= box.maxX() + INFLUENCE_MARGIN &&
            player.y >= box.minY() - INFLUENCE_MARGIN && player.y <= box.maxY() + INFLUENCE_MARGIN &&
            player.z >= box.minZ() - INFLUENCE_MARGIN && player.z <= box.maxZ() + INFLUENCE_MARGIN

    private fun tickCountdowns(level: ServerLevel, now: Long) {
        val iterator = countdowns.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val berthId = entry.key
            val armAt = entry.value
            val report = reports[berthId]
            if (report == null || now - report.lastSeen > STALE_TICKS) {
                iterator.remove()
                continue
            }
            val zone = zoneOf(level, berthId, report) ?: continue
            val trespass = trespasserIn(level, zone)
            // The scan (every 10 ticks) is what CANCELS an abandoned countdown; skipping a lone empty tick
            // here just lets the prompt lapse for a quarter second if someone dances on the boundary.
            if (trespass == null) continue

            if (now < armAt) {
                val seconds = ((armAt - now) + 19) / 20
                for (player in trespass.players) {
                    PathMessages.send(
                        player,
                        "The pirates have spotted your ship -- ${seconds}s to get clear.",
                        PathMessages.Kind.PROMPT
                    )
                }
                continue
            }

            // A refused wake (cap, cooldown) retries in a couple of seconds rather than restarting the whole
            // countdown -- the player already had their warning; the queue is the pirates' problem.
            if (!arm(level, berthId, report, trespass.ship.id, trespass.players.firstOrNull()?.uuid, now)) {
                // A silent refusal reads as a broken feature: the countdown reaches zero, nothing happens,
                // and it does it again two seconds later forever. Whatever the throttle decided, say so.
                val cfg = EurekaConfig.SERVER
                val waiting = if (chases.size >= cfg.pirateMaxAssembled) {
                    "There are already ${chases.size} pirate ships under way."
                } else {
                    val ticks = (cfg.pirateAssemblyCooldownSeconds * 20.0).toLong() - (now - lastAssemblyAt)
                    val left = ((ticks + 19) / 20).coerceAtLeast(1L)
                    "The pirates are still getting under way -- ${left}s."
                }
                for (player in trespass.players) {
                    PathMessages.send(player, waiting, PathMessages.Kind.WARN)
                }
                entry.setValue(now + ARM_RETRY_TICKS)
                continue
            }
            iterator.remove()
        }
    }

    /**
     * The countdown ran out with somebody still inside: wake the ship.
     *
     * Assembly is the heaviest thing this feature does unattended, so it is throttled twice -- a hard cap
     * on concurrently assembled pirates and a global cooldown between any two assemblies. A wake refused by
     * either is simply dropped; the zone re-arms on the next scan if the player is still loitering.
     */
    private fun arm(
        level: ServerLevel,
        berthId: Long,
        report: Report,
        leaderId: Long?,
        playerId: UUID?,
        now: Long,
        bypassCooldown: Boolean = false
    ): Boolean {
        val cfg = EurekaConfig.SERVER
        if (chases.size >= cfg.pirateMaxAssembled) return false
        if (!bypassCooldown && lastAssemblyAt >= 0 && now - lastAssemblyAt < cfg.pirateAssemblyCooldownSeconds * 20L) {
            return false
        }

        lastAssemblyAt = now
        arming[berthId] = Arming(leaderId, playerId, now + ARM_PATIENCE_TICKS)
        logger.info("[pirates] berth {} waking (leader ship {})", BlockPos.of(berthId), leaderId)

        // holdEntities = true: the world-freeze is exactly what carries the deck crew through the swap.
        report.helm.assemble(null, knownBlocks = null, holdEntities = true)
        return true
    }

    private fun tickArming(level: ServerLevel, now: Long) {
        val iterator = arming.iterator()
        while (iterator.hasNext()) {
            val (berthId, order) = iterator.next()
            val report = reports[berthId]
            val shipId = report?.takeIf { now - it.lastSeen <= STALE_TICKS }?.shipId
            if (shipId == null) {
                // The relocated wheel has not reported back yet. Patience runs out eventually -- an assembly
                // that failed outright would otherwise hold this berth's zone disarmed forever.
                if (now > order.deadline) iterator.remove()
                continue
            }

            iterator.remove()
            val now2 = level.gameTime
            val chase = PirateChase(
                berthId = berthId,
                leaderId = null,
                lingerUntil = now2 + lingerTicks(),
                bornAt = now2
            )
            chases[shipId] = chase

            val world = level.shipObjectWorld
            val pirate = world.loadedShips.getById(shipId) ?: continue
            // The trespasser that woke this ship. It may have sailed off or unloaded in the seconds the
            // assembly took; the re-acquire machinery then hunts instead of the chase dying on the spot.
            val target = order.leaderId?.let { world.loadedShips.getById(it) }
            if (target == null) {
                logger.info("[pirates] {} awake; trespasser gone, hunting", BlockPos.of(berthId))
                continue
            }
            bind(level, pirate, target, chase)
        }
    }

    /** Assembled, fresh report, no chase, no pending arm order: somebody has to own this ship again. */
    private fun adoptAwake(level: ServerLevel, now: Long) {
        for ((berthId, report) in reports) {
            val shipId = report.shipId ?: continue
            if (now - report.lastSeen > STALE_TICKS) continue
            if (shipId in chases || berthId in arming) continue
            // A fresh linger window: if whoever it was chasing before the relog is still in the zone,
            // the next scan resumes the pursuit at once; otherwise it stands down when the window closes.
            chases[shipId] = PirateChase(
                berthId = berthId,
                leaderId = null,
                lingerUntil = now + lingerTicks(),
                bornAt = now
            )
            logger.info("[pirates] adopted awake pirate (ship {}), lingering", shipId)
        }
    }

    /**
     * Every wakened pirate's clockwork, run once per tick.
     *
     * The states chain one way -- pursuing, hunting (re-acquire), circling, standing down, retired -- with
     * one road back: a successful re-acquire returns the ship to pursuit with its patience refilled. The
     * retirement at the top is what re-arms the berth: a chase exists exactly as long as the ship does.
     */
    private fun tickChases(level: ServerLevel, now: Long) {
        val world = level.shipObjectWorld
        val iterator = chases.iterator()
        while (iterator.hasNext()) {
            val (shipId, chase) = iterator.next()
            val report = reports[chase.berthId]
            val fresh = report != null && now - report.lastSeen <= STALE_TICKS

            // The ship is gone from its own wheel's report: it disassembled. Retire the chase -- this is the
            // line that hands the berth back to the proximity scan, and what was missing when the field test
            // found a pirate that would only ever hunt once.
            if (fresh && report!!.shipId == null) {
                iterator.remove()
                continue
            }

            val pirate = world.loadedShips.getById(shipId)
            if (pirate == null) {
                // Unloaded: hold our breath for a moment, because a hull crossing a chunk boundary is
                // briefly nobody's. But not FOREVER -- a chase whose ship never comes back was pinning a
                // slot against pirateMaxAssembled for the rest of the session, so every later wake was
                // refused and the countdown looped at zero with nothing to show for it. Letting go is
                // safe: [adoptAwake] picks the pursuit straight back up if she ever reports again.
                if (!fresh && now - (report?.lastSeen ?: 0L) > HELM_LOST_TICKS) {
                    logger.info("[pirates] chase for ship {} retired -- unloaded and silent", shipId)
                    iterator.remove()
                }
                continue
            }
            // loadedShips is GLOBAL across dimensions while this tick is PER-DIMENSION -- the same trap
            // ShipFollows.tick guards with this same line. Without it, the Nether's tick resolves an
            // overworld pirate, asks the NETHER for nearby players, finds none, and executes the ship;
            // the field test watched exactly that ("nearest player: none" beside a correct wheel position).
            if (pirate.chunkClaimDimension != level.dimensionId) continue
            val control = pirate.getAttachment(EurekaShipControl::class.java) ?: continue

            // No report for ten seconds while the ship itself is loaded: the wheel is gone entirely
            // (conquest owns the orderly version of this in M6; creative pickaxes exist today). Let go --
            // ShipFollows has already broken off, and the helm-less hull is physics' problem now.
            if (!fresh && now - (report?.lastSeen ?: 0L) > HELM_LOST_TICKS) {
                control.pathRelease(true)
                iterator.remove()
                continue
            }

            // Shot to pieces: her wheel gives out, and that is the same event as a boarder breaking it.
            if (fresh && report != null && shootDownWheel(level, report, control)) continue

            if (chase.standingDown) {
                // Never take the deck out from under a player -- the boarding fight IS the feature, and
                // disassembly under someone's feet is the fall-through case. Wait them out.
                if (ShipCrew.aboard(level, pirate).isNotEmpty()) continue

                // A hull whose top pokes past the world ceiling cannot be unfilled -- the blocks would have
                // to land in a chunk section that does not exist -- so an airship ordered to stand down up
                // there simply retried forever, red zone and all. Fly her DOWN to a height she fits under,
                // then tear down. Every tick while descending, not on the retry clock: the descent is
                // steering, and steering on a one-second clock lurches.
                if (tooHighToDisassemble(level, pirate)) {
                    if (control.pathFollowing || control.pathBegin()) {
                        control.pathCommand(0.0, -DISASSEMBLE_DESCENT_MPS, 0.0, 0.0)
                    }
                    continue
                }
                // Nothing solid may be standing where her blocks are about to land. A hull that unfills
                // into a village, a hillside or a tree does not merely look wrong: her blocks MERGE with
                // whatever they touch, and the next wake's flood-fill walks out of the hull through the
                // join and tries to assemble the building too. One lightning rod on a city roof was enough
                // to leave a raider unassemblable for good -- "Ship is too big", on every wake, forever.
                if (obstructed(level, pirate)) {
                    // Up and out. Rising clears terrain, buildings and trees alike without any of the
                    // cornered-in-a-canyon reasoning that choosing a horizontal direction would need, and
                    // the ceiling case above already owns the other end of the same problem.
                    if (chase.clearingSince == 0L) chase.clearingSince = now
                    if (now - chase.clearingSince < DISASSEMBLE_STUCK_TICKS) {
                        if (control.pathFollowing || control.pathBegin()) {
                            control.pathCommand(0.0, DISASSEMBLE_CLEAR_MPS, 0.0, 0.0)
                        }
                        continue
                    }
                    // Wedged, with nowhere to rise to. Taking her apart HERE is the one outcome worth
                    // avoiding, so she is removed instead -- blocks and all, the way a bottled hull is
                    // emptied. Her berth generates a fresh ship in its own time, which is a far better end
                    // than a permanent unassemblable wreck welded to somebody's roof.
                    logger.info("[pirates] ship {} wedged at stand-down -- removed rather than merged", shipId)
                    if (control.pathFollowing) control.pathRelease(true)
                    VSShipAssembler.deleteShip(level, pirate, true, false)
                    iterator.remove()
                    continue
                }
                chase.clearingSince = 0L
                if (control.pathFollowing) control.pathRelease(true)

                if (now < chase.nextDisassembleTry) continue
                chase.nextDisassembleTry = now + 20L
                report?.helm?.disassemble()
                continue
            }

            // Nobody within earshot of the whole affair: stand down NOW, whatever else is going on. An
            // assembled ship is live physics, and physics for an audience of no one is the one cost this
            // feature must never carry -- the user's rule, overriding the linger clock outright.
            //
            // Measured from the WHEEL's transformed position, never from ship.worldAABB. The box is a stored
            // field some pipeline refreshes, and for a ship assembled this session it stayed shipyard-stale
            // for many seconds -- the field test watched this check kill every wake 5-8s in, with the player
            // alongside, twice, and the log named it. shipToWorld is live from the first frame (the zone
            // sphere demonstrably sails with the hull), so the wheel's position is the honest one. The
            // newborn grace stays as a belt over those first frames.
            if (now - chase.bornAt > NEWBORN_GRACE_TICKS && tickCount % 20 == 0L && fresh) {
                val wheel = helmWorldCentre(level, report!!)
                val nearest = nearestPlayerDistance(level, wheel)
                if (nearest > EurekaConfig.SERVER.pirateStandDownRange) {
                    ShipFollows.stopShip(pirate)
                    control.pathRelease(true)
                    chase.standingDown = true
                    logger.info(
                        "[pirates] {} beyond stand-down range; disassembling (wheel {},{},{} " +
                            "helmPos {} shipId {} nearest player {}m range {})",
                        shipId,
                        wheel.x.toInt(), wheel.y.toInt(), wheel.z.toInt(),
                        report.helmPos, report.shipId,
                        if (nearest == Double.MAX_VALUE) "none" else nearest.toInt(),
                        EurekaConfig.SERVER.pirateStandDownRange.toInt()
                    )
                    continue
                }
            }

            // A dead crew's wheel: hold everything. The ship must not resume a pursuit with nobody to sail
            // it, and it must not disassemble either -- it is a prize now, waiting to be conquered or for
            // its crew to respawn. The linger clock rides along frozen.
            if (report != null && report.helm.blockState.getValue(EurekaProperties.MARK) == HelmMark.TAKEN) {
                ShipFollows.stopShip(pirate)
                chase.leaderId = null
                chase.lingerUntil = now + lingerTicks()
                continue
            }

            // The guns, before the follow bookkeeping's several exits: every path below this line is an
            // ACTIVE chase -- pursuing or lingering, assembled, someone in earshot -- and those are
            // exactly the moments she should be shooting.
            PirateGunnery.tick(level, pirate, chase, now)

            // Pursuing and the follow is holding: the linger clock rides along fully wound, so the two
            // minutes are measured from the moment the pursuit BREAKS, never from the wake-up.
            if (chase.leaderId != null && ShipFollows.isFollowing(shipId)) {
                chase.lingerUntil = now + lingerTicks()
                continue
            }
            // The follow just ended (outpaced, leader gone) -- say so to whoever it was chasing, because
            // the deck-standers-only channel ShipFollows uses misses a captain hovering beside their ship.
            if (chase.leaderId != null) {
                world.loadedShips.getById(chase.leaderId!!)?.let { oldLeader ->
                    tellNear(level, oldLeader, "'${ShipCrew.name(pirate)}' has fallen astern.")
                }
                chase.leaderId = null
            }

            // The linger: assembled and waiting. A trespasser in the ZONE resumes the pursuit on the spot
            // -- the ship is already awake, so the 15-second ceremony would be theatre -- and the window
            // closing is what finally stands it down. (An earlier cut hunted by follow-reach in three
            // timed attempts and then circled, Rust-style; both redacted by the user for this.)
            if (now < chase.lingerUntil) {
                if (fresh && tickCount % 10 == 0L) {
                    val zone = zoneOf(level, chase.berthId, report!!)
                    val trespass = zone?.let { trespasserIn(level, it) }
                    if (trespass != null) bind(level, pirate, trespass.ship, chase)
                }
                continue
            }

            control.pathRelease(true)
            chase.standingDown = true
            logger.info("[pirates] {} linger expired; standing down", shipId)
        }
    }

    // region conquest + wreck + regeneration

    /**
     * A pirate-marked wheel just left the world. Called from ShipHelmBlock's removal hook, which cannot
     * tell a conquest from an assembly relocating the block -- so this opens a short grace, and the
     * relocated wheel clearing it by reporting from its new address is the disambiguation. A wheel that
     * stays silent past the grace was BROKEN, and that is the conquest.
     */
    fun helmVanished(level: ServerLevel, pos: BlockPos) {
        if (!EurekaConfig.SERVER.pirateShipsEnabled) return
        val berthId = reports.entries.firstOrNull { it.value.helmPos == pos }?.key ?: return
        vanished.putIfAbsent(berthId, level.gameTime + VANISH_GRACE_TICKS)
    }

    private fun tickVanished(level: ServerLevel, now: Long) {
        val iterator = vanished.iterator()
        while (iterator.hasNext()) {
            val (berthId, deadline) = iterator.next()
            if (now < deadline) continue
            iterator.remove()
            conquer(level, berthId, now)
        }
    }

    /**
     * The wheel is broken: the site enters its regeneration wait -- a full day on the DAY clock, so a ship
     * conquered at dawn is back next dawn, and `/time set` walks the wait forward for testing -- and an
     * assembled hull is FROZEN in place for the conquest window. Placing any helm inside the window claims
     * her (the pirate wheel is gone, so the placement ban has already lifted); letting it close cuts her
     * loose to founder.
     */
    private fun conquer(level: ServerLevel, berthId: Long, now: Long) {
        val store = PirateStore.get(level)
        val berth = store.berth(berthId) ?: return
        berth.state = PirateStore.REGEN_WAIT
        berth.regenAt = level.dayTime + regenTicks()
        berth.crewRespawnAt = -1L
        store.markDirty()

        val report = reports.remove(berthId)
        countdowns.remove(berthId)
        arming.remove(berthId)

        val shipId = report?.shipId
        if (shipId == null) {
            logger.info("[pirates] wheel broken on dormant berth {}; site regenerates next dawn", BlockPos.of(berthId))
            return
        }
        chases.remove(shipId)
        store.frozenShips[shipId] = now + freezeTicks()
        store.markDirty()
        level.shipObjectWorld.loadedShips.getById(shipId)?.let { loaded ->
            ShipFollows.stopShip(loaded)
            // The window is a HOLD, not a freeze: she keeps riding the water (or hanging in the air)
            // exactly as she did before the wheel broke -- the break only starts the clock. The first cut
            // set isStatic and the user watched a "conquered" ship stand rigid as if disassembled.
            loaded.getAttachment(EurekaShipControl::class.java)?.founderHold = true
        }
        logger.info("[pirates] wheel broken on ship {}; conquest window open", shipId)
    }

    /**
     * Every frozen hull: released to its claimant the moment a new helm stands on it, or cut loose to
     * founder when the window closes. The deadline lives in the SavedData store because `isStatic`
     * persists on the ship itself -- a forgotten deadline would leave a hull frozen forever.
     */
    private fun tickFrozen(level: ServerLevel, now: Long) {
        val store = PirateStore.get(level)
        if (store.frozenShips.isEmpty()) return
        val world = level.shipObjectWorld
        val iterator = store.frozenShips.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val shipId = entry.key
            val loaded = world.loadedShips.getById(shipId)
            val control = loaded?.getAttachment(EurekaShipControl::class.java)

            // Claimed: a helm placed aboard makes helms >= 1, and the ship answers to its new captain.
            // The main control body clears the hold itself on its first healthy tick.
            if (control != null && control.helms >= 1) {
                iterator.remove()
                store.markDirty()
                tellNear(level, loaded, "The vessel answers to a new wheel -- she is yours.")
                logger.info("[pirates] ship {} claimed", shipId)
                continue
            }

            if (now < entry.value) {
                // Keep the hold asserted while the window runs -- belt over the persisted flag.
                control?.founderHold = true
                continue
            }

            // Unclaimed, and the window is over. Dropping the hold is the whole sentence: a helm-less,
            // unheld hull is ShipFoundering's the moment it exists, so the sinking, the settling and the
            // seabed breakup all follow with no pirate bookkeeping at all. Needs the ship loaded to write
            // the flag; an unloaded hull just stays held until it next loads.
            if (control == null) {
                if (world.allShips.getById(shipId) == null) {
                    iterator.remove() // the ship no longer exists; nothing left to cut loose
                    store.markDirty()
                }
                continue
            }
            iterator.remove()
            store.markDirty()
            control.founderHold = false
            logger.info("[pirates] ship {} unclaimed; cut loose to founder", shipId)
        }
    }

    /**
     * Conquered sites past their dawn get a fresh ship -- a RANDOM hull from the configured pool, which is
     * also where a weight system slots in later. Measured on the DAY clock; a deadline further away than
     * the whole window means somebody set time BACKWARDS, and it re-anchors rather than freezing the site.
     * Refusals are silent retries: debris still in the box, a player standing too close, chunks unloaded.
     */
    private fun tickRegen(level: ServerLevel) {
        val store = PirateStore.get(level)
        val waiting = store.allBerths.filter { it.value.state == PirateStore.REGEN_WAIT }
        if (waiting.isEmpty()) return
        val dayNow = level.dayTime
        val window = regenTicks()
        for ((berthId, berth) in waiting) {
            if (berth.regenAt - dayNow > window) {
                berth.regenAt = dayNow + window
                store.markDirty()
                continue
            }
            if (dayNow < berth.regenAt) continue
            attemptRegen(level, berthId, berth, ignorePlayers = false)
        }
    }

    /** One regeneration attempt. Returns a human answer for the debug command; null means it happened. */
    fun attemptRegen(level: ServerLevel, berthId: Long, berth: PirateStore.Berth, ignorePlayers: Boolean): String? {
        val store = PirateStore.get(level)
        val origin = BlockPos.of(berth.originPos)
        if (!level.isLoaded(origin)) return "the site's chunks are not loaded"
        if (!ignorePlayers) {
            val clear = EurekaConfig.SERVER.pirateRegenPlayerClearRadius
            val tooClose = level.players().any {
                it.isAlive && !it.isSpectator && it.distanceToSqr(
                    origin.x + 0.5, origin.y + 0.5, origin.z + 0.5
                ) <= clear * clear
            }
            if (tooClose) return "a player is too close to the site"
        }
        // Weighted, not uniform: "pirate/sloop*3" outdraws "pirate/brig" three to one, a bare name is
        // weight 1, and only names that actually resolve to a template are in the draw at all. Keep the
        // weights in step with the worldgen template_pool JSON, which vanilla reads separately.
        val name = WeightedNames.pick(EurekaConfig.SERVER.pirateHulls, level.random) {
            ShipTemplate.find(level, it) != null
        } ?: return "no pirateHulls entry resolves to a template"
        val template = ShipTemplate.find(level, name)!!
        val size = template.size
        // First free Y over the water column -- the same seam the worldgen learned: the keel rides ON the
        // surface, riding one above the top water block.
        val floorY = level.getHeight(Heightmap.Types.WORLD_SURFACE, origin.x, origin.z)
        val corner = BlockPos(origin.x - size.x / 2, floorY, origin.z - size.z / 2)
        if (PlacementCheck.test(level, template, corner) !is PlacementCheck.Fits) {
            return "the site is obstructed (wreck debris?); it will retry"
        }
        if (ShipTemplate.place(level, name, corner) !is ShipTemplate.Placed) return "placement failed"

        // The new wheel adopts itself into a NEW berth on its first tick; this site's record retires. The
        // new key is the new wheel's position, so a site can wander by up to half a hull per regeneration
        // -- bounded, and the price of never needing a worldgen hook.
        store.removeBerth(berthId)
        logger.info("[pirates] site {} regenerated as {}", origin.toShortString(), name)
        return null
    }

    private fun freezeTicks(): Long = (EurekaConfig.SERVER.pirateConquestFreezeMinutes * 1200.0).toLong()

    private fun regenTicks(): Long = (EurekaConfig.SERVER.pirateRegenDays * 24000.0).toLong()

    // endregion

    /** The linger window, in ticks, from the config's minutes. */
    private fun lingerTicks(): Long = (EurekaConfig.SERVER.pirateLingerMinutes * 1200.0).toLong()

    /** How far the closest living, non-spectator player is from [centre]; MAX_VALUE with nobody at all. */
    private fun nearestPlayerDistance(level: ServerLevel, centre: Vector3d): Double {
        var best = Double.MAX_VALUE
        for (player in level.players()) {
            if (!player.isAlive || player.isSpectator) continue
            val dx = player.x - centre.x
            val dy = player.y - centre.y
            val dz = player.z - centre.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < best) best = dist
        }
        return best
    }

    /**
     * Who still stands on every loaded pirate deck. All dead starts the respawn clock and turns the wheel
     * white -- breakable, the conquest's door; the clock running out with the wheel still standing brings
     * the complement back from the papers and turns it black again.
     *
     * Judged only while a player is within [CREW_JUDGE_RANGE] of the wheel, and that guard is load-bearing:
     * an assembled ship's SHIPYARD chunks tick even when the world chunks under its crew do not, so without
     * it `getEntity(uuid) == null` would read as "dead" for a crew that is merely unloaded -- and the
     * respawn would then quietly duplicate them.
     */
    private fun tickCrew(level: ServerLevel, now: Long) {
        val store = PirateStore.get(level)
        for ((berthId, report) in reports) {
            if (now - report.lastSeen > STALE_TICKS) continue
            val helm = report.helm
            if (helm.pirateCrewUuids.isEmpty() && helm.pirateCrew.isEmpty()) continue // not ours to track
            val berth = store.berth(berthId) ?: continue
            if (berth.state != PirateStore.BERTHED) continue

            val centre = helmWorldCentre(level, report)
            val judge = level.players().any { player ->
                if (!player.isAlive || player.isSpectator) return@any false
                val dx = player.x - centre.x
                val dy = player.y - centre.y
                val dz = player.z - centre.z
                dx * dx + dy * dy + dz * dz <= CREW_JUDGE_RANGE * CREW_JUDGE_RANGE
            }
            if (!judge) continue

            // Overboard is dead, as far as the wheel knows. A pillager more than OVERBOARD_RANGE from its
            // own ship is not coming back -- knocked into the sea, left behind by a chase -- and a wheel
            // that stayed black forever on the strength of one pillager treading water half a map away
            // would be unconquerable in practice. The lost hand is discarded, not just discounted, so a
            // tagged, persistent mob is never left wandering the ocean floor for nobody.
            var living = 0
            val tether = (kotlin.math.max(berth.sizeX, berth.sizeZ) + 2).toDouble()
            // The boarding rule, resolved once per pass: a player standing on HER deck (the assembled
            // ship's carry state -- a dormant hull arms the tick a boot lands, so it reaches here
            // assembled) is every hand's first problem.
            val ship = level.getLoadedShipManagingPos(report.helmPos)
            val boarders = if (ship != null) ShipCrew.aboard(level, ship) else emptyList()
            for (id in helm.pirateCrewUuids) {
                val raider = level.getEntity(id) as? Raider ?: continue
                if (!raider.isAlive) continue
                val dx = raider.x - centre.x
                val dy = raider.y - centre.y
                val dz = raider.z - centre.z
                if (dx * dx + dy * dy + dz * dz > OVERBOARD_RANGE * OVERBOARD_RANGE) {
                    raider.discard()
                    logger.info("[pirates] a crew hand lost overboard at berth {}", BlockPos.of(berthId))
                    continue
                }
                living++
                // The combat outfit, re-asserted on the watch's clock: goals do not survive a save and
                // a /reload should retune ranges within the second -- the crew tag is what persists.
                PirateCombat.outfit(level, raider)
                if (boarders.isNotEmpty() && !raider.isNoAi) {
                    boarders.minByOrNull { it.distanceToSqr(raider) }?.let { PirateCombat.swarm(raider, it) }
                }
                // The tether: combat pathing marches a pillager toward its target with no idea the deck
                // ends -- the user watched one wade off into the sea after a passing ship. Anyone in the
                // water, or past the hull's own box, is hauled straight back to a seat by the wheel.
                if (raider.isInWater || dx * dx + dz * dz > tether * tether) {
                    PirateMuster.reseat(level, raider, centre, living)
                }
            }

            if (living > 0) {
                if (berth.crewRespawnAt != -1L) {
                    berth.crewRespawnAt = -1L
                    store.markDirty()
                }
                continue
            }

            if (berth.crewRespawnAt == -1L) {
                berth.crewRespawnAt = now + respawnTicks()
                store.markDirty()
                setMark(level, helm, HelmMark.TAKEN)
                tellAround(level, centre, "The pirate crew is dead -- their wheel can be taken.")
                logger.info("[pirates] berth {} crew wiped; wheel TAKEN", BlockPos.of(berthId))
                continue
            }

            if (now < berth.crewRespawnAt) continue

            // The wheel still stands (it reported this tick), the crew stayed dead the whole wait: relieve
            // them from the papers. No snapshots means an authored hull without a complement -- clear the
            // stamp and leave the wheel white rather than re-arming a clock that can never fire.
            if (helm.pirateCrew.isEmpty()) {
                berth.crewRespawnAt = -1L
                store.markDirty()
                continue
            }
            val spawned = PirateMuster.respawn(level, helm.pirateCrew, centre)
            if (spawned.isEmpty()) continue // chunks fought back; retry next pass
            helm.pirateCrewUuids = spawned
            helm.setChanged()
            berth.crewRespawnAt = -1L
            store.markDirty()
            setMark(level, helm, HelmMark.PIRATE)
            tellAround(level, centre, "A fresh pirate crew has taken the deck!")
            logger.info("[pirates] berth {} crew respawned ({})", BlockPos.of(berthId), spawned.size)
        }
    }

    /** The wheel's world position -- through shipToWorld when its ship is assembled. */
    private fun helmWorldCentre(level: ServerLevel, report: Report): Vector3d =
        helmWorldCentre(level, report.helmPos)

    private fun helmWorldCentre(level: ServerLevel, pos: BlockPos): Vector3d {
        val ship = level.getLoadedShipManagingPos(pos)
        val centre = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        return ship?.shipToWorld?.transformPosition(centre) ?: centre
    }

    /** Flip the wheel's mark in place. Same block, so the block entity and its papers ride through. */
    private fun setMark(level: ServerLevel, helm: ShipHelmBlockEntity, mark: HelmMark) {
        val state = helm.blockState
        if (state.getValue(EurekaProperties.MARK) == mark) return
        level.setBlock(helm.blockPos, state.setValue(EurekaProperties.MARK, mark), Block.UPDATE_ALL)
    }

    /** A line for everyone near a POINT -- the crew messages, where there may be no ship to speak from. */
    /**
     * A raider shot below [EurekaConfig.Server.pirateHelmBreaksBelow] breaks her own wheel. Returns whether
     * she did, in which case this tick is over for her.
     *
     * Conquest was only ever written one way -- somebody boards and breaks the wheel -- and that left a hole
     * the moment gunnery could beat a ship without anyone setting foot on her. A raider shot to pieces at
     * sea went ungoverned, ragdolled, and then simply DRIFTED: still assembled, still holding a berth, still
     * costing physics, waiting forever for a boarder who had no reason to come. Letting the hull finish
     * herself closes it. Everything downstream is the machinery that already exists -- the wheel vanishing
     * is reported by [ShipHelmBlock.affectNeighborsAfterRemoval] exactly as a pickaxe's break is, the
     * manager sees no report come back, the conquest lands, the hull founders and comes apart where it
     * lies, and the berth starts its regeneration clock.
     *
     * No drops. A wheel that failed with the ship is wreckage, not salvage; a boarder who wants the helm
     * itself can still take it the old way, by breaking it before her hull gives out.
     */
    private fun shootDownWheel(level: ServerLevel, report: Report, control: EurekaShipControl): Boolean {
        val threshold = EurekaConfig.SERVER.pirateHelmBreaksBelow
        if (threshold <= 0) return false
        if (ShipIntegrity.integrityPercent(control) >= threshold.coerceIn(1, 100)) return false

        val pos = report.helmPos
        if (!PirateHelm.gated(level.getBlockState(pos))) return false

        val centre = helmWorldCentre(level, pos)
        level.destroyBlock(pos, false)
        tellAround(level, centre, "The pirate ship is beaten -- her wheel has given out!")
        logger.info("[pirates] berth {} shot down at {}% integrity", report.helmPos, ShipIntegrity.integrityPercent(control))
        return true
    }

    private fun tellAround(level: ServerLevel, centre: Vector3d, message: String) {
        for (player in level.players()) {
            if (!player.isAlive || player.isSpectator) continue
            val dx = player.x - centre.x
            val dy = player.y - centre.y
            val dz = player.z - centre.z
            if (dx * dx + dy * dy + dz * dz <= EARSHOT * EARSHOT) {
                PathMessages.send(player, message, PathMessages.Kind.WARN)
            }
        }
    }

    private fun respawnTicks(): Long = (EurekaConfig.SERVER.pirateRespawnMinutes * 1200.0).toLong()

    /**
     * How many of the wheel's crew still live, for the break-refusal message. -1 when the wheel keeps no
     * crew records at all (a set-mark'd test helm), which the message words differently. Counts by the
     * same overboard rule the crew watch judges by, but read-only -- the watch does the discarding.
     */
    fun livingCrew(level: ServerLevel, helm: ShipHelmBlockEntity): Int {
        if (helm.pirateCrewUuids.isEmpty()) return -1
        val centre = helmWorldCentre(level, helm.blockPos)
        return helm.pirateCrewUuids.count { id ->
            val raider = level.getEntity(id) as? Raider ?: return@count false
            if (!raider.isAlive) return@count false
            val dx = raider.x - centre.x
            val dy = raider.y - centre.y
            val dz = raider.z - centre.z
            dx * dx + dy * dy + dz * dz <= OVERBOARD_RANGE * OVERBOARD_RANGE
        }
    }

    private fun bind(level: ServerLevel, pirate: LoadedServerShip, target: LoadedServerShip, chase: PirateChase) {
        when (val refusal = ShipFollows.bind(level, pirate, target, ownerId = null)) {
            null -> {
                chase.leaderId = target.id
                tellNear(level, target, "Pirates! '${ShipCrew.name(pirate)}' is in pursuit.")
            }
            ShipFollows.BindRefusal.NOT_READY -> Unit // wheel not up yet; the pursuit watch retries
            else -> logger.info("[pirates] bind refused: {}", refusal)
        }
    }

    /**
     * A pirate warning, to everyone it concerns: the ship's influence riders AND anyone within earshot of
     * the hull. [ShipCrew.tell] alone was the first cut and it missed the person who most needed the line
     * -- a captain hovering in the air beside their own ship watching the pirate come about hears nothing
     * from a deck-standers-only channel.
     */
    private fun tellNear(level: ServerLevel, ship: LoadedServerShip, message: String) {
        val box = ship.worldAABB
        val cx = (box.minX() + box.maxX()) * 0.5
        val cy = (box.minY() + box.maxY()) * 0.5
        val cz = (box.minZ() + box.maxZ()) * 0.5
        for (player in level.players()) {
            if (!player.isAlive || player.isSpectator) continue
            val dx = player.x - cx
            val dy = player.y - cy
            val dz = player.z - cz
            val near = dx * dx + dy * dy + dz * dz <= EARSHOT * EARSHOT
            if (near || withinInfluence(player, box)) {
                PathMessages.send(player, message, PathMessages.Kind.WARN)
            }
        }
    }

    // endregion

    // region zones

    /**
     * What a zone's ship is doing, for the debug wireframe to colour itself by. Server-side fact; the
     * colours themselves belong to the renderer, which is the only thing that cares what they look like.
     */
    enum class ZoneState { DORMANT, AWAKE, COUNTING, PURSUING }

    /** A proximity sphere, in world space. */
    class Zone(val x: Double, val y: Double, val z: Double, val radius: Double, val state: ZoneState) {
        fun contains(player: ServerPlayer): Boolean {
            val dx = player.x - x
            val dy = player.y - y
            val dz = player.z - z
            return dx * dx + dy * dy + dz * dz <= radius * radius
        }

        /** Sphere-vs-box: any part of a hull crossing the line counts, not just its centre. */
        fun touches(box: org.joml.primitives.AABBdc): Boolean {
            val cx = x.coerceIn(box.minX(), box.maxX())
            val cy = y.coerceIn(box.minY(), box.maxY())
            val cz = z.coerceIn(box.minZ(), box.maxZ())
            val dx = cx - x
            val dy = cy - y
            val dz = cz - z
            return dx * dx + dy * dy + dz * dz <= radius * radius
        }
    }

    /**
     * Where [berthId]'s sphere is right now. Centred on the wheel -- through shipToWorld when the ship is
     * assembled, so the zone sails with it -- with a radius scaled from the hull's own footprint.
     */
    private fun zoneOf(level: ServerLevel, berthId: Long, report: Report): Zone? {
        val cfg = EurekaConfig.SERVER
        val ship = report.shipId?.let { level.shipObjectWorld.loadedShips.getById(it) }
        val centre: Vector3d
        val halfDiag: Double
        if (ship != null) {
            centre = ship.shipToWorld.transformPosition(
                Vector3d(report.helmPos.x + 0.5, report.helmPos.y + 0.5, report.helmPos.z + 0.5)
            )
            halfDiag = FollowGeometry.horizHalfDiagonal(ship)
        } else {
            centre = Vector3d(report.helmPos.x + 0.5, report.helmPos.y + 0.5, report.helmPos.z + 0.5)
            val berth = PirateStore.get(level).berth(berthId) ?: return null
            halfDiag = sqrt(
                (berth.sizeX * berth.sizeX + berth.sizeZ * berth.sizeZ).toDouble()
            ) * 0.5
        }
        val radius = zoneRadius(level, halfDiag)

        // Read most-urgent-first: a ship in pursuit is pursuing whatever else is true of its berth --
        // and a BOARDED ship is every bit as engaged as a pursuing one, so it burns the same red.
        val chase = report.shipId?.let { chases[it] }
        val boarded = report.shipId != null && level.players().any {
            it.isAlive && !it.isSpectator && ShipCrew.standingOn(it) == report.shipId
        }
        val state = when {
            chase?.leaderId != null || boarded -> ZoneState.PURSUING
            berthId in countdowns -> ZoneState.COUNTING
            report.shipId != null -> ZoneState.AWAKE
            else -> ZoneState.DORMANT
        }
        return Zone(centre.x, centre.y, centre.z, radius, state)
    }

    /**
     * Whether [pos] sits inside a loaded, dormant pirate hull's box -- the placement gate's question. A
     * wheel seated on a sleeping pirate's deck FROM RANGE (never touching, so never waking it) would ride
     * into the assembled ship as a working helm and hand over everything the pirate wheel gates. The box is
     * the boarder's generous one, so the refusal may reach a little past the planks; nobody needs a ship
     * wheel two blocks from a pirate wreck.
     */
    fun nearDormantPirateHull(level: ServerLevel, pos: BlockPos): Boolean {
        val store = PirateStore.get(level)
        val now = level.gameTime
        for ((berthId, report) in reports) {
            if (now - report.lastSeen > STALE_TICKS) continue
            if (report.shipId != null) continue
            val berth = store.berth(berthId) ?: continue
            val half = max(berth.sizeX, berth.sizeZ) + 2
            if (kotlin.math.abs(pos.x - report.helmPos.x) > half) continue
            if (kotlin.math.abs(pos.z - report.helmPos.z) > half) continue
            if (kotlin.math.abs(pos.y - report.helmPos.y) > berth.sizeY + 2) continue
            return true
        }
        return false
    }

    /** Every live zone in [level], for the debug wireframe and nothing else. */
    /**
     * Every zone in this dimension: the live ones from their wheels, and the sleeping ones from the store.
     *
     * The store half is not a nicety. A wheel only REPORTS while its block entity ticks, which happens only
     * inside a player's simulation distance -- so a zone built solely from reports vanishes the moment you
     * are far enough away, and a big hull's zone is drawn at four times its half-diagonal, which for a large
     * ship is FURTHER OUT THAN SIMULATION DISTANCE. The ring for a sloop therefore lived comfortably inside
     * the reporting range and the ring for a hundred-block raider did not: it disappeared as you backed off
     * and reappeared as you closed, while the same ring drawn around an already-ASSEMBLED pirate stayed put
     * at any distance -- because an assembled ship's chunks are kept active and her wheel never stops
     * reporting. That difference is the whole bug, and it is why it looked like the sloops were fine.
     *
     * A dormant site cannot move (it is a structure in the world, not a ship), so the berth's own record --
     * the wheel position it was adopted at, and the template's measurements -- draws exactly the same circle
     * the wheel would have. Note this only fixes the DRAWING: waking still needs the wheel to be ticking,
     * so the outer reaches of a very large ring are still drawn further than she can notice you from.
     */
    fun zones(level: ServerLevel): List<Zone> {
        val now = level.gameTime
        val out = ArrayList<Zone>()
        val live = HashSet<Long>()

        for ((berthId, report) in reports) {
            if (now - report.lastSeen > STALE_TICKS) continue
            zoneOf(level, berthId, report)?.let {
                out.add(it)
                live.add(berthId)
            }
        }

        for ((berthId, berth) in PirateStore.get(level).allBerths) {
            if (berthId in live) continue
            if (berth.state != PirateStore.BERTHED) continue
            out.add(dormantZone(level, berthId, berth))
        }
        return out
    }

    /**
     * How far a site's zone reaches -- and it never promises more than it can deliver.
     *
     * The size-scaled radius is what a captain asked for, but a dormant site can only NOTICE anyone while
     * its wheel is ticking, and a wheel ticks only inside a player's simulation distance. On a large hull
     * four times the half-diagonal reaches past that, so the outer band of the ring was decoration: you
     * could cross it, watch the line go by, and nothing aboard would ever stir. Clamped, the ring means
     * exactly one thing again -- inside it she wakes.
     *
     * A chunk of margin off the simulation edge, because a chunk that has only just come into range is a
     * chunk whose block entities have not necessarily ticked yet, and a line drawn on that boundary would
     * be true only most of the time.
     */
    private fun zoneRadius(level: ServerLevel, halfDiag: Double): Double {
        val cfg = EurekaConfig.SERVER
        val wanted = max(cfg.pirateZoneMinRadius, halfDiag * cfg.pirateZoneScale)
        if (!cfg.pirateZoneClampToSimulationDistance) return wanted
        val reach = ((level.server.playerList.simulationDistance - 1) * 16).toDouble()
        // Never below the configured floor: a tiny simulation distance should shrink the ring, not delete it.
        return wanted.coerceAtMost(max(reach, cfg.pirateZoneMinRadius))
    }

    /** A sleeping site's ring, drawn from its papers rather than from a wheel that is not ticking. */
    private fun dormantZone(level: ServerLevel, berthId: Long, berth: PirateStore.Berth): Zone {
        val pos = BlockPos.of(berth.lastPos)
        val halfDiag = sqrt((berth.sizeX * berth.sizeX + berth.sizeZ * berth.sizeZ).toDouble()) * 0.5
        return Zone(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, zoneRadius(level, halfDiag), ZoneState.DORMANT)
    }

    /**
     * The /vs pirate-zones wireframe's supply line. The renderer runs on the RENDER thread and must not
     * iterate this object's live maps while the server writes them, so the tick PUBLISHES an immutable
     * per-dimension snapshot while the flag is up and the renderer only ever reads that. Single-player only
     * by construction, like every other client debug toggle here. DEV ONLY, strip-listed.
     */
    @Volatile
    @JvmStatic
    var publishZones = false

    private val publishedZones = java.util.concurrent.ConcurrentHashMap<String, List<Zone>>()

    fun publishedZones(dimension: String): List<Zone> = publishedZones[dimension] ?: emptyList()

    // endregion

    // region queries + debug

    fun chaseCount(): Int = chases.size

    /** One line per berth for /vs pirate list. */
    fun describe(level: ServerLevel): List<String> {
        val store = PirateStore.get(level)
        val now = level.gameTime
        if (store.allBerths.isEmpty()) return listOf("No pirate berths in this dimension.")
        return store.allBerths.map { (id, berth) ->
            val report = reports[id]
            val fresh = report != null && now - report.lastSeen <= STALE_TICKS
            val chase = report?.shipId?.let { chases[it] }
            val state = when {
                berth.state == PirateStore.REGEN_WAIT ->
                    "REGEN_WAIT (${(berth.regenAt - level.dayTime).coerceAtLeast(0) / 20}s of day-time)"
                !fresh -> "unloaded"
                chase?.standingDown == true -> "standing down (ship ${report!!.shipId})"
                chase != null && chase.leaderId != null -> "PURSUING ship ${chase.leaderId} (ship ${report!!.shipId})"
                chase != null -> "lingering ${(chase.lingerUntil - now) / 20}s (ship ${report!!.shipId})"
                report!!.shipId != null -> "AWAKE (ship ${report.shipId})"
                id in countdowns -> "counting down (${(countdowns[id]!! - now) / 20}s)"
                else -> "dormant"
            }
            "${BlockPos.of(berth.originPos).toShortString()} [${berth.templateId}] $state"
        }
    }

    /** Force the nearest waiting site's regeneration now, clock be damned. DEV ONLY, /vs pirate regen. */
    fun forceRegen(level: ServerLevel, player: ServerPlayer): String? {
        val store = PirateStore.get(level)
        val nearest = store.allBerths.entries
            .filter { it.value.state == PirateStore.REGEN_WAIT }
            .minByOrNull { BlockPos.of(it.value.originPos).distSqr(player.blockPosition()) }
            ?: return "no site is waiting to regenerate in this dimension"
        return attemptRegen(level, nearest.key, nearest.value, ignorePlayers = true)
    }

    /** Force the nearest fresh berth's countdown to expire now. DEV ONLY, /vs pirate arm. */
    fun forceArm(level: ServerLevel, player: ServerPlayer): Boolean {
        val now = level.gameTime
        val nearest = reports.entries
            .filter { now - it.value.lastSeen <= STALE_TICKS && it.value.shipId == null }
            .minByOrNull { it.value.helmPos.distSqr(player.blockPosition()) }
            ?: return false
        val leaderId = ShipCrew.standingOn(player)
        return arm(level, nearest.key, nearest.value, leaderId, player.uuid, now)
    }

    // endregion

    /** Forget every runtime record. SERVER_STOPPED teardown -- the singleton outlives the world otherwise. */
    fun reset() {
        reports.clear()
        countdowns.clear()
        arming.clear()
        chases.clear()
        vanished.clear()
        lastAssemblyAt = -1L
        tickCount = 0
    }

    /** The entity tag every adopted pirate crew member carries. */
    const val CREW_TAG = "vs_eureka_pirate"

    /** A report older than this is a wheel whose chunk unloaded; its berth holds still until it is back. */
    private const val STALE_TICKS = 40L

    /** How long an arm order waits for the assembled wheel to report back before giving up. */
    private const val ARM_PATIENCE_TICKS = 100L

    /** How soon an expired countdown retries a wake the cap or cooldown refused. */
    private const val ARM_RETRY_TICKS = 40L

    /** The bottle's influence margin: VS2's per-face default, mirrored server-side. */
    private const val INFLUENCE_MARGIN = 2.0

    /** A loaded ship whose wheel has not reported for this long has lost the wheel itself. */
    private const val HELM_LOST_TICKS = 200L

    /** How far a pirate warning carries beyond the ship it concerns, in blocks. */
    private const val EARSHOT = 64.0

    /**
     * Crew life-or-death is judged only with a player this close to the wheel, so the crew's own world
     * chunks are honestly loaded. Matches vanilla's entity-despawn horizon.
     */
    private const val CREW_JUDGE_RANGE = 128.0

    /** How long a fresh chase is exempt from the stand-down range check while its world box settles. */
    private const val NEWBORN_GRACE_TICKS = 100L

    /** A crew hand farther than this from their own wheel is lost overboard: dead, as the wheel counts. */
    private const val OVERBOARD_RANGE = 80.0

    /** How far past the player's own box "touching" reaches -- a hand on the rail, not a near miss. */
    private const val CONTACT_MARGIN = 0.35

    /** How long a vanished wheel has to report back before the vanishing counts as a break. */
    private const val VANISH_GRACE_TICKS = 10L

    /** How fast a stood-down pirate sinks toward a height she can be taken apart at, in m/s. */
    private const val DISASSEMBLE_DESCENT_MPS = 6.0

    /** How fast a hull rises to get out of her own way before standing down. */
    private const val DISASSEMBLE_CLEAR_MPS = 5.0

    /** How long she may spend looking for clear air before she is removed instead. */
    private const val DISASSEMBLE_STUCK_TICKS = 300L

    /** Blocks of daylight left under the world ceiling before a teardown is considered safe. */
    private const val DISASSEMBLE_CEILING_MARGIN = 8.0
}
