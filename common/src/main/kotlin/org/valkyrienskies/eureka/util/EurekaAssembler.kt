package org.valkyrienskies.eureka.util

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.block.FloaterBlock
import org.valkyrienskies.mod.common.BlockStateInfo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The Eureka Assembler. Runs during helm assembly (from ShipHelmBlockEntity.assemble), AFTER the
 * connected block set is collected but BEFORE the ship object is created, so it can replace hull
 * blocks in the world and cancel cleanly (no ship, no world mutation) if requirements aren't met.
 *
 * When the assembling player has a mode enabled ([AssemblerPreferences]):
 *  - `floater`: computes how many floaters keep the keel at the waterline (interior dry) and replaces
 *    whitelisted hull blocks with floaters, LAYER BY LAYER FROM THE KEEL UP -- each layer is drained
 *    across the whole material whitelist (oak plank, then spruce, then logs -- config order) before the
 *    next layer up, so the buoyancy stays packed as low as possible.
 *  - `balloon`: computes how many balloons let the ship ASCEND (sized for a target climb speed, not just
 *    neutral hover -- see [GRAVITY_MAGNITUDE] / assemblerBalloonAscendRate) and replaces whitelisted hull
 *    blocks (wool by default) with balloons, LAYER BY LAYER FROM THE TOP DOWN (same per-layer whitelist
 *    drain), so the lift stays packed as high as possible.
 *  - both: balloons sized for flight + floaters sized for the resting waterline; Eureka's water
 *    altitude-hold then pins the keel at the surface until the ship flies.
 *
 * Nothing is free in survival: the player must have the required floater/balloon items in inventory or the
 * whole assembly is cancelled with a chat message (kept in the chat history, press T) so the player can read
 * the full multi-line shortfall detail. In CREATIVE the items are drawn from the game's infinite supply and
 * nothing is consumed, the same way placing a block by hand is free there. Counts are solved against the ship's FINAL
 * post-swap mass (each replacement changes the total), using the exact thresholds from
 * [org.valkyrienskies.eureka.command.ShipWeightCommand] so /vs get-ship-weight agrees with what the
 * assembler builds. Block masses are read live from [BlockStateInfo], so pack rebalances are honored.
 */
object EurekaAssembler {

    // Mirrors ShipWeightCommand's calibration. The "keel at waterline, interior dry" target is DRY.
    private const val AFLOAT_ADDED_FACTOR = 1.0
    private const val DRY_ADDED_FACTOR = 7.5
    private const val SAFETY_MARGIN = 1

    // |EurekaShipControl.GRAVITY| (10.0). Balloon lift only cancels weight at the neutral-hover count; a
    // ship sized to exactly hover holds itself up but never climbs, because physTick caps the vertical
    // force at the balloons' lift, so the usable CLIMB force is the SURPLUS over weight. Terminal climb
    // velocity when balloon-limited is balloonForce/mass - g, so the balloon count for a target climb speed
    // V is the neutral-hover count times (1 + V/g). Sizing by this factor (not a flat margin) makes the
    // surplus scale WITH mass, so light and heavy ships alike ascend at ~the configured rate. Keep in step
    // with EurekaShipControl.GRAVITY.
    private const val GRAVITY_MAGNITUDE = 10.0

    // The count<->mass feedback (each swap changes ship mass, which changes the count needed) is a
    // fast-converging fixpoint; this is a generous cap so it always terminates.
    private const val MAX_SOLVE_ITERATIONS = 32

    sealed interface Outcome
    class Cancelled(val message: Component) : Outcome
    class Applied(val floatersPlaced: Int, val balloonsPlaced: Int, val fromCreative: Boolean) : Outcome

    private fun massOf(state: BlockState): Double = BlockStateInfo.get(state)?.first ?: 0.0

    private fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()

    // A hull block eligible for replacement: its world position, its current VS mass, and the source block
    // (the balloon path needs the source to pick the color-matched balloon variant).
    private class Candidate(val pos: BlockPos, val mass: Double, val block: Block)

    // The dye color a source block maps its balloon to, read from the registry path (red_wool /
    // red_concrete -> RED). Start-anchored "<color>_" prefix, so light_blue/light_gray win over blue/gray
    // with no ambiguity. null = no color match (use the plain uncolored balloon).
    private fun dyeColorOf(block: Block): DyeColor? {
        val path = BuiltInRegistries.BLOCK.getKey(block).path
        return DyeColor.values().firstOrNull { path.startsWith(it.serializedName + "_") }
    }

    // The balloon block to place for a given source color. Every DyeColor has a matching Eureka balloon;
    // an uncolored/unmatched source (null) uses the plain balloon.
    private fun balloonBlockFor(color: DyeColor?): Block = when (color) {
        DyeColor.WHITE -> EurekaBlocks.WHITE_BALLOON.get()
        DyeColor.ORANGE -> EurekaBlocks.ORANGE_BALLOON.get()
        DyeColor.MAGENTA -> EurekaBlocks.MAGENTA_BALLOON.get()
        DyeColor.LIGHT_BLUE -> EurekaBlocks.LIGHT_BLUE_BALLOON.get()
        DyeColor.YELLOW -> EurekaBlocks.YELLOW_BALLOON.get()
        DyeColor.LIME -> EurekaBlocks.LIME_BALLOON.get()
        DyeColor.PINK -> EurekaBlocks.PINK_BALLOON.get()
        DyeColor.GRAY -> EurekaBlocks.GRAY_BALLOON.get()
        DyeColor.LIGHT_GRAY -> EurekaBlocks.LIGHT_GRAY_BALLOON.get()
        DyeColor.CYAN -> EurekaBlocks.CYAN_BALLOON.get()
        DyeColor.PURPLE -> EurekaBlocks.PURPLE_BALLOON.get()
        DyeColor.BLUE -> EurekaBlocks.BLUE_BALLOON.get()
        DyeColor.BROWN -> EurekaBlocks.BROWN_BALLOON.get()
        DyeColor.GREEN -> EurekaBlocks.GREEN_BALLOON.get()
        DyeColor.RED -> EurekaBlocks.RED_BALLOON.get()
        DyeColor.BLACK -> EurekaBlocks.BLACK_BALLOON.get()
        null -> EurekaBlocks.BALLOON.get()
    }

    // Candidate hull positions whose block id is in [whitelist], sorted LAYER-MAJOR: fill exhausts the
    // ENTIRE material whitelist on one layer before moving to the next, so lift blocks stay packed at the
    // extreme layer (floaters lowest, balloons highest) instead of a single material draining top-to-bottom
    // first. Primary key is Y (asc when [bottomUp], desc otherwise); WITHIN a layer the whitelist index
    // still decides which material converts first (oak plank before spruce, ...); X/Z break ties for
    // determinism.
    private fun candidates(
        level: ServerLevel,
        positions: Set<BlockPos>,
        whitelist: List<String>,
        bottomUp: Boolean
    ): List<Candidate> {
        if (whitelist.isEmpty()) return emptyList()
        val priority = HashMap<String, Int>(whitelist.size)
        whitelist.forEachIndexed { i, id -> priority.putIfAbsent(id, i) }

        val ranked = ArrayList<Pair<Int, Candidate>>()
        for (pos in positions) {
            val state = level.getBlockState(pos)
            val idx = priority[idOf(state.block)] ?: continue
            ranked.add(idx to Candidate(pos, massOf(state), state.block))
        }
        val byLayer: Comparator<Pair<Int, Candidate>> =
            if (bottomUp) compareBy { it.second.pos.y } else compareByDescending { it.second.pos.y }
        ranked.sortWith(
            byLayer.thenBy { it.first }.thenBy { it.second.pos.x }.thenBy { it.second.pos.z }
        )
        return ranked.map { it.second }
    }

    /**
     * Run the assembler over [positions] (the world-space block set that is about to become the ship).
     * Returns [Applied] with how many floaters/balloons were placed (0/0 if the ship already met the
     * targets), or [Cancelled] with a player-facing message if there aren't enough convertible blocks
     * or enough items in inventory. On [Cancelled] the world is left untouched.
     *
     * @param existingFloaters full-floater-equivalent count already present on the collected blocks
     * @param existingBalloons balloon count already present on the collected blocks
     * @param floaterBonusPercent extra % (0..) added on top of the auto-calculated floater target -- the helm
     *   menu's per-assembly "Auto Floaters + N%" nudge for a ship that rides too low
     * @param balloonBonusPercent extra % (0..) added on top of the auto-calculated balloon target -- the helm
     *   menu's per-assembly "Auto Balloons + N%" nudge for a ship that ascends too weakly
     */
    fun apply(
        level: ServerLevel,
        player: Player,
        positions: Set<BlockPos>,
        floaterMode: Boolean,
        balloonMode: Boolean,
        existingFloaters: Int,
        existingBalloons: Int,
        floaterBonusPercent: Int = 0,
        balloonBonusPercent: Int = 0
    ): Outcome {
        val cfg = EurekaConfig.SERVER

        val floaterState = EurekaBlocks.FLOATER.get().defaultBlockState()
        val balloonState = EurekaBlocks.BALLOON.get().defaultBlockState()
        val floaterMass = massOf(floaterState)
        val balloonMass = massOf(balloonState)

        // Floaters fill lowest layer first (keep buoyancy at the keel); balloons fill highest layer first
        // (keep lift up top). Each layer is drained across the whole whitelist before the next -- see candidates().
        val floaterCandidates =
            if (floaterMode) candidates(level, positions, cfg.assemblerFloaterReplaceWhitelist, bottomUp = true) else emptyList()
        val balloonCandidates =
            if (balloonMode) candidates(level, positions, cfg.assemblerBalloonReplaceWhitelist, bottomUp = false) else emptyList()

        val baseMass = positions.sumOf { massOf(level.getBlockState(it)) }
        val liftPerBalloon = cfg.massPerBalloon * cfg.balloonLiftMultiplier
        // Size for a real climb, not just neutral hover (see GRAVITY_MAGNITUDE). factor 1.0 = old hover-only.
        val balloonAscendFactor = 1.0 + max(0.0, cfg.assemblerBalloonAscendRate) / GRAVITY_MAGNITUDE
        // Manual helm-menu nudges: each adds its % on top of the auto-calculated target for THIS assembly only.
        val floaterBonusMul = 1.0 + max(0, floaterBonusPercent) / 100.0
        val balloonBonusMul = 1.0 + max(0, balloonBonusPercent) / 100.0

        // Solve the needed counts against the FINAL (post-swap) mass. placeF/placeB are what we'd
        // actually place (target minus what's already on the ship, clamped to available blocks); the
        // mass estimate uses those so it reflects the real assembled ship.
        var placeF = 0
        var placeB = 0
        var targetF = 0
        var targetB = 0
        repeat(MAX_SOLVE_ITERATIONS) {
            val finalMass = baseMass +
                floaterCandidates.take(placeF).sumOf { floaterMass - it.mass } +
                balloonCandidates.take(placeB).sumOf { balloonMass - it.mass }
            if (finalMass <= 0.0) return@repeat

            targetB = if (balloonMode && liftPerBalloon > 0.0)
                // The ascend factor already provides genuine climb headroom (>= 1 balloon of surplus for any
                // real ship via ceil), so it supersedes the old flat +SAFETY_MARGIN for balloons. The bonus
                // multiplier then adds the player's manual % on top.
                ceil(finalMass * balloonAscendFactor / liftPerBalloon * balloonBonusMul).toInt()
            else 0

            val perFloater = min(cfg.floaterBuoyantFactorPerKg / finalMass, 15.0 * cfg.maxFloaterBuoyantFactor)
            targetF = if (floaterMode && perFloater > 0.0)
                ceil(
                    max(
                        ceil(DRY_ADDED_FACTOR / perFloater).toInt(),
                        ceil(AFLOAT_ADDED_FACTOR / perFloater).toInt() + SAFETY_MARGIN
                    ) * floaterBonusMul
                ).toInt()
            else 0

            val newPlaceF = min(max(0, targetF - existingFloaters), floaterCandidates.size)
            val newPlaceB = min(max(0, targetB - existingBalloons), balloonCandidates.size)
            if (newPlaceF == placeF && newPlaceB == placeB) return@repeat // converged; remaining iters are no-ops
            placeF = newPlaceF
            placeB = newPlaceB
        }

        val needF = max(0, targetF - existingFloaters)
        val needB = max(0, targetB - existingBalloons)

        // Not enough whitelisted hull blocks to convert -> can't reach the target; abort before touching anything.
        if (needF > floaterCandidates.size || needB > balloonCandidates.size) {
            return Cancelled(
                blockShortfall(
                    floaterMode, balloonMode,
                    needF, floaterCandidates.size,
                    needB, balloonCandidates.size
                )
            )
        }

        // The exact balloons to place: the first needB candidates (bottom-up), each mapped to the balloon
        // variant matching the color of the block it replaces (red wool/concrete -> red balloon, ...).
        val balloonPlacements = balloonCandidates.take(needB).map { it.pos to balloonBlockFor(dyeColorOf(it.block)) }
        // How many of each colored balloon item the player must supply.
        val neededByBalloon = LinkedHashMap<Block, Int>()
        for ((_, target) in balloonPlacements) neededByBalloon.merge(target, 1, Int::plus)

        // Creative draws the floaters and balloons from the game's infinite supply, the same way placing a
        // block by hand is free there, so both the inventory gate below and the consumption further down are
        // skipped. The BLOCK-availability check above still applies either way -- creative hands out items,
        // not more hull to convert, so a ship without enough whitelisted blocks is still cancelled.
        val creative = player.abilities.instabuild

        // Not enough items in the player's inventory -> abort before touching anything. Balloons are
        // matched per color, so each color is checked against its own item count.
        val inv = player.inventory
        if (!creative) {
            val haveF = if (floaterMode) countMatching(inv) { isFloaterItem(it) } else 0
            val balloonShort = LinkedHashMap<Block, Pair<Int, Int>>()
            for ((target, need) in neededByBalloon) {
                val have = countMatching(inv) { isSpecificBalloonItem(it, target) }
                if (need > have) balloonShort[target] = need to have
            }
            if ((floaterMode && needF > haveF) || balloonShort.isNotEmpty()) {
                return Cancelled(inventoryShortfall(floaterMode, needF, haveF, balloonShort))
            }
        }

        // Commit: swap the chosen blocks, then consume the items. onPlace is a no-op here (the block is
        // still in the world, not on a ship yet); the counters are applied by the helm's applyControl.
        for (i in 0 until needF) {
            level.setBlock(floaterCandidates[i].pos, floaterState, Block.UPDATE_CLIENTS)
        }
        for ((pos, target) in balloonPlacements) {
            level.setBlock(pos, target.defaultBlockState(), Block.UPDATE_CLIENTS)
        }
        // Creative pays nothing, so changedSlots stays empty and the re-sync below no-ops.
        val changedSlots = ArrayList<Int>(needF + needB)
        if (!creative) {
            if (needF > 0) consumeMatching(inv, needF, changedSlots) { isFloaterItem(it) }
            for ((target, need) in neededByBalloon) {
                consumeMatching(inv, need, changedSlots) { isSpecificBalloonItem(it, target) }
            }
        }
        if (changedSlots.isNotEmpty() && player is ServerPlayer) {
            // Keep the server's inventoryMenu snapshot in step with the real inventory...
            player.inventoryMenu.broadcastChanges()
            // ...but broadcastChanges() emits a containerId-0 sync, and assembly runs from the helm menu's
            // clickMenuButton, so the open container is the helm menu (a non-zero containerId). The client
            // drops a containerId-0 slot update for non-hotbar slots while another menu is open, leaving a
            // ghost stack in the main inventory. A direct per-slot player-inventory packet is applied by the
            // client regardless of the open container, so the consumed floaters/balloons vanish immediately.
            for (slot in changedSlots) {
                player.connection.send(ClientboundSetPlayerInventoryPacket(slot, inv.getItem(slot)))
            }
        }

        return Applied(needF, needB, creative)
    }

    /**
     * Chat summary of what the assembler swapped in. Worth saying out loud because the swap happens to the
     * hull rather than to anything the player is looking at: in survival the inventory is the only other
     * signal, and in creative nothing is consumed at all, so without this the assembler is silent.
     */
    fun placementSummary(outcome: Applied): Component {
        val parts = ArrayList<String>(2)
        if (outcome.floatersPlaced > 0) parts.add("${outcome.floatersPlaced} ${plural(outcome.floatersPlaced, "floater")}")
        if (outcome.balloonsPlaced > 0) parts.add("${outcome.balloonsPlaced} ${plural(outcome.balloonsPlaced, "balloon")}")

        val msg = Component.literal("Eureka Assembler").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        if (parts.isEmpty()) {
            // The ship already met both targets, so nothing was swapped. Say so rather than nothing at all,
            // otherwise an enabled assembler looks broken when it was simply not needed.
            msg.append(
                Component.literal(" -- already had enough; nothing placed").withStyle(ChatFormatting.GRAY)
            )
            return msg
        }
        msg.append(Component.literal(" -- placed ${parts.joinToString(" and ")}").withStyle(ChatFormatting.GREEN))
        if (outcome.fromCreative) {
            msg.append(Component.literal(" (Creative: no materials used)").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
        }
        return msg
    }

    private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"

    private fun isFloaterItem(stack: ItemStack): Boolean {
        val item = stack.item
        return item is BlockItem && item.block is FloaterBlock
    }

    // True when [stack] is the block item for exactly [block] (one specific colored balloon). Reference
    // equality on the block, so red_balloon items only satisfy red_balloon placements.
    private fun isSpecificBalloonItem(stack: ItemStack, block: Block): Boolean {
        val item = stack.item
        return item is BlockItem && item.block === block
    }

    private inline fun countMatching(inv: Inventory, predicate: (ItemStack) -> Boolean): Int {
        var count = 0
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.isEmpty && predicate(stack)) count += stack.count
        }
        return count
    }

    // Records each inventory slot it drew from into [changedSlots] so the caller can push a targeted
    // client update for exactly those slots (the helm menu is open, so a blanket container sync won't do).
    private inline fun consumeMatching(
        inv: Inventory,
        amount: Int,
        changedSlots: MutableList<Int>,
        predicate: (ItemStack) -> Boolean
    ) {
        var remaining = amount
        for (i in 0 until inv.containerSize) {
            if (remaining <= 0) break
            val stack = inv.getItem(i)
            if (!stack.isEmpty && predicate(stack)) {
                val take = min(remaining, stack.count)
                stack.shrink(take)
                remaining -= take
                changedSlots.add(i)
            }
        }
    }

    private fun newline() = Component.literal("\n")

    // A multi-line chat message (not the action bar): a bold red header, one yellow detail line per
    // shortfall, then a gray hint. Chat wraps each line to the screen and keeps it in the scrollback (T),
    // so nothing is cut off however long the detail gets. Matches ShipWeightCommand's formatting.
    private fun blockShortfall(
        floaterMode: Boolean, balloonMode: Boolean,
        needF: Int, availF: Int, needB: Int, availB: Int
    ): Component {
        val msg = Component.literal("Eureka Assembler -- can't assemble this ship")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        if (floaterMode && needF > availF) {
            msg.append(newline())
            msg.append(
                Component.literal("Needs $needF floaters, but only $availF convertible plank/log blocks in the hull")
                    .withStyle(ChatFormatting.YELLOW)
            )
        }
        if (balloonMode && needB > availB) {
            msg.append(newline())
            msg.append(
                Component.literal("Needs $needB balloons, but only $availB convertible wool/concrete blocks in the hull")
                    .withStyle(ChatFormatting.YELLOW)
            )
        }
        msg.append(newline())
        msg.append(
            Component.literal("Build the hull with more of those blocks, or turn it off with /vs eureka-assembler.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        )
        return msg
    }

    // Balloons are reported per color (only the colors actually short), one line each, since each colored
    // balloon is a distinct item the player must supply -- e.g. a "red_balloon" line then a "blue_balloon" line.
    private fun inventoryShortfall(
        floaterMode: Boolean,
        needF: Int, haveF: Int,
        balloonShort: Map<Block, Pair<Int, Int>>
    ): Component {
        val msg = Component.literal("Eureka Assembler -- missing materials")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        if (floaterMode && needF > haveF) {
            msg.append(newline())
            msg.append(
                Component.literal("Need $needF floaters (have $haveF)").withStyle(ChatFormatting.YELLOW)
            )
        }
        for ((block, nh) in balloonShort) {
            msg.append(newline())
            msg.append(
                Component.literal("Need ${nh.first} ${BuiltInRegistries.BLOCK.getKey(block).path} (have ${nh.second})")
                    .withStyle(ChatFormatting.YELLOW)
            )
        }
        msg.append(newline())
        msg.append(
            Component.literal("Put these in your inventory, or turn it off with /vs eureka-assembler <floater|balloon> false.")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        )
        return msg
    }
}
