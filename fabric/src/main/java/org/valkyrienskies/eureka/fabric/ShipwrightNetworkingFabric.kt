package org.valkyrienskies.eureka.fabric

import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Item
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.BenchPart
import org.valkyrienskies.eureka.block.ShipwrightsBenchBlock
import org.valkyrienskies.eureka.shipwright.ShipwrightMenu
import org.valkyrienskies.eureka.shipwright.ShipwrightTalk

/**
 * The shipwright's screen on the wire, plus the click that opens it.
 *
 * Two packets and nothing else: a shelf going out, an action coming back. Every action is answered with a fresh
 * shelf, which is what lets the screen hold a snapshot and never poll.
 *
 * Kept apart from `PathNetworkingFabric` because it shares nothing with it -- different data, different
 * lifetime, different screen -- and that file is already carrying twelve payload types.
 */
object ShipwrightNetworkingFabric {

    private val SHELF_RL = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "shipwright_shelf")
    private val ACTION_RL = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "shipwright_action")

    private val SHELF_TYPE = CustomPacketPayload.Type<ShelfPayload>(SHELF_RL)
    private val ACTION_TYPE = CustomPacketPayload.Type<ActionPayload>(ACTION_RL)

    private val SHELF_CODEC: StreamCodec<FriendlyByteBuf, ShelfPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ShelfPayload::data) { ShelfPayload(it) }
    private val ACTION_CODEC: StreamCodec<FriendlyByteBuf, ActionPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ActionPayload::data) { ActionPayload(it) }

    class ShelfPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = SHELF_TYPE
    }

    class ActionPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = ACTION_TYPE
    }

    fun registerCommon() {
        PayloadTypeRegistry.playS2C().register(SHELF_TYPE, SHELF_CODEC)
        PayloadTypeRegistry.playC2S().register(ACTION_TYPE, ACTION_CODEC)
    }

    fun registerServer() {
        // Right-click a shipwright. Claimed outright so it never reaches vanilla's trade screen -- the
        // profession sells nothing, and an unclaimed click makes the villager shake its head.
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (level.isClientSide) return@register InteractionResult.PASS
            val villager = entity as? Villager ?: return@register InteractionResult.PASS
            if (!ShipwrightTalk.isShipwright(villager)) return@register InteractionResult.PASS

            val serverLevel = level as? ServerLevel ?: return@register InteractionResult.PASS
            val serverPlayer = player as? ServerPlayer ?: return@register InteractionResult.PASS

            val handled = ShipwrightTalk.interact(
                serverLevel, serverPlayer, villager, player.getItemInHand(hand)
            )
            if (handled) InteractionResult.SUCCESS else InteractionResult.PASS
        }

        // How :common gets a screen open without knowing what a packet is.
        ShipwrightTalk.sender = { player, shelf ->
            if (ServerPlayNetworking.canSend(player, SHELF_TYPE)) {
                ServerPlayNetworking.send(player, ShelfPayload(encodeShelf(shelf)))
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(ACTION_TYPE) { payload, context ->
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val villagerId = buf.readVarInt()
            val action = ShipwrightMenu.Action.entries.getOrNull(buf.readVarInt()) ?: return@registerGlobalReceiver
            val shipName = buf.readUtf()
            val argument = buf.readUtf()
            // A second argument, appended rather than packed into the first: SWAP needs a row AND a
            // replacement, and a registry id may not contain a separator that would make splitting safe.
            val argument2 = if (buf.isReadable) buf.readUtf() else ""

            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            level.server.execute {
                // Resolved server-side and re-checked every time: a client asking about a counter it cannot
                // reach -- a distant villager, a broken bench, a session it never opened -- gets nothing.
                val counter = resolveCounter(level, player, villagerId) ?: return@execute
                ShipwrightTalk.act(level, player, counter, action, shipName, argument, argument2)
            }
        }
    }

    @Environment(EnvType.CLIENT)
    fun registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(SHELF_TYPE) { payload, context ->
            val shelf = decodeShelf(payload.data)
            context.client().execute { ShipwrightMenu.open(shelf) }
        }
    }

    /** Called from the screen. */
    @Environment(EnvType.CLIENT)
    fun send(
        villager: Int,
        action: ShipwrightMenu.Action,
        shipName: String,
        argument: String = "",
        argument2: String = ""
    ) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(villager)
        buf.writeVarInt(action.ordinal)
        buf.writeUtf(shipName)
        buf.writeUtf(argument)
        buf.writeUtf(argument2)
        ClientPlayNetworking.send(ActionPayload(toArray(buf)))
    }

    /**
     * One material line, written in one place.
     *
     * The two halves of this codec sit fifty lines apart and the compiler cannot tell when they disagree --
     * a field added to one and forgotten in the other shifts every byte after it and the screen decodes
     * gibberish. Writing and reading a row through a matched pair is what keeps that from being possible.
     */
    private fun writeMaterial(buf: FriendlyByteBuf, material: ShipwrightMenu.Material) {
        buf.writeUtf(BuiltInRegistries.ITEM.getKey(material.item).toString())
        buf.writeVarInt(material.needed)
        buf.writeVarInt(material.given)
        buf.writeUtf(material.swappedFrom?.let { BuiltInRegistries.ITEM.getKey(it).toString() } ?: "")
        buf.writeUtf(material.family ?: "")
        buf.writeUtf(material.category)
        buf.writeBoolean(material.anyOfKind)
    }

    private fun readMaterial(buf: FriendlyByteBuf): ShipwrightMenu.Material? {
        val id = buf.readUtf()
        val needed = buf.readVarInt()
        val given = buf.readVarInt()
        val from = buf.readUtf()
        val family = buf.readUtf()
        val category = buf.readUtf()
        val anyOfKind = buf.readBoolean()
        val item: Item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null) ?: return null
        return ShipwrightMenu.Material(
            item, needed, given,
            swappedFrom = from.takeIf { it.isNotEmpty() }
                ?.let { BuiltInRegistries.ITEM.getOptional(Identifier.parse(it)).orElse(null) },
            family = family.takeIf { it.isNotEmpty() },
            category = category,
            anyOfKind = anyOfKind
        )
    }

    private fun encodeShelf(shelf: ShipwrightMenu.Shelf): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(shelf.villager)
        buf.writeVarInt(shelf.slots)
        buf.writeBoolean(shelf.hasFreeBottle)
        buf.writeBoolean(shelf.repairEnabled)
        buf.writeBoolean(shelf.partialRepair)
        buf.writeBoolean(shelf.hasBlankBlueprint)
        buf.writeBoolean(shelf.excludeEnabled)
        buf.writeBoolean(shelf.swapEnabled)
        buf.writeBoolean(shelf.swapFoundational)
        buf.writeVarInt(shelf.rows.size)
        for (row in shelf.rows) {
            buf.writeUtf(row.shipName)
            buf.writeVarInt(row.width)
            buf.writeVarInt(row.height)
            buf.writeVarInt(row.length)
            buf.writeVarInt(row.blocks)
            buf.writeDouble(row.mass)
            buf.writeDouble(row.topSpeed)
            buf.writeUtf(row.profile)
            buf.writeBoolean(row.altered)
            buf.writeVarInt(row.materials.size)
            for (material in row.materials) writeMaterial(buf, material)
            buf.writeVarInt(row.struck.size)
            for (material in row.struck) writeMaterial(buf, material)
            buf.writeVarInt(row.fee.size)
            for (material in row.fee) writeMaterial(buf, material)
            buf.writeBoolean(row.feePaid)
        }

        buf.writeVarInt(shelf.vessels.size)
        for (vessel in shelf.vessels) {
            buf.writeUtf(vessel.slug)
            buf.writeVarInt(vessel.width)
            buf.writeVarInt(vessel.height)
            buf.writeVarInt(vessel.length)
            buf.writeVarInt(vessel.blocks)
            buf.writeDouble(vessel.mass)
            buf.writeFloat(vessel.fuel)
            buf.writeBoolean(vessel.child)
            buf.writeUtf(vessel.plansName ?: "")
            buf.writeFloat(vessel.match)
            buf.writeUtf(vessel.refusal ?: "")
            buf.writeVarInt(vessel.repairs.size)
            for (material in vessel.repairs) {
                buf.writeUtf(BuiltInRegistries.ITEM.getKey(material.item).toString())
                buf.writeVarInt(material.needed)
                buf.writeVarInt(material.given)
            }
            buf.writeVarInt(vessel.fee.size)
            for (material in vessel.fee) writeMaterial(buf, material)
        }

        buf.writeBoolean(shelf.dismantleEnabled)
        buf.writeVarInt(shelf.salvage.size)
        for (pile in shelf.salvage) {
            buf.writeUtf(pile.shipName)
            buf.writeVarInt(pile.keepsakes.size)
            for (kept in pile.keepsakes) {
                buf.writeVarInt(kept.index)
                buf.writeUtf(BuiltInRegistries.ITEM.getKey(kept.item).toString())
                buf.writeVarInt(kept.count)
                buf.writeUtf(kept.label)
            }
            buf.writeVarInt(pile.hull.size)
            for (material in pile.hull) writeMaterial(buf, material)
            buf.writeVarInt(pile.cargo.size)
            for (material in pile.cargo) writeMaterial(buf, material)
        }
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeShelf(data: ByteArray): ShipwrightMenu.Shelf {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val villager = buf.readVarInt()
        val slots = buf.readVarInt()
        val hasFreeBottle = buf.readBoolean()
        val repairEnabled = buf.readBoolean()
        val partialRepair = buf.readBoolean()
        val hasBlankBlueprint = buf.readBoolean()
        val excludeEnabled = buf.readBoolean()
        val swapEnabled = buf.readBoolean()
        val swapFoundational = buf.readBoolean()
        val rows = List(buf.readVarInt()) {
            val shipName = buf.readUtf()
            val width = buf.readVarInt()
            val height = buf.readVarInt()
            val length = buf.readVarInt()
            val blocks = buf.readVarInt()
            val mass = buf.readDouble()
            val topSpeed = buf.readDouble()
            val profile = buf.readUtf()
            val altered = buf.readBoolean()
            val materials = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { materials.add(it) } }
            val struck = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { struck.add(it) } }
            val fee = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { fee.add(it) } }
            val feePaid = buf.readBoolean()
            // Named from here on. The positional call was already eleven arguments deep and two of them
            // were lists of the same type; the next person to append a field should not have to count.
            ShipwrightMenu.Row(
                shipName = shipName, width = width, height = height, length = length, blocks = blocks,
                items = materials.sumOf { it.needed }, mass = mass, topSpeed = topSpeed, profile = profile,
                materials = materials, struck = struck, altered = altered, fee = fee, feePaid = feePaid
            )
        }
        val vessels = List(buf.readVarInt()) {
            val slug = buf.readUtf()
            val width = buf.readVarInt()
            val height = buf.readVarInt()
            val length = buf.readVarInt()
            val blocks = buf.readVarInt()
            val mass = buf.readDouble()
            val fuel = buf.readFloat()
            val child = buf.readBoolean()
            val plansName = buf.readUtf().takeIf { it.isNotEmpty() }
            val match = buf.readFloat()
            val refusal = buf.readUtf().takeIf { it.isNotEmpty() }
            val repairs = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) {
                val id = buf.readUtf()
                val needed = buf.readVarInt()
                val given = buf.readVarInt()
                val item: Item? = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)
                if (item != null) repairs.add(ShipwrightMenu.Material(item, needed, given))
            }
            val fee = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { fee.add(it) } }
            ShipwrightMenu.Vessel(
                slug, width, height, length, blocks, mass, fuel, child, plansName, match, refusal,
                repairs, fee
            )
        }

        val dismantleEnabled = buf.readBoolean()
        val salvage = List(buf.readVarInt()) {
            val shipName = buf.readUtf()
            val keepsakes = ArrayList<ShipwrightMenu.Keepsake>()
            repeat(buf.readVarInt()) {
                val index = buf.readVarInt()
                val id = buf.readUtf()
                val count = buf.readVarInt()
                val label = buf.readUtf()
                BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)?.let {
                    keepsakes.add(ShipwrightMenu.Keepsake(index, it, count, label))
                }
            }
            val hull = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { hull.add(it) } }
            val cargo = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) { readMaterial(buf)?.let { cargo.add(it) } }
            ShipwrightMenu.Pile(shipName, hull, cargo, keepsakes)
        }

        return ShipwrightMenu.Shelf(
            villager, slots, hasFreeBottle, rows, vessels, repairEnabled, partialRepair,
            hasBlankBlueprint, excludeEnabled, swapEnabled, swapFoundational, salvage, dismantleEnabled
        )
    }

    private fun toArray(buf: FriendlyByteBuf): ByteArray {
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    /**
     * The counter this action is aimed at, re-validated server-side, or null to refuse silently.
     *
     * A non-negative id is the villager path exactly as it always was. [ShipwrightTalk.BENCH_WIRE_ID] is a
     * bench session: the id names nothing, so the anchor comes from the server's own session record, and
     * the block, the config gate and the reach are all re-checked against it -- a forged -1 with no
     * session, an expired one, or a desk since broken all fall out the same silent way the villager path
     * refuses.
     */
    private fun resolveCounter(
        level: ServerLevel,
        player: ServerPlayer,
        villagerId: Int
    ): ShipwrightTalk.Counter? {
        if (villagerId != ShipwrightTalk.BENCH_WIRE_ID) {
            if (!EurekaConfig.SERVER.shipwrightVillagerAccess) return null
            val villager = level.getEntity(villagerId) as? Villager ?: return null
            if (!ShipwrightTalk.isShipwright(villager)) return null
            if (villager.distanceToSqr(player) > REACH_SQR) return null
            return ShipwrightTalk.Counter.AtVillager(villager)
        }

        if (!EurekaConfig.SERVER.shipwrightBenchAccess) return null
        val anchor = ShipwrightTalk.benchSessionOf(level, player) ?: return null
        val state = level.getBlockState(anchor)
        if (state.block !is ShipwrightsBenchBlock) return null
        if (state.getValue(EurekaProperties.BENCH_PART) != BenchPart.MIDDLE_LOWER) return null

        // A bench aboard an assembled ship holds SHIPYARD coordinates; reach is measured against where the
        // desk actually stands in the world. The lift lives in common (see benchWorldPosition).
        val world = ShipwrightTalk.benchWorldPosition(level, anchor)
        val dx = world.x - player.x
        val dy = world.y - player.y
        val dz = world.z - player.z
        if (dx * dx + dy * dy + dz * dz > REACH_SQR) return null
        return ShipwrightTalk.Counter.AtBench(anchor)
    }

    /** Six blocks, squared. Comfortably past reach, tight enough that it is not a remote control. */
    private const val REACH_SQR = 36.0
}
