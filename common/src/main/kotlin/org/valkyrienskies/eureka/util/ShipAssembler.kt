package org.valkyrienskies.eureka.util

import com.google.common.collect.Sets
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.joml.AxisAngle4d
import org.joml.Matrix4d
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.util.datastructures.DenseBlockPosSet
import org.valkyrienskies.eureka.EurekaConfig
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

    // Turn a collected block set into a ship. Must run AFTER any pre-assembly world edits, since
    // VSShipAssembler reads the current world state into the ship. Clears orphaned resting snow first.
    fun finishAssembly(level: ServerLevel, blockPositions: Set<BlockPos>): ServerShip {
        clearRestingSnowLayers(level, blockPositions)
        return VSShipAssembler.assembleToShip(level, blockPositions, 1.0)
    }

    // Back-compat one-shot collect-then-assemble (no pre-assembly hook). Kept for any external callers;
    // the helm now drives the two steps directly so it can run the Eureka Auto-Shipwright in between.
    fun collectBlocks(level: ServerLevel, center: BlockPos, predicate: (BlockPos, BlockState) -> Boolean): ServerShip? =
        collectBlockPositions(level, center, predicate)?.let { finishAssembly(level, it) }

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
    fun unfillShip(level: ServerLevel, ship: ServerShip, shipCenter: BlockPos, center: BlockPos): Boolean {
        val rotation: Rotation = ship.transform.shipToWorldRotation
            .let(::AxisAngle4d)
            .let(ShipAssembler::snapRotation)
            .let(::rotationFromAxisAngle)

        // ship's rotation rounded to nearest 90*
        val shipToWorld = ship.transform.run {
            Matrix4d()
                .translate(positionInWorld)
                .rotate(snapRotation(AxisAngle4d(shipToWorldRotation)))
                .scale(shipToWorldScaling)
                .translate(-positionInShip.x(), -positionInShip.y(), -positionInShip.z())
        }

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

        // Every block below is written to floor(its world center), so a ship sitting at any sub-block offset --
        // which is every ship that hasn't come to rest exactly on the grid -- has its whole structure snapped to
        // the block grid on the way out, moving it by up to half a block on each axis. The entities standing on
        // it are not part of that write. When the deck rises into a player's feet it leaves them embedded in it:
        // never on ground (so no jump, and mining runs at the airborne penalty), every neighbouring block at foot
        // level is deck too, and the server keeps correcting the client's attempt to fall out -- the rapid bob.
        // Relogging can't clear it, because the position genuinely is inside the block. So carry whatever was
        // riding the ship by the same offset the blocks take.
        //
        // Measured off the helm, which is one of this ship's own blocks and so goes through exactly the math the
        // loop applies. A 90-degree rotation maps the block lattice onto itself, so every block of the ship
        // shares the helm's fractional offset -- one vector describes the whole move.
        val gridOffset = shipToWorld
            .transformPosition(Vector3d(shipCenter.x + 0.5, shipCenter.y + 0.5, shipCenter.z + 0.5))
            .let { Vector3d(floor(it.x) + 0.5 - it.x, floor(it.y) + 0.5 - it.y, floor(it.z) + 0.5 - it.z) }

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
        ).filter { !it.isPassenger }

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
                            val realY = bottomY + y + level.minY
                            val realZ = (chunkZ shl 4) + z

                            val inWorldPos = shipToWorld.transformPosition(alloc0.set(realX + 0.5, realY + 0.5, realZ + 0.5)).floor()

                            val inWorldBlockPos = BlockPos(inWorldPos.x.toInt(), inWorldPos.y.toInt(), inWorldPos.z.toInt())
                            val inShipPos = BlockPos(realX, realY, realZ)

                            toUpdate.add(Triple(inShipPos, inWorldBlockPos, state))
                            level.relocateBlock(inShipPos, inWorldBlockPos, false, null, rotation)
                        }
                    }
                }
            }
        }
        // We update the blocks after they're set to prevent blocks from breaking
        for (triple in toUpdate) {
            updateBlock(level, triple.first, triple.second, triple.third)
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
