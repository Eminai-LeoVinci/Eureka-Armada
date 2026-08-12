package org.valkyrienskies.eureka.template

import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.mod.util.StructureTemplateFillFromVoxelSet

/**
 * A ship, serialized to data and back again.
 *
 * ## Why this is the foundation
 * Blueprints, ships in a bottle, the shipwright's build and repair, and pirate-ship worldgen are all the same
 * operation wearing different hats: copy a ship out to something that isn't a ship, then put it back. Written
 * separately that is the same block-copying code five times over, and the five copies drift. This is the one copy.
 *
 * ## Why it is a vanilla StructureTemplate and not a bespoke format
 * VS2 mixes [StructureTemplateFillFromVoxelSet] into vanilla's `StructureTemplate`, which is how its own
 * `ShipAssembler.moveBlocksFromTo` relocates a hull on every single assemble. Filling from a voxel set gives us
 * block states, block-entity NBT and any `ICopyableBlock` tags for free, and because the result is a *plain*
 * `StructureTemplate` we inherit `.nbt` save/load, `StructureTemplateManager`, and the exact file format vanilla
 * worldgen and jigsaw already consume. One format therefore serves blueprints, bottled ships, hulls shipped inside
 * the jar, and hulls a builder authors for us -- and a player's blueprint is a real file that can be handed to
 * someone else.
 *
 * The 48x48x48 cap people associate with structures belongs to the structure *block*, not to the format or to
 * placement. Nothing here is bounded by it; [MAX_CAPTURE_CELLS] is our own sanity limit.
 *
 * ## What this does NOT capture yet
 * Deliberately, so that the round trip can be proven before anything is built on top of it:
 * - **Entities.** VS2's mixin explicitly clears `entityInfoList`. Item frames and armour stands need adding here.
 *   Crew do *not* -- `CrewLedger` already persists berths with snapshots and `CrewMuster` already re-musters on
 *   assembly, so a bottled ship keeps its crew by *skipping* the stand-down, not by capturing villagers.
 * - **Ship transform, scale, velocity, slug and VS attachments.** Those live on the `ServerShip`, not in the
 *   blocks; `ShipAssembler.assembleToShipFull` shows the restore path via `unsafeSetKinematics`.
 * - **A bill of materials.** Nothing in the codebase counts arbitrary blocks by item yet.
 *
 * Placement here drops blocks into the world *unassembled*, which is what the shipwright wants and what worldgen
 * wants. Turning a placement into a live ship is [org.valkyrienskies.eureka.util.ShipAssembler]'s job, separately.
 */
object ShipTemplate {

    /**
     * Refuse to walk an absurd volume. A capture visits every cell of the ship's block AABB once, so this bounds
     * the scan, not the ship: a long thin hull is cheap, a 200-cube is not. Matched to the same order of magnitude
     * as [org.valkyrienskies.eureka.armada.SubAir]'s fill cap for consistency.
     */
    const val MAX_CAPTURE_CELLS = 8_000_000L

    /** Template names are a path segment of an [Identifier], so they live under the same character rules. */
    private val VALID_NAME = Regex("[a-z0-9_.\\-/]+")

    sealed interface Outcome
    class Failed(val message: String) : Outcome
    class Captured(val id: Identifier, val blocks: Int, val size: BlockPos) : Outcome
    class Placed(val id: Identifier, val at: BlockPos, val size: BlockPos) : Outcome

    /**
     * Copy [ship]'s shipyard blocks into a template and write it to `<world>/generated/vs_eureka/structures/`.
     *
     * The bounds come from the blocks actually found, not from `shipAABB`, so a hull that does not fill its own
     * bounding box does not carry a skirt of empty space around with it forever.
     */
    fun capture(level: ServerLevel, ship: LoadedServerShip, name: String): Outcome {
        val id = idFor(name) ?: return Failed("'$name' is not a usable template name.")
        val aabb = ship.shipAABB ?: return Failed("That ship has no blocks.")

        val spanX = (aabb.maxX() - aabb.minX() + 1).toLong()
        val spanY = (aabb.maxY() - aabb.minY() + 1).toLong()
        val spanZ = (aabb.maxZ() - aabb.minZ() + 1).toLong()
        val cells = spanX * spanY * spanZ
        if (cells > MAX_CAPTURE_CELLS) {
            return Failed("That ship is too big to capture ($cells cells, limit $MAX_CAPTURE_CELLS).")
        }

        // Collect the solid blocks and their true bounds in one pass.
        val blocks = ArrayList<BlockPos>()
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    // An ungenerated chunk in the shipyard is empty space, not a hole in the hull.
                    if (!level.hasChunkAt(cursor)) continue
                    if (level.getBlockState(cursor).isAir) continue
                    blocks.add(cursor.immutable())
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                }
            }
        }
        if (blocks.isEmpty()) return Failed("That ship has no blocks to capture.")

        val min = BlockPos(minX, minY, minZ)
        val max = BlockPos(maxX, maxY, maxZ)
        // Centre of the captured volume, in shipyard coordinates. Only ICopyableBlock consumers read it (they use
        // it to rewrite cross-ship references), but VS2 computes it the same way and a wrong value here would
        // quietly corrupt those blocks rather than fail loudly.
        val centre = Vector3d(
            (minX + maxX + 1) / 2.0,
            (minY + maxY + 1) / 2.0,
            (minZ + maxZ + 1) / 2.0
        )

        val manager = level.server.structureManager
        // getOrCreate registers the template in the manager's repository, which is what makes save() able to find
        // it -- the repository field itself is private, so this is the only supported way in. Re-capturing under an
        // existing name overwrites, which is the semantics we want.
        val template = manager.getOrCreate(id)
        (template as StructureTemplateFillFromVoxelSet).`vs$fillFromVoxelSet`(
            level,
            blocks,
            listOf(ship as ServerShip),
            mapOf(ship.id to centre),
            min,
            max
        )

        if (!manager.save(id)) {
            // Don't leave a half-made template in the repository shadowing a real one.
            manager.remove(id)
            return Failed("Captured ${blocks.size} blocks but could not write the template to disk.")
        }

        val size = template.size
        return Captured(id, blocks.size, BlockPos(size.x, size.y, size.z))
    }

    /**
     * Place a saved template into the world with its corner at [at], as loose blocks -- no ship is created.
     *
     * Resolves through `StructureTemplateManager.get`, which reads from a datapack *or* from `<world>/generated`,
     * so hulls shipped inside the jar and hulls captured by a player load through the same call.
     */
    fun place(level: ServerLevel, name: String, at: BlockPos): Outcome {
        val id = idFor(name) ?: return Failed("'$name' is not a usable template name.")
        val template = level.server.structureManager.get(id).orElse(null)
            ?: return Failed("No template named '$name'.")

        val settings = StructurePlaceSettings()
        if (!template.placeInWorld(level, at, at, settings, level.random, Block.UPDATE_CLIENTS)) {
            return Failed("'$name' could not be placed there.")
        }

        val size = template.size
        return Placed(id, at, BlockPos(size.x, size.y, size.z))
    }

    /** Every template this mod can see, ours only -- the manager also lists vanilla's and other mods'. */
    fun list(level: ServerLevel): List<Identifier> =
        level.server.structureManager.listTemplates().toList().filter { it.namespace == EurekaMod.MOD_ID }

    private fun idFor(name: String): Identifier? =
        if (name.matches(VALID_NAME)) Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, name) else null
}
