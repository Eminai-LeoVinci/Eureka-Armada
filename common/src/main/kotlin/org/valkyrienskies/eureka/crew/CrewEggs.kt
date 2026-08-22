package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * A villager spawn egg used on a workstation hatches somebody who already holds that station's trade.
 *
 * ## Why this does not undo "the rule is the block"
 * A shipwright is made by CLAIMING a bench, and a crewman by claiming a wheel -- that is the whole spawn
 * rule for both, and `ShipwrightsBenchBlock` explains at some length why nothing should manufacture a
 * shipwright any other way: benches are harbor furniture with no recipe, so "shipwrights are only found at
 * harbors" holds without anything ever checking where a villager is standing.
 *
 * This is an authoring gesture, not a second spawn rule, and it is gated the same way the cannon's
 * egg-mounted gun crews are: creative only. A player in survival cannot reach it (villager eggs are not
 * obtainable), so the harbor still means what it meant. What it buys is the thing that was genuinely
 * tedious -- laying out a harbor or a crewed hull meant spawning villagers, waiting for them to notice the
 * furniture, and re-spawning the ones that wandered off and took a different job on the way.
 *
 * ## The profession is set, and nothing else is
 * A trade is not a berth. Signing crew onto a captain's articles costs a Heart of the Sea and is its own
 * deliberate gesture (Sneak+C), which never touches a villager's profession -- and this never touches the
 * articles. Somebody hatched at a wheel is a crewman by trade and is on nobody's roster until they are
 * signed on, which is exactly what a villager who wandered up and claimed the wheel themselves would be.
 */
object CrewEggs {

    /**
     * Hatch a villager holding [profession] against the [face] of the block at [pos]. Returns null when one
     * was hatched, or the reason there wasn't one; a non-villager egg answers [NOT_A_VILLAGER] so the caller
     * can fall through and let the egg do its ordinary job instead of refusing the click.
     */
    fun hatch(
        level: ServerLevel,
        pos: BlockPos,
        face: Direction,
        egg: SpawnEggItem,
        stack: ItemStack,
        player: ServerPlayer?,
        profession: ResourceKey<VillagerProfession>
    ): String? {
        if (egg.getType(stack) != EntityType.VILLAGER) return NOT_A_VILLAGER

        // Vanilla's own placement rule, followed exactly rather than approximated: hatch INSIDE the clicked
        // block when it has no collision, otherwise against the face. The chosen block's FLOOR is where they
        // stand, which is what a villager standing on a bench or a deck plank is actually doing -- the first
        // version buried them a block down by asking vanilla to align against world blocks that, aboard a
        // ship, are not there.
        val solid = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
        val target = if (solid) pos.relative(face) else pos

        // Where they should END UP, worked out in the frame the click happened in and then carried into the
        // world -- because aboard a ship every address here is a SHIPYARD one, millions of blocks from the
        // deck the player is standing on.
        //
        // The obvious thing is to let vanilla align the spawn in shipyard space, where the deck actually
        // is, and move the result afterwards. It does not survive the move: the villager is added to the
        // world at the shipyard and simply stays there, invisible, which is what "the message appeared but
        // nothing hatched" was. So the position is computed first and the villager is created AT it, the
        // same order GunnerMounts posts a gun crew in -- the one pattern known to work across the two frames.
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip
        val local = Vector3d(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
        val world = ship?.shipToWorld?.transformPosition(local) ?: local

        val spawned = egg.getType(stack).spawn(
            level, stack, player, BlockPos.containing(world.x, world.y, world.z),
            EntitySpawnReason.SPAWN_ITEM_USE, false, false
        )
        val villager = spawned as? Villager
        if (villager == null) {
            spawned?.discard()
            return NOT_A_VILLAGER
        }
        // Exactly where the arithmetic said, rather than wherever rounding to a block put them: a deck is
        // rarely at an integer world height once a hull is under way.
        villager.snapTo(world.x, world.y, world.z, villager.yRot, villager.xRot)

        val held = level.registryAccess()
            .lookupOrThrow(Registries.VILLAGER_PROFESSION)
            .getOrThrow(profession)
        // The trade, a point of standing to hold it with, and the workstation it belongs to. The trade
        // alone is what was asked for; the other two are so it survives contact with the villager's own
        // brain, which hands back a job held at level one with nothing earned and no job site to show for
        // it. The job site is the block that was clicked, which is the same one they would have claimed on
        // their own -- so a hatched shipwright works the bench they were hatched against.
        villager.villagerData = villager.villagerData.withProfession(held).withLevel(1)
        if (villager.villagerXp <= 0) villager.villagerXp = 1
        villager.brain.setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), pos))
        villager.refreshBrain(level)
        // Hatched to work here, so they stay: an authored harbor whose shipwright despawned overnight is
        // the same bug as one that never had a shipwright.
        villager.setPersistenceRequired()
        return null
    }

    /** Say what happened, in the same HUD line every other crew gesture answers in. */
    fun tell(player: ServerPlayer?, refusal: String?, trade: String) {
        val target = player ?: return
        if (refusal == null) {
            PathMessages.send(target, "A $trade takes up the post.", PathMessages.Kind.GOOD)
        } else {
            PathMessages.send(target, refusal, PathMessages.Kind.ERROR)
        }
    }

    /** The one refusal a caller should treat as "not my business" rather than as an error worth saying. */
    const val NOT_A_VILLAGER = "that egg does not hatch a villager"
}
