package org.valkyrienskies.eureka.armada

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.world.connectivity.ConnectionStatus
import org.valkyrienskies.core.impl.config.VSCoreConfig
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * TEMPORARY -- diagnostic behind "/armada sealed". Delete this file with its command once submarines settle.
 *
 * ## What it is for now
 * VS2 ships its own submarine "sealed area" system: vs-core's connectivity engine flood-fills the air inside
 * every ship, classifies each voxel CONNECTED / DISCONNECTED / UNKNOWN, and VS2's entity mixins suppress
 * eye-in-water, the swim pose and water physics for anyone in a DISCONNECTED pocket. The 2026-07-24 experiment
 * proved it works -- and also that we should NOT build on it: every one of those checks is gated on connectivity
 * being enabled, and enabling connectivity is what arms VS2's ship SPLITTER (SplitHandler is fed from the same
 * flag), which would tear apart any ship whose blocks form more than one component. [SubAir] therefore does the
 * job with an explicit marker block and no connectivity at all.
 *
 * So connectivity is deliberately left at its compiled-in default (OFF), and this command now serves the
 * opposite purpose to the one it was written for: it PROVES VS2's system is inactive, so that a dry submarine
 * can only be [SubAir]'s doing. `connectivity: core=false` in the output is the expected, wanted result.
 *
 * Note for anyone trying to turn connectivity on here: you cannot do it from a file. VS2 registers its configs
 * through Forge Config API Port and that registration throws at launch in this instance
 * (`NoClassDefFoundError: fuzs/forgeconfigapiport/fabric/api/neoforge/v4/NeoForgeConfigRegistry`), so no
 * `valkyrienskies-*.toml` is ever written and every VS2 config value sits at its default.
 */
object ArmadaSubExperiment {

    /**
     * Dump what the sealed-area machinery actually believes at [entity]'s position. Reports the live flags
     * (so we can tell "the feature is off" apart from "the feature is on and says CONNECTED"), then, for
     * every ship the entity is standing in, the ship-space block, its connectivity status and the size of
     * the air pocket it belongs to.
     */
    fun report(level: ServerLevel, entity: Entity): Component {
        val msg = Component.literal("Armada sub experiment").withStyle(ChatFormatting.AQUA)
        fun line(s: String, color: ChatFormatting = ChatFormatting.GRAY) =
            msg.append(Component.literal("\n  $s").withStyle(color))

        val coreOn = try {
            ValkyrienSkiesMod.vsCore.hooks.enableConnectivity
        } catch (e: Throwable) {
            null
        }
        line(
            "connectivity: core=${coreOn ?: "?"} config=${VSCoreConfig.SERVER.sp.enableConnectivity} " +
                "clientMirror=${VSGameConfig.CLIENT.Connectivity.enableClientConnectivity} " +
                "splitting=${VSCoreConfig.SERVER.sp.enableSplitting}",
            if (coreOn == true) ChatFormatting.GREEN else ChatFormatting.RED
        )

        val pos = entity.position()
        val worldBlock = BlockPos.containing(pos)
        val worldFluid = level.getFluidState(worldBlock)
        line(
            "world pos ${worldBlock.x} ${worldBlock.y} ${worldBlock.z} -- " +
                if (worldFluid.isEmpty) "no fluid" else "IN ${fluidName(level, worldBlock)}",
            if (worldFluid.isEmpty) ChatFormatting.GRAY else ChatFormatting.BLUE
        )

        // The entity's own verdict -- this is the flag every VS2 water mixin actually branches on.
        val sealed = (entity as? IEntityDraggingInformationProvider)?.`vs$isInSealedArea`()
        line(
            "entity vs\$isInSealedArea = ${sealed ?: "n/a"}",
            if (sealed == true) ChatFormatting.GREEN else ChatFormatting.YELLOW
        )

        if (level.isBlockInShipyard(worldBlock)) {
            line("standing in the SHIPYARD directly", ChatFormatting.YELLOW)
            describeShipSpace(level, worldBlock, ::line)
            return msg
        }

        // Normal case: the entity is at a world position and the hull it's inside lives in the shipyard.
        val ships = level.getShipsIntersecting(entity.boundingBox.inflate(1.0)).toList()
        if (ships.isEmpty()) {
            line("no ship contains this position -- stand inside a hull", ChatFormatting.YELLOW)
            return msg
        }
        for (ship in ships.take(MAX_SHIPS_REPORTED)) {
            line("ship ${name(ship)}:", ChatFormatting.AQUA)
            val inShip = ship.worldToShip.transformPosition(Vector3d(pos.x, pos.y, pos.z), Vector3d())
            describeShipSpace(level, BlockPos.containing(inShip.x, inShip.y, inShip.z), ::line)
        }
        if (ships.size > MAX_SHIPS_REPORTED) {
            line("(+${ships.size - MAX_SHIPS_REPORTED} more ships here)")
        }
        return msg
    }

    /** Connectivity verdict + block identity at one shipyard position. */
    private fun describeShipSpace(
        level: ServerLevel,
        shipPos: BlockPos,
        line: (String, ChatFormatting) -> Unit
    ) {
        val state = level.getBlockState(shipPos)
        val blockId = state.block.descriptionId
        val fluid = if (state.fluidState.isEmpty) "dry" else "FLUID"
        line("  ship-space ${shipPos.x} ${shipPos.y} ${shipPos.z} = $blockId ($fluid)", ChatFormatting.GRAY)

        val status = try {
            level.shipObjectWorld.isIsolatedAir(shipPos.x, shipPos.y, shipPos.z, level.dimensionId)
        } catch (e: Throwable) {
            null
        }
        if (status == null) {
            line("  connectivity: unavailable", ChatFormatting.RED)
            return
        }
        val size = try {
            level.shipObjectWorld.getAirComponentSize(shipPos.x, shipPos.y, shipPos.z, level.dimensionId)
        } catch (e: Throwable) {
            -1L
        }
        // isPositionSealed counts UNKNOWN as sealed too -- show the raw status so we can see it guessing.
        line(
            "  connectivity: $status (air pocket $size blocks) -> sealed=${status != ConnectionStatus.CONNECTED}",
            when (status) {
                ConnectionStatus.DISCONNECTED -> ChatFormatting.GREEN
                ConnectionStatus.UNKNOWN -> ChatFormatting.YELLOW
                else -> ChatFormatting.RED
            }
        )
    }

    private fun fluidName(level: ServerLevel, pos: BlockPos): String =
        level.getBlockState(pos).block.descriptionId

    private fun name(ship: Ship): String = ship.slug ?: ship.id.toString()

    private const val MAX_SHIPS_REPORTED = 3
}
