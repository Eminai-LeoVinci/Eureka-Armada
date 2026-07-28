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
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.armada.ArmadaBindings
import org.valkyrienskies.eureka.armada.ArmadaClientBonds

/**
 * Syncs armada parent/child bonds to clients so the client knows which ships share an armada. The bond lives
 * only on the server (an [ArmadaShipControl] attachment, which VS does not sync), so we push a small S2C
 * snapshot; the client keeps it in [ArmadaClientBonds], where the ship-mounted camera uses it to treat the
 * whole formation as one. A child is a real welded physics body that VS renders natively, so no render-follow
 * is involved.
 *
 * Snapshot semantics keep the wire logic trivial: the server sends the complete bond list for a dimension to
 * the players in it, and the client replaces its registry wholesale. That one mechanism covers bind, unbind,
 * join, relog and ship (un)tracking with no per-event bookkeeping. To avoid pointless traffic for the ~all
 * worlds with no armada, we broadcast only while a dimension actually has bonds, plus one trailing empty
 * snapshot the tick its last bond goes away. Sends are throttled to ~5 Hz -- bond changes are rare and a
 * ≤200 ms convergence is invisible.
 */
object ArmadaNetworkingFabric {

    private val BONDS_RL: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "armada_bonds")
    private val BONDS_TYPE = CustomPacketPayload.Type<ArmadaBondsPayload>(BONDS_RL)
    private val BONDS_CODEC: StreamCodec<FriendlyByteBuf, ArmadaBondsPayload> =
        StreamCodec.composite(ByteBufCodecs.BYTE_ARRAY, ArmadaBondsPayload::data) { ArmadaBondsPayload(it) }

    /** Dimensions whose last broadcast was non-empty, so we can emit exactly one trailing empty snapshot. */
    private val dimensionsWithBonds = HashSet<String>()

    /** Opaque byte blob so we can reuse VS's simple BYTE_ARRAY codec; layout is handled by [encode]/[decode]. */
    class ArmadaBondsPayload(val data: ByteArray) : CustomPacketPayload {
        override fun type() = BONDS_TYPE
    }

    /** Register the payload type on both sides. Called from onInitialize (runs on client and server). */
    fun registerCommon() {
        PayloadTypeRegistry.playS2C().register(BONDS_TYPE, BONDS_CODEC)
    }

    /** Register the client receiver. Called from the client initializer only. */
    @Environment(EnvType.CLIENT)
    fun registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(BONDS_TYPE) { payload, context ->
            val snapshot = decode(payload.data)
            context.client().execute { ArmadaClientBonds.update(snapshot) }
        }
    }

    /**
     * Server: broadcast this dimension's current bond list to its players. Call each world tick; it
     * self-throttles and self-silences when there are no bonds.
     */
    fun broadcastBonds(level: ServerLevel) {
        if (level.gameTime % 4L != 0L) return // ~5 Hz; bond changes are rare

        // Gather in common (ArmadaBindings), which owns the VS shipObjectWorld access + dimension key.
        val entries = ArmadaBindings.collectBonds(level)
        val dimKey = ArmadaBindings.dimKey(level)
        if (entries.isEmpty()) {
            // Emit one trailing empty snapshot to clear clients, then stay quiet for this dimension.
            if (!dimensionsWithBonds.remove(dimKey)) return
        } else {
            dimensionsWithBonds.add(dimKey)
        }

        val payload = ArmadaBondsPayload(encode(entries))
        for (player in level.players()) ServerPlayNetworking.send(player, payload)
    }

    private fun encode(entries: List<ArmadaBindings.ArmadaBondData>): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(entries.size)
        for (e in entries) {
            buf.writeLong(e.childId)
            buf.writeLong(e.parentId)
            buf.writeDouble(e.pos.x); buf.writeDouble(e.pos.y); buf.writeDouble(e.pos.z)
            buf.writeDouble(e.rot.x); buf.writeDouble(e.rot.y); buf.writeDouble(e.rot.z); buf.writeDouble(e.rot.w)
        }
        val arr = ByteArray(buf.readableBytes())
        buf.readBytes(arr)
        return arr
    }

    @Environment(EnvType.CLIENT)
    private fun decode(data: ByteArray): Map<Long, ArmadaClientBonds.Bond> {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val n = buf.readVarInt()
        val map = HashMap<Long, ArmadaClientBonds.Bond>(n)
        repeat(n) {
            val childId = buf.readLong()
            val parentId = buf.readLong()
            val pos = Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val rot = Quaterniond(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble())
            map[childId] = ArmadaClientBonds.Bond(parentId, pos, rot)
        }
        return map
    }
}
