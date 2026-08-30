package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.pirate.PirateShips
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/**
 * Mob gun crews: any creature, planted at a cannon, as a fact of the SHIP rather than of the moment.
 *
 * This is the authoring tool behind every crewed hull design -- pillager pirates today, undead and piglin
 * ships later. A creative-mode spawn egg used on an unmanned gun aboard an assembled ship hatches that mob
 * at its post, and the posting is not an arrangement the mob has any say in: it cannot walk off, it does
 * not run its own brain (the ship's gunnery is its eyes), and it comes free exactly two ways -- the ship
 * disassembles, or the gun itself stops existing.
 *
 * ## Why the record is a pair of vanilla entity TAGS and nothing else
 * The binding must survive the one trip nothing runtime survives: capture into a template `.nbt` and
 * placement as a fresh copy, hours later, in a world that has never heard of this mob. Ledgers key on
 * uuids that placement re-rolls, and maps here die with the server. Scoreboard tags ride in the entity's
 * own NBT through every one of those doors for free. So: `vs_eureka_gunner` marks the mob, and
 * `vs_eureka_gun:x_y_z` names its gun's shipyard rear-block. Everything else -- the anchor, the NoAi
 * hold, the map below -- is rebuilt from those two strings.
 *
 * ## Why a gunner is ANCHORED and not seated, unlike the villager crew
 * The villager crew ride VS2 passenger seats ([GunStations]), and for a villager that is invisible. For
 * anything else it is not: a rider is a rider as far as the client is concerned, so Fresh Animations
 * posed every pillager, skeleton and zombie cross-legged and lifted them clear of their guns. Chasing
 * that through the render stack meant clearing vanilla's seated flag, then raising VS2's standing flag,
 * then hoping EMF's own `is_riding` -- read from the LIVE entity, not from anything we write -- agreed;
 * three builds in, the server had them provably standing on the deck (`error 0.000`) and the screen still
 * drew them sitting in the air.
 *
 * So a gunner does not ride anything. It stands where it is put and is TELEPORTED to its post every tick,
 * which is precisely what the seat was doing anyway -- the seat's whole job was to re-derive a world
 * position from `shipToWorld(anchor)` each tick. Doing that ourselves costs one transform per gunner and
 * buys three things at once: no render mod can give a riding pose to a mob that is not riding; the feet
 * land exactly on the deck with no per-species attachment arithmetic; and the mount survives a relog with
 * nothing to persist, because the anchor is recomputed from the tags rather than restored.
 */
object GunnerMounts {

    /** The mark: this mob is a gun's crew. Rides entity NBT, so it rides templates. */
    const val GUNNER_TAG = "vs_eureka_gunner"

    /** Prefix of the tag naming WHICH gun: `vs_eureka_gun:<rearX>_<rearY>_<rearZ>` in shipyard space. */
    private const val GUN_TAG_PREFIX = "vs_eureka_gun:"

    /**
     * One gunner's post: the gun, the shipyard-space spot they stand on, and the way they face.
     *
     * [dimension] is load-bearing, not bookkeeping. This map is GLOBAL while the tick that maintains it
     * runs once per LEVEL, so the Nether's tick sees an overworld gunner's post, asks its own level for
     * that mob, is told "no such entity", and deletes a perfectly good posting. That is exactly how 24
     * crews could take their guns and the census report `0 manned` seconds later -- the same
     * every-level-ticks-a-global-map trap `PirateShips` hit with its chases.
     */
    private class Post(val gunPos: BlockPos, val stand: Vector3d, val yaw: Float, val dimension: String)

    /** mob uuid -> post. Rebuilt from tags by [reconcile]; never saved. */
    private val posts = HashMap<UUID, Post>()

    fun tick(level: ServerLevel) {
        // Hold every gunner on its mark. Every tick, because the deck moves every tick -- this is the
        // work VS2's seat used to do for us, and the reason a gunner rides nothing now.
        if (posts.isNotEmpty()) hold(level)
        if (level.gameTime % RECONCILE_INTERVAL == 0L) reconcile(level)
    }

    /** Server stopped: the world's entities went with it; only the map survives to be wrong. */
    fun reset() = posts.clear()

    /** Is this entity a bound gun crew? The one question template capture asks. */
    fun isGunner(entity: Entity): Boolean = entity is Mob && GUNNER_TAG in entity.tags

    /** The living mob posted at the gun whose rear is [gunRear], or null: an unmanned gun is a silent gun. */
    fun gunnerAt(level: ServerLevel, gunRear: BlockPos): Mob? {
        for ((mobId, post) in posts) {
            if (post.gunPos != gunRear || post.dimension != level.dimensionId) continue
            val mob = level.getEntity(mobId) as? Mob ?: continue
            if (mob.isAlive) return mob
        }
        return null
    }

    /**
     * The egg gesture: hatch [egg]'s mob at the gun whose rear is [gunRear].
     * Returns the plain-words refusal, or null when the gunner is at their post.
     *
     * The egg spawn (rather than a bare [net.minecraft.world.entity.EntityType.create]) matters: it is the
     * path that finalizes the mob -- a pillager without it has no crossbow, and a crossbow-less gunner
     * reads as a bug in every hull built from the template.
     */
    fun mountFromEgg(
        level: ServerLevel,
        gunRear: BlockPos,
        egg: SpawnEggItem,
        stack: ItemStack,
        player: ServerPlayer?
    ): String? {
        if (level.getBlockEntity(gunRear) !is CannonBlockEntity) return "that is not a working gun"
        val ship = level.getLoadedShipManagingPos(gunRear) as? LoadedServerShip
            ?: return "the gun must be aboard an assembled ship"
        if (gunnerAt(level, gunRear) != null) return "someone already serves that gun"
        val stand = standFor(level, gunRear) ?: return footingProblem(level, gunRear) ?: "there is no room to stand behind it"

        val world = ship.shipToWorld.transformPosition(Vector3d(stand.stand))
        val spawned = egg.getType(stack).spawn(
            level, stack, player, BlockPos.containing(world.x, world.y, world.z),
            MobSpawnType.SPAWN_EGG, false, false
        )
        val mob = spawned as? Mob
        if (mob == null) {
            spawned?.discard()
            return "that egg does not hatch a creature that can serve a gun"
        }

        mob.addTag(GUNNER_TAG)
        retag(mob, gunRear)
        // Mounted aboard a ship that is ALREADY a pirate: stamp the crew mark now, the way template
        // capture stamps it for authored hulls -- it is what lets the binding sleep through dormancy
        // instead of reading "disassembled build" and releasing.
        if (PirateHelm.shipHasPirateWheel(level, ship)) mob.addTag(PirateShips.CREW_TAG)
        station(level, mob, gunRear, stand)
        return null
    }

    /**
     * Cut a mob loose: papers struck, its own brain and its own weight handed back. The two legitimate
     * doors -- disassembly and a destroyed gun -- both arrive here via [reconcile]; nothing else releases
     * a gunner.
     */
    @Suppress("UNUSED_PARAMETER")
    fun release(level: ServerLevel, mob: Mob, reason: String) {
        // [reason] is unread since the gunner trace came out, and kept anyway: it is the only place the
        // two legitimate doors above are named at the call site, and a caller that has to say WHY it is
        // cutting a gunner loose is a caller that has thought about whether it should.
        posts.remove(mob.uuid)
        for (tag in mob.tags.filter { it == GUNNER_TAG || it.startsWith(GUN_TAG_PREFIX) }.toList()) {
            mob.removeTag(tag)
        }
        mob.isNoAi = false
        mob.isNoGravity = false
    }

    /**
     * Cut every gunner aboard [ship] loose at once, and answer how many there were.
     *
     * Found by sweeping the hull's WORLD box rather than by walking [posts]: a posted gunner is held at
     * ordinary world coordinates on a moving deck, which is the same place [ShipCrew.villagersAboard] looks
     * for the crew, and it means a mob whose post record was lost to a restart is still cut loose.
     */
    fun releaseAll(level: ServerLevel, ship: LoadedServerShip, reason: String): Int {
        val hull = ship.worldAABB
        val box = AABB(
            hull.minX() - 2.0, hull.minY() - 2.0, hull.minZ() - 2.0,
            hull.maxX() + 2.0, hull.maxY() + 2.0, hull.maxZ() + 2.0
        )
        val gunners = level.getEntitiesOfClass(Mob::class.java, box) { isGunner(it) }
        for (mob in gunners) release(level, mob, reason)
        return gunners.size
    }

    /** Every posted gunner, held on its mark against the deck's movement. */
    private fun hold(level: ServerLevel) {
        for ((mobId, post) in posts.entries.toList()) {
            // Another dimension's gunner: not ours to hold, and emphatically not ours to forget.
            if (post.dimension != level.dimensionId) continue
            val mob = level.getEntity(mobId) as? Mob
            if (mob == null || !mob.isAlive) {
                posts.remove(mobId)
                continue
            }
            val ship = level.getLoadedShipManagingPos(post.gunPos) ?: continue
            val world = ship.shipToWorld.transformPosition(Vector3d(post.stand))
            // The facing is re-derived every tick alongside the position, and for the same reason: both are
            // shipyard facts that only mean something once the hull's current transform is applied to them.
            val yaw = worldYawOf(ship, post)
            // snapTo, not teleportTo: this is the same per-tick re-placement VS2's seat performs, and it
            // must not be read as a teleport by anything watching (no dismount, no fall reset, no event).
            mob.moveTo(world.x, world.y, world.z, yaw, 0.0f)
            mob.yHeadRot = yaw
            mob.yBodyRot = yaw
            mob.deltaMovement = net.minecraft.world.phys.Vec3.ZERO
            mob.fallDistance = 0.0f
        }
    }

    /**
     * Once a second, walk every tagged mob in the level and make the world agree with its papers.
     *
     * The pass is a plain sweep of the loaded entity list rather than any cleverer index because the tags
     * ARE the index: after a relog, a template placement, or a dimension hop, the tags are the only thing
     * guaranteed to still exist, and everything here must be derivable from them alone.
     */
    private fun reconcile(level: ServerLevel) {
        for (entity in level.getAllEntities()) {
            val mob = entity as? Mob ?: continue
            if (GUNNER_TAG !in mob.tags || !mob.isAlive) continue
            handle(level, mob)
        }
    }

    private fun handle(level: ServerLevel, mob: Mob) {
        val assigned = gunPosOf(mob)
        val posted = posts[mob.uuid]

        // At a gun that still exists: nothing to do but keep the hold on. NoAi is re-asserted rather than
        // trusted because anything -- a command, another mod -- may have handed the brain back, and a
        // gunner freelancing from its post is exactly what this exists to prevent.
        if (posted != null && assigned == posted.gunPos &&
            level.getBlockEntity(posted.gunPos) is CannonBlockEntity &&
            level.getLoadedShipManagingPos(posted.gunPos) != null
        ) {
            mob.isNoAi = true
            return
        }

        // Papers unreadable: a hand-edited or truncated tag. Not a state to limp along in.
        if (assigned == null) {
            release(level, mob, "its gun tag was unreadable")
            return
        }

        // The fast path: the recorded gun, where the papers say, on an assembled ship, unmanned.
        if (level.hasChunkAt(assigned) && level.getBlockEntity(assigned) is CannonBlockEntity &&
            level.getLoadedShipManagingPos(assigned) != null && gunnerAt(level, assigned) == null
        ) {
            standFor(level, assigned)?.let { station(level, mob, assigned, it) }
            return
        }

        // The recorded address is wrong. If the mob is aboard a loaded ship, the address was re-dealt
        // (capture/placement/reassembly) -- recover the assignment by proximity and re-write the
        // papers. No gun left to serve means the gun was shot away: the gunner stands down into the
        // fighting crew.
        // ...but only onto a hull this gunner could plausibly belong to.
        //
        // [shipUnder] answers by GEOMETRY when the drag has not claimed the mob -- any hull whose box
        // contains it, give or take a deck's reach -- and geometry cannot tell "my ship" from "the ship
        // that happens to be moored alongside". A raider's gunner left over from a conquered hull, drifting
        // within three blocks of a captain's ship, was therefore handed the nearest unmanned gun ABOARD
        // THAT SHIP: uninvited pillagers appearing at the guns of a ship they were never part of, noticed
        // on the relog that ran the reconcile.
        //
        // A pirate crew member belongs on a pirate hull and nobody else's; a gunner a captain mounted with
        // an egg belongs on a hull that is not a raider's. That is the whole test, and it is enough --
        // between two hulls of the same kind a mix-up is possible but harmless, while this one put hostile
        // mobs on somebody's deck.
        val ship = shipUnder(level, mob)?.takeIf { hull ->
            PirateHelm.shipHasPirateWheel(level, hull) == (PirateShips.CREW_TAG in mob.tags)
        }
        if (ship != null) {
            val gun = nearestUnmannedGun(level, ship, mob)
            if (gun != null) {
                retag(mob, gun.blockPos)
                standFor(level, gun.blockPos)?.let { station(level, mob, gun.blockPos, it) }
            } else {
                release(level, mob, "no unmanned gun left aboard its ship")
            }
            return
        }

        // Not on a loaded ship. A pirate crew member off-ship is usually a DORMANT hull's gunner --
        // world blocks, nothing anchored yet -- and sleeps at their gun until the hull assembles.
        // Anyone else is standing in a disassembled build: the posting released, by the author's rule.
        //
        // "Usually", because the ship can also have stopped existing: `/vs delete` takes the hull out
        // from under its crew, and a sleeping gunner is weightless, so two dozen pillagers hung in the
        // sky over the empty sea, never falling. Sleep only over something SOLID; over nothing at all,
        // let go -- which hands back both the brain and the gravity, and they drop.
        if (PirateShips.CREW_TAG !in mob.tags) {
            release(level, mob, "its ship is no longer assembled")
        } else if (!groundBelow(level, mob)) {
            release(level, mob, "the hull beneath it is gone")
        }
    }

    /** Is there anything solid under this gunner -- a dormant hull's deck, or any ground at all? */
    private fun groundBelow(level: ServerLevel, mob: Mob): Boolean {
        val foot = BlockPos.containing(mob.x, mob.y, mob.z)
        for (drop in 0..GROUND_PROBE) {
            val pos = foot.below(drop)
            if (!level.hasChunkAt(pos)) return true // unreadable is not evidence of absence
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return true
        }
        return false
    }

    /** Put [mob] on the mark and hold it there: no brain, no weight, no wandering. */
    private fun station(level: ServerLevel, mob: Mob, gunPos: BlockPos, post: Post) {
        // A gunner from an older build may still be sitting on a VS2 seat; cut it loose first.
        (mob.vehicle as? ShipMountingEntity)?.let { seat ->
            mob.stopRiding()
            if (!seat.isController) seat.kill()
        }
        mob.setPersistenceRequired()
        mob.isNoAi = true
        // The deck carries them, not gravity: without this a gunner falls a tick's worth between every
        // re-placement, which reads as a shiver on a ship under way.
        mob.isNoGravity = true
        posts[mob.uuid] = post
        val ship = level.getLoadedShipManagingPos(gunPos)
        val world = ship?.shipToWorld?.transformPosition(Vector3d(post.stand)) ?: post.stand
        // Off a ship the shipyard IS the world, so the post's own yaw is already the right one.
        val yaw = if (ship != null) worldYawOf(ship, post) else post.yaw
        mob.moveTo(world.x, world.y, world.z, yaw, 0.0f)
        mob.yHeadRot = yaw
        mob.yBodyRot = yaw
    }

    /**
     * [post]'s shipyard-space facing, as a WORLD yaw on the hull it belongs to.
     *
     * The post's yaw is the gun's blockstate facing, which lives in the shipyard like everything else about
     * a ship's blocks. It was being written straight onto the mob as a world yaw -- so a gunner's POSITION
     * followed the hull (that goes through `shipToWorld`) while their FACING did not. On a ship holding a
     * heading nobody could tell; put the wheel over and the whole gun deck slowly rotates in place, every
     * crewman turning away from his own breech as the hull comes round under him. Circle such a ship and
     * they appear to spin, which is exactly what it looks like because it is exactly what was happening.
     *
     * Derived by transforming TWO points and subtracting them rather than by rotating a direction vector --
     * [org.valkyrienskies.eureka.cannon.CannonFire] takes the muzzle line the same way, and for the same
     * reason: the ship's rotation AND its scale are then handled by the one transform that placed the post,
     * with no second code path to get subtly out of step with it.
     */
    private fun worldYawOf(ship: LoadedServerShip, post: Post): Float {
        val rad = Math.toRadians(post.yaw.toDouble())
        // Minecraft's yaw convention: 0 looks along +Z, and +90 swings to -X.
        val ahead = Vector3d(post.stand).add(-kotlin.math.sin(rad), 0.0, kotlin.math.cos(rad))
        val here = ship.shipToWorld.transformPosition(Vector3d(post.stand))
        val there = ship.shipToWorld.transformPosition(ahead)
        return Math.toDegrees(kotlin.math.atan2(-(there.x - here.x), there.z - here.z)).toFloat()
    }

    /** The post behind [gunPos]'s breech, in shipyard space, or null when nobody could stand there. */
    private fun standFor(level: ServerLevel, gunPos: BlockPos): Post? {
        val behind = behindOf(level, gunPos) ?: return null
        val footing = footingOf(level, gunPos) as? Footing.Stand ?: return null
        val state = level.getBlockState(gunPos)
        // The gun's own facing, so the crew looks down the bore rather than out to sea.
        val yaw = if (state.hasProperty(HORIZONTAL_FACING)) state.getValue(HORIZONTAL_FACING).toYRot() else 0.0f
        return Post(gunPos, Vector3d(behind.x + 0.5, footing.surfaceY, behind.z + 0.5), yaw, level.dimensionId)
    }

    /**
     * The ship a gunner is aboard, when its gun tag no longer resolves.
     *
     * VS2's carry state ([ShipCrew.standingOn]) is the exact answer and is tried first -- but it is only
     * ever written by the entity DRAGGER, and a gunner is the one mob on the deck the dragger has no
     * reason to touch: it has no AI, no gravity, and never moves itself. That was invisible while
     * gunners rode seats (a seat answers "which ship" directly); the moment they were anchored instead,
     * every template-born crew reported no ship, slept through the whole chase, and 24 loaded guns
     * stood silent with `0 manned` in the census.
     *
     * So fall back to geometry: the hull whose world box the gunner is standing in. That is true by
     * construction for a crew placed with its own ship, and stays true the instant she assembles.
     */
    private fun shipUnder(level: ServerLevel, mob: Mob): LoadedServerShip? {
        ShipCrew.standingOn(mob)?.let { id ->
            level.shipObjectWorld.loadedShips.getById(id)?.let { return it }
        }
        var best: LoadedServerShip? = null
        var bestSq = Double.MAX_VALUE
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != level.dimensionId) continue
            val box = ship.worldAABB ?: continue
            if (mob.x < box.minX() - DECK_REACH || mob.x > box.maxX() + DECK_REACH) continue
            if (mob.y < box.minY() - DECK_REACH || mob.y > box.maxY() + DECK_REACH) continue
            if (mob.z < box.minZ() - DECK_REACH || mob.z > box.maxZ() + DECK_REACH) continue
            val cx = (box.minX() + box.maxX()) * 0.5 - mob.x
            val cy = (box.minY() + box.maxY()) * 0.5 - mob.y
            val cz = (box.minZ() + box.maxZ()) * 0.5 - mob.z
            val distSq = cx * cx + cy * cy + cz * cz
            if (distSq < bestSq) {
                bestSq = distSq
                best = ship
            }
        }
        return best
    }

    private fun nearestUnmannedGun(level: ServerLevel, ship: LoadedServerShip, mob: Mob): CannonBlockEntity? {
        var best: CannonBlockEntity? = null
        var bestDistSq = Double.MAX_VALUE
        for (gun in ShipGuns.aboard(level, ship)) {
            if (gunnerAt(level, gun.blockPos) != null) continue
            if (GunStations.manned(gun.blockPos)) continue
            val hull = level.getLoadedShipManagingPos(gun.blockPos) ?: continue
            val world = hull.shipToWorld.transformPosition(
                Vector3d(gun.blockPos.x + 0.5, gun.blockPos.y + 0.5, gun.blockPos.z + 0.5)
            )
            val distSq = mob.distanceToSqr(world.x, world.y, world.z)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = gun
            }
        }
        return best
    }

    private fun gunTagOf(gunPos: BlockPos): String = "$GUN_TAG_PREFIX${gunPos.x}_${gunPos.y}_${gunPos.z}"

    private fun retag(mob: Mob, gunPos: BlockPos) {
        for (tag in mob.tags.filter { it.startsWith(GUN_TAG_PREFIX) }.toList()) mob.removeTag(tag)
        mob.addTag(gunTagOf(gunPos))
    }

    private fun gunPosOf(mob: Mob): BlockPos? {
        val tag = mob.tags.firstOrNull { it.startsWith(GUN_TAG_PREFIX) } ?: return null
        val parts = tag.removePrefix(GUN_TAG_PREFIX).split('_')
        if (parts.size != 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return BlockPos(x, y, z)
    }

    // The stand geometry below is GunStations' (behindOf / footingOf), copied rather than shared: that
    // object's internals are welded to the villager crew ledger, and a refactor to serve both would put
    // the stable villager path at risk for the sake of forty lines.

    private fun behindOf(level: ServerLevel, gunPos: BlockPos): BlockPos? {
        val state = level.getBlockState(gunPos)
        if (!state.hasProperty(HORIZONTAL_FACING)) return null
        return gunPos.relative(state.getValue(HORIZONTAL_FACING).opposite)
    }

    private sealed class Footing {
        class Stand(val surfaceY: Double) : Footing()
        class Refused(val reason: String) : Footing()
    }

    private fun footingOf(level: ServerLevel, gunPos: BlockPos): Footing {
        val behind = behindOf(level, gunPos)
            ?: return Footing.Refused("the gun is missing its breech")
        var surface: Double? = null
        for (y in behind.y + 1 downTo behind.y - 2) {
            val pos = BlockPos(behind.x, y, behind.z)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            if (!shape.isEmpty) {
                surface = y + shape.max(Direction.Axis.Y)
                break
            }
        }
        if (surface == null) {
            return Footing.Refused("there is nothing to stand on behind it")
        }
        if (surface < behind.y - 1.0 || surface > behind.y + 1.0) {
            return Footing.Refused("the footing behind it is more than a block above or below the gun")
        }
        val room = AABB(
            behind.x + 0.2, surface + 0.05, behind.z + 0.2,
            behind.x + 0.8, surface + 1.95, behind.z + 0.8
        )
        if (!level.noCollision(null, room)) {
            return Footing.Refused("there is no room to stand behind it")
        }
        return Footing.Stand(surface)
    }

    private fun footingProblem(level: ServerLevel, gunPos: BlockPos): String? =
        (footingOf(level, gunPos) as? Footing.Refused)?.reason

    /** How far past a hull's world box a gunner standing on her deck can be. */
    private const val DECK_REACH = 3.0

    /** How far below a sleeping gunner to look for a deck before concluding its ship is gone. */
    private const val GROUND_PROBE = 4

    private const val RECONCILE_INTERVAL = 20L
}
