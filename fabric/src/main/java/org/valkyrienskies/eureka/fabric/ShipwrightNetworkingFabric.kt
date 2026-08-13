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
import org.valkyrienskies.eureka.EurekaMod
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

            val player = context.player()
            val level = player.level() as? ServerLevel ?: return@registerGlobalReceiver
            level.server.execute {
                // Resolved server-side and re-checked every time: a client asking about a villager it cannot
                // reach, or one that has since stopped being a shipwright, gets nothing.
                val villager = level.getEntity(villagerId) as? Villager ?: return@execute
                if (!ShipwrightTalk.isShipwright(villager)) return@execute
                if (villager.distanceToSqr(player) > REACH_SQR) return@execute

                ShipwrightTalk.act(level, player, villager, action, shipName, argument)
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
    fun send(villager: Int, action: ShipwrightMenu.Action, shipName: String, argument: String = "") {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(villager)
        buf.writeVarInt(action.ordinal)
        buf.writeUtf(shipName)
        buf.writeUtf(argument)
        ClientPlayNetworking.send(ActionPayload(toArray(buf)))
    }

    private fun encodeShelf(shelf: ShipwrightMenu.Shelf): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(shelf.villager)
        buf.writeVarInt(shelf.slots)
        buf.writeBoolean(shelf.hasFreeBottle)
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
            buf.writeVarInt(row.materials.size)
            for (material in row.materials) {
                buf.writeUtf(BuiltInRegistries.ITEM.getKey(material.item).toString())
                buf.writeVarInt(material.needed)
                buf.writeVarInt(material.given)
            }
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
        }
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeShelf(data: ByteArray): ShipwrightMenu.Shelf {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val villager = buf.readVarInt()
        val slots = buf.readVarInt()
        val hasFreeBottle = buf.readBoolean()
        val rows = List(buf.readVarInt()) {
            val shipName = buf.readUtf()
            val width = buf.readVarInt()
            val height = buf.readVarInt()
            val length = buf.readVarInt()
            val blocks = buf.readVarInt()
            val mass = buf.readDouble()
            val topSpeed = buf.readDouble()
            val profile = buf.readUtf()
            val materials = ArrayList<ShipwrightMenu.Material>()
            repeat(buf.readVarInt()) {
                val id = buf.readUtf()
                val needed = buf.readVarInt()
                val given = buf.readVarInt()
                val item: Item? = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null)
                if (item != null) materials.add(ShipwrightMenu.Material(item, needed, given))
            }
            ShipwrightMenu.Row(
                shipName, width, height, length, blocks,
                materials.sumOf { it.needed }, mass, topSpeed, profile, materials
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
            ShipwrightMenu.Vessel(
                slug, width, height, length, blocks, mass, fuel, child, plansName, match, refusal, repairs
            )
        }

        return ShipwrightMenu.Shelf(villager, slots, hasFreeBottle, rows, vessels)
    }

    private fun toArray(buf: FriendlyByteBuf): ByteArray {
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    /** Six blocks, squared. Comfortably past reach, tight enough that it is not a remote control. */
    private const val REACH_SQR = 36.0
}
