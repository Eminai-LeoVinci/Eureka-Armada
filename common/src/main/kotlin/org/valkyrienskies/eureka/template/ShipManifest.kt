package org.valkyrienskies.eureka.template

import net.minecraft.world.item.Item
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.block.BalloonBlock
import org.valkyrienskies.eureka.block.EngineBlock
import org.valkyrienskies.eureka.ship.ControlProfile
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.BuoyancyMath
import org.valkyrienskies.mod.common.BlockStateInfo

/**
 * The facts about a ship that a blueprint page shows: how big, how heavy, what it is made of.
 *
 * ## Derived, never stored
 * Every number here is computed from the [StructureTemplate] on demand. There is deliberately no manifest file
 * sitting next to the `.nbt`, because two records of the same ship can disagree and this one would have no way
 * to notice -- a template edited by hand, or shipped in a datapack, would carry a manifest describing a
 * different vessel. Recomputing costs one pass over the block list and can never be stale.
 *
 * It also means a hull that arrives from somewhere else -- a pirate ship in the mod jar, a file a builder sent
 * you -- prices and weighs identically to one a player captured, with nothing extra to author.
 *
 * ## Mass is the physics number, not a guess
 * Weight comes from `BlockStateInfo`, the same live table VS2's physics reads and the same one behind
 * `/vs get-ship-weight` and the Auto-Shipwright's floater maths. So the figure on the blueprint is the figure
 * the ship will actually have once it is built, including any mass overrides a pack has applied.
 */
object ShipManifest {

    class Manifest(
        val width: Int,
        val height: Int,
        val length: Int,
        /** Solid blocks -- what the hull is, as opposed to the volume it occupies. */
        val blocks: Int,
        /** Items a player must carry, with double blocks charged once. */
        val items: Int,
        val kinds: Int,
        val mass: Double,
        /** Engines aboard -- what the speed rating is driven by. */
        val engines: Int,
        /** Floaters in whole blocks. Only the sign is read, to tell a boat from an airship. */
        val floaters: Int,
        val balloons: Int,
        val census: Map<Item, Int>
    ) {
        /** Floaters needed to keep this hull dry once built, from the same maths the helm readout uses. */
        val floatersToRideDry: Int
            get() = maxOf(BuoyancyMath.recommendedFloaters(mass), BuoyancyMath.afloatFloaters(mass))

        /** Balloons needed to get it off the ground, if it is meant to fly. */
        val balloonsToAscend: Int get() = BuoyancyMath.recommendedBalloons(mass)

        /**
         * What this hull will steer as once built, from the same classifier the live ship uses.
         *
         * `wet = true` because a hull described on paper has not launched, and a boat reading is both the
         * conservative answer and the one the helm itself falls back to before assembly.
         */
        val profile: ControlProfile get() = ControlProfile.classify(balloons, floaters, wet = true)

        /** Estimated top speed (m/s), rated on the settings block for the category above. */
        val topSpeed: Double get() = EurekaShipControl.estimateTopSpeed(profile.preset, engines, mass)
    }

    fun of(template: StructureTemplate): Manifest {
        val census = BillOfMaterials.census(template)

        var blocks = 0
        var mass = 0.0
        var engines = 0
        var floaters = 0
        var balloons = 0
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.isAir) continue
                blocks++
                // A block VS2 has no mass entry for contributes nothing rather than breaking the total; that is
                // the same fallback the assembler's own solve uses.
                mass += BlockStateInfo.get(info.state)?.first ?: 0.0

                // Counted off the block states rather than the item census, so a coloured balloon counts as a
                // balloon without this having to know how many dyes exist.
                val block = info.state.block
                when {
                    block is EngineBlock -> engines++
                    block is BalloonBlock -> balloons++
                    block == EurekaBlocks.FLOATER.get() -> floaters++
                }
            }
        }

        val size = template.size
        return Manifest(
            width = size.x,
            height = size.y,
            length = size.z,
            blocks = blocks,
            items = BillOfMaterials.total(census),
            kinds = census.size,
            mass = mass,
            engines = engines,
            floaters = floaters,
            balloons = balloons,
            census = census
        )
    }
}
