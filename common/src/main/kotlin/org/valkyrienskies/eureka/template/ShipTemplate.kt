package org.valkyrienskies.eureka.template

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.bottle.ThrownShipBottle
import org.valkyrienskies.eureka.cannon.CannonShot
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
 * - **Ship transform, scale, velocity, slug and VS attachments.** Those live on the `ServerShip`, not in the
 *   blocks; `ShipAssembler.assembleToShipFull` shows the restore path via `unsafeSetKinematics`.
 * - **A bill of materials.** Nothing in the codebase counts arbitrary blocks by item yet.
 *
 * Entities *are* captured -- see [captureEntities] -- but crew deliberately are not: `CrewLedger` already
 * persists berths with snapshots and `CrewMuster` re-musters on assembly, so a bottled ship keeps its crew by
 * skipping the stand-down rather than by serializing villagers.
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
    class Captured(val id: Identifier, val blocks: Int, val entities: Int, val size: BlockPos) : Outcome
    class Placed(val id: Identifier, val at: BlockPos, val size: BlockPos) : Outcome

    /**
     * Copy [ship]'s shipyard blocks into a template and write it to `<world>/generated/vs_eureka/structures/`.
     *
     * The bounds come from the blocks actually found, not from `shipAABB`, so a hull that does not fill its own
     * bounding box does not carry a skirt of empty space around with it forever.
     */
    /**
     * @param keepShipName whether the captured helm should carry the ship's name with it.
     *
     * Off by default, because a template is a *design*: build five hulls from one blueprint with this on and you
     * get five ships sharing a name, and a duplicate name is not cosmetic. `ShipArgument.getShip` returns a ship
     * only when exactly one matches and throws `ERROR_MANY_SHIP_FOUND` otherwise, so every `/vs` command that
     * takes a ship -- teleport and rename included -- stops working on all of them at once. Nothing enforces
     * uniqueness at rename time, so nothing else will catch it.
     *
     * Ship-in-a-bottle is the case that wants this ON: that really is the same vessel, and it should come back
     * under its own name.
     */
    fun capture(
        level: ServerLevel,
        ship: LoadedServerShip,
        name: String,
        keepShipName: Boolean = false
    ): Outcome {
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

        // MUST follow the fill: vs$fillFromVoxelSet ends by clearing entityInfoList, so anything added before it
        // would be silently dropped.
        val entities = captureEntities(level, ship, template, min, max)
        if (!keepShipName) stripShipName(template)

        if (!manager.save(id)) {
            // Don't leave a half-made template in the repository shadowing a real one.
            manager.remove(id)
            return Failed("Captured ${blocks.size} blocks but could not write the template to disk.")
        }

        val size = template.size
        return Captured(id, blocks.size, entities, BlockPos(size.x, size.y, size.z))
    }

    /**
     * Add the ship's fixtures -- item frames, paintings, armour stands -- to [template].
     *
     * VS2's fill deliberately ends with `entityInfoList.clear()`, so a captured ship arrives with no entities at
     * all and this puts them back. Everything downstream is vanilla: `save` already writes `entityInfoList` into
     * the `.nbt`, and `placeInWorld` already recreates it, so nothing here needs a matching restore path.
     *
     * ## Two coordinate spaces, one query
     * A ship's blocks live in the SHIPYARD, but a thing standing on its deck is at a WORLD position -- VS2 only
     * renders it over the hull. Both kinds answer a shipyard-box query, and they do NOT agree about where they
     * are:
     * - **Shipyard-space**, e.g. an item frame, which assembly relocated in with the hull. Subtracting [min]
     *   from its position gives a sensible offset directly.
     * - **World-space**, e.g. an armour stand on the deck. Subtracting a shipyard [min] from a world position
     *   produces garbage -- measured at ~28 million blocks on a real ship -- and the entity is then faithfully
     *   recreated 28 million blocks from the target. It does not look "dropped"; it looks absent.
     *
     * So an offset that lands outside the captured volume is not an error to discard, it is the signal that this
     * entity was reporting world coordinates: transform it through `worldToShip` and try again. Anything still
     * outside after that genuinely was not aboard, and is skipped rather than flung into the void.
     *
     * ## What is deliberately not captured
     * Players, passengers (a rider is already written inside its vehicle's tag, so capturing both duplicates it),
     * and living things. Crew, livestock and pets are cargo rather than ship -- and crew in particular ride the
     * [org.valkyrienskies.eureka.crew.CrewLedger], so a bottled ship keeps them by skipping the stand-down, never
     * by serializing villagers. Armour stands are the deliberate exception: furniture that happens to extend
     * `LivingEntity`.
     *
     * Loose items and experience orbs are skipped too, and for a different reason: they are not part of a
     * ship, and carrying them makes a bottle a duplicator -- restored on release, then captured again by the
     * next bottling, a copy deeper every cycle.
     */
    private fun captureEntities(
        level: ServerLevel,
        ship: LoadedServerShip,
        template: StructureTemplate,
        min: BlockPos,
        max: BlockPos
    ): Int {
        val box = AABB(
            min.x.toDouble(), min.y.toDouble(), min.z.toDouble(),
            (max.x + 1).toDouble(), (max.y + 1).toDouble(), (max.z + 1).toDouble()
        )
        val spanX = (max.x - min.x).toDouble()
        val spanY = (max.y - min.y).toDouble()
        val spanZ = (max.z - min.z).toDouble()

        fun inside(v: Vec3) =
            v.x >= -1.0 && v.x <= spanX + 1.0 &&
                v.y >= -1.0 && v.y <= spanY + 1.0 &&
                v.z >= -1.0 && v.z <= spanZ + 1.0

        var captured = 0
        val candidates = level.getEntitiesOfClass(Entity::class.java, box) {
            it !is Player && !it.isPassenger && (it !is LivingEntity || it is ArmorStand) &&
                // Loose items and orbs are not part of a ship, and capturing them is how a bottle turns into
                // a duplicator. A dropped stack is written into the template as an entity, laid back out on
                // release, and -- because it is on the deck of the ship that just came out -- captured again
                // by the next bottling. Every cycle adds another copy. The gunpowder and shot raining off a
                // released hull were exactly this: the magazines came back from the blocks' own NBT, and the
                // loose duplicates came back from the entity list beside them.
                it !is ItemEntity && it !is ExperienceOrb &&
                // And our own two things in flight, for a sharper version of the same reason: a bottle
                // CAPTURES ITSELF.
                //
                // `takeTheShip` stations the bottle RISE_ABOVE blocks over the wheel and calls the capture from
                // there -- so at the instant the template is written, the bottle doing the writing is hovering
                // inside the ship's own box and gets recorded like a deck fixture. It is written in the state
                // it held at that moment: still carrying the EMPTY it set out with, since `carry(taken)` has
                // not run yet, and already `helm = null`, which `takeTheShip` clears first.
                //
                // Releasing the ship then hatches that copy. It arrives in phase HOMING with no helm, which
                // `home` reads as "lost my ship" on its very first tick, so it turns straight round and hands
                // its empty bottle to the captain -- which is the empty, unmarked bottle that came back after
                // every single release. Nothing about it was a bottle-spending bug; it was a stowaway.
                //
                // A cannonball in flight over the deck at capture time is the identical trap, and would come
                // back out of the bottle still flying, so it goes the same way.
                it !is ThrownShipBottle && it !is CannonShot
        }

        for (entity in candidates) {
            // Shipyard reading first; fall back to transforming a world position into the ship's frame.
            var relative = Vec3(entity.x - min.x, entity.y - min.y, entity.z - min.z)
            if (!inside(relative)) {
                val inShip = ship.transform.worldToShip.transformPosition(Vector3d(entity.x, entity.y, entity.z))
                relative = Vec3(inShip.x - min.x, inShip.y - min.y, inShip.z - min.z)
            }
            if (!inside(relative)) continue // not actually aboard

            val tag = try {
                val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess())
                // save(), not saveWithoutId(): placement rebuilds from the tag alone and needs the entity id in it.
                // A false return means the entity declined to persist -- already removed, or a type that never saves.
                if (!entity.save(output)) continue
                output.buildResult()
            } catch (ex: Exception) {
                continue // one bad fixture must not cost us the whole ship
            }

            // Hanging things are positioned by the block they cling to, not by their own centre. Anchoring on the
            // attachment point keeps an item frame on its wall instead of a block off it. Everything else anchors
            // on its own position, clamped because placeEntities bounds-tests this and silently drops what is
            // outside -- our bounds are tight to the solid blocks where vanilla's have author-drawn headroom, so
            // anything standing on the topmost block would otherwise land exactly one index past the end.
            val raw =
                if (entity is BlockAttachedEntity) entity.pos.subtract(min) else BlockPos.containing(relative)
            val anchor = BlockPos(
                raw.x.coerceIn(0, max.x - min.x),
                raw.y.coerceIn(0, max.y - min.y),
                raw.z.coerceIn(0, max.z - min.z)
            )

            template.entityInfoList.add(StructureTemplate.StructureEntityInfo(relative, anchor, tag))
            captured++
        }
        return captured
    }

    /**
     * Forget, in every captured helm, which ship this used to be.
     *
     * The helm block entity stores the name as vanilla's own `CustomName` plus a remembered slug, and re-applies
     * it through `renameShip` a few ticks after the hull next assembles -- so a copied helm renames its new ship
     * to the old one's name without anyone asking. Note the flag is written only when *false*, so an absent key
     * reads back as "keep the name": clearing the name is not enough, the refusal has to be written in.
     */
    private fun stripShipName(template: StructureTemplate) {
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.block !is ShipHelmBlock) continue
                val tag = info.nbt ?: continue
                tag.remove("CustomName")
                tag.remove(REMEMBERED_SHIP_KEY)
                tag.remove(SHIP_SLUG_KEY)
                // The bottle-binding identity goes the same way the name does, and for the same reason a
                // copy must not share a name: two wheels carrying one binding would have every bottle marked
                // on the original answering to whichever copy reported its address last.
                tag.remove(BOTTLE_BINDING_KEY)
                tag.putBoolean(KEEP_NAME_KEY, false)
            }
        }
    }

    /** Mirrors the private constants in ShipHelmBlockEntity; the tags are written by that class, not this one. */
    private const val REMEMBERED_SHIP_KEY = "vs_eureka:remembered_ship"
    private const val KEEP_NAME_KEY = "vs_eureka:keep_name"
    private const val SHIP_SLUG_KEY = "vs_eureka:ship_slug"
    private const val BOTTLE_BINDING_KEY = "vs_eureka:bottle_binding"

    /**
     * Place a saved template into the world with its corner at [at], as loose blocks -- no ship is created.
     *
     * Resolves through `StructureTemplateManager.get`, which reads from a datapack *or* from `<world>/generated`,
     * so hulls shipped inside the jar and hulls captured by a player load through the same call.
     */
    fun place(level: ServerLevel, name: String, at: BlockPos): Outcome {
        val id = idFor(name) ?: return Failed("'$name' is not a usable template name.")
        val template = find(level, name) ?: return Failed("No template named '$name'.")

        // Drop loose items and orbs on the way OUT as well as on the way in.
        //
        // captureEntities no longer records them, but every template written before it stopped still carries
        // them, and a bottle is a saved file: those keep laying their pile out on every release, and every
        // re-bottling of the ship they land on writes a deeper copy. Filtering here retires the whole cycle
        // instead of only stopping new ones, so an existing bottle is safe to use rather than being one last
        // duplication waiting to happen.
        template.entityInfoList.removeIf { info ->
            when (info.nbt?.getString("id")?.orElse("")) {
                "minecraft:item", "minecraft:experience_orb" -> true
                // The stowaway bottle, and a shot that happened to be over the deck -- see captureEntities.
                // Filtered here as well as at capture for the reason the items are: a bottle is a saved file,
                // and every template already on disk carries its own stowaway. Without this, existing bottles
                // would go on handing an empty back forever and only newly captured ones would behave.
                "vs_eureka:thrown_ship_bottle", "vs_eureka:cannon_shot" -> true
                else -> false
            }
        }

        // Explicit rather than relying on the default: entities are half the point of a captured ship.
        val settings = StructurePlaceSettings().setIgnoreEntities(false)
        if (!template.placeInWorld(level, at, at, settings, level.random, Block.UPDATE_CLIENTS)) {
            return Failed("'$name' could not be placed there.")
        }

        val size = template.size
        return Placed(id, at, BlockPos(size.x, size.y, size.z))
    }

    /**
     * Load a saved template by name, or null if there is none.
     *
     * Resolves through `StructureTemplateManager.get`, which reads from a datapack *or* from `<world>/generated`
     * -- so a hull shipped inside the jar and one a player captured come back through the same call.
     */
    fun find(level: ServerLevel, name: String): StructureTemplate? {
        val id = idFor(name) ?: return null
        return level.server.structureManager.get(id).orElse(null)
    }

    /**
     * Delete a saved template.
     *
     * A bottle's template is single-use: once the ship is out, the file describes a vessel that exists again
     * for real, and leaving it behind would let the same hull be duplicated by anyone who knew its name.
     */
    fun forget(level: ServerLevel, name: String) {
        val id = idFor(name) ?: return
        level.server.structureManager.remove(id)
    }

    /**
     * The same state with any stored heat taken out of it.
     *
     * An engine's HEAT is a blockstate property -- it drives the glow and the running look -- while its fuel
     * lives on the block entity. A template captured from a ship under way therefore remembers hot engines,
     * and writing one back gives a brand-new, empty engine that *looks* like it is at full power. The needle
     * only corrects itself when somebody puts fuel in and the next tick rewrites the state.
     *
     * Used by **repair only**, deliberately, and the difference is real rather than taste. A bottled ship
     * carries its engines' block-entity NBT with it -- capture records the coal before the hull is cleared --
     * so it comes back both hot and fuelled, and cooling it would be a lie in the other direction. A repaired
     * block is a genuinely new block that has never burned anything.
     */
    fun cooled(state: BlockState): BlockState =
        if (state.hasProperty(EurekaProperties.HEAT) && state.getValue(EurekaProperties.HEAT) > 0) {
            state.setValue(EurekaProperties.HEAT, 0)
        } else {
            state
        }

    /**
     * Write a second, independent copy of [from] under the name [to].
     *
     * Needed wherever one saved ship becomes two things with different lifetimes. A shipwright minting a
     * bottled ship from a blueprint is the case that forced it: releasing a bottle *forgets* its template
     * when the ship comes out, so a bottle sharing the blueprint's file would delete the blueprint's ship on
     * first use -- silently, and for every copy of that page in the world at once.
     *
     * Goes through the template's own save/load rather than copying the file, so the result is whatever the
     * running version of Minecraft considers a valid template rather than bytes that merely used to be one.
     */
    fun copy(level: ServerLevel, from: String, to: String): Boolean {
        val source = find(level, from) ?: return false
        val target = idFor(to) ?: return false

        val manager = level.server.structureManager
        val written = manager.getOrCreate(target)
        written.load(level.holderLookup(Registries.BLOCK), source.save(CompoundTag()))
        return manager.save(target)
    }

    /** Every template this mod can see, ours only -- the manager also lists vanilla's and other mods'. */
    fun list(level: ServerLevel): List<Identifier> =
        level.server.structureManager.listTemplates().toList().filter { it.namespace == EurekaMod.MOD_ID }

    private fun idFor(name: String): Identifier? =
        if (name.matches(VALID_NAME)) Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, name) else null
}
