package org.valkyrienskies.eureka.fabric

import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.path.ClientPathState
import org.valkyrienskies.eureka.path.PathFollower
import org.valkyrienskies.eureka.path.PathStore
import org.valkyrienskies.eureka.path.ShipPath
import org.valkyrienskies.eureka.armada.ArmadaBindings
import org.valkyrienskies.eureka.path.ShipPaths

/**
 * Wire protocol for ship paths: one tiny C2S action packet, and two S2C snapshots.
 *
 * Follows [ArmadaNetworkingFabric] exactly -- an opaque byte blob over VS's simple `BYTE_ARRAY` codec, with
 * the layout handled by hand-written encode/decode. That keeps the codec boilerplate to one line per payload
 * and keeps all the wire logic readable in one place.
 *
 * ## What goes where, and why
 * ROUTES (saved geometry) go to everyone in the dimension, but only when the set actually changes -- recording
 * a route is a rare event, so pushing the whole set then costs nothing and means SHIFT+O is instant rather
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

    private val ACTION_TYPE = CustomPacketPayload.Type<ActionPayload>(ACTION_RL)
    private val ROUTES_TYPE = CustomPacketPayload.Type<RoutesPayload>(ROUTES_RL)
    private val LIVE_TYPE = CustomPacketPayload.Type<LivePayload>(LIVE_RL)

    private val ACTION_CODEC: StreamCodec<FriendlyByteBuf, ActionPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ActionPayload::data) { ActionPayload(it) }
    private val ROUTES_CODEC: StreamCodec<FriendlyByteBuf, RoutesPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, RoutesPayload::data) { RoutesPayload(it) }
    private val LIVE_CODEC: StreamCodec<FriendlyByteBuf, LivePayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, LivePayload::data) { LivePayload(it) }

    class ActionPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = ACTION_TYPE
    }

    class RoutesPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = ROUTES_TYPE
    }

    class LivePayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = LIVE_TYPE
    }

    // The hotkey actions a client can ask for. Ordinals are the wire format; append only.
    const val ACTION_RECORD_START: Byte = 0
    const val ACTION_RECORD_CANCEL: Byte = 1
    const val ACTION_PLAY: Byte = 2
    const val ACTION_STOP: Byte = 3
    const val ACTION_REQUEST_ROUTES: Byte = 4

    /**
     * How much of a recording each player has already been sent, so updates can be incremental.
     *
     * Keyed by ship id, holding the exact [ServerPlayer] instance as well as the count: a relogged player is a
     * NEW instance, which is what tells us to start again from zero rather than leave them with a line that is
     * missing its first half.
     */
    private class SentState(var player: ServerPlayer, var count: Int)

    private val sent = HashMap<Long, SentState>()

    /** Dimensions whose last route broadcast was non-empty, so one trailing empty snapshot can clear clients. */
    private val dimensionsWithRoutes = HashSet<String>()

    /** Route sets already pushed this session, so we only resend when something actually changed. */
    private val lastRouteStamp = HashMap<String, Int>()

    fun registerCommon() {
        PayloadTypeRegistry.playC2S().register(ACTION_TYPE, ACTION_CODEC)
        PayloadTypeRegistry.playS2C().register(ROUTES_TYPE, ROUTES_CODEC)
        PayloadTypeRegistry.playS2C().register(LIVE_TYPE, LIVE_CODEC)
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
                    ACTION_PLAY -> ShipPaths.play(level, player)
                    ACTION_STOP -> ShipPaths.stop(level, player)
                    ACTION_REQUEST_ROUTES -> sendRoutes(player, PathStore.get(level))
                }
            }
        }
    }

    @Environment(EnvType.CLIENT)
    fun registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ROUTES_TYPE) { payload, context ->
            val routes = decodeRoutes(payload.data)
            context.client().execute { ClientPathState.replaceRoutes(routes) }
        }
        ClientPlayNetworking.registerGlobalReceiver(LIVE_TYPE) { payload, context ->
            val update = decodeLive(payload.data)
            context.client().execute { update.apply() }
        }
    }

    /** Client: ask the server to perform one of the hotkey actions. */
    @Environment(EnvType.CLIENT)
    fun sendAction(action: Byte) {
        ClientPlayNetworking.send(ActionPayload(byteArrayOf(action)))
    }

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
            if (!dimensionsWithRoutes.remove(dimKey)) return
            lastRouteStamp.remove(dimKey)
        } else {
            if (lastRouteStamp[dimKey] == stamp) return
            lastRouteStamp[dimKey] = stamp
            dimensionsWithRoutes.add(dimKey)
        }

        val payload = RoutesPayload(encodeRoutes(store))
        for (player in level.players()) ServerPlayNetworking.send(player, payload)
    }

    private fun broadcastLive(level: ServerLevel) {
        val recorders = ShipPaths.recordersIn(level)
        val followers = ShipPaths.followersIn(level)

        // Forget send-state for recordings that have ended, so the map can't grow across a session.
        if (sent.isNotEmpty()) {
            val live = recorders.mapTo(HashSet()) { it.shipId }
            sent.keys.retainAll(live)
        }
        if (recorders.isEmpty() && followers.isEmpty()) return

        for (recorder in recorders) {
            val player = level.server.playerList.getPlayer(recorder.playerId) ?: continue

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

            // Followers ride along in the same packet; the list is short and changes rarely.
            writeFollowers(buf, followers)

            ServerPlayNetworking.send(player, LivePayload(toArray(buf)))
        }

        // Players who aren't recording still need follower state (to draw a flown route), but only when there
        // is any, and only when nobody already sent it to them above.
        if (followers.isEmpty()) return
        val recordingPlayers = recorders.mapTo(HashSet()) { it.playerId }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(0)
        writeFollowers(buf, followers)
        val payload = LivePayload(toArray(buf))
        for (player in level.players()) {
            if (player.uuid in recordingPlayers) continue
            ServerPlayNetworking.send(player, payload)
        }
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
            // A route we can't rebuild is dropped rather than allowed to take the render thread down later.
            runCatching { ShipPath(id, name, "", control) }
                .onSuccess { out[id] = ClientPathState.Route(id, name, it) }
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
        val followers: List<ClientPathState.Following>
    ) {
        fun apply() {
            if (recordingShipId != null) {
                val rec = ClientPathState.appendRecording(recordingShipId, from, points)
                rec.armed = armed
                rec.start.set(start)
                rec.keel.set(keel)
                rec.gap = gap
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

        if (hasRecording) {
            shipId = buf.readLong()
            from = buf.readVarInt()
            val n = buf.readVarInt()
            points = DoubleArray(n * 3) { buf.readDouble() }
            armed = buf.readBoolean()
            start.set(buf.readDouble(), buf.readDouble(), buf.readDouble())
            keel.set(buf.readDouble(), buf.readDouble(), buf.readDouble())
            gap = buf.readDouble()
        }

        val followerCount = buf.readVarInt()
        val followers = ArrayList<ClientPathState.Following>(followerCount)
        repeat(followerCount) {
            val followShipId = buf.readLong()
            val pathId = buf.readLong()
            val offset = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            followers.add(ClientPathState.Following(followShipId, pathId, offset))
        }

        return LiveUpdate(shipId, from, points, armed, start, keel, gap, followers)
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
