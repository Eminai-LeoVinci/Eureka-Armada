package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.cannon.AutoGunnery
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.pirate.PirateGunnery
import org.valkyrienskies.eureka.pirate.PirateCrewTypes
import org.valkyrienskies.eureka.pirate.PirateShips
import org.valkyrienskies.eureka.pirate.PirateTestHull
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * "/vs pirate ..." -- the harness for the pirate-ship machinery.
 *
 * DEV ONLY, remove before release with the other debug commands (see ROADMAP 6c). Like
 * [ShipTemplateCommand] it merges into VS2's "vs" root and is gated on COMMANDS_GAMEMASTER --
 * `set-mark` mints the two helm states that are deliberately unobtainable in survival and creative alike,
 * and `capture` is the authoring pen every shipped pirate hull is written with.
 *
 * `set-mark` is also the testing vehicle for the whole gate: mark any placed helm PIRATE and every one of
 * the fourteen doors can be walked in game without a single generated ship existing yet. What it is NOT is
 * a way to make a working raider: a hand-marked wheel has no papers, so `adopt` refuses it on sight and no
 * berth, zone, chase or proximity ring ever comes of it. That road starts at `test-hull` and `capture`.
 */
object PirateCommand {

    /** Standing this close to the hull counts as aboard -- the same margin every crew box-test uses. */
    private const val DECK_MARGIN = 2.0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("pirate")
                    .requires { it.hasPermission(2) }
                    .then(
                        literal("set-mark")
                            .then(literal("normal").executes { setMark(it, HelmMark.NORMAL) })
                            .then(literal("pirate").executes { setMark(it, HelmMark.PIRATE) })
                            .then(literal("taken").executes { setMark(it, HelmMark.TAKEN) })
                    )
                    .then(
                        literal("capture").then(
                            argument("ship", ShipArgument.ships()).then(
                                // greedyString, not word(): pirate hulls are namespaced "pirate/sloop" and
                                // word() refuses the slash.
                                argument("name", StringArgumentType.greedyString()).executes { capture(it) }
                            )
                        )
                    )
                    .then(literal("test-hull").executes { testHull(it) })
                    .then(literal("list").executes { list(it) })
                    .then(literal("prune").executes { prune(it) })
                    .then(literal("arm").executes { arm(it) })
                    .then(literal("regen").executes { regen(it) })
                    .then(
                        // The gunnery test bench: stand on any armed ship, name a point, and every gun
                        // that can bear solves its own arc and fires it -- no pirates required.
                        literal("aim").then(
                            argument("at", Vec3Argument.vec3()).executes { aim(it) }
                        )
                    )
            )
        )
    }

    /**
     * "/vs pirate aim <x> <y> <z>" -- DEV ONLY: lay and fire every gun of the ship the caller stands on
     * at the given point, printing each gun's solved arc or its reason for silence. This is the proving
     * ground for [PirateGunnery]'s solver: markers at known ranges, read the pitches off the chat.
     * No jitter, on purpose -- the bench measures the solver, not the scatter.
     */
    private fun aim(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val player = ctx.source.player ?: run {
            ctx.source.sendFailure(Component.literal("A player has to be standing on the ship."))
            return 0
        }
        val target = Vec3Argument.getVec3(ctx, "at")
        val shipId = ShipCrew.standingOn(player)
        val ship = shipId?.let { level.shipObjectWorld.loadedShips.getById(it) }
        if (ship == null) {
            ctx.source.sendFailure(Component.literal("Stand on the ship whose guns you want to lay."))
            return 0
        }

        var fired = 0
        val now = level.gameTime
        for (gun in ShipGuns.aboard(level, ship)) {
            val label = "(${gun.blockPos.toShortString()})"
            if (!gun.readyBy(now)) {
                ctx.source.sendSuccess({ Component.literal("$label cooling") }, false)
                continue
            }
            val tolerance = EurekaConfig.SERVER.pirateCannonBearingToleranceDegrees
            val lay = AutoGunnery.lay(level, gun, target, tolerance)
            if (lay == null) {
                ctx.source.sendSuccess({ Component.literal("$label cannot bear") }, false)
                continue
            }
            val refusal = AutoGunnery.fireAt(
                level, gun, target, jitterBlocks = 0.0, bearingToleranceDegrees = tolerance,
                consume = !EurekaConfig.SERVER.pirateCannonInfiniteAmmo
            )
            if (refusal == null) {
                fired++
                ctx.source.sendSuccess({
                    Component.literal(
                        "$label FIRED pitch %.2f deg, speed %.2f b/t, %d ticks, %s charge".format(
                            lay.solution.pitchDegrees, lay.solution.speed,
                            lay.solution.flightTicks, lay.charge.name
                        )
                    ).withStyle(ChatFormatting.GREEN)
                }, false)
            } else {
                ctx.source.sendSuccess({ Component.literal(label).append(": ").append(refusal) }, false)
            }
        }
        if (fired == 0) {
            ctx.source.sendFailure(Component.literal("No gun could take that shot."))
        }
        return fired
    }

    /** Every berth in the caller's dimension: position, template, and what it is doing right now. */
    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val lines = PirateShips.describe(ctx.source.level)
        for (line in lines) {
            ctx.source.sendSuccess({ Component.literal(line) }, false)
        }
        return lines.size
    }

    /**
     * Drop the berths that no wheel answers for any more.
     *
     * These are the wreckage of a bug: a disassembly rebuilt the wheel's block entity as a new object, the
     * manager mistook it for a rival copy claiming the same site, and the ship adopted a FRESH berth
     * wherever she came apart -- leaving the old record behind, permanently BERTHED and drawing its dormant
     * ring forever. Harmless but untidy, and they stack up one per teardown.
     *
     * The test is deliberately narrow, because the alternative is eating somebody's real pirate site. A
     * berth is only pruned when its last known wheel position holds NO pirate wheel, or holds one that has
     * since claimed a DIFFERENT berth -- which is precisely the shape a ghost has, and never the shape of a
     * genuine sleeping site (whose wheel is exactly where the berth says it is). A site whose ship has
     * sailed away is not touched, because `lastPos` follows the wheel.
     *
     * Loads each candidate's chunk to look. Run once, by hand, on a world that collected ghosts.
     */
    private fun prune(ctx: CommandContext<CommandSourceStack>): Int {
        val level = ctx.source.level
        val removed = PirateShips.prune(level)
        if (removed.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("No abandoned berths; every site has its wheel.") }, false)
            return 0
        }
        for (line in removed) {
            ctx.source.sendSuccess({ Component.literal(line) }, false)
        }
        ctx.source.sendSuccess(
            { Component.literal("Pruned ${removed.size} abandoned berth(s).") }, false
        )
        return removed.size
    }

    /** Skip the countdown on the nearest loaded dormant berth: the caller is the intruder. */
    private fun arm(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        return if (PirateShips.forceArm(ctx.source.level, player)) {
            ctx.source.sendSuccess({
                Component.literal("Pirate ship waking.").withStyle(ChatFormatting.GREEN)
            }, true)
            1
        } else {
            ctx.source.sendFailure(
                Component.literal("No loaded dormant pirate berth to wake (or the cap/cooldown refused it).")
            )
            0
        }
    }

    /** Skip the day-clock on the nearest waiting site. Placement checks still apply; players are ignored. */
    private fun regen(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val refusal = PirateShips.forceRegen(ctx.source.level, player)
        return if (refusal == null) {
            ctx.source.sendSuccess({
                Component.literal("Site regenerated.").withStyle(ChatFormatting.GREEN)
            }, true)
            1
        } else {
            ctx.source.sendFailure(Component.literal("Regeneration refused: $refusal."))
            0
        }
    }

    /**
     * "/vs pirate test-hull" -- DEV ONLY: lay the reference sloop down in front of the caller.
     *
     * The other half of [capture]. A structure template cannot cross versions (the reason is written out in
     * [PirateTestHull]), so every version has to author its own, and this is the ship to author it from --
     * the same hull every time, on every game, so that a difference in pirate behaviour between two of them
     * is a difference in the code and not in what somebody happened to build that afternoon.
     */
    private fun testHull(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val built = PirateTestHull.build(ctx.source.level, player)
        ctx.source.sendSuccess({
            Component.literal(
                "Test sloop laid down -- ${built.blocks} blocks, ${built.crew} crew, " +
                    "${PirateTestHull.BEAM}x${PirateTestHull.HEIGHT}x${PirateTestHull.LENGTH}, " +
                    "bow at ${built.origin.toShortString()} running ${built.forward.name.lowercase()}."
            ).withStyle(ChatFormatting.GREEN)
        }, true)
        ctx.source.sendSuccess({
            Component.literal(
                "Assemble her at the wheel, then: /vs pirate capture <ship> pirate/sloop"
            ).withStyle(ChatFormatting.GRAY)
        }, false)
        return built.blocks
    }

    /**
     * Author a pirate hull: capture [ship] -- pillagers, papers, black wheel and all -- as a template a
     * generated pirate ship can be placed from.
     *
     * The wheel is marked PIRATE and given its papers (template name, crew snapshots) BEFORE the capture,
     * because the capture reads live block entities: that is what bakes the mark into the palette and the
     * papers into the wheel's NBT, so a hull placed from this file arrives already pirate. The author's own
     * ship is then restored to whatever mark it had -- the command borrows the wheel, it does not keep it.
     *
     * The crew is whoever is standing aboard: every live raider inside the hull's box. Their snapshots go
     * into the wheel as the ship's COMPLEMENT (what the respawn brings back), and the same raiders ride the
     * template's own entity list (what generation places on deck) -- two copies, two different jobs.
     */
    private fun capture(ctx: CommandContext<CommandSourceStack>): Int {
        val ship = ShipArgument.getShip(ctx, "ship")
        if (ship !is LoadedServerShip) {
            ctx.source.sendFailure(
                Component.literal("Ship '${ship.slug}' is not loaded -- get closer to it and try again.")
            )
            return 0
        }
        val name = StringArgumentType.getString(ctx, "name").trim()
        // A hull captured as "large1" instead of "pirate/large1" lands loose in the structures directory,
        // where the worldgen pool is not looking and the pirate listing will not show it. The command
        // succeeds, the file exists, and nothing generates -- so it is caught here, at the only moment
        // anybody is in a position to notice.
        ShipTemplateCommand.folderRefusal(name)?.let { ctx.source.sendFailure(it); return 0 }
        val level = ctx.source.level

        val helms = CrewStations.helmsAboard(level, ship) ?: run {
            ctx.source.sendFailure(Component.literal("That ship's chunks are not readable right now."))
            return 0
        }
        if (helms.size != 1) {
            ctx.source.sendFailure(
                Component.literal(
                    "A pirate ship carries exactly one wheel -- this hull has ${helms.size}."
                )
            )
            return 0
        }
        val helm = helms.single()

        val hull = ship.worldAABB
        val box = AABB(
            hull.minX() - DECK_MARGIN, hull.minY() - DECK_MARGIN, hull.minZ() - DECK_MARGIN,
            hull.maxX() + DECK_MARGIN, hull.maxY() + DECK_MARGIN, hull.maxZ() + DECK_MARGIN
        )
        val raiders = level.getEntitiesOfClass(Mob::class.java, box) { it.isAlive && PirateCrewTypes.eligible(it) }
        if (raiders.isEmpty()) {
            ctx.source.sendFailure(
                Component.literal("No crew aboard -- stand her complement on the deck before capturing.")
            )
            return 0
        }
        val snapshots = raiders.mapNotNull { snapshot(it) }
        if (snapshots.isEmpty()) {
            ctx.source.sendFailure(Component.literal("None of the crew aboard could be recorded."))
            return 0
        }

        // Borrow the wheel: mark + papers on, capture, then put the author's mark back whatever happens.
        val original = helm.blockState
        level.setBlock(helm.blockPos, original.setValue(EurekaProperties.MARK, HelmMark.PIRATE), Block.UPDATE_ALL)
        helm.pirateTemplate = name
        helm.pirateCrew = snapshots
        helm.setChanged()
        val outcome = try {
            ShipTemplate.capture(level, ship, name, keepShipName = false, admitRaiders = true)
        } finally {
            level.setBlock(helm.blockPos, original, Block.UPDATE_ALL)
        }

        return when (outcome) {
            is ShipTemplate.Failed -> {
                ctx.source.sendFailure(Component.literal(outcome.message))
                0
            }
            is ShipTemplate.Captured -> {
                val size = outcome.size
                ctx.source.sendSuccess({
                    Component.literal(
                        "Captured pirate hull '${outcome.id}' -- ${"%,d".format(outcome.blocks)} blocks, " +
                            "${snapshots.size} crew, ${size.x}x${size.y}x${size.z}. " +
                            "Load-test it with /vs template load $name"
                    ).withStyle(ChatFormatting.GREEN)
                }, true)
                1
            }
            else -> 0
        }
    }

    /**
     * A raider written down as this hull's complement: persistent (a ship generated an ocean from anywhere
     * must still be crewed when someone sails near) and stripped of patrol ambitions (patrol AI is a
     * marching order to the nearest village). Tag-level on purpose -- the author's live build props are
     * left exactly as they stood. Stale position and UUID stay in the snapshot; every restore path assigns
     * both fresh.
     */
    private fun snapshot(raider: Mob): CompoundTag? = try {
        val output = CompoundTag()
        if (!raider.save(output)) null else output.also {
            it.putBoolean("PersistenceRequired", true)
            it.remove("Patrolling")
            it.remove("patrol_target")
            it.remove("PatrolLeader")
        }
    } catch (ex: Exception) {
        null
    }

    /**
     * Restamp the helm under the caller's crosshair. `setBlock` with the same block keeps the block entity,
     * so a wheel's name, crew station and bottle binding all survive the change of allegiance -- which is
     * exactly what the real machinery will rely on when it flips PIRATE <-> TAKEN.
     */
    private fun setMark(ctx: CommandContext<CommandSourceStack>, mark: HelmMark): Int {
        val player = ctx.source.playerOrException
        val level = ctx.source.level

        val hit = player.pick(if (player.isCreative) 5.0 else 4.5, 1.0f, false)
        val pos = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos ?: run {
            ctx.source.sendFailure(Component.literal("Look at a ship helm to mark it."))
            return 0
        }
        val state = level.getBlockState(pos)
        if (state.block !is ShipHelmBlock) {
            ctx.source.sendFailure(Component.literal("That is not a ship helm."))
            return 0
        }

        level.setBlock(pos, state.setValue(EurekaProperties.MARK, mark), Block.UPDATE_ALL)
        ctx.source.sendSuccess({
            Component.literal("Helm marked ${mark.serializedName}.").withStyle(ChatFormatting.GREEN)
        }, true)
        return 1
    }
}
