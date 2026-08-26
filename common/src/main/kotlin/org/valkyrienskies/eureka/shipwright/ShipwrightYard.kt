package org.valkyrienskies.eureka.shipwright

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The ships in front of a shipwright, and what can be done to them.
 *
 * Where [ShipwrightLedger] is a captain's paperwork, this is the water: which hulls the bench can see, what
 * state each is in, and which set of plans each one probably belongs to.
 */
object ShipwrightYard {

    /**
     * Every ship a shipwright at [bench] can work on.
     *
     * Three lookups, not one:
     *
     *  1. **The ship the bench is standing on**, if it is aboard one. A yard can careen the hull it is built
     *     into, and a shipwright who could mend every ship in the harbour except the one under their feet
     *     would be a strange craftsman.
     *  2. **Proximity**, for the hulls moored nearby.
     *  3. **Armadas**, where every parent in range contributes its children whether or not each child is
     *     individually within reach. A formation arrives as one thing, and being told half of it is too far
     *     away would be nonsense when the children are welded to a parent that is right there.
     */
    fun visible(level: ServerLevel, bench: BlockPos): List<Pair<LoadedServerShip, Boolean>> {
        val dimension = level.dimensionId
        val reach = ShipRepair.reach * ShipRepair.reach
        val found = LinkedHashMap<Long, Pair<LoadedServerShip, Boolean>>()

        // A bench aboard a ship has a SHIPYARD position, and ships report their transform in WORLD space.
        // Comparing the two directly is comparing coordinate systems that sit millions of blocks apart, so
        // every hull reads as impossibly distant and the Yard tab comes up empty on a ship that is right
        // there. Lift the bench into world space first; on solid ground the transform is the identity.
        val host = level.getLoadedShipManagingPos(bench)
        val here = Vector3d(bench.x + 0.5, bench.y + 0.5, bench.z + 0.5)
        val benchWorld = host?.shipToWorld?.transformPosition(Vector3d(here)) ?: here

        // The hull underfoot, listed regardless of distance: a big ship's centre can easily be further from
        // its own bench than the reach allows, and "too far from itself" is not a sentence that should
        // ever be true.
        if (host != null && host.shipAABB != null) found[host.id] = host to false

        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            if (ship.shipAABB == null) continue

            val position = ship.transform.position
            val dx = position.x() - benchWorld.x
            val dy = position.y() - benchWorld.y
            val dz = position.z() - benchWorld.z
            if (dx * dx + dy * dy + dz * dz > reach) continue

            found[ship.id] = ship to false
        }

        // A parent in range brings its whole armada with it.
        for ((ship, _) in found.values.toList()) {
            val armada = ArmadaShipControl.get(ship) ?: continue
            if (armada.isChild) continue
            for (child in armada.childShips.values) {
                if (child.shipAABB == null) continue
                if (!found.containsKey(child.id)) found[child.id] = child to true
            }
        }

        return found.values.toList()
    }

    /**
     * How full a hull's engines are, averaged, or -1 if it has none.
     *
     * Averaged rather than totalled: a captain reading "40%" wants to know how much sailing is left in the
     * ship, and eight engines at 40% is the same answer as one at 40%.
     */
    fun fuelOf(level: ServerLevel, ship: LoadedServerShip): Float {
        val aabb = ship.shipAABB ?: return -1f
        var sum = 0.0
        var engines = 0

        val cursor = BlockPos.MutableBlockPos()
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    val engine = level.getBlockEntity(cursor) as? EngineBlockEntity ?: continue
                    sum += engine.fuelFraction()
                    engines++
                }
            }
        }
        return if (engines == 0) -1f else (sum / engines).toFloat()
    }

    /**
     * The wheel's block id, e.g. `vs_eureka:birch_ship_helm`, or null on a hull with no wheel.
     *
     * Part of how a hull is matched to its plans: a birch helm and a dark oak one are different ships however
     * similar the rest of them looks, and unlike block count this survives damage.
     */
    fun helmVariantOf(level: ServerLevel, ship: LoadedServerShip): String? {
        val aabb = ship.shipAABB ?: return null
        val cursor = BlockPos.MutableBlockPos()
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (state.block is ShipHelmBlock) {
                        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.block).toString()
                    }
                }
            }
        }
        return null
    }

    /**
     * The shipwright's guess at which plans a hull belongs to.
     *
     * Candidates are filtered on **dimensions and helm variant**, then ranked by how much of each set of plans
     * the hull already matches, best first.
     *
     * ## Why block count is not a filter
     * A damaged hull has fewer blocks than its plans -- that is what damage *is*. Gating on an equal count
     * would pre-select only for ships in perfect repair and silently fail on every ship anyone actually brings
     * to a shipwright, which is the exact opposite of useful. Dimensions and the wheel survive damage; the
     * count does not.
     *
     * A guess is only ever a guess: the player can override it, and the [ShipRepair.matchPercent] check runs
     * regardless.
     */
    fun guessPlans(
        level: ServerLevel,
        owner: UUID,
        ship: LoadedServerShip
    ): ShipwrightLedger.Plans? {
        // The hull's TIGHT bounds, not its shipAABB -- a template measures the blocks, and an AABB measures
        // the volume they sit in. See ShipRepair.bounds.
        val hull = ShipRepair.bounds(level, ship) ?: return null
        val helm = helmVariantOf(level, ship)

        var best: ShipwrightLedger.Plans? = null
        var bestMatch = 0f

        for (plans in ShipwrightLedger.get(level.server).allPlans(owner)) {
            val template = ShipTemplate.find(level, plans.template) ?: continue
            val size = template.size
            // Fits inside, rather than measures the same. A hull built with its decor left off can be
            // SMALLER than the page that describes it -- strike the lanterns off an outer face and the
            // tight bounds shrink by a block -- and an exact test would then refuse to recognise a ship
            // built from these very plans. The match percentage below is what decides identity; this is
            // only here to skip pages that could not possibly be it.
            if (size.x < hull.width || size.y < hull.height || size.z < hull.length) continue

            // A ship whose wheel this page does not describe is not this page's ship, whatever else lines up.
            if (helm != null && templateHelm(template) != null && templateHelm(template) != helm) continue

            val assessment = ShipRepair.assess(level, ship, plans) ?: continue
            if (assessment.match > bestMatch) {
                bestMatch = assessment.match
                best = plans
            }
        }
        return best
    }

    private fun templateHelm(
        template: net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
    ): String? {
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.block is ShipHelmBlock) {
                    return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(info.state.block).toString()
                }
            }
        }
        return null
    }

    /**
     * Write a paid repair into the hull.
     *
     * Re-assessed at the moment of repair rather than trusting the quote. A hull can take more damage while
     * its captain is away fetching timber, and the honest thing is to mend what is broken *now* -- so the
     * quote is re-checked, and anything newly missing that the materials do not cover leaves a fresh bill
     * rather than being silently skipped.
     */
    fun repair(
        level: ServerLevel,
        player: ServerPlayer,
        ship: LoadedServerShip,
        plans: ShipwrightLedger.Plans
    ): Boolean {
        val assessment = ShipRepair.assess(level, ship, plans) ?: run {
            PathMessages.send(player, "The plans for that ship are missing.", PathMessages.Kind.ERROR)
            return false
        }

        val refusal = assessment.refusal
        if (refusal != null) {
            PathMessages.send(player, refusal, PathMessages.Kind.WARN)
            return false
        }

        val slug = ship.slug ?: return false
        val ledger = ShipwrightLedger.get(level.server)
        val bill = ledger.quoteRepair(player.uuid, slug, plans.shipName, assessment.missing)

        // All-or-nothing is a config choice now, not the design. With partial repair on, whatever is in the
        // pot goes into the hull keel-up and the rest of the bill simply stays open.
        if (!EurekaConfig.SERVER.shipwrightPartialRepair && !bill.ready) {
            PathMessages.send(
                player,
                "'${plans.shipName}' still needs ${bill.outstanding().values.sum()} more items to mend.",
                PathMessages.Kind.WARN
            )
            return false
        }

        // The pot is what has been handed over, capped by the quote. A full pot is simply the whole bill,
        // which is why there is no separate complete-repair path -- see ShipRepair.apply.
        val pot = HashMap<Item, Int>()
        for ((item, needed) in bill.cost) {
            val held = minOf(bill.delivered[item] ?: 0, needed)
            if (held > 0) pot[item] = held
        }

        val outcome = ShipRepair.apply(level, ShipRepair.ordered(level, ship, assessment), pot)
        ledger.spendRepair(bill, outcome.consumed)

        when {
            outcome.remaining <= 0 -> {
                ledger.closeRepair(player.uuid, slug)
                PathMessages.send(
                    player,
                    "'$slug' is mended -- ${outcome.placed} blocks put back.",
                    PathMessages.Kind.GOOD, PathMessages.Topic.REPAIR_COMPLETE
                )
            }
            outcome.placed <= 0 -> PathMessages.send(
                player,
                "Nothing in the pot covers what '$slug' is missing -- " +
                    "${outcome.remaining} blocks still wanting. Hand the shipwright materials first.",
                PathMessages.Kind.WARN
            )
            else -> PathMessages.send(
                player,
                "'$slug' patched keel-up -- ${outcome.placed} blocks put back, " +
                    "${outcome.remaining} still wanting more materials.",
                PathMessages.Kind.GOOD, PathMessages.Topic.REPAIR_PARTIAL
            )
        }
        return true
    }
}
