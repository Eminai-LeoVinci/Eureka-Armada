package org.valkyrienskies.eureka.fabric

import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.crew.CrewManifest
import org.valkyrienskies.eureka.crew.CrewMarkers
import org.valkyrienskies.eureka.crew.HelmNames
import org.valkyrienskies.eureka.crew.ShipCrews
import org.valkyrienskies.eureka.fabric.client.ClientCrewMarkers
import org.valkyrienskies.eureka.fabric.client.PathHud
import org.valkyrienskies.eureka.fabric.client.crew.CrewManifestScreen
import org.valkyrienskies.eureka.path.ClientPathState
import org.valkyrienskies.eureka.path.PathFollower
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.path.PathMode
import org.valkyrienskies.eureka.path.PathStore
import org.valkyrienskies.eureka.path.ShipPath
import org.valkyrienskies.eureka.armada.ArmadaBindings
import org.valkyrienskies.eureka.follow.ShipFollows
import org.valkyrienskies.eureka.path.ShipPaths
import java.util.UUID

/**
 * Wire protocol for ship paths: one tiny C2S action packet, and two S2C snapshots.
 *
 * Follows [ArmadaNetworkingFabric] exactly -- an opaque byte blob over VS's simple `BYTE_ARRAY` codec, with
 * the layout handled by hand-written encode/decode. That keeps the codec boilerplate to one line per payload
 * and keeps all the wire logic readable in one place.
 *
 * ## What goes where, and why
 * ROUTES (saved geometry) go to everyone in the dimension, but only when the set actually changes -- recording
 * a route is a rare event, so pushing the whole set then costs nothing and means SHIFT+H is instant rather
 * than round-tripping to the server.
 *
 * Routes travel as their DECIMATED control points, not their dense followed form, and the client re-expands
 * them through the same [ShipPath] code the server uses. That is roughly a fifth of the bytes for an
 * identical curve.
 *
 * LIVE state (the trail being recorded, the snap markers) goes only to the player doing the recording, and
 * only the samples they have not already been sent. Broadcasting a growing polyline to everyone every tick is
 * the obvious way to make this feature expensive, and nobody else needs to watch someone else's trail.
 */
object PathNetworkingFabric {

    private val ACTION_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_action")
    private val ROUTES_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_routes")
    private val LIVE_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_live")
    private val MESSAGE_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_message")
    private val CREW_MARKS_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_marks")
    private val CREW_MANIFEST_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_manifest")
    private val CREW_DETAIL_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_detail")
    private val CREW_ASK_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_detail_ask")
    private val CREW_RENAME_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_rename")
    private val CREW_DISMISS_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "crew_dismiss")
    private val HELM_NAME_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "helm_name")
    private val SHIP_NAME_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "ship_name")

    private val ACTION_TYPE = CustomPacketPayload.Type<ActionPayload>(ACTION_RL)
    private val ROUTES_TYPE = CustomPacketPayload.Type<RoutesPayload>(ROUTES_RL)
    private val LIVE_TYPE = CustomPacketPayload.Type<LivePayload>(LIVE_RL)
    private val MESSAGE_TYPE = CustomPacketPayload.Type<MessagePayload>(MESSAGE_RL)
    private val CREW_MARKS_TYPE = CustomPacketPayload.Type<CrewMarksPayload>(CREW_MARKS_RL)
    private val CREW_MANIFEST_TYPE = CustomPacketPayload.Type<CrewManifestPayload>(CREW_MANIFEST_RL)
    private val CREW_DETAIL_TYPE = CustomPacketPayload.Type<CrewDetailPayload>(CREW_DETAIL_RL)
    private val CREW_ASK_TYPE = CustomPacketPayload.Type<CrewAskPayload>(CREW_ASK_RL)
    private val CREW_RENAME_TYPE = CustomPacketPayload.Type<CrewRenamePayload>(CREW_RENAME_RL)
    private val CREW_DISMISS_TYPE = CustomPacketPayload.Type<CrewDismissPayload>(CREW_DISMISS_RL)
    private val HELM_NAME_TYPE = CustomPacketPayload.Type<HelmNamePayload>(HELM_NAME_RL)
    private val SHIP_NAME_TYPE = CustomPacketPayload.Type<ShipNamePayload>(SHIP_NAME_RL)

    private val ACTION_CODEC: StreamCodec<FriendlyByteBuf, ActionPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ActionPayload::data) { ActionPayload(it) }
    private val ROUTES_CODEC: StreamCodec<FriendlyByteBuf, RoutesPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, RoutesPayload::data) { RoutesPayload(it) }
    private val LIVE_CODEC: StreamCodec<FriendlyByteBuf, LivePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, LivePayload::data) { LivePayload(it) }
    private val MESSAGE_CODEC: StreamCodec<FriendlyByteBuf, MessagePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, MessagePayload::data) { MessagePayload(it) }
    private val CREW_MARKS_CODEC: StreamCodec<FriendlyByteBuf, CrewMarksPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewMarksPayload::data) { CrewMarksPayload(it) }
    private val CREW_MANIFEST_CODEC: StreamCodec<FriendlyByteBuf, CrewManifestPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewManifestPayload::data) { CrewManifestPayload(it) }
    private val CREW_DETAIL_CODEC: StreamCodec<FriendlyByteBuf, CrewDetailPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewDetailPayload::data) { CrewDetailPayload(it) }
    private val CREW_ASK_CODEC: StreamCodec<FriendlyByteBuf, CrewAskPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewAskPayload::data) { CrewAskPayload(it) }
    private val CREW_RENAME_CODEC: StreamCodec<FriendlyByteBuf, CrewRenamePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewRenamePayload::data) { CrewRenamePayload(it) }
    private val CREW_DISMISS_CODEC: StreamCodec<FriendlyByteBuf, CrewDismissPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, CrewDismissPayload::data) { CrewDismissPayload(it) }
    private val HELM_NAME_CODEC: StreamCodec<FriendlyByteBuf, HelmNamePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, HelmNamePayload::data) { HelmNamePayload(it) }
    private val SHIP_NAME_CODEC: StreamCodec<FriendlyByteBuf, ShipNamePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ShipNamePayload::data) { ShipNamePayload(it) }

    class ActionPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = ACTION_TYPE
    }

    class RoutesPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = ROUTES_TYPE
    }

    class LivePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = LIVE_TYPE
    }

    class MessagePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = MESSAGE_TYPE
    }

    /** Entity ids of the crew to mark on this client's screen. An empty list puts the markers away. */
    class CrewMarksPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_MARKS_TYPE
    }

    /** A ship's crew manifest: who is aboard, in which berth, and how many berths this captain holds. */
    class CrewManifestPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_MANIFEST_TYPE
    }

    /** One crew member in full, trades included. Sent only when a player opens their card. */
    class CrewDetailPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_DETAIL_TYPE
    }

    /** "Tell me about this one." */
    class CrewAskPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_ASK_TYPE
    }

    /** "Call this one that." */
    class CrewRenamePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_RENAME_TYPE
    }

    /** "This one is off the articles." Whether that is allowed is the server's business, not the button's. */
    class CrewDismissPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = CREW_DISMISS_TYPE
    }

    /** "Call this WHEEL that." Names the CREW, and is the key they are filed under. Not the ship. */
    class HelmNamePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = HELM_NAME_TYPE
    }

    /**
     * "Call this SHIP that." The helm menu's Rename button, off `/vs rename` and onto a payload.
     *
     * Carries the wheel's position rather than a ship id: the position is what the menu already knows, and it
     * is what the server can check a player is standing next to. A ship id would be a number a client could
     * make up.
     */
    class ShipNamePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = SHIP_NAME_TYPE
    }

    // The hotkey actions a client can ask for. Ordinals are the wire format; append only.
    //
    // Four of these are two keys' worth of gestures rather than four keys: SHIFT+R taps to START and is held to
    // CANCEL, SHIFT+P taps to PLAY-or-pause and is held to STOP. The split between tap and hold is made on the
    // client, since only it can time a key; the split between play, pause and resume is made on the server,
    // since only it knows which of the three the ship is in.
    const val ACTION_RECORD_START: Byte = 0
    const val ACTION_RECORD_CANCEL: Byte = 1
    const val ACTION_PLAY: Byte = 2
    const val ACTION_STOP: Byte = 3
    const val ACTION_REQUEST_ROUTES: Byte = 4

    /**
     * Sneak+F: follow the ship the player is looking at.
     *
     * Carries no target -- the server does its own raycast from the player's eyes. Trusting a client-supplied
     * ship id here would be letting the client pick which ship it gets to command.
     */
    const val ACTION_FOLLOW_SHIP: Byte = 5

    /**
     * CTRL+SHIFT+P: fly the route's recorded speed and stops, not just its line.
     *
     * A separate action rather than a flag on [ACTION_PLAY] because the two are genuinely different requests
     * and the server treats them so -- on a ship already bound, each one means "be in MY mode", which is what
     * makes the pair a mode switch as well as a start.
     */
    const val ACTION_PLAY_REPLAY: Byte = 6

    /**
     * Sneak+C: sign on or pay off the villager under the crosshair, read the articles of the wheel under it,
     * or -- aimed at neither -- mark the crew on the deck you are standing on.
     *
     * ONE action for three meanings rather than three, because the client cannot tell them apart: which the
     * player meant is decided by what the SERVER's raycast finds, and the server is the only side allowed to
     * answer that. Same reasoning as [ACTION_FOLLOW_SHIP], which also carries no target -- a client-supplied
     * entity id would be a client choosing whose crew a villager joins.
     */
    const val ACTION_CREW: Byte = 7

    /** "This player's ship isn't flying a route." Route ids are positive, so 0 is free for this. */
    private const val NO_ROUTE = 0L

    /**
     * How much of a recording each player has already been sent, so updates can be incremental.
     *
     * Keyed by ship id, holding the exact [ServerPlayer] instance as well as the count: a relogged player is a
     * NEW instance, which is what tells us to start again from zero rather than leave them with a line that is
     * missing its first half.
     */
    private class SentState(var player: ServerPlayer, var count: Int)

    private val sent = HashMap<Long, SentState>()

    /**
     * The route set a dimension last pushed, and which of its players are actually holding it.
     *
     * Per-PLAYER as well as per-dimension, because the set changing is not the only reason someone needs it: a
     * player who has just joined, relogged or stepped through a portal holds nothing, and no change is coming to
     * tell them. That gap didn't show while following was runtime only -- SHIFT+H asked for the set outright, and
     * nothing else drew until you did. A ship that resumes its route on load has to draw its line with nobody
     * pressing anything, so arrival has to count as a reason to send.
     */
    private class RouteState(var stamp: Int) {
        val holders = HashSet<UUID>()
    }

    private val routeState = HashMap<String, RouteState>()

    /**
     * Who was sent a live snapshot on the previous broadcast, per dimension.
     *
     * The client's overlay is driven entirely by these packets, so it can only be taken down by one. Keeping the
     * previous recipient set is what lets a recording that has just closed its loop -- or a follower that has
     * just stopped -- push one final empty snapshot to exactly the players who are still drawing something.
     */
    private val liveRecipients = HashMap<String, MutableSet<UUID>>()

    fun registerCommon() {
        PayloadTypeRegistry.playC2S().register(ACTION_TYPE, ACTION_CODEC)
        PayloadTypeRegistry.playS2C().register(ROUTES_TYPE, ROUTES_CODEC)
        PayloadTypeRegistry.playS2C().register(LIVE_TYPE, LIVE_CODEC)
        PayloadTypeRegistry.playS2C().register(MESSAGE_TYPE, MESSAGE_CODEC)
        PayloadTypeRegistry.playS2C().register(CREW_MARKS_TYPE, CREW_MARKS_CODEC)
        PayloadTypeRegistry.playS2C().register(CREW_MANIFEST_TYPE, CREW_MANIFEST_CODEC)
        PayloadTypeRegistry.playS2C().register(CREW_DETAIL_TYPE, CREW_DETAIL_CODEC)
        PayloadTypeRegistry.playC2S().register(CREW_ASK_TYPE, CREW_ASK_CODEC)
        PayloadTypeRegistry.playC2S().register(CREW_RENAME_TYPE, CREW_RENAME_CODEC)
        PayloadTypeRegistry.playC2S().register(CREW_DISMISS_TYPE, CREW_DISMISS_CODEC)
        PayloadTypeRegistry.playC2S().register(HELM_NAME_TYPE, HELM_NAME_CODEC)
        PayloadTypeRegistry.playC2S().register(SHIP_NAME_TYPE, SHIP_NAME_CODEC)

        // Both of these report whether the push went out, which is what lets ShipCrews fall back to the roster
        // in chat rather than leaving a client the payload cannot reach with a key that does nothing.
        CrewManifest.sender = { player, snapshot ->
            if (ServerPlayNetworking.canSend(player, CREW_MANIFEST_TYPE)) {
                ServerPlayNetworking.send(player, CrewManifestPayload(encodeManifest(snapshot)))
                true
            } else {
                false
            }
        }

        CrewManifest.detailSender = { player, detail ->
            if (ServerPlayNetworking.canSend(player, CREW_DETAIL_TYPE)) {
                ServerPlayNetworking.send(player, CrewDetailPayload(encodeDetail(detail, player.registryAccess())))
                true
            } else {
                false
            }
        }

        // Point :common's crew markers at the wire. Guarded on the client having declared the channel, like
        // every other S2C send here -- Fabric treats a send on an undeclared channel as an error, not a no-op.
        CrewMarkers.sender = { player, ids ->
            if (ServerPlayNetworking.canSend(player, CREW_MARKS_TYPE)) {
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeVarInt(ids.size)
                for (id in ids) buf.writeVarInt(id)
                ServerPlayNetworking.send(player, CrewMarksPayload(toArray(buf)))
            }
        }

        // Point `:common`'s feedback at the stacking HUD, but only for players whose client actually declared
        // the channel -- sending to one that didn't is an error, not a no-op. Anyone else keeps the action bar,
        // which is the whole reason PathMessages has a fallback.
        PathMessages.sender = { player, text, kind ->
            if (ServerPlayNetworking.canSend(player, MESSAGE_TYPE)) {
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeByte(kind.ordinal)
                buf.writeUtf(text)
                ServerPlayNetworking.send(player, MessagePayload(toArray(buf)))
            } else {
                player.displayClientMessage(Component.literal(text).withStyle(kind.formatting), true)
            }
        }
    }

    /** Server: handle hotkey actions. Registered from the common initializer. */
    fun registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(ACTION_TYPE) { payload, context ->
            val action = payload.data.firstOrNull() ?: return@registerGlobalReceiver
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                when (action) {
                    ACTION_RECORD_START -> ShipPaths.startRecording(level, player)
                    ACTION_RECORD_CANCEL -> ShipPaths.cancelRecording(level, player)
                    ACTION_PLAY -> ShipPaths.playOrPause(level, player, PathMode.GEOMETRY)
                    ACTION_PLAY_REPLAY -> ShipPaths.playOrPause(level, player, PathMode.REPLAY)
                    ACTION_STOP -> ShipPaths.stop(level, player)
                    ACTION_REQUEST_ROUTES -> sendRoutes(player, PathStore.get(level))
                    ACTION_FOLLOW_SHIP -> ShipFollows.begin(level, player)
                    ACTION_CREW -> ShipCrews.gesture(level, player)
                }
            }
        }

        // The helm position rides both of these rather than the server keeping a per-player handle on an open
        // manifest. That makes a stale or forged request simply a lookup that fails, and it means a player who
        // logs out with the screen open leaves nothing behind to clean up.
        ServerPlayNetworking.registerGlobalReceiver(CREW_ASK_TYPE) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestDetail(level, player, helm, villager)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_RENAME_TYPE) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            // Bounded on the way IN as well as on the way out: the server sanitises and caps the string, but a
            // buffer read is what a hostile client reaches first.
            val name = buf.readUtf(CrewManifest.MAX_NAME_LENGTH * 4)
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestRename(level, player, helm, villager, name)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_DISMISS_TYPE) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestDismiss(level, player, helm, villager)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(HELM_NAME_TYPE) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val pos = buf.readBlockPos()
            // Read bound as well as sanitised: the server trims and caps the string, but the buffer read is
            // what a hostile client reaches first. x4 leaves room for multi-byte characters in the cap.
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                HelmNames.rename(level, player, pos, name)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(SHIP_NAME_TYPE) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val pos = buf.readBlockPos()
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            context.server().execute {
                val level = player.level() as? ServerLevel ?: return@execute
                HelmNames.renameShip(level, player, pos, name)
            }
        }
    }

    @Environment(EnvType.CLIENT)
    fun registerClient() {
        // The helm menu lives in :common and cannot reach this package, so it names a wheel through this seam.
        // Installed here rather than at the screen, so a screen opened before the client is ready still finds
        // a live sender rather than the no-op default.
        HelmNames.clientSender = { pos, name -> sendHelmName(pos, name) }
        HelmNames.clientShipSender = { pos, name -> sendShipName(pos, name) }

        ClientPlayNetworking.registerGlobalReceiver(ROUTES_TYPE) { payload, context ->
            val routes = decodeRoutes(payload.data)
            context.client().execute { ClientPathState.replaceRoutes(routes) }
        }
        ClientPlayNetworking.registerGlobalReceiver(LIVE_TYPE) { payload, context ->
            val update = decodeLive(payload.data)
            context.client().execute { update.apply() }
        }
        // Markers are the only client state here keyed on entity ids, which are per-connection: carrying a set
        // into the next world would draw plates over whatever happened to inherit those ids.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ClientCrewMarkers.clear() }

        ClientPlayNetworking.registerGlobalReceiver(CREW_MARKS_TYPE) { payload, context ->
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val ids = IntArray(buf.readVarInt()) { buf.readVarInt() }
            context.client().execute { ClientCrewMarkers.replace(ids) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_MANIFEST_TYPE) { payload, context ->
            val snapshot = decodeManifest(payload.data)
            context.client().execute { CrewManifestScreen.open(snapshot) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_DETAIL_TYPE) { payload, context ->
            // Decoded on the netty thread, so the registries come from the connection rather than from a client
            // field that may be mid-swap.
            val detail = decodeDetail(payload.data, context.client().connection!!.registryAccess())
            context.client().execute { CrewManifestScreen.acceptDetail(detail) }
        }
        ClientPlayNetworking.registerGlobalReceiver(MESSAGE_TYPE) { payload, context ->
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(payload.data))
            val kind = PathMessages.Kind.entries.getOrElse(buf.readByte().toInt()) { PathMessages.Kind.GOOD }
            val text = buf.readUtf()
            context.client().execute {
                if (kind == PathMessages.Kind.PROMPT) PathHud.prompt(Component.literal(text))
                else PathHud.add(Component.literal(text), kind.argb)
            }
        }
    }

    /** Client: ask the server to perform one of the hotkey actions. */
    @Environment(EnvType.CLIENT)
    fun sendAction(action: Byte) {
        ClientPlayNetworking.send(ActionPayload(byteArrayOf(action)))
    }

    /** Client: ask for one crew member's card. Answered by a [CrewDetailPayload], or by silence. */
    @Environment(EnvType.CLIENT)
    fun sendCrewAsk(helm: Long, villager: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        ClientPlayNetworking.send(CrewAskPayload(toArray(buf)))
    }

    /** Client: rename one crew member. An empty name asks for the berth's default back. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRename(helm: Long, villager: UUID, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        buf.writeUtf(name.take(CrewManifest.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(CrewRenamePayload(toArray(buf)))
    }

    /** Client: strike one crew member off the articles. */
    @Environment(EnvType.CLIENT)
    fun sendCrewDismiss(helm: Long, villager: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        ClientPlayNetworking.send(CrewDismissPayload(toArray(buf)))
    }

    /** Client: name the wheel at [pos]. An empty name clears it back to blank. */
    @Environment(EnvType.CLIENT)
    fun sendHelmName(pos: BlockPos, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(HelmNamePayload(toArray(buf)))
    }

    /** Client: name the SHIP that the wheel at [pos] belongs to. */
    @Environment(EnvType.CLIENT)
    fun sendShipName(pos: BlockPos, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(ShipNamePayload(toArray(buf)))
    }

    // region crew manifest codecs

    private fun encodeManifest(snapshot: CrewManifest.Snapshot): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(snapshot.ship)
        buf.writeLong(snapshot.helm)
        buf.writeVarInt(snapshot.berths)
        buf.writeVarInt(snapshot.maxBerths)
        buf.writeVarInt(snapshot.rows.size)
        for (row in snapshot.rows) {
            buf.writeVarInt(row.slot)
            buf.writeUUID(row.villager)
            buf.writeVarInt(row.entityId)
            buf.writeUtf(row.profession)
            buf.writeUtf(row.villagerType)
            buf.writeVarInt(row.level)
            buf.writeUtf(row.name)
        }
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeManifest(data: ByteArray): CrewManifest.Snapshot {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val ship = buf.readUtf()
        val helm = buf.readLong()
        val berths = buf.readVarInt()
        val maxBerths = buf.readVarInt()
        val rows = List(buf.readVarInt()) {
            CrewManifest.Row(
                slot = buf.readVarInt(),
                villager = buf.readUUID(),
                entityId = buf.readVarInt(),
                profession = buf.readUtf(),
                villagerType = buf.readUtf(),
                level = buf.readVarInt(),
                name = buf.readUtf()
            )
        }
        return CrewManifest.Snapshot(ship, helm, berths, maxBerths, rows)
    }

    /**
     * The card, including whole trade stacks.
     *
     * This is the one payload here that needs a REGISTRY-aware buffer: `ItemStack.OPTIONAL_STREAM_CODEC` resolves
     * items and data components against the connection's registries, and enchantments are data components. The
     * byte-array payload style does not hand one out, so we build it -- a `RegistryFriendlyByteBuf` is a plain
     * buffer plus a `RegistryAccess`, and both ends already have theirs.
     */
    private fun encodeDetail(detail: CrewManifest.Detail, registries: RegistryAccess): ByteArray {
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), registries)
        buf.writeUUID(detail.villager)
        buf.writeUtf(detail.name)
        buf.writeUtf(detail.profession)
        buf.writeVarInt(detail.level)
        buf.writeVarInt(detail.xp)
        buf.writeBoolean(detail.aboard)
        buf.writeVarInt(detail.offers.size)
        for (offer in detail.offers) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.costA)
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.costB)
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.result)
            buf.writeVarInt(offer.uses)
            buf.writeVarInt(offer.maxUses)
            buf.writeBoolean(offer.outOfStock)
        }
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeDetail(data: ByteArray, registries: RegistryAccess): CrewManifest.Detail {
        val buf = RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries)
        val villager = buf.readUUID()
        val name = buf.readUtf()
        val profession = buf.readUtf()
        val level = buf.readVarInt()
        val xp = buf.readVarInt()
        val aboard = buf.readBoolean()
        val offers = List(buf.readVarInt()) {
            CrewManifest.Offer(
                costA = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                costB = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                result = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                uses = buf.readVarInt(),
                maxUses = buf.readVarInt(),
                outOfStock = buf.readBoolean()
            )
        }
        return CrewManifest.Detail(villager, name, profession, level, xp, offers, aboard)
    }

    // endregion

    // region server broadcast

    /**
     * Per world tick: push route changes and live recording state. Self-throttles and self-silences.
     */
    fun broadcast(level: ServerLevel) {
        if (level.gameTime % 4L != 0L) return // ~5 Hz, same cadence as the armada bond snapshot

        broadcastRoutes(level)
        broadcastLive(level)
    }

    private fun broadcastRoutes(level: ServerLevel) {
        val store = PathStore.get(level)
        val dimKey = ArmadaBindings.dimKey(level)

        // Cheap change stamp: route count plus the ids. Enough to notice a save, a delete or a rename-by-id,
        // and it costs nothing on the ~every world that has no routes at all.
        var stamp = store.all.size
        for (path in store.all) stamp = stamp * 31 + path.id.toInt()

        if (store.isEmpty) {
            // Every route was deleted. One trailing empty snapshot to whoever is still holding some, then quiet.
            val state = routeState.remove(dimKey) ?: return
            if (state.holders.isEmpty()) return
            val payload = RoutesPayload(encodeRoutes(store))
            for (player in level.players()) {
                if (player.uuid in state.holders && canReceive(player, ROUTES_TYPE)) {
                    ServerPlayNetworking.send(player, payload)
                }
            }
            return
        }

        val state = routeState.getOrPut(dimKey) { RouteState(stamp) }
        if (state.stamp != stamp) {
            // The set changed, so everybody's copy is stale -- including their own.
            state.stamp = stamp
            state.holders.clear()
        }

        val here = HashSet<UUID>()
        var payload: RoutesPayload? = null
        for (player in level.players()) {
            if (!canReceive(player, ROUTES_TYPE)) continue
            here.add(player.uuid)
            if (player.uuid in state.holders) continue
            // Encoded at most once per broadcast, and only when somebody actually needs it.
            if (payload == null) payload = RoutesPayload(encodeRoutes(store))
            ServerPlayNetworking.send(player, payload)
        }

        // Rebuilt rather than added to, so leaving the dimension drops you from it -- and coming back therefore
        // counts as an arrival that needs the routes again.
        state.holders.clear()
        state.holders.addAll(here)
    }

    /**
     * Whether [player]'s client has declared the channel for [type] yet.
     *
     * Guards every S2C send, because Fabric treats a send on an undeclared channel as an ERROR rather than a
     * no-op, and a player who has only just joined has a short window before their declaration arrives. Both
     * snapshots are polls, so a false here costs nothing -- the next pass 200 ms later sends it.
     */
    private fun <T : CustomPacketPayload> canReceive(player: ServerPlayer, type: CustomPacketPayload.Type<T>) =
        ServerPlayNetworking.canSend(player, type)

    private fun broadcastLive(level: ServerLevel) {
        val recorders = ShipPaths.recordersIn(level)
        val followers = ShipPaths.followersIn(level)

        // Forget send-state for recordings that have ended, so the map can't grow across a session.
        if (sent.isNotEmpty()) {
            val live = recorders.mapTo(HashSet()) { it.shipId }
            sent.keys.retainAll(live)
        }

        val dimKey = ArmadaBindings.dimKey(level)
        val previous = liveRecipients[dimKey]

        if (recorders.isEmpty() && followers.isEmpty()) {
            // Everything has gone quiet -- but a client only ever DROPS its overlay on being told to, so simply
            // falling silent here is what left a finished recording's trail and snap spheres on screen until
            // another recording replaced them. Send exactly one empty snapshot to whoever was watching, then go
            // quiet properly.
            if (previous.isNullOrEmpty()) return
            clearLive(level, previous)
            liveRecipients.remove(dimKey)
            return
        }

        val recipients = HashSet<UUID>()

        for (recorder in recorders) {
            val player = level.server.playerList.getPlayer(recorder.playerId) ?: continue
            // Before the send-state bookkeeping below, not after: advancing `count` for a packet we then didn't
            // send would drop those samples from the client's trail for good.
            if (!canReceive(player, LIVE_TYPE)) continue
            recipients.add(player.uuid)

            val state = sent[recorder.shipId]
            val from = if (state == null || state.player !== player) 0 else state.count
            if (state == null || state.player !== player) {
                sent[recorder.shipId] = SentState(player, recorder.pointCount)
            } else {
                state.count = recorder.pointCount
            }

            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(1)
            buf.writeLong(recorder.shipId)
            buf.writeVarInt(from)
            val slice = recorder.slice(from)
            buf.writeVarInt(slice.size / 3)
            for (v in slice) buf.writeDouble(v)
            buf.writeBoolean(recorder.armed)
            writeVec(buf, recorder.start.x, recorder.start.y, recorder.start.z)
            // The keel travels too: the client draws the ship's half of the snap pair there, and it has no way
            // to work out where a hull's keel is on its own.
            writeVec(buf, recorder.keel.x, recorder.keel.y, recorder.keel.z)
            buf.writeDouble(recorder.gap)
            // Constant for the whole recording, and sent every update anyway -- one double against not having
            // to invent a second packet for a value the client cannot derive.
            buf.writeDouble(recorder.markerScale)

            // Followers ride along in the same packet; the list is short and changes rarely.
            writeFollowers(buf, followers)
            buf.writeLong(localRoute(level, player))

            ServerPlayNetworking.send(player, LivePayload(toArray(buf)))
        }

        // Players who aren't recording still need follower state (to draw a flown route), but only when there
        // is any, and only when nobody already sent it to them above.
        if (followers.isNotEmpty()) {
            val recordingPlayers = recorders.mapTo(HashSet()) { it.playerId }
            for (player in level.players()) {
                if (player.uuid in recordingPlayers) continue
                // A player who joined into a dimension where a ship is already flying a route hits this on their
                // first broadcast, which is exactly the window where the channel may not be declared yet.
                if (!canReceive(player, LIVE_TYPE)) continue
                // Built per player rather than once, because of the last field: which route the player's OWN
                // ship is flying. That is what lets SHIFT+H mean "hide the line I'm riding" instead of the
                // global show-all toggle, and only the server can resolve a player to their ship (and a
                // child of an armada to its parent).
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeVarInt(0)
                writeFollowers(buf, followers)
                buf.writeLong(localRoute(level, player))
                ServerPlayNetworking.send(player, LivePayload(toArray(buf)))
                recipients.add(player.uuid)
            }
        }

        // Anyone who was being sent live state and no longer is -- a player who walked off the ship that is
        // being flown, say -- needs the same one-shot clear as the all-quiet case above.
        previous?.forEach { if (it !in recipients) clearLive(level, setOf(it)) }
        liveRecipients[dimKey] = recipients
    }

    /** Tell [players] that nothing is live: no recording trail, no followers. Drops their whole overlay. */
    private fun clearLive(level: ServerLevel, players: Set<UUID>) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(0) // no recording
        buf.writeVarInt(0) // no followers
        buf.writeLong(NO_ROUTE) // not aboard anything that's flying one
        val payload = LivePayload(toArray(buf))
        for (id in players) {
            val player = level.server.playerList.getPlayer(id) ?: continue
            if (canReceive(player, LIVE_TYPE)) ServerPlayNetworking.send(player, payload)
        }
    }

    /**
     * The id of the route the ship [player] is aboard is currently flying, or [NO_ROUTE].
     *
     * Resolved server-side because that is where the answer lives: the standing-on-deck case comes from the
     * dragger's bookkeeping, and a child of an armada has to resolve to the parent, which is the ship that
     * actually holds the follower.
     */
    private fun localRoute(level: ServerLevel, player: ServerPlayer): Long {
        val ship = ShipPaths.resolveShip(level, player) ?: return NO_ROUTE
        return ShipPaths.followerFor(ship.id)?.path?.id ?: NO_ROUTE
    }

    private fun writeFollowers(buf: FriendlyByteBuf, followers: List<PathFollower>) {
        buf.writeVarInt(followers.size)
        val offset = Vector3d()
        for (f in followers) {
            buf.writeLong(f.shipId)
            buf.writeLong(f.path.id)
            // The offset travels too: without it the client would draw the route on the line while the ship
            // flies a displaced copy of it, which reads as the follower being broken.
            f.copyOffset(offset)
            writeVec(buf, offset.x, offset.y, offset.z)
        }
    }

    /** Push the route set to one player, in answer to a request. */
    private fun sendRoutes(player: ServerPlayer, store: PathStore) {
        ServerPlayNetworking.send(player, RoutesPayload(encodeRoutes(store)))
    }

    /**
     * Forget who has been sent what. Called when the server stops, alongside [ShipPaths.reset] -- see there for
     * why a singleton surviving a world matters rather than merely being untidy.
     *
     * Here the consequence is milder but still wrong: the next world would start with players already recorded as
     * holding a route set they have never seen.
     */
    fun resetServer() {
        sent.clear()
        routeState.clear()
        liveRecipients.clear()
    }

    // endregion

    // region wire format

    private fun encodeRoutes(store: PathStore): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(store.all.size)
        for (path in store.all) {
            buf.writeLong(path.id)
            buf.writeUtf(path.name)
            buf.writeVarInt(path.control.size)
            for (v in path.control) buf.writeDouble(v)
            // Only the PAUSES travel, not the timeline: the client draws a marker where a replayed ship will
            // stop, and has no use at all for when it gets there. A handful of doubles per route against
            // sending the whole track for a picture that would not use it.
            val dwells = path.motion?.dwells ?: DoubleArray(0)
            buf.writeVarInt(dwells.size / 2)
            for (i in 0 until dwells.size / 2) buf.writeDouble(dwells[i * 2])
        }
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeRoutes(data: ByteArray): Map<Long, ClientPathState.Route> {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val count = buf.readVarInt()
        val out = HashMap<Long, ClientPathState.Route>(count)
        repeat(count) {
            val id = buf.readLong()
            val name = buf.readUtf()
            val size = buf.readVarInt()
            val control = DoubleArray(size) { buf.readDouble() }
            val dwells = DoubleArray(buf.readVarInt()) { buf.readDouble() }
            // A route we can't rebuild is dropped rather than allowed to take the render thread down later.
            runCatching { ShipPath(id, name, "", control) }
                .onSuccess { out[id] = ClientPathState.Route(id, name, it, dwells) }
        }
        return out
    }

    /** Decoded live update, applied on the client thread. */
    @Environment(EnvType.CLIENT)
    private class LiveUpdate(
        val recordingShipId: Long?,
        val from: Int,
        val points: DoubleArray,
        val armed: Boolean,
        val start: Vector3d,
        val keel: Vector3d,
        val gap: Double,
        val markerScale: Double,
        val followers: List<ClientPathState.Following>,
        val localRouteId: Long
    ) {
        fun apply() {
            ClientPathState.localRouteId = localRouteId
            if (recordingShipId != null) {
                val rec = ClientPathState.appendRecording(recordingShipId, from, points)
                rec.armed = armed
                rec.start.set(start)
                rec.keel.set(keel)
                rec.gap = gap
                rec.markerScale = markerScale
            } else {
                ClientPathState.recordings.clear()
            }

            ClientPathState.following.keys.retainAll(followers.mapTo(HashSet()) { it.shipId })
            for (f in followers) ClientPathState.following[f.shipId] = f
        }
    }

    @Environment(EnvType.CLIENT)
    private fun decodeLive(data: ByteArray): LiveUpdate {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val hasRecording = buf.readVarInt() > 0

        var shipId: Long? = null
        var from = 0
        var points = DoubleArray(0)
        var armed = false
        val start = Vector3d()
        val keel = Vector3d()
        var gap = 0.0
        var markerScale = 1.0

        if (hasRecording) {
            shipId = buf.readLong()
            from = buf.readVarInt()
            val n = buf.readVarInt()
            points = DoubleArray(n * 3) { buf.readDouble() }
            armed = buf.readBoolean()
            start.set(buf.readDouble(), buf.readDouble(), buf.readDouble())
            keel.set(buf.readDouble(), buf.readDouble(), buf.readDouble())
            gap = buf.readDouble()
            markerScale = buf.readDouble()
        }

        val followerCount = buf.readVarInt()
        val followers = ArrayList<ClientPathState.Following>(followerCount)
        repeat(followerCount) {
            val followShipId = buf.readLong()
            val pathId = buf.readLong()
            val offset = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            followers.add(ClientPathState.Following(followShipId, pathId, offset))
        }

        return LiveUpdate(shipId, from, points, armed, start, keel, gap, markerScale, followers, buf.readLong())
    }

    private fun writeVec(buf: FriendlyByteBuf, x: Double, y: Double, z: Double) {
        buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z)
    }

    private fun toArray(buf: FriendlyByteBuf): ByteArray {
        val arr = ByteArray(buf.readableBytes())
        buf.readBytes(arr)
        return arr
    }

    // endregion
}
