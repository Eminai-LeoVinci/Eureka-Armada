package org.valkyrienskies.eureka.util

import com.google.common.collect.Sets
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.Clearable
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.joml.AxisAngle4d
import org.joml.Matrix4d
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.util.datastructures.DenseBlockPosSet
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties.HEAT
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.eureka.crew.HoldRetag
import org.valkyrienskies.eureka.crew.HoldTags
import org.valkyrienskies.mod.common.assembly.ShipAssembler as VSShipAssembler
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.isTickingChunk
import org.valkyrienskies.mod.common.networking.PacketRestartChunkUpdates
import org.valkyrienskies.mod.common.networking.PacketStopChunkUpdates
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.util.logger
import org.valkyrienskies.mod.util.relocateBlock
import org.valkyrienskies.mod.util.updateBlock
import kotlin.collections.set
import kotlin.math.*
import org.valkyrienskies.eureka.EurekaMod

// The two tags a WRECK is taken apart by. They exist only on that path -- a ship a captain disassembles at
// her own wheel is rebuilt exactly as she always was -- and they are declared here, at their only point of
// use, rather than beside the assembly tags they are nothing to do with.

// Blocks a wreck does not bring down with her. A hull that came to rest on her side lays her blocks out
// rolled while their STATES stay as they were, so anything that hung on a wall, sat on a floor or pointed
// somewhere in particular arrives meaningless -- and vanilla then knocks most of it off, silently and
// without drops. Losing it on purpose is the same outcome, minus the surprise.
val WRECK_DISCARD: TagKey<Block> =
    TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "wreck_discard"))

// World blocks that beat a wreck's own. Everywhere else the ship wins and buries herself -- sand, gravel,
// stone and water all give way, which is what makes her look part of the seabed rather than dropped onto
// it. Coral is the exception: a reef the hull came down through should grow back out through her deck,
// because a wreck erasing the reef it is lying in reads as damage rather than as time passing.
val WRECK_PRESERVE: TagKey<Block> =
    TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "wreck_preserve"))

// Blocks that fall to what will hold them once a wreck is laid down. A hull comes apart around her cargo,
// and a chest left hanging in open water where a deck used to be reads as the wreck being unfinished.
val WRECK_SETTLES: TagKey<Block> =
    TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "wreck_settles"))

object ShipAssembler {
    // BFS-collect the connected block set (world coordinates) that would become a ship, or null if it
    // exceeds maxShipBlocks. Deliberately does NOT clear snow or create the ship, so a caller can inspect
    // (and, for the Eureka Auto-Shipwright, mutate) the world set and still abort cleanly before anything is built.
    fun collectBlockPositions(level: ServerLevel, center: BlockPos, predicate: (BlockPos, BlockState) -> Boolean): HashSet<BlockPos>? {
        val blocks = DenseBlockPosSet()

        blocks.add(center.toJOML())
        if (!bfs(level, center, blocks, predicate)) return null

        val blockPositions = HashSet<BlockPos>()
        blocks.forEach { x, y, z -> blockPositions.add(BlockPos(x, y, z)) }
        return blockPositions
    }

    /**
     * A finished assembly, and the exact block map it was pasted with.
     *
     * VS2 lays the collected world blocks into the shipyard by pure integer translation -- a corner plus
     * each block's offset from the set's minimum -- so the only faithful way to answer "where did MY block
     * land" is to repeat that arithmetic. Asking the freshly seeded transform instead was the previous
     * answer, and it is wrong by whatever the ship's centre of mass does to the transform's offset term:
     * right most days, one block off on a bottle-released hull -- and everything keyed to the answer, the
     * crew station above all, quietly broke downstream of that one flooring.
     */
    class Assembled(val ship: ServerShip, private val corner: BlockPos, private val min: BlockPos) {
        /** Where [worldPos] -- one of the assembled blocks -- landed in the shipyard. Exact. */
        fun inShipyard(worldPos: BlockPos): BlockPos = corner.offset(worldPos.subtract(min))
    }

    // Turn a collected block set into a ship. Must run AFTER any pre-assembly world edits, since
    // VSShipAssembler reads the current world state into the ship. Clears orphaned resting snow first.
    fun finishAssembly(level: ServerLevel, blockPositions: Set<BlockPos>): Assembled {
        clearRestingSnowLayers(level, blockPositions)
        tagHoldsBeforeAssembly(level, blockPositions)
        val context = VSShipAssembler.assembleToShipFull(level, blockPositions, 1.0)

        // Recompute the paste's corner exactly as the assembler computed it: min/max of the set, the box's
        // half-extent, corner = ceil(centre - halfExtent). Same doubles, same ceil, same answer.
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (pos in blockPositions) {
            minX = min(minX, pos.x); minY = min(minY, pos.y); minZ = min(minZ, pos.z)
            maxX = max(maxX, pos.x); maxY = max(maxY, pos.y); maxZ = max(maxZ, pos.z)
        }
        val offset = Vector3d(
            (maxX - minX + 1) / 2.0, (maxY - minY + 1) / 2.0, (maxZ - minZ + 1) / 2.0
        )
        val corner = Vector3d(context.toCenter).sub(offset).ceil()
        return Assembled(
            context.ship,
            BlockPos(corner.x.toInt(), corner.y.toInt(), corner.z.toInt()),
            BlockPos(minX, minY, minZ)
        )
    }

    // Back-compat one-shot collect-then-assemble (no pre-assembly hook). Kept for any external callers;
    // the helm now drives the two steps directly so it can run the Eureka Auto-Shipwright in between.
    fun collectBlocks(level: ServerLevel, center: BlockPos, predicate: (BlockPos, BlockState) -> Boolean): ServerShip? =
        collectBlockPositions(level, center, predicate)?.let { finishAssembly(level, it).ship }

    // Snow layers (minecraft:snow) are in the assemble_blacklist, so they're never collected into the
    // ship -- but excluding them just leaves them behind in the world, hovering over the spot the deck
    // used to occupy, where (once they stack up) their collision box stops the player from moving. A
    // layer always rests directly on the block beneath it, so any layer sitting on top of an assembled
    // block is one that would be orphaned: delete it before the ship relocates to the shipyard. Whole
    // snow blocks (minecraft:snow_block) are NOT SnowLayerBlock, so they assemble with the ship as usual.
    private fun clearRestingSnowLayers(level: ServerLevel, blockPositions: Set<BlockPos>) {
        val above = BlockPos.MutableBlockPos()
        for (pos in blockPositions) {
            above.set(pos.x, pos.y + 1, pos.z)
            // Another ship block sits directly on top -- can't be a resting layer, and skips a world read.
            if (blockPositions.contains(above)) continue
            if (level.getBlockState(above).block is SnowLayerBlock) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
            }
        }
    }

    // Teach every chest and barrel what it is for, from what is already in it, BEFORE the hull moves.
    //
    // Done here rather than after the ship exists for two reasons. The blocks are still in the world, so
    // reading them needs no shipyard chunk to have settled -- and a relocation carries a block entity's full
    // saved tag with it, so a tag written now arrives in the shipyard already attached to the right box.
    //
    // The alternative was making a captain open and shut forty barrels to tell the ship what it can plainly
    // see: a magazine stocked before the ship was raised is still a magazine.
    private fun tagHoldsBeforeAssembly(level: ServerLevel, blockPositions: Set<BlockPos>) {
        val holds = ArrayList<BaseContainerBlockEntity>()
        for (pos in blockPositions) {
            val blockEntity = level.getBlockEntity(pos) ?: continue
            if (HoldTags.isHold(blockEntity)) holds.add(blockEntity as BaseContainerBlockEntity)
        }
        if (holds.isNotEmpty()) HoldRetag.tagAllAboard(level, holds)
    }

    private fun roundToNearestMultipleOf(number: Double, multiple: Double) = multiple * round(number / multiple)

    // modified from https://gamedev.stackexchange.com/questions/83601/from-3d-rotation-snap-to-nearest-90-directions
    private fun snapRotation(direction: AxisAngle4d): AxisAngle4d {
        val x = abs(direction.x)
        val y = abs(direction.y)
        val z = abs(direction.z)
        val angle = roundToNearestMultipleOf(direction.angle, PI / 2)

        return if (x > y && x > z) {
            direction.set(angle, direction.x.sign, 0.0, 0.0)
        } else if (y > x && y > z) {
            direction.set(angle, 0.0, direction.y.sign, 0.0)
        } else {
            direction.set(angle, 0.0, 0.0, direction.z.sign)
        }
    }

    private fun rotationFromAxisAngle(axis: AxisAngle4d): Rotation {
        if (axis.y.absoluteValue < 0.1) {
            // if the axis isn't Y, either we're tilted up/down (which should not happen often) or we haven't moved and it's
            // along the z axis with a magnitude of 0 for some reason. In these cases, we don't rotate.
            return Rotation.NONE
        }

        // normalize into counterclockwise rotation (i.e. positive y-axis, according to testing + right hand rule)
        if (axis.y.sign < 0.0) {
            axis.y = 1.0
            // the angle is always positive and < 2pi coming in
            axis.angle = 2.0 * PI - axis.angle
            axis.angle %= (2.0 * PI)
        }

        val eps = 0.001
        if (axis.angle < eps)
            return Rotation.NONE
        else if ((axis.angle - PI / 2.0).absoluteValue < eps)
            return Rotation.COUNTERCLOCKWISE_90
        else if ((axis.angle - PI).absoluteValue < eps)
            return Rotation.CLOCKWISE_180
        else if ((axis.angle - 3.0 * PI / 2.0).absoluteValue < eps)
            return Rotation.CLOCKWISE_90
        else {
            logger.warn("failed to convert $axis into a rotation")
            return Rotation.NONE
        }
    }

    /**
     * Puts [ship]'s blocks back into the world and tears the ship down. Returns false without touching anything if
     * the ship can't be laid down where it is -- see the height check below.
     */
    /**
     * The exact block map the next [unfillShip] will apply: the ship-to-world matrix with its rotation
     * snapped to the nearest quarter turn, and the sub-block offset every relocated block -- and every
     * rider -- is carried by. Published so the crew's stand-down can measure posts in the lattice the ship
     * is about to be laid into: any other frame misses the snap, and a snap that actually turns shifts a
     * reconstruction by a whole block. [anchor] must be one of the ship's own blocks (the helm).
     */
    fun unfillPlan(ship: ServerShip, anchor: BlockPos): Pair<Matrix4d, Vector3d> =
        unfillPlan(ship, anchor, 0, 0.0, 0)

    /**
     * As above, but able to lay a WRECK down: [rollDegrees] tips her onto her side about her own keel line,
     * and [sink] puts her that many blocks under the ground she came to rest on.
     *
     * Both stay on the block lattice. The roll is a multiple of 90 so rotated block coordinates land back on
     * whole blocks, and the drop that re-seats her works out integral for the same reason -- so a laid wreck
     * reconstructs exactly as cleanly as an upright ship, just lower and on her side.
     */
    fun unfillPlan(
        ship: ServerShip,
        anchor: BlockPos,
        rollDegrees: Int,
        burialFraction: Double,
        maxSink: Int
    ): Pair<Matrix4d, Vector3d> {
        val upright = layMatrix(ship, 0)
        val laid = if (rollDegrees == 0 && burialFraction <= 0.0) {
            upright
        } else {
            val rolled = layMatrix(ship, rollDegrees)
            val (rolledLow, rolledHigh) = verticalSpan(ship, rolled)

            // Measured against her height AFTER the roll, which is the whole point. A first-rate laid on
            // her side is as tall as she is wide, and burying her by a fraction of the height she had while
            // upright put her masts-deep in the seabed -- one test hull could not be found at all. Half of
            // what you can actually see is what "half buried" means.
            val sink = ((rolledHigh - rolledLow) * burialFraction.coerceIn(0.0, 1.0))
                .toInt().coerceIn(0, maxSink)

            // Re-seat her. A roll swings the hull about the SHIP's origin, which is nowhere near her keel,
            // so without this she would come apart somewhere above or below where she actually lay. Put the
            // lowest point of the rolled hull exactly where the lowest point of the upright one was, then
            // take her under by `sink`.
            val drop = lowestCorner(ship, upright) - rolledLow - sink
            Matrix4d().translation(0.0, drop, 0.0).mul(rolled)
        }
        val gridOffset = laid
            .transformPosition(Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5))
            .let { Vector3d(floor(it.x) + 0.5 - it.x, floor(it.y) + 0.5 - it.y, floor(it.z) + 0.5 - it.z) }
        return laid to gridOffset
    }

    /** The ship-to-world matrix with her rotation snapped to 90*, optionally rolled onto her side first. */
    private fun layMatrix(ship: ServerShip, rollDegrees: Int): Matrix4d = ship.transform.run {
        val m = Matrix4d()
            .translate(positionInWorld)
            .rotate(snapRotation(AxisAngle4d(shipToWorldRotation)))
        val snapped = snapRotation(AxisAngle4d(shipToWorldRotation))
        // A hull that already came down at an angle has heeled over by herself, and laying her down again
        // would stand her back up. The test is the one rotationFromAxisAngle uses to decide whether block
        // states can be rotated at all: a rotation about anything but Y is a ship that is not upright.
        val upright = snapped.angle < 1.0e-6 || abs(snapped.y) >= 0.9
        if (rollDegrees != 0 && upright) {
            // Applied in the SHIP's own frame, so she rolls about her keel line rather than about a world
            // axis: the longer of her two horizontal spans, which for anything built like a ship is her
            // length. The same rule sizes her wreck box, so the two always agree which way she is facing.
            val hull = ship.shipAABB
            val alongX = hull == null || (hull.maxX() - hull.minX()) >= (hull.maxZ() - hull.minZ())
            m.rotate(
                AxisAngle4d(
                    Math.toRadians(rollDegrees.toDouble()),
                    if (alongX) 1.0 else 0.0, 0.0, if (alongX) 0.0 else 1.0
                )
            )
        }
        m.scale(shipToWorldScaling)
            .translate(-positionInShip.x(), -positionInShip.y(), -positionInShip.z())
    }

    /**
     * Chests fall to whatever will hold them.
     *
     * A wreck loses a fair share of herself on the way down, and what she loses is mostly the deck a chest
     * was standing on -- so the cargo ends up hanging in open water above a hole. Dropping it to the first
     * thing underneath puts it on the seabed, or on the part of the hull that DID survive, which is where a
     * diver would expect to find it.
     *
     * Lowest first, so a chest that has already landed can be the thing the next one lands on.
     *
     * A double chest moves as a PAIR and only as far as the shyer half can go. Letting the two halves fall
     * independently would tear the pair in two -- one block of a two-block thing, with the contents of both
     * behind whichever half kept the block entity.
     */
    private fun settleCargo(level: ServerLevel, placed: Set<Triple<BlockPos, BlockPos, BlockState>>) {
        val floor = level.minBuildHeight + 1
        val moved = HashSet<BlockPos>()

        for (pos in placed.map { it.second }.sortedBy { it.y }) {
            if (pos in moved) continue
            val state = level.getBlockState(pos)
            if (!state.`is`(WRECK_SETTLES)) continue

            // The other half, when there is one. Read off the CURRENT state, not the shipyard's: a rolled
            // wreck lays its chests out having never rotated their block states, so the pairing on the
            // ground is whatever actually ended up next to what.
            val partner = if (state.hasProperty(ChestBlock.TYPE) &&
                state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
            ) {
                pos.relative(ChestBlock.getConnectedDirection(state))
            } else {
                null
            }
            val halves = if (partner != null && level.getBlockState(partner).`is`(WRECK_SETTLES)) {
                listOf(pos, partner)
            } else {
                listOf(pos)
            }

            val drop = halves.minOf { fallDistance(level, it, floor) }
            if (drop <= 0) continue

            for (half in halves) {
                level.relocateBlock(half, half.below(drop), true, null, Rotation.NONE)
                moved.add(half)
            }
        }
    }

    /** How far [pos] can fall before something stops it: open air and water are not something. */
    private fun fallDistance(level: ServerLevel, pos: BlockPos, floor: Int): Int {
        var drop = 0
        val cursor = BlockPos.MutableBlockPos()
        while (pos.y - drop - 1 >= floor) {
            cursor.set(pos.x, pos.y - drop - 1, pos.z)
            val below = level.getBlockState(cursor)
            if (!below.isAir && below.fluidState.isEmpty) break
            drop++
        }
        return drop
    }

    /** The world Y of the lowest corner of the ship's block box through [m]. */
    private fun lowestCorner(ship: ServerShip, m: Matrix4d): Double = verticalSpan(ship, m).first

    /** The world Y of the lowest and highest corners of the ship's block box through [m]. */
    private fun verticalSpan(ship: ServerShip, m: Matrix4d): Pair<Double, Double> {
        val b = ship.shipAABB ?: return 0.0 to 0.0
        val probe = Vector3d()
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        for (x in intArrayOf(b.minX(), b.maxX() + 1)) {
            for (y in intArrayOf(b.minY(), b.maxY() + 1)) {
                for (z in intArrayOf(b.minZ(), b.maxZ() + 1)) {
                    val corner = m.transformPosition(probe.set(x.toDouble(), y.toDouble(), z.toDouble()))
                    lowest = min(lowest, corner.y)
                    highest = max(highest, corner.y)
                }
            }
        }
        if (lowest == Double.MAX_VALUE) return 0.0 to 0.0
        return lowest to highest
    }

    /**
     * Take [ship] apart into world blocks.
     *
     * [wreck] is the difference between a captain disassembling her at the wheel and a hull that was shot
     * down coming apart where it fell. A wreck MERGES with the ground -- see [WRECK_DISCARD] and
     * [WRECK_PRESERVE] -- rather than stamping a ship-shaped hole into it. It defaults false, so every
     * existing caller keeps the exact behaviour it had.
     */
    fun unfillShip(
        level: ServerLevel,
        ship: ServerShip,
        shipCenter: BlockPos,
        center: BlockPos,
        wreck: Boolean = false,
        burialFraction: Double = 0.0,
        maxSink: Int = 0,
        rollDegrees: Int = 0
    ): Boolean {
        val rotation: Rotation = ship.transform.shipToWorldRotation
            .let(::AxisAngle4d)
            .let(ShipAssembler::snapRotation)
            .let(::rotationFromAxisAngle)

        val (shipToWorld, gridOffset) = unfillPlan(ship, shipCenter, rollDegrees, burialFraction, maxSink)

        // Every block below is written to floor(its world centre), and nothing downstream checks that against the
        // world's height range: a hull straddling the build ceiling -- which a high-flying ship reaches easily --
        // relocates blocks into a chunk section that doesn't exist and takes the server down with an out-of-bounds
        // index. Bail out before anything is mutated. Refusing beats the alternatives: skipping the offending
        // blocks would silently delete the top of someone's build, and clamping them would fuse it into a slab.
        // The rotation is snapped to 90* here, so the ship's block AABB through this matrix is exact.
        val bounds = ship.shipAABB
        if (bounds != null) {
            val probe = Vector3d()
            var lowest = Double.MAX_VALUE
            var highest = -Double.MAX_VALUE
            for (x in intArrayOf(bounds.minX(), bounds.maxX() + 1)) {
                for (y in intArrayOf(bounds.minY(), bounds.maxY() + 1)) {
                    for (z in intArrayOf(bounds.minZ(), bounds.maxZ() + 1)) {
                        val corner = shipToWorld.transformPosition(probe.set(x.toDouble(), y.toDouble(), z.toDouble()))
                        lowest = min(lowest, corner.y)
                        highest = max(highest, corner.y)
                    }
                }
            }
            if (level.isOutsideBuildHeight(floor(lowest).toInt()) ||
                level.isOutsideBuildHeight(floor(highest).toInt() - 1)
            ) {
                return false
            }
        }

        ship.isStatic = true

        val alloc0 = Vector3d()

        // gridOffset (from unfillPlan): every block below is written to floor(its world center), so a ship at
        // any sub-block offset -- which is every ship that hasn't come to rest exactly on the grid -- has its
        // whole structure snapped to the block grid on the way out, moving it by up to half a block on each
        // axis. The entities standing on it are not part of that write. When the deck rises into a player's
        // feet it leaves them embedded in it: never on ground (so no jump, and mining runs at the airborne
        // penalty), every neighbouring block at foot level is deck too, and the server keeps correcting the
        // client's attempt to fall out -- the rapid bob. Relogging can't clear it, because the position
        // genuinely is inside the block. So carry whatever was riding the ship by the same offset the blocks
        // take. Measured off the helm, which is one of this ship's own blocks and so goes through exactly the
        // math the loop applies; a 90-degree rotation maps the lattice onto itself, so one vector describes
        // the whole move.

        // Captured BEFORE the relocation: the block updates it triggers can spawn entities of their own (falling
        // sand off a column that just lost its support), and those already appear at their final position. The
        // query box is deliberately generous -- worldAABB is the hull, and someone standing on deck is a hair
        // outside it -- with the destination check below, not the box, keeping bystanders out of the move.
        val worldToShip = ship.transform.worldToShip
        val footprint = ship.worldAABB
        val riders = level.getEntities(
            null as Entity?,
            AABB(
                footprint.minX() - 1.0, footprint.minY() - 1.0, footprint.minZ() - 1.0,
                footprint.maxX() + 1.0, footprint.maxY() + 1.0, footprint.maxZ() + 1.0
            )
        ).filter { !it.isPassenger && it !is BlockAttachedEntity }

        // Fixtures -- item frames, paintings, leash knots -- are a separate problem from riders, and they were
        // being lost. A rider stands on the deck and is therefore at a WORLD position, which is what the query
        // above finds. Anything hanging on the hull was relocated INTO the shipyard when the ship assembled, so
        // it is nowhere near that box: disassembly moved the blocks out from under it and left it behind,
        // attached to a wall that no longer existed. Assembly learned to carry these in; nothing carried them
        // back out.
        //
        // Their anchor blocks are recorded now, while the shipyard still exists, and replayed through the same
        // transform the blocks take, below.
        val fixtures = ship.shipAABB?.let { b ->
            val box = AABB(
                (b.minX() - 3).toDouble(), (b.minY() - 3).toDouble(), (b.minZ() - 3).toDouble(),
                (b.maxX() + 4).toDouble(), (b.maxY() + 4).toDouble(), (b.maxZ() + 4).toDouble()
            )
            // getEntities, not getEntitiesOfClass: the riders query above uses it and reliably sees ship-space
            // entities, and VS2 mixes into entity lookups -- the two calls do not necessarily agree.
            level.getEntities(null as Entity?, box).filterIsInstance<BlockAttachedEntity>()
        } ?: emptyList()
        val fixtureAnchors = fixtures.associateWith { it.pos }

        val chunksToBeUpdated = mutableMapOf<ChunkPos, Pair<ChunkPos, ChunkPos>>()

        ship.activeChunksSet.forEach { chunkX, chunkZ ->
            chunksToBeUpdated[ChunkPos(chunkX, chunkZ)] =
                Pair(ChunkPos(chunkX, chunkZ), ChunkPos(chunkX, chunkZ))
        }

        val chunkPairs = chunksToBeUpdated.values.toList()
        val chunkPoses = chunkPairs.flatMap { it.toList() }
        val chunkPosesJOML = chunkPoses.map { it.toJOML() }

        // Send a list of all the chunks that we plan on updating to players, so that they
        // defer all updates until assembly is finished
        level.players().forEach { player ->
            with (vsCore.simplePacketNetworking) {
                PacketStopChunkUpdates(chunkPosesJOML).sendToClient(player.playerWrapper)
            }
        }

        val toUpdate = Sets.newHashSet<Triple<BlockPos, BlockPos, BlockState>>()

        // Blocks that are NOT coming with her: struck off by tag, torn away on the way down, or beaten by
        // something the world would rather keep. Collected rather than cleared in place, because the walk
        // below is reading the very chunk sections a clear would rewrite.
        val discarded = ArrayList<BlockPos>()
        val shatter = if (wreck) WreckDamage.rules() else emptyList()

        ship.activeChunksSet.forEach { chunkX, chunkZ ->
            val chunk = level.getChunk(chunkX, chunkZ)
            for (sectionIndex in 0 until chunk.sections.size) {
                val section = chunk.sections[sectionIndex]

                if (section == null || section.hasOnlyAir()) continue

                val bottomY = sectionIndex shl 4

                for (x in 0..15) {
                    for (y in 0..15) {
                        for (z in 0..15) {
                            val state = section.getBlockState(x, y, z)
                            if (state.isAir) continue

                            val realX = (chunkX shl 4) + x
                            val realY = bottomY + y + level.minBuildHeight
                            val realZ = (chunkZ shl 4) + z
                            val inShipPos = BlockPos(realX, realY, realZ)

                            if (wreck &&
                                (state.`is`(WRECK_DISCARD) || WreckDamage.shatters(state, shatter, level.random))
                            ) {
                                discarded.add(inShipPos)
                                continue
                            }

                            val inWorldPos = shipToWorld.transformPosition(alloc0.set(realX + 0.5, realY + 0.5, realZ + 0.5)).floor()

                            val inWorldBlockPos = BlockPos(inWorldPos.x.toInt(), inWorldPos.y.toInt(), inWorldPos.z.toInt())

                            // The one place the world ever beats the ship. Everything else it lands on --
                            // sand, gravel, stone, water -- gives way, and that giving way is the burial.
                            if (wreck && level.getBlockState(inWorldBlockPos).`is`(WRECK_PRESERVE)) {
                                discarded.add(inShipPos)
                                continue
                            }

                            toUpdate.add(Triple(inShipPos, inWorldBlockPos, state))
                            level.relocateBlock(inShipPos, inWorldBlockPos, false, null, rotation)
                        }
                    }
                }
            }
        }

        // Taken out of the shipyard by hand, because NOTHING else will.
        //
        // unfillShip does not delete the ship -- it empties her and sets her static, and vs-core reaps a hull
        // once there is nothing left in it. A block merely skipped above is a block still standing in the
        // shipyard, so the ship never becomes empty and never goes: the first cut left every cannon on a
        // sunk hull floating at the wreck's old angle, solid, clickable, and droppable for its gunpowder.
        // A ghost ship made of exactly the parts that were supposed to be destroyed.
        //
        // Contents first and drops suppressed. A cannon holds its powder and shot, and a wreck's guns going
        // down with her should not rain their ammunition into the sea on the way.
        for (pos in discarded) {
            (level.getBlockEntity(pos) as? Clearable)?.clearContent()
            level.setBlock(
                pos, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS
            )
        }
        // We update the blocks after they're set to prevent blocks from breaking
        for (triple in toUpdate) {
            updateBlock(level, triple.first, triple.second, triple.third)
        }

        if (wreck) settleCargo(level, toUpdate)

        // A sunk ship's engines are cold ones. Done HERE, on the world copy, rather than in the shipyard
        // before the move: relocateBlock rebuilds the block entity from a saved tag, so anything doused on
        // the way out would simply be restored on the way in.
        if (wreck) {
            for ((_, worldPos, _) in toUpdate) {
                val placed = level.getBlockState(worldPos)
                if (!placed.hasProperty(HEAT)) continue
                (level.getBlockEntity(worldPos) as? EngineBlockEntity)?.douse()
                if (placed.getValue(HEAT) != 0) {
                    level.setBlock(worldPos, placed.setValue(HEAT, 0), Block.UPDATE_ALL)
                }
            }
        }

        // The world blocks exist again, so the walls these hang on are back: move each fixture onto the world
        // block its shipyard anchor became, using the same centre-of-block transform the blocks themselves took.
        // Setting the anchor first matters -- setDirection recalculates position and bounding box FROM it, and a
        // hanging entity whose facing no longer meets a wall fails survives() and pops off on the next tick.
        val alloc2 = Vector3d()
        for (entity in fixtures) {
            if (entity.isRemoved) continue
            // A wreck loses hers. These hang on walls, and a hull that came to rest on her side puts those
            // walls at an angle nothing was ever nailed to; carrying a frame across only for vanilla to
            // knock it off a tick later is the same loss with an extra step. See WRECK_DISCARD.
            if (wreck) {
                entity.discard()
                continue
            }
            val anchor = fixtureAnchors[entity] ?: continue
            val moved = shipToWorld
                .transformPosition(alloc2.set(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5))
                .floor()
            val worldAnchor = BlockPos(moved.x.toInt(), moved.y.toInt(), moved.z.toInt())

            entity.pos = worldAnchor
            if (entity is HangingEntity) {
                // Rotation is snapped to 90* above, so a hull that turned takes its frames round with it.
                entity.setDirection(rotation.rotate(entity.direction))
            } else {
                entity.setPos(worldAnchor.x + 0.5, worldAnchor.y + 0.5, worldAnchor.z + 0.5)
            }
        }

        // The world blocks exist again: put the riders back where the structure carried them. A ship-space round
        // trip rather than a plain translate, so the rotation snap (up to the helm's disassemble threshold of
        // yaw) turns them with the hull instead of leaving them offset from it.
        val alloc1 = Vector3d()
        for (entity in riders) {
            if (entity.isRemoved) continue
            val to = shipToWorld
                .transformPosition(worldToShip.transformPosition(alloc1.set(entity.x, entity.y, entity.z)))
                .add(gridOffset)
            val moved = entity.boundingBox.move(to.x - entity.x, to.y - entity.y, to.z - entity.z)
            // A destination inside a block means this was never riding the ship -- a bystander standing against
            // the hull, caught by the generous query box. Leave it be; where it already is, is free.
            if (level.getBlockCollisions(entity, moved).iterator().hasNext()) continue
            entity.teleportTo(to.x, to.y, to.z)
        }

        level.server.executeIf(
            // This condition will return true if all modified chunks have been both loaded AND
            // chunk update packets were sent to players
            { chunkPoses.all(level::isTickingChunk) }
        ) {
            // Once all the chunk updates are sent to players, we can tell them to restart chunk updates
            level.players().forEach { player ->
                with (vsCore.simplePacketNetworking) {
                    PacketRestartChunkUpdates(chunkPosesJOML).sendToClient(player.playerWrapper)
                }
            }
        }
        return true
    }

    private fun bfs(
        level: ServerLevel,
        start: BlockPos,
        blocks: DenseBlockPosSet,
        predicate: (BlockPos, BlockState) -> Boolean
    ): Boolean {

        val blacklist = DenseBlockPosSet()
        val stack = ObjectArrayList<BlockPos>()

        // Mark seeds as visited as they're queued -- the SAME guard the pop loop uses below. Without it,
        // start's neighbors sit on the stack un-blacklisted, so once start itself is reached (a neighbor
        // re-offers it) it re-offers those same neighbors and they get processed a SECOND time. That
        // double-fires the predicate's side effects (the helm's floater/balloon/blockCount tally),
        // inflating the counts the assembler and applyControl consume. start is deliberately NOT
        // blacklisted: it must be reachable exactly once so ShipHelmBlock is counted (helms >= 1).
        directions(start) {
            if (!blacklist.contains(it.x, it.y, it.z)) {
                blacklist.add(it.x, it.y, it.z)
                stack.push(it)
            }
        }

        while (!stack.isEmpty) {
            val pos = stack.pop()

            if (predicate(pos, level.getBlockState(pos))) {
                blocks.add(pos.x, pos.y, pos.z)
                directions(pos) {
                    if (!blacklist.contains(it.x, it.y, it.z)) {
                        blacklist.add(it.x, it.y, it.z)
                        stack.push(it)
                    }
                }
            }
            if ((EurekaConfig.SERVER.maxShipBlocks > 0) and (blocks.size > EurekaConfig.SERVER.maxShipBlocks)) {
                logger.info("Stopped ship assembly due too many blocks")
                return false
            }
        }
        if (EurekaConfig.SERVER.maxShipBlocks > 0) {
            logger.info("Assembled ship with ${blocks.size} blocks, out of ${EurekaConfig.SERVER.maxShipBlocks} allowed")
        }
        return true
    }

    // Decides whether a patch of terrain-type blocks is part of a player's build or part of the world.
    //
    // Minecraft keeps no record of who placed a block -- a grass block in a hill and a grass block laid as
    // a ship's deck are the same value in the same chunk array -- so origin cannot be looked up; it has to
    // be inferred. What does distinguish a build from the landscape is EXTENT. A deck is a pocket of at
    // most a few thousand blocks sealed in by hull and air; a beach or a hillside just keeps going. So
    // when assembly meets a block of a terrain-type kind (the vs_eureka:assemble_terrain tag) it floods
    // outward through terrain-type blocks alone and asks whether the patch ENDS. Ends within the budget:
    // it is a build, and the whole patch sails. Still going when the budget runs out: it is the world, and
    // the whole patch stays. Air, hull blocks and blacklisted blocks bound the flood on every side.
    //
    // The honest limits of the inference: a genuinely tiny landform -- a sand islet smaller than the
    // budget -- reads as a build, so a ship parked touching one will take it. And a deck that physically
    // touches the shore reads as the world, so it stays behind exactly as it would have before. Both
    // resolve the moment the ship isn't parked against the thing it's being confused with.
    //
    // Verdicts are cached per assembly, and a flood that runs into an already-rejected patch rejects
    // immediately -- it has just proven it is connected to the same landmass.
    class TerrainPocketClassifier(
        private val level: ServerLevel,
        private val budget: Int,
        private val walkable: (BlockState) -> Boolean,
    ) {
        private val accepted = LongOpenHashSet()
        private val rejected = LongOpenHashSet()

        fun isBoundedPocket(start: BlockPos): Boolean {
            val startKey = start.asLong()
            if (accepted.contains(startKey)) return true
            if (rejected.contains(startKey)) return false
            if (budget <= 0) return false // 0 restores the old behavior: terrain-type blocks never assemble

            val region = LongOpenHashSet()
            val stack = ObjectArrayList<BlockPos>()
            region.add(startKey)
            stack.push(start)
            var bounded = true
            while (bounded && !stack.isEmpty) {
                val pos = stack.pop()
                directions(pos) {
                    val key = it.asLong()
                    if (bounded && !region.contains(key)) {
                        if (rejected.contains(key)) {
                            bounded = false // joined a patch already proven to be the landmass
                        } else if (walkable(level.getBlockState(it))) {
                            region.add(key)
                            if (region.size > budget) bounded = false else stack.push(it)
                        }
                    }
                }
            }
            if (bounded) accepted.addAll(region) else rejected.addAll(region)
            return bounded
        }
    }

    private fun directions(center: BlockPos, lambda: (BlockPos) -> Unit) {
        // diagonals=false means 6-connectivity ONLY; without this return the 26-neighbor loop
        // below always ran too, making the toggle a no-op (bug inherited from upstream).
        if (!EurekaConfig.SERVER.diagonals) {
            Direction.entries.forEach { lambda(center.relative(it)) }
            return
        }
        for (x in -1..1) {
            for (y in -1..1) {
                for (z in -1..1) {
                    if (x != 0 || y != 0 || z != 0) {
                        lambda(center.offset(x, y, z))
                    }
                }
            }
        }
    }

    private val logger by logger()
}
