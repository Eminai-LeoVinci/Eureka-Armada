package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.cannon.CannonFire
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.util.ShipFireBurn
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/**
 * What the crew actually DO -- the first jobs in the mod that a villager holds rather than a player.
 *
 * Two of them, and they were built together on purpose because each is what makes the other worth paying for:
 * an incendiary round sets a deck alight, and a fire party is what puts it out. A ship with guns and no fire
 * watch wins the exchange and burns; a ship with a fire watch and no guns survives a fight it cannot end.
 *
 * ## The crew are the ship's, not the captain's
 * Berths are bought per captain, but a duty is performed for whatever hull the crew member is standing on. So a
 * broadside is fired by every gunner ABOARD, whoever signed them on -- two captains sailing one ship crew it
 * between them without any agreement having to be recorded anywhere. This is the same intersection rule the
 * roster already uses ("crew is who is signed on AND here"), applied to work instead of to membership.
 *
 * ## One crewman per cannon
 * A gun does nothing on its own. The number of guns that fire is `min(gunners aboard, guns aboard)`, which is
 * the whole of the rule -- a six-gun broadside costs six berths, and berths cost Hearts of the Sea. Extra guns
 * are dead weight until somebody is bought to stand at them, and extra gunners are simply idle. Neither is an
 * error worth refusing: hulls lose guns to cannon fire mid-battle, and a crew that had to be re-assigned every
 * time a gunport was blown in would be unmanageable exactly when it mattered.
 *
 * ## Why guns are ordered to fire rather than firing on sight
 * There is nothing yet for a gunner to decide to shoot AT -- pirates are the next phase. Until there is, the
 * captain calls the volley and the crew serve the guns, which is both the truer picture of a gun deck and the
 * half that has to exist first either way: an autonomous gunner is this code plus a target, not instead of it.
 */
object CrewDuties {

    // region the broadside

    /**
     * "Fire!" -- every manned gun aboard the ship [player] is on, one after another.
     *
     * Deliberately fires ALL manned guns, including the battery pointing at open sea. Which guns speak is
     * chosen by what the captain has LOADED, not by a side-picker in here: leave the starboard magazines empty
     * and only the port battery answers. That keeps the decision in the world, where the player can see it,
     * rather than in a rule they would have to be told about.
     */
    fun broadside(level: ServerLevel, player: ServerPlayer) {
        val ship = shipUnder(level, player)
        if (ship == null) {
            PathMessages.send(player, "Stand aboard a ship to give the order.", PathMessages.Kind.ERROR)
            return
        }

        val guns = ShipGuns.aboard(level, ship)
        if (guns.isEmpty()) {
            PathMessages.send(player, "${ShipCrew.name(ship)} carries no guns.", PathMessages.Kind.ERROR)
            return
        }

        val gunners = onDuty(level, ship, CrewDuty.GUNNER)
        if (gunners.isEmpty()) {
            PathMessages.send(
                player,
                "Nobody is at the guns. Open the crew manifest and assign a gunner.",
                PathMessages.Kind.ERROR
            )
            return
        }

        // One crewman per cannon. ShipGuns puts the loaded, cooled guns first, so a short-handed crew mans the
        // ones that can actually shoot rather than whichever happened to be found first.
        val manned = guns.take(minOf(gunners.size, guns.size))
        val now = level.gameTime
        val ready = manned.filter { it.loaded && it.readyAt <= now }

        if (ready.isEmpty()) {
            PathMessages.send(
                player,
                if (manned.any { !it.loaded }) "The guns want powder and shot." else "Still reloading.",
                PathMessages.Kind.WARN
            )
            return
        }

        volleys.add(
            Volley(
                dimension = level.dimension(),
                guns = ArrayDeque(ready.map { it.blockPos }),
                by = player.uuid,
                nextAt = now
            )
        )

        val idle = gunners.size - manned.size
        val unmanned = guns.size - manned.size
        val note = when {
            unmanned > 0 -> " $unmanned unmanned."
            idle > 0 -> " $idle gunner${if (idle == 1) "" else "s"} idle."
            else -> ""
        }
        ShipCrew.tellOthers(level, ship, player, "${ShipCrew.name(ship)} is firing.", PathMessages.Kind.WARN)
        PathMessages.send(
            player,
            "Fire! ${ready.size} of ${guns.size} gun${if (guns.size == 1) "" else "s"}.$note",
            PathMessages.Kind.GOOD
        )
    }

    /**
     * A volley in progress: the guns still to speak, and when the next one does.
     *
     * Guns are fired a few ticks apart rather than all on one tick, which is presentation rather than balance --
     * six cannons on a single tick is one loud noise and one cloud of smoke, where a rolling broadside is the
     * thing the player built a gun deck to hear. It also spreads six projectile spawns and six sound packets
     * over half a second.
     *
     * Note this is NOT the per-ship stagger the fire-rate question is about. Each gun still reloads on its own
     * four-second clock, so ordering two volleys back to back fires the same guns no faster than one would.
     */
    private class Volley(
        val dimension: ResourceKey<Level>,
        val guns: ArrayDeque<BlockPos>,
        /** Resolved to a player at each shot, so a captain who logs out mid-volley leaves the guns firing. */
        val by: UUID,
        var nextAt: Long
    )

    private val volleys = ArrayList<Volley>()

    private fun advanceVolleys(level: ServerLevel) {
        if (volleys.isEmpty()) return
        val now = level.gameTime
        val iterator = volleys.iterator()

        while (iterator.hasNext()) {
            val volley = iterator.next()
            if (volley.dimension != level.dimension()) continue
            if (now < volley.nextAt) continue

            val gun = volley.guns.removeFirstOrNull()
            if (gun == null) {
                iterator.remove()
                continue
            }

            // The refusal is dropped on purpose. A gun that has been shot off its mounting, or emptied by
            // somebody else between the order and its turn, has nothing useful to say to a captain mid-volley --
            // and the count they were given when they gave the order already told them what to expect.
            CannonFire.fire(level, gun, level.server.playerList.getPlayer(volley.by))
            volley.nextAt = now + STAGGER_TICKS

            if (volley.guns.isEmpty()) iterator.remove()
        }
    }

    // endregion

    // region the fire watch

    /**
     * One crew member on their way to one fire.
     *
     * Held as a deadline rather than as movement, and that is a deliberate limit. A villager cannot be trusted
     * to walk anywhere on a hull that is itself moving -- pathfinding on a ship is the mod's oldest sore point --
     * so making the fix depend on them arriving would make the whole duty fail exactly when the ship is under
     * way, which is when it catches fire. What is simulated instead is the TIME it takes them: a fire across
     * the ship takes longer to reach than one at their feet, and that is the part the player can feel.
     */
    private class Job(val dimension: ResourceKey<Level>, val fire: BlockPos, val doneAt: Long)

    private val working = HashMap<UUID, Job>()

    private fun fireWatch(level: ServerLevel) {
        if (level.gameTime % WATCH_INTERVAL != 0L) return

        // Douse FIRST, then look. The other order reads more naturally and is wrong: a fire put out on this
        // pass would still be in the list built before it, and the hand that had just been freed would be sent
        // straight back to it -- busy for several seconds over a fire that was already out.
        finishJobs(level)

        // The tracked list is a superset -- entries outlive the fires that made them -- so every position is
        // checked against the world before anybody is sent to it.
        val burning = HashMap<Long, MutableList<BlockPos>>()
        for (packed in ShipFireBurn.tracked(level)) {
            val pos = BlockPos.of(packed)
            if (level.getBlockState(pos).block !is BaseFireBlock) continue
            val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: continue
            burning.getOrPut(ship.id) { mutableListOf() }.add(pos)
        }
        if (burning.isEmpty()) return

        val ships = level.shipObjectWorld.loadedShips
        for ((id, fires) in burning) {
            val ship = ships.getById(id) as? LoadedServerShip ?: continue
            val party = onDuty(level, ship, CrewDuty.FIREFIGHTER)
            if (party.isEmpty()) continue

            // Anything already being dealt with is off the list, or every free hand would be sent to the same
            // flame and the rest of the ship would burn.
            val claimed = working.values.mapTo(HashSet()) { it.fire }
            val unclaimed = fires.filterNot { it in claimed }.toMutableList()

            for (crew in party) {
                if (unclaimed.isEmpty()) break
                if (working.containsKey(crew.uuid)) continue

                // Nearest first, in WORLD space: the fire is at shipyard coordinates and the villager is
                // standing on the deck at ordinary ones, so the two are not comparable until one is moved.
                val nearest = unclaimed.minByOrNull { crew.position().distanceToSqr(worldOf(ship, it)) } ?: break
                unclaimed.remove(nearest)

                val run = crew.position().distanceTo(worldOf(ship, nearest))
                val ticks = REACH_TICKS + (run * TICKS_PER_BLOCK).toLong()
                working[crew.uuid] = Job(level.dimension(), nearest, level.gameTime + ticks)
            }
        }
    }

    /** Put out every fire whose crew member has had time to get there. */
    private fun finishJobs(level: ServerLevel) {
        if (working.isEmpty()) return
        val now = level.gameTime
        val doused = HashMap<Long, Int>()
        val iterator = working.entries.iterator()

        while (iterator.hasNext()) {
            val job = iterator.next().value
            if (job.dimension != level.dimension()) continue
            if (now < job.doneAt) continue
            iterator.remove()

            // The fire may have burned through, been broken, or been rained on while they were on their way.
            // Nothing to do, and nothing to report -- it is out either way.
            if (level.getBlockState(job.fire).block !is BaseFireBlock) continue
            val ship = level.getLoadedShipManagingPos(job.fire) as? LoadedServerShip ?: continue

            level.removeBlock(job.fire, false)
            ShipFireBurn.forget(level, job.fire)

            val at = worldOf(ship, job.fire)
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.0f)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.3, 0.3, 0.3, 0.02)
            doused[ship.id] = (doused[ship.id] ?: 0) + 1
        }

        // Reported per ship per pass rather than per fire. An incendiary round lights several at once, and six
        // separate "a fire is out" lines would push everything else off the HUD to say one thing.
        val ships = level.shipObjectWorld.loadedShips
        for ((id, count) in doused) {
            val ship = ships.getById(id) as? LoadedServerShip ?: continue
            ShipCrew.tell(
                level, ship,
                "Fire party put out $count fire${if (count == 1) "" else "s"}.",
                PathMessages.Kind.GOOD
            )
        }
    }

    // endregion

    /** Per world tick: advance any volley in flight, and run the fire watch on its own slower clock. */
    fun tick(level: ServerLevel) {
        advanceVolleys(level)
        fireWatch(level)
    }

    /**
     * Drop everything in flight. Called when the server stops, for the reason `ShipPaths.reset` is.
     *
     * These are runtime-only by design -- a volley is half a second long and a crew member running to a fire is
     * a few seconds -- so persisting them would be recording something that has already finished. But in single
     * player the singleton outlives the world, and a stale entity uuid in the next world is a firefighter who
     * never reports back and a berth that never picks up another fire.
     */
    fun reset() {
        volleys.clear()
        working.clear()
    }

    // region lookups

    /**
     * The crew aboard [ship] who have been put on [duty], whoever signed them on.
     *
     * The ledger says who holds the duty and the world says who is here; a gunner standing on the quay is not
     * at their gun. Same intersection the roster is read through everywhere else.
     */
    private fun onDuty(level: ServerLevel, ship: LoadedServerShip, duty: CrewDuty): List<Villager> {
        val ledger = CrewLedger.get(level.server)
        return ShipCrew.villagersAboard(level, ship).filter { ledger.dutyOf(it.uuid) == duty }
    }

    /** A shipyard block's centre, in world space, which is where its sounds and particles belong. */
    private fun worldOf(ship: LoadedServerShip, pos: BlockPos): Vec3 {
        val world = ship.shipToWorld.transformPosition(
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        )
        return Vec3(world.x, world.y, world.z)
    }

    /** The ship [player] is standing on or seated in. Seated counts: the order is given from the wheel. */
    private fun shipUnder(level: ServerLevel, player: ServerPlayer): LoadedServerShip? {
        val id = ShipCrew.standingOn(player) ?: return null
        return level.shipObjectWorld.loadedShips.getById(id) as? LoadedServerShip
    }

    // endregion

    /** Ticks between one gun speaking and the next. Two is fast enough to read as one broadside, not six shots. */
    private const val STAGGER_TICKS = 2L

    /** How often the fire watch looks. Once a second against a burn-through of three seconds at worst. */
    private const val WATCH_INTERVAL = 20L

    /** The fixed part of getting to a fire: noticing it, and picking up something to put it out with. */
    private const val REACH_TICKS = 20L

    /** And the part that depends on how far they had to run -- five blocks a second, which is a jog. */
    private const val TICKS_PER_BLOCK = 4.0
}
