package org.valkyrienskies.eureka.fabric

import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.crew.CrewDuties
import org.valkyrienskies.eureka.crew.CrewDuty
import org.valkyrienskies.eureka.crew.CrewManifest
import org.valkyrienskies.eureka.crew.CrewMarkers
import org.valkyrienskies.eureka.crew.CrewOperations
import net.minecraft.world.inventory.ChestMenu
import org.valkyrienskies.eureka.crew.CrewRoll
import org.valkyrienskies.eureka.crew.HoldRetag
import org.valkyrienskies.eureka.crew.HoldTag
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.crew.HelmNames
import org.valkyrienskies.eureka.crew.HoldLabelSync
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.ShipCrews
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmScreen
import org.valkyrienskies.eureka.crew.ShipStores
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.fabric.client.ClientCrewMarkers
import org.valkyrienskies.eureka.fabric.client.PathHud
import org.valkyrienskies.eureka.fabric.client.crew.CrewManifestScreen
import org.valkyrienskies.eureka.fabric.client.crew.HoldLabelClient
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

    // 1.20.1 Fabric networking speaks raw channel bufs; every send funnels its packed bytes through here.
    private fun wrap(data: ByteArray): FriendlyByteBuf = PacketByteBufs.create().also { it.writeByteArray(data) }

    private val ACTION_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "path_action")
    private val ROUTES_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "path_routes")
    private val LIVE_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "path_live")
    private val MESSAGE_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "path_message")
    private val CREW_MARKS_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_marks")
    private val CREW_MANIFEST_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_manifest")
    private val CREW_DETAIL_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_detail")
    private val CREW_ASK_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_detail_ask")
    private val CREW_RENAME_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_rename")
    private val CREW_DISMISS_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_dismiss")
    private val CREW_DUTY_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_duty")
    private val CREW_STATION_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_station")
    private val CREW_OPS_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_ops")
    private val CREW_STORES_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_stores")
    private val CREW_LIST_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_list")
    private val CREW_ROSTER_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "crew_roster")
    private val HELM_NAME_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "helm_name")
    private val BLUEPRINT_NAME_RL: ResourceLocation =
        ResourceLocation(EurekaMod.MOD_ID, "blueprint_name")
    private val SHIP_NAME_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "ship_name")
    private val HELM_ITEM_NAME_RL: ResourceLocation =
        ResourceLocation(EurekaMod.MOD_ID, "helm_item_name")
    private val HOLD_LABEL_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "hold_label")
    private val HOLD_TAG_RL: ResourceLocation = ResourceLocation(EurekaMod.MOD_ID, "hold_tag")



    /** Upper bound on a gun label's wire length -- "L12 - D3" is eight characters; twelve leaves room for absurd fleets. */
    private const val MAX_GUN_LABEL = 12

    /** Upper bound on an occupant name in the station dropdown; crew names are capped well under this. */
    private const val MAX_OCCUPANT_NAME = 64

    /** Entity ids of the crew to mark on this client's screen. An empty list puts the markers away. */
    /** A ship's crew manifest: who is aboard, in which berth, and how many berths this captain holds. */
    /** One crew member in full, trades included. Sent only when a player opens their card. */
    /** "Tell me about this one." */
    /** "Call this one that." */
    /** "This one is off the articles." Whether that is allowed is the server's business, not the button's. */
    /** "Put this one on that duty." Carries the duty the button landed on, not "next" -- see the sender. */
    /** "Seat this one at that gun." Carries the label the button landed on; "" stands them down. */
    /**
     * Every order the Operations tab can give, multiplexed onto one channel: an action byte after the
     * helm, then that action's few arguments. One payload rather than one per order because they are the
     * same shape end to end -- same gate, same tiny arguments, same "answer with fresh state" -- and the
     * byte-blob codec erases any type safety separate channels would pretend to add.
     */
    /** What the holds hold, for the Operations tab's readouts: powder, shot by kind, fuel by burn. */
    /** Every crew this captain owns, for the helm menu's list: which one is aboard, and what each would cost. */
    /** One crew's articles, read-only, for the Crews tab. */
    /** "Call this WHEEL that." Names the CREW, and is the key they are filed under. Not the ship. */
    /** Server -> client: which numbered box this chest screen is looking at, and what it is for. */
    /**
     * "Call this PAGE that." Renames the blueprint in the sender's hands.
     *
     * No position and no slot: the only blueprint a player can be reading is one they are holding, and a slot
     * index is a number a client could make up. The server looks in the two hands and nowhere else.
     */
    /**
     * "Call this WHEEL that." Renames the ship's helm in the sender's hands.
     *
     * The page's rename, one item over, and for the same reasons: no position because the wheel is not in
     * the world, and no slot because a slot index is a number a client could make up. The server looks in
     * the two hands and nowhere else.
     */
    /**
     * "Call this SHIP that." The helm menu's Rename button, off `/vs rename` and onto a payload.
     *
     * Carries the wheel's position rather than a ship id: the position is what the menu already knows, and it
     * is what the server can check a player is standing next to. A ship id would be a number a client could
     * make up.
     */
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

    /**
     * "Fire!" -- every manned gun aboard the ship the player is on.
     *
     * The only binding in the mod with NO sneak on it, and that is forced rather than chosen: a helm dismounts
     * any rider holding shift, so every SHIFT hotkey is usable only while standing on the deck. Ordering a
     * broadside from the deck and not from the wheel would be the wrong way round -- fighting a ship IS
     * steering it, and the captain giving the order is the one at the helm.
     *
     * Carries no target for the same reason [ACTION_FOLLOW_SHIP] does not: which ship, which guns and which
     * crew are all the server's to work out from where the player is standing.
     */
    const val ACTION_BROADSIDE: Byte = 8

    /**
     * CTRL+Sneak+C, and the controller's crouch + D-pad Left: sign on everyone the ship is carrying.
     *
     * Carries no target, and reads the same three ways [ACTION_CREW] does -- villager, wheel, or neither --
     * for the same reason: only the server can see what the crosshair is on. It differs from [ACTION_CREW]
     * only in what "neither" means, which is the whole feature. A crew of eighty was eighty presses before
     * this.
     *
     * The pad sends this one rather than [ACTION_CREW] because crouch + D-pad Left is a controller's only
     * crew gesture and hiring is what it is most often wanted for; a pad player keeps the individual toggle
     * by aiming at the villager, which is how they would reach for it anyway.
     */
    const val ACTION_CREW_ALL: Byte = 9

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

        // Both of these report whether the push went out, which is what lets ShipCrews fall back to the roster
        // in chat rather than leaving a client the payload cannot reach with a key that does nothing.
        CrewManifest.sender = { player, snapshot ->
            if (ServerPlayNetworking.canSend(player, CREW_MANIFEST_RL)) {
                ServerPlayNetworking.send(player, CREW_MANIFEST_RL, wrap(encodeManifest(snapshot)))
                true
            } else {
                false
            }
        }

        CrewManifest.detailSender = { player, detail ->
            if (ServerPlayNetworking.canSend(player, CREW_DETAIL_RL)) {
                ServerPlayNetworking.send(player, CREW_DETAIL_RL, wrap(encodeDetail(detail)))
                true
            } else {
                false
            }
        }

        CrewRoll.sender = { player, roll -> sendCrewRoll(player, roll) }
        CrewRoll.rosterSender = { player, roster -> sendCrewRoster(player, roster) }
        CrewOperations.storesSender = { player, helm, stores, decks, firing, memory ->
            if (ServerPlayNetworking.canSend(player, CREW_STORES_RL)) {
                ServerPlayNetworking.send(player, CREW_STORES_RL, wrap(encodeStores(helm, stores, decks, firing, memory)))
                true
            } else {
                false
            }
        }

        // Point :common's crew markers at the wire. Guarded on the client having declared the channel, like
        // every other S2C send here -- Fabric treats a send on an undeclared channel as an error, not a no-op.
        CrewMarkers.sender = { player, ids ->
            if (ServerPlayNetworking.canSend(player, CREW_MARKS_RL)) {
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeVarInt(ids.size)
                for (id in ids) buf.writeVarInt(id)
                ServerPlayNetworking.send(player, CREW_MARKS_RL, wrap(toArray(buf)))
            }
        }

        // Point `:common`'s feedback at the stacking HUD, but only for players whose client actually declared
        // the channel -- sending to one that didn't is an error, not a no-op. Anyone else keeps the action bar,
        // which is the whole reason PathMessages has a fallback.
        PathMessages.sender = { player, text, kind, topic, seconds ->
            if (ServerPlayNetworking.canSend(player, MESSAGE_RL)) {
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeByte(kind.ordinal)
                buf.writeUtf(text)
                // 0 means "hold it for however long the player configured"; anything else is this line
                // asking to be more perishable than the default.
                buf.writeFloat(seconds.toFloat())
                // APPENDED after the three fields an older build wrote, never inserted among them. The
                // channel id has not changed, so canSend is true for a client on an older jar, and the
                // payload codec is an opaque byte array that never checks a length -- so an old client
                // simply reads three fields and ignores the trailing byte. Insert it in the middle
                // instead and a version mismatch garbles the text rather than degrading gracefully.
                buf.writeByte(topic.ordinal)
                ServerPlayNetworking.send(player, MESSAGE_RL, wrap(toArray(buf)))
            } else {
                player.displayClientMessage(Component.literal(text).withStyle(kind.formatting), true)
            }
        }

        // The chest screen's number. Pushed once at open; see HoldLabelSync.
        HoldLabelSync.sender = { player, containerId, label, tags ->
            if (ServerPlayNetworking.canSend(player, HOLD_LABEL_RL)) {
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeVarInt(containerId)
                buf.writeUtf(label)
                buf.writeVarInt(tags)
                ServerPlayNetworking.send(player, HOLD_LABEL_RL, wrap(toArray(buf)))
            }
        }
    }

    /** Server: handle hotkey actions. Registered from the common initializer. */
    fun registerServer() {

        // The captain ticked a hold checkbox. Guarded on the menu the SERVER believes is open rather than on
        // anything the packet claims, so a hand-made packet cannot re-tag a box on the far side of the world.
        ServerPlayNetworking.registerGlobalReceiver(HOLD_TAG_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val containerId = buf.readVarInt()
            val ordinal = buf.readVarInt()
            server.execute {
                val tag = HoldTag.entries.getOrNull(ordinal) ?: return@execute
                val menu = player.containerMenu
                if (menu !is ChestMenu || menu.containerId != containerId) return@execute
                HoldRetag.toggle(menu.container, tag)
                HoldLabelSync.sender?.invoke(player, containerId, "", HoldRetag.maskOf(menu.container))
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ACTION_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val action = data.firstOrNull() ?: return@registerGlobalReceiver
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            server.execute {
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
                    ACTION_CREW_ALL -> ShipCrews.gestureAll(level, player)
                    ACTION_BROADSIDE -> CrewDuties.broadside(level, player)
                }
            }
        }

        // The helm position rides both of these rather than the server keeping a per-player handle on an open
        // manifest. That makes a stale or forged request simply a lookup that fails, and it means a player who
        // logs out with the screen open leaves nothing behind to clean up.
        ServerPlayNetworking.registerGlobalReceiver(CREW_ASK_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestDetail(level, player, helm, villager)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_RENAME_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            // Bounded on the way IN as well as on the way out: the server sanitises and caps the string, but a
            // buffer read is what a hostile client reaches first.
            val name = buf.readUtf(CrewManifest.MAX_NAME_LENGTH * 4)
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestRename(level, player, helm, villager, name)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_DISMISS_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestDismiss(level, player, helm, villager)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_DUTY_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            // byOrdinal, never entries[]: a client on a different build sends whatever ITS list said, and an
            // unknown duty has to read as "off duty" rather than throw on the netty thread.
            val duty = CrewDuty.byOrdinal(buf.readByte().toInt())
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestDuty(level, player, helm, villager, duty)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_STATION_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val villager = buf.readUUID()
            // Bounded read first, like every string a client hands us; labels are a handful of characters.
            val label = buf.readUtf(MAX_GUN_LABEL)
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                CrewManifest.requestStation(level, player, helm, villager, label)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(HELM_NAME_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val pos = buf.readBlockPos()
            // Read bound as well as sanitised: the server trims and caps the string, but the buffer read is
            // what a hostile client reaches first. x4 leaves room for multi-byte characters in the cap.
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                HelmNames.rename(level, player, pos, name)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(BLUEPRINT_NAME_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            server.execute { Blueprint.rename(player, name) }
        }

        ServerPlayNetworking.registerGlobalReceiver(HELM_ITEM_NAME_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            server.execute { HelmNames.renameHeld(player, name) }
        }

        ServerPlayNetworking.registerGlobalReceiver(SHIP_NAME_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val pos = buf.readBlockPos()
            val name = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                HelmNames.renameShip(level, player, pos, name)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(CREW_OPS_RL) { server, sender, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val player = sender as? ServerPlayer ?: return@registerGlobalReceiver
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val helm = buf.readLong()
            val action = buf.readByte()

            // Arguments decode on the netty thread, like every receiver here; anything malformed -- an
            // ordinal off the end of an enum, an unknown action -- is silence, not an exception. The work
            // itself hops to the server thread with the level re-resolved inside.
            val order: ((ServerLevel) -> Unit)? = when (action) {
                OPS_STORES -> { level: ServerLevel ->
                    CrewOperations.requestStores(level, player, helm)
                }
                OPS_FIRE_AT_WILL -> {
                    val on = buf.readBoolean()
                    ({ level: ServerLevel ->
                        CrewOperations.requestFireAtWill(level, player, helm, on)
                    })
                }
                OPS_ASSIGN_GUNNERS -> {
                    val count = buf.readByte().toInt()
                    val side = readSide(buf) ?: return@registerGlobalReceiver
                    val layer = readLayer(buf)
                    val mode = readMode(buf) ?: return@registerGlobalReceiver
                    ({ level: ServerLevel ->
                        CrewOperations.requestAssignGunners(level, player, helm, count, side, layer, mode)
                    })
                }
                OPS_ASSIGN_FIREFIGHTERS -> {
                    val count = buf.readByte().toInt()
                    val mode = readMode(buf) ?: return@registerGlobalReceiver
                    ({ level: ServerLevel ->
                        CrewOperations.requestAssignFirefighters(level, player, helm, count, mode)
                    })
                }
                OPS_RESTOCK_POWDER -> { level: ServerLevel ->
                    CrewOperations.requestRestockPowder(level, player, helm)
                }
                OPS_RESTOCK_SHOT -> {
                    val side = readSide(buf) ?: return@registerGlobalReceiver
                    val ball = Cannonball.entries.getOrNull(buf.readByte().toInt())
                        ?: return@registerGlobalReceiver
                    val charge = CannonCharge.entries.getOrNull(buf.readByte().toInt())
                        ?: return@registerGlobalReceiver
                    val layer = readLayer(buf)
                    ({ level: ServerLevel ->
                        CrewOperations.requestRestockShot(level, player, helm, side, ball, charge, layer)
                    })
                }
                OPS_REFUEL -> { level: ServerLevel ->
                    CrewOperations.requestRefuel(level, player, helm)
                }
                OPS_ELEVATION -> {
                    val side = readSide(buf) ?: return@registerGlobalReceiver
                    val index = buf.readByte().toInt()
                    val layer = readLayer(buf)
                    // Parenthesised: an open brace after a call would parse as its trailing lambda.
                    ({ level: ServerLevel ->
                        CrewOperations.requestElevation(level, player, helm, side, index, layer)
                    })
                }
                OPS_GUN_CHARGE -> {
                    val villager = buf.readUUID()
                    val ordinal = buf.readByte().toInt()
                    ({ level: ServerLevel ->
                        CrewOperations.requestGunCharge(level, player, helm, villager, ordinal)
                    })
                }
                OPS_GUN_ELEVATION -> {
                    val villager = buf.readUUID()
                    val index = buf.readByte().toInt()
                    ({ level: ServerLevel ->
                        CrewOperations.requestGunElevation(level, player, helm, villager, index)
                    })
                }
                OPS_GUN_AMMO -> {
                    val villager = buf.readUUID()
                    val ball = Cannonball.entries.getOrNull(buf.readByte().toInt())
                        ?: return@registerGlobalReceiver
                    val charge = CannonCharge.entries.getOrNull(buf.readByte().toInt())
                        ?: return@registerGlobalReceiver
                    { level: ServerLevel ->
                        CrewOperations.requestGunAmmo(level, player, helm, villager, ball, charge)
                    }
                }
                OPS_LOCK -> {
                    val villager = buf.readUUID()
                    val locked = buf.readBoolean()
                    ({ level: ServerLevel ->
                        CrewManifest.requestLock(level, player, helm, villager, locked)
                    })
                }
                OPS_SET_POWER -> {
                    val side = readSide(buf) ?: return@registerGlobalReceiver
                    val ordinal = buf.readByte().toInt()
                    val layer = readLayer(buf)
                    // Parenthesised: an open brace after a call would parse as its trailing lambda.
                    ({ level: ServerLevel ->
                        CrewOperations.requestPower(level, player, helm, side, ordinal, layer)
                    })
                }
                OPS_OPEN_HELM -> { level: ServerLevel ->
                    // The manifest's Back button: hand the player the helm menu whose book opened it.
                    val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity
                    if (wheel != null) ShipCrews.openHelm(level, player, wheel)
                }
                OPS_CREW_LIST_ASK -> { level: ServerLevel ->
                    sendCrewList(player, level, helm)
                }
                OPS_CREW_SELECT -> {
                    // Read on the netty thread with everything else, per this handler's rule. A cleared pick
                    // travels as the nil UUID rather than a flag byte -- one shape on the wire either way.
                    val picked: UUID = buf.readUUID()
                    ({ level: ServerLevel ->
                        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity
                        // Picking is not calling: no reach test, no fare, nothing moves. It is a note of what
                        // the dropdown says, so that Assemble -- a container button with no room for a crew id
                        // -- can read it. The ownership check that matters happens when the crew is called.
                        //
                        // Noted on the STATION when the wheel is on a ship: the terminals all show the
                        // station's dropdown (CrewRoll.build redirects there), so a pick made at one wheel
                        // must land where every other wheel reads. Off-ship there is no station and the
                        // clicked wheel is the one Assemble will read -- exactly where the note belongs.
                        if (wheel != null) {
                            val station = CrewStations.shipOf(level, wheel)
                                ?.let { CrewStations.stationOf(level, it) } ?: wheel
                            station.selectCrew(player.uuid, picked.takeIf { it != NO_CREW })
                        }
                        Unit
                    })
                }
                OPS_CREW_SUMMON -> {
                    val called: UUID = buf.readUUID()
                    ({ level: ServerLevel -> ShipCrews.summonCrew(level, player, helm, called) })
                }
                OPS_CREW_RETURN -> {
                    val sent: UUID = buf.readUUID()
                    ({ level: ServerLevel -> ShipCrews.returnCrew(level, player, helm, sent) })
                }
                OPS_CREW_ROSTER_ASK -> {
                    val asked: UUID = buf.readUUID()
                    ({ level: ServerLevel -> ShipCrews.requestCrewRoster(level, player, helm, asked) })
                }
                OPS_CREW_DISBAND -> {
                    val doomed: UUID = buf.readUUID()
                    ({ level: ServerLevel -> ShipCrews.disbandCrew(level, player, helm, doomed) })
                }
                OPS_CREW_RENAME -> {
                    val renamed: UUID = buf.readUUID()
                    val typed = buf.readUtf(HelmNames.MAX_NAME_LENGTH * 4)
                    ({ level: ServerLevel -> ShipCrews.renameCrew(level, player, helm, renamed, typed) })
                }
                else -> null
            }
            if (order == null) return@registerGlobalReceiver

            server.execute {
                val level = player.level() as? ServerLevel ?: return@execute
                order(level)
            }
        }
    }

    private fun readSide(buf: FriendlyByteBuf): CrewOperations.Side? =
        CrewOperations.Side.entries.getOrNull(buf.readByte().toInt())

    /** A deck scope off the wire: 0 is every deck, anything hostile is clamped rather than trusted. */
    private fun readLayer(buf: FriendlyByteBuf): Int = buf.readByte().toInt().coerceIn(0, MAX_LAYERS)

    private fun readMode(buf: FriendlyByteBuf): CrewOperations.AssignMode? =
        CrewOperations.AssignMode.entries.getOrNull(buf.readByte().toInt())

    @Environment(EnvType.CLIENT)
    fun registerClient() {
        // The helm menu lives in :common and cannot reach this package, so it names a wheel through this seam.
        // Installed here rather than at the screen, so a screen opened before the client is ready still finds
        // a live sender rather than the no-op default.
        HelmNames.clientSender = { pos, name -> sendHelmName(pos, name) }
        HelmNames.clientShipSender = { pos, name -> sendShipName(pos, name) }
        HelmNames.clientItemNameSender = { name -> sendHelmItemName(name) }

        ClientPlayNetworking.registerGlobalReceiver(ROUTES_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val routes = decodeRoutes(data)
            client.execute { ClientPathState.replaceRoutes(routes) }
        }
        ClientPlayNetworking.registerGlobalReceiver(LIVE_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val update = decodeLive(data)
            client.execute { update.apply() }
        }
        // Markers are the only client state here keyed on entity ids, which are per-connection: carrying a set
        // into the next world would draw plates over whatever happened to inherit those ids.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientCrewMarkers.clear()
            // The Operations tab's assignment modes are remembered for the session, not for ever: leaving
            // the world puts both toggles back on the safe one rather than carrying a Release into the
            // next ship a player opens a book on.
            CrewManifestScreen.forgetModes()
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_MARKS_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val ids = IntArray(buf.readVarInt()) { buf.readVarInt() }
            client.execute { ClientCrewMarkers.replace(ids) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_MANIFEST_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val snapshot = decodeManifest(data)
            client.execute { CrewManifestScreen.open(snapshot) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_DETAIL_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            // Decoded on the netty thread, so the registries come from the connection rather than from a client
            // field that may be mid-swap.
            val detail = decodeDetail(data)
            client.execute { CrewManifestScreen.acceptDetail(detail) }
        }

        CrewRoll.clientAsk = { helm -> sendCrewListAsk(helm) }
        CrewRoll.clientSelect = { helm, crew -> sendCrewSelect(helm, crew) }
        CrewRoll.clientSummon = { helm, crew -> sendCrewSummon(helm, crew) }
        CrewRoll.clientReturn = { helm, crew -> sendCrewReturn(helm, crew) }
        HoldLabelClient.sender = { containerId, ordinal -> sendHoldTag(containerId, ordinal) }
        CrewRoll.clientRosterAsk = { helm, crew -> sendCrewRosterAsk(helm, crew) }
        CrewRoll.clientDisband = { helm, crew -> sendCrewDisband(helm, crew) }
        CrewRoll.clientRenameCrew = { helm, crew, name -> sendCrewRenameById(helm, crew, name) }

        ClientPlayNetworking.registerGlobalReceiver(CREW_ROSTER_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val roster = try {
                decodeCrewRoster(data)
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("vs_eureka")
                    .error("Crew-roster payload failed to decode on {} bytes", data.size, t)
                return@registerGlobalReceiver
            }
            client.execute { CrewManifestScreen.acceptCrewRoster(roster) }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_LIST_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val roll = try {
                decodeCrewList(data)
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("vs_eureka")
                    .error("Crew-list payload failed to decode on {} bytes", data.size, t)
                return@registerGlobalReceiver
            }
            client.execute {
                // Both screens read the same roll: the helm menu's dropdown and the book's Crews tab. Each
                // ignores it unless it is about the wheel they are open on.
                ShipHelmScreen.acceptCrewRoll(roll)
                CrewManifestScreen.acceptCrewRoll(roll)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(CREW_STORES_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            // A malformed tally is worth a line: it is silent on screen otherwise, and the tab simply
            // sits on "Reading the holds" forever with nothing to say why.
            val update = try {
                decodeStores(data)
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("vs_eureka")
                    .error("Stores payload failed to decode on {} bytes", data.size, t)
                return@registerGlobalReceiver
            }
            client.execute {
                CrewManifestScreen.acceptStores(update.helm, update.stores, update.decks, update.firing, update.memory)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(HOLD_LABEL_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val containerId = buf.readVarInt()
            val label = buf.readUtf()
            val tags = buf.readVarInt()
            client.execute { HoldLabelClient.accept(containerId, label, tags) }
        }
        ClientPlayNetworking.registerGlobalReceiver(MESSAGE_RL) { client, _, rawBuf, _ ->
            val data = rawBuf.readByteArray()
            val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
            val kind = PathMessages.Kind.entries.getOrElse(buf.readByte().toInt()) { PathMessages.Kind.GOOD }
            val text = buf.readUtf()
            val seconds = buf.readFloat()
            // Read defensively, so the channel survives a version mismatch in BOTH directions: an older
            // server writes nothing here and `isReadable` is false, and an ordinal from some other build's
            // enum falls through getOrElse. Either way the answer is ALWAYS -- a message nobody can
            // classify is a message that shows, which is the safe way for this to fail.
            val topic = if (buf.isReadable) {
                PathMessages.Topic.entries.getOrElse(buf.readByte().toInt()) { PathMessages.Topic.ALWAYS }
            } else {
                PathMessages.Topic.ALWAYS
            }
            client.execute {
                if (kind == PathMessages.Kind.PROMPT) PathHud.prompt(Component.literal(text), topic)
                else PathHud.add(Component.literal(text), kind.argb, topic, seconds)
            }
        }
    }

    /** Client: ask the server to perform one of the hotkey actions. */
    @Environment(EnvType.CLIENT)
    fun sendAction(action: Byte) {
        ClientPlayNetworking.send(ACTION_RL, wrap(byteArrayOf(action)))
    }

    /** Client: ask for one crew member's card. Answered by a [CrewDetailPayload], or by silence. */
    @Environment(EnvType.CLIENT)
    fun sendCrewAsk(helm: Long, villager: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        ClientPlayNetworking.send(CREW_ASK_RL, wrap(toArray(buf)))
    }

    /** Client: rename one crew member. An empty name asks for the berth's default back. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRename(helm: Long, villager: UUID, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        buf.writeUtf(name.take(CrewManifest.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(CREW_RENAME_RL, wrap(toArray(buf)))
    }

    /** Client: strike one crew member off the articles. */
    @Environment(EnvType.CLIENT)
    fun sendCrewDismiss(helm: Long, villager: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        ClientPlayNetworking.send(CREW_DISMISS_RL, wrap(toArray(buf)))
    }

    /**
     * Client: put one crew member on a duty.
     *
     * Sends the duty the button has ARRIVED at rather than "advance one", so two clicks that cross in flight
     * cannot leave the server one step out from what the player is looking at.
     */
    @Environment(EnvType.CLIENT)
    fun sendCrewDuty(helm: Long, villager: UUID, duty: CrewDuty) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        buf.writeByte(duty.ordinal)
        ClientPlayNetworking.send(CREW_DUTY_RL, wrap(toArray(buf)))
    }

    /**
     * Client: seat one crew member at the gun answering to [label], or stand them down with "".
     *
     * Absolute for the same reason the duty is: two clicks crossing in flight must land on what the player
     * is looking at, not one step past it.
     */
    @Environment(EnvType.CLIENT)
    fun sendCrewStation(helm: Long, villager: UUID, label: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeUUID(villager)
        buf.writeUtf(label.take(MAX_GUN_LABEL))
        ClientPlayNetworking.send(CREW_STATION_RL, wrap(toArray(buf)))
    }

    // region crew operations wire

    // The Operations tab's action bytes. Ordinals are the wire format; append only.
    private const val OPS_STORES: Byte = 0
    private const val OPS_ASSIGN_GUNNERS: Byte = 1
    private const val OPS_ASSIGN_FIREFIGHTERS: Byte = 2
    private const val OPS_RESTOCK_POWDER: Byte = 3
    private const val OPS_RESTOCK_SHOT: Byte = 4
    private const val OPS_ELEVATION: Byte = 5
    private const val OPS_REFUEL: Byte = 6
    private const val OPS_GUN_CHARGE: Byte = 7
    private const val OPS_GUN_ELEVATION: Byte = 8
    private const val OPS_GUN_AMMO: Byte = 9
    private const val OPS_LOCK: Byte = 10
    private const val OPS_SET_POWER: Byte = 11
    private const val OPS_OPEN_HELM: Byte = 12
    private const val OPS_FIRE_AT_WILL: Byte = 13
    private const val OPS_CREW_LIST_ASK: Byte = 14
    private const val OPS_CREW_SELECT: Byte = 15
    private const val OPS_CREW_SUMMON: Byte = 16
    private const val OPS_CREW_ROSTER_ASK: Byte = 17
    private const val OPS_CREW_DISBAND: Byte = 18
    private const val OPS_CREW_RENAME: Byte = 19
    private const val OPS_CREW_RETURN: Byte = 20

    /** Upper bound on a fuel item id's wire length; registry ids are far shorter. */
    private const val MAX_ITEM_ID = 256

    /** One writer for every Operations order: the helm, the action byte, then that action's arguments. */
    @Environment(EnvType.CLIENT)
    private inline fun sendOps(helm: Long, action: Byte, write: (FriendlyByteBuf) -> Unit) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeByte(action.toInt())
        write(buf)
        ClientPlayNetworking.send(CREW_OPS_RL, wrap(toArray(buf)))
    }

    /** Client: ask what the holds hold. Answered by a [CrewStoresPayload], or by silence. */
    @Environment(EnvType.CLIENT)
    fun sendCrewStoresAsk(helm: Long) = sendOps(helm, OPS_STORES) {}

    /** Client: ask which crews this captain has. Answered by a [CrewListPayload], or by silence. */
    @Environment(EnvType.CLIENT)
    fun sendCrewListAsk(helm: Long) = sendOps(helm, OPS_CREW_LIST_ASK) {}

    /**
     * Client: note which crew the helm menu's list is showing. Costs nothing and moves nobody.
     *
     * [crew] is null to clear the pick, which travels as the nil UUID -- one shape on the wire either way.
     */
    @Environment(EnvType.CLIENT)
    fun sendCrewSelect(helm: Long, crew: UUID?) = sendOps(helm, OPS_CREW_SELECT) {
        it.writeUUID(crew ?: NO_CREW)
    }

    /** Client: call [crew] to this ship, paying their passage. */
    @Environment(EnvType.CLIENT)
    fun sendCrewSummon(helm: Long, crew: UUID) = sendOps(helm, OPS_CREW_SUMMON) { it.writeUUID(crew) }

    /** Client: send this wheel's crew back to the articles. */
    @Environment(EnvType.CLIENT)
    fun sendCrewReturn(helm: Long, crew: UUID) = sendOps(helm, OPS_CREW_RETURN) { it.writeUUID(crew) }

    /** Client: tick or untick one hold tag on the open chest screen. */
    @Environment(EnvType.CLIENT)
    fun sendHoldTag(containerId: Int, ordinal: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(containerId)
        buf.writeVarInt(ordinal)
        ClientPlayNetworking.send(HOLD_TAG_RL, wrap(toArray(buf)))
    }

    /** Client: ask for one crew's articles. Answered by a [CrewRosterPayload], or by silence. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRosterAsk(helm: Long, crew: UUID) = sendOps(helm, OPS_CREW_ROSTER_ASK) { it.writeUUID(crew) }

    /** Client: strike [crew] off and destroy its members. Final. */
    @Environment(EnvType.CLIENT)
    fun sendCrewDisband(helm: Long, crew: UUID) = sendOps(helm, OPS_CREW_DISBAND) { it.writeUUID(crew) }

    /** Client: rename [crew] by id, whether or not they are on this ship. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRenameById(helm: Long, crew: UUID, name: String) = sendOps(helm, OPS_CREW_RENAME) {
        it.writeUUID(crew)
        it.writeUtf(name)
    }

    /**
     * "No crew", on the wire.
     *
     * A nil UUID rather than a flag byte, so a cleared pick and a real one are the same eight-plus-eight
     * bytes and the reader has one shape to parse. Nothing can ever be filed under it: [UUID.randomUUID]
     * does not produce it, and the ledger only ever stores what it minted.
     */
    // The sentinel lives on CrewRoll now, so the screen can offer it as a row and the wire keeps meaning
    // exactly what it always meant.
    private val NO_CREW: UUID get() = CrewRoll.NO_CREW

    /** Server: send [captain] the crews they can call at this wheel. */
    private fun sendCrewList(captain: ServerPlayer, level: ServerLevel, helm: Long) {
        // A wheel in the hand has no position to look up, and needs none: the crews are the captain's own.
        if (helm == CrewManifest.HELM_IN_HAND) {
            sendCrewRoll(captain, CrewRoll.buildInHand(captain))
            return
        }
        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return
        sendCrewRoll(captain, CrewRoll.build(captain, wheel))
    }

    private fun sendCrewRoll(captain: ServerPlayer, roll: CrewRoll.Roll) {
        if (!ServerPlayNetworking.canSend(captain, CREW_LIST_RL)) return
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(roll.helm)
        buf.writeUtf(roll.shipName)
        buf.writeVarInt(roll.entries.size)
        for (entry in roll.entries) {
            buf.writeUUID(entry.id)
            buf.writeUtf(entry.name)
            buf.writeVarInt(entry.heads)
            buf.writeBoolean(entry.aboard)
            buf.writeVarInt(entry.present)
            buf.writeVarInt(entry.fare)
        }
        ServerPlayNetworking.send(captain, CREW_LIST_RL, wrap(toArray(buf)))
    }

    /** Server: send [captain] one crew's articles. Rows travel in the same shape the manifest uses. */
    private fun sendCrewRoster(captain: ServerPlayer, roster: CrewRoll.Roster) {
        if (!ServerPlayNetworking.canSend(captain, CREW_ROSTER_RL)) return
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(roster.id)
        buf.writeUtf(roster.name)
        buf.writeVarInt(roster.fare)
        writeCrewRows(buf, roster.rows)
        ServerPlayNetworking.send(captain, CREW_ROSTER_RL, wrap(toArray(buf)))
    }

    private fun decodeCrewRoster(data: ByteArray): CrewRoll.Roster {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val id = buf.readUUID()
        val name = buf.readUtf()
        val fare = buf.readVarInt()
        return CrewRoll.Roster(id, name, fare, readCrewRows(buf))
    }

    /**
     * The manifest's row list, on the wire. One writer and one reader for both payloads that carry rows, so
     * the ship's roster and a crew's own articles cannot drift into two nearly-identical encodings.
     */
    private fun writeCrewRows(buf: FriendlyByteBuf, rows: List<CrewManifest.Row>) {
        buf.writeVarInt(rows.size)
        for (row in rows) {
            buf.writeVarInt(row.slot)
            buf.writeUUID(row.villager)
            buf.writeVarInt(row.entityId)
            buf.writeUtf(row.profession)
            buf.writeUtf(row.villagerType)
            buf.writeVarInt(row.level)
            buf.writeUtf(row.name)
            buf.writeByte(row.duty.ordinal)
            buf.writeBoolean(row.locked)
        }
    }

    private fun readCrewRows(buf: FriendlyByteBuf): List<CrewManifest.Row> = List(buf.readVarInt()) {
        CrewManifest.Row(
            slot = buf.readVarInt(),
            villager = buf.readUUID(),
            entityId = buf.readVarInt(),
            profession = buf.readUtf(),
            villagerType = buf.readUtf(),
            level = buf.readVarInt(),
            name = buf.readUtf(),
            duty = CrewDuty.byOrdinal(buf.readByte().toInt()),
            locked = buf.readBoolean()
        )
    }

    private fun decodeCrewList(data: ByteArray): CrewRoll.Roll {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val helm = buf.readLong()
        val shipName = buf.readUtf()
        val entries = List(buf.readVarInt()) {
            CrewRoll.Entry(
                id = buf.readUUID(),
                name = buf.readUtf(),
                heads = buf.readVarInt(),
                aboard = buf.readBoolean(),
                present = buf.readVarInt(),
                fare = buf.readVarInt()
            )
        }
        return CrewRoll.Roll(helm, shipName, entries)
    }

    /** Client: give or lift the Fire at Will order -- the gun crews lay their own guns. */
    @Environment(EnvType.CLIENT)
    fun sendCrewFireAtWill(helm: Long, on: Boolean) = sendOps(helm, OPS_FIRE_AT_WILL) { it.writeBoolean(on) }

    /** Client: work [side]'s guns on deck [layer] (0 = all) per [mode], to a total of [count] manned. */
    @Environment(EnvType.CLIENT)
    fun sendCrewAssignGunners(
        helm: Long,
        count: Int,
        side: CrewOperations.Side,
        layer: Int,
        mode: CrewOperations.AssignMode
    ) = sendOps(helm, OPS_ASSIGN_GUNNERS) {
        it.writeByte(count.coerceIn(0, MAX_OPS_COUNT))
        it.writeByte(side.ordinal)
        it.writeByte(layer.coerceIn(0, MAX_LAYERS))
        it.writeByte(mode.ordinal)
    }

    /** Client: post the fire watch per [mode] -- [count] firefighters in total. */
    @Environment(EnvType.CLIENT)
    fun sendCrewAssignFirefighters(helm: Long, count: Int, mode: CrewOperations.AssignMode) =
        sendOps(helm, OPS_ASSIGN_FIREFIGHTERS) {
            it.writeByte(count.coerceIn(0, MAX_OPS_COUNT))
            it.writeByte(mode.ordinal)
        }

    /** Client: run powder to every gun aboard from the holds, split evenly. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRestockPowder(helm: Long) = sendOps(helm, OPS_RESTOCK_POWDER) {}

    /** Client: run the chosen round to [side]'s battery on deck [layer] (0 = all) from the holds. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRestockShot(helm: Long, side: CrewOperations.Side, ball: Cannonball, charge: CannonCharge, layer: Int) =
        sendOps(helm, OPS_RESTOCK_SHOT) {
            it.writeByte(side.ordinal)
            it.writeByte(ball.ordinal)
            it.writeByte(charge.ordinal)
            it.writeByte(layer.coerceIn(0, MAX_LAYERS))
        }

    /** Client: stoke every engine aboard from the holds, best fuel first. */
    @Environment(EnvType.CLIENT)
    fun sendCrewRefuel(helm: Long) = sendOps(helm, OPS_REFUEL) {}

    /** Client: lay [side]'s battery on deck [layer] ([CrewOperations.Side.BOTH] = all sides, 0 = all decks) to elevation [index], 0..18. */
    @Environment(EnvType.CLIENT)
    fun sendCrewSetElevation(helm: Long, side: CrewOperations.Side, index: Int, layer: Int) =
        sendOps(helm, OPS_ELEVATION) {
            it.writeByte(side.ordinal)
            it.writeByte(index.coerceIn(0, 18))
            it.writeByte(layer.coerceIn(0, MAX_LAYERS))
        }

    /** Client: set [side]'s battery on deck [layer] (0 = all decks) to powder charge [ordinal], 0..2. */
    @Environment(EnvType.CLIENT)
    fun sendCrewSetPower(helm: Long, side: CrewOperations.Side, ordinal: Int, layer: Int) =
        sendOps(helm, OPS_SET_POWER) {
            it.writeByte(side.ordinal)
            it.writeByte(ordinal.coerceIn(0, 2))
            it.writeByte(layer.coerceIn(0, MAX_LAYERS))
        }

    /** Client: set one gunner's cannon to a powder charge. Absolute, like the duty button. */
    @Environment(EnvType.CLIENT)
    fun sendCrewGunCharge(helm: Long, villager: UUID, ordinal: Int) =
        sendOps(helm, OPS_GUN_CHARGE) {
            it.writeUUID(villager)
            it.writeByte(ordinal.coerceIn(0, 2))
        }

    /** Client: lay one gunner's cannon to elevation [index], 0..18. */
    @Environment(EnvType.CLIENT)
    fun sendCrewGunElevation(helm: Long, villager: UUID, index: Int) =
        sendOps(helm, OPS_GUN_ELEVATION) {
            it.writeUUID(villager)
            it.writeByte(index.coerceIn(0, 18))
        }

    /** Client: arm one gunner's cannon with the chosen round from the holds. */
    @Environment(EnvType.CLIENT)
    fun sendCrewGunAmmo(helm: Long, villager: UUID, ball: Cannonball, charge: CannonCharge) =
        sendOps(helm, OPS_GUN_AMMO) {
            it.writeUUID(villager)
            it.writeByte(ball.ordinal)
            it.writeByte(charge.ordinal)
        }

    /** Client: reopen the helm menu the manifest's book came from. */
    @Environment(EnvType.CLIENT)
    fun sendCrewOpenHelm(helm: Long) = sendOps(helm, OPS_OPEN_HELM) {}

    /** Client: set or lift the lock on one crew member. */
    @Environment(EnvType.CLIENT)
    fun sendCrewLock(helm: Long, villager: UUID, locked: Boolean) =
        sendOps(helm, OPS_LOCK) {
            it.writeUUID(villager)
            it.writeBoolean(locked)
        }

    /** A count byte's honest ceiling; berth caps live far below it. */
    private const val MAX_OPS_COUNT = 127

    private fun encodeStores(
        helm: Long,
        stores: ShipStores.Stores,
        decks: List<Int>,
        firing: Boolean,
        memory: CrewOperations.OpsMemory?
    ): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(helm)
        buf.writeVarInt(stores.gunpowder)
        buf.writeVarInt(stores.ammo.size)
        for (ammo in stores.ammo) {
            buf.writeByte(ammo.ball.ordinal)
            buf.writeByte(ammo.charge.ordinal)
            buf.writeVarInt(ammo.count)
        }
        buf.writeVarInt(stores.fuels.size)
        for (fuel in stores.fuels) {
            buf.writeUtf(fuel.itemId.take(MAX_ITEM_ID))
            buf.writeVarInt(fuel.count)
            buf.writeVarInt(fuel.burnTicks)
        }
        // Guns per deck, keel up -- what the screen's deck dropdowns list. Rides the stores payload
        // because it changes with the same gestures the holds do, and this payload already refreshes on
        // screen-open and after every ops order.
        buf.writeVarInt(decks.size.coerceAtMost(MAX_LAYERS))
        for (deck in decks.take(MAX_LAYERS)) buf.writeVarInt(deck)
        // Whether this ship is under Fire at Will, so the tab's toggle shows the ORDER rather than what
        // the last person to press it happened to want. Rides here for the same reason the deck counts
        // do: the payload already refreshes on screen-open and after every ops order.
        buf.writeBoolean(firing)
        // The book's ship-side memory, appended last (see CrewOperations.OpsMemory). A presence byte
        // rather than sentinel-stuffing every field: absent means no order has ever fired on this hull,
        // which the screen reads as "keep the captain's own habits".
        buf.writeBoolean(memory != null)
        if (memory != null) {
            buf.writeVarInt(memory.gunnerCount)
            buf.writeVarInt(memory.fireCount)
            buf.writeByte(memory.crewSide)
            buf.writeByte(memory.ctrlSide)
            buf.writeByte(memory.shotSide)
            buf.writeByte(memory.crewMode)
            buf.writeByte(memory.fireMode)
            buf.writeByte(memory.ammoBall)
            buf.writeByte(memory.ammoCharge)
            buf.writeByte(memory.crewLayer.coerceIn(0, MAX_LAYERS))
            buf.writeByte(memory.ctrlLayer.coerceIn(0, MAX_LAYERS))
            buf.writeByte(memory.shotLayer.coerceIn(0, MAX_LAYERS))
        }
        return toArray(buf)
    }

    class StoresUpdate(
        val helm: Long,
        val stores: ShipStores.Stores,
        val decks: List<Int>,
        val firing: Boolean,
        val memory: CrewOperations.OpsMemory?
    )

    private fun decodeStores(data: ByteArray): StoresUpdate {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val helm = buf.readLong()
        val gunpowder = buf.readVarInt()

        val ammoCount = buf.readVarInt().coerceIn(0, MAX_AMMO_KINDS)
        val ammo = ArrayList<ShipStores.AmmoCount>(ammoCount)
        repeat(ammoCount) {
            val ball = Cannonball.entries.getOrNull(buf.readByte().toInt())
            val charge = CannonCharge.entries.getOrNull(buf.readByte().toInt())
            val count = buf.readVarInt()
            if (ball != null && charge != null) ammo.add(ShipStores.AmmoCount(ball, charge, count))
        }

        val fuelCount = buf.readVarInt().coerceIn(0, ShipStores.MAX_FUEL_KINDS)
        val fuels = ArrayList<ShipStores.FuelCount>(fuelCount)
        repeat(fuelCount) {
            fuels.add(ShipStores.FuelCount(buf.readUtf(MAX_ITEM_ID), buf.readVarInt(), buf.readVarInt()))
        }

        val deckCount = buf.readVarInt().coerceIn(0, MAX_LAYERS)
        val decks = List(deckCount) { buf.readVarInt() }

        val firing = buf.readBoolean()
        // Ordinals are handed on raw: the screen resolves them through entries.getOrNull, so a value from
        // some other build's enum order degrades to "no seed" rather than to a wrong widget.
        val memory = if (buf.isReadable && buf.readBoolean()) {
            CrewOperations.OpsMemory(
                gunnerCount = buf.readVarInt().coerceIn(0, MAX_OPS_COUNT),
                fireCount = buf.readVarInt().coerceIn(0, MAX_OPS_COUNT),
                crewSide = buf.readByte().toInt(),
                ctrlSide = buf.readByte().toInt(),
                shotSide = buf.readByte().toInt(),
                crewMode = buf.readByte().toInt(),
                fireMode = buf.readByte().toInt(),
                ammoBall = buf.readByte().toInt(),
                ammoCharge = buf.readByte().toInt(),
                crewLayer = buf.readByte().toInt().coerceIn(0, MAX_LAYERS),
                ctrlLayer = buf.readByte().toInt().coerceIn(0, MAX_LAYERS),
                shotLayer = buf.readByte().toInt().coerceIn(0, MAX_LAYERS)
            )
        } else {
            null
        }
        return StoresUpdate(helm, ShipStores.Stores(gunpowder, ammo, fuels), decks, firing, memory)
    }

    /** Decode bound on shot kinds; the item set is 15 today, and a hostile length is clamped, not trusted. */
    private const val MAX_AMMO_KINDS = 64

    /** Decode bound on gun decks; a real ship carries a handful, and a hostile count is clamped, not trusted. */
    private const val MAX_LAYERS = 64

    // endregion

    /** Client: name the wheel at [pos]. An empty name clears it back to blank. */
    @Environment(EnvType.CLIENT)
    fun sendHelmName(pos: BlockPos, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(HELM_NAME_RL, wrap(toArray(buf)))
    }

    /** Client: rename the blueprint in hand. Blank clears it back to the name the page was drawn under. */
    @Environment(EnvType.CLIENT)
    fun sendBlueprintName(name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(BLUEPRINT_NAME_RL, wrap(toArray(buf)))
    }

    /** Client: name the ship's wheel in hand. Blank clears it back to blank. */
    @Environment(EnvType.CLIENT)
    fun sendHelmItemName(name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(HELM_ITEM_NAME_RL, wrap(toArray(buf)))
    }

    /** Client: name the SHIP that the wheel at [pos] belongs to. */
    @Environment(EnvType.CLIENT)
    fun sendShipName(pos: BlockPos, name: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeUtf(name.take(HelmNames.MAX_NAME_LENGTH))
        ClientPlayNetworking.send(SHIP_NAME_RL, wrap(toArray(buf)))
    }

    // region crew manifest codecs

    private fun encodeManifest(snapshot: CrewManifest.Snapshot): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(snapshot.ship)
        buf.writeUtf(snapshot.crew)
        buf.writeLong(snapshot.helm)
        // NOTE: the rows themselves go through writeCrewRows below, shared with the Crews tab's roster.
        buf.writeVarInt(snapshot.berths)
        buf.writeVarInt(snapshot.maxBerths)
        buf.writeBoolean(snapshot.readOnly)
        writeCrewRows(buf, snapshot.rows)
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeManifest(data: ByteArray): CrewManifest.Snapshot {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val ship = buf.readUtf()
        val crew = buf.readUtf()
        val helm = buf.readLong()
        val berths = buf.readVarInt()
        val maxBerths = buf.readVarInt()
        val readOnly = buf.readBoolean()
        return CrewManifest.Snapshot(ship, crew, helm, berths, maxBerths, readCrewRows(buf), readOnly)
    }

    /**
     * The card, including whole trade stacks.
     *
     * On 1.20.1 an ItemStack rides a plain buffer (writeItem/readItem carries the full NBT), so the trade
     * stacks need no registry-aware buffer at all -- the 1.21.1 OPTIONAL_STREAM_CODEC dance collapses away.
     */
    private fun encodeDetail(detail: CrewManifest.Detail): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(detail.villager)
        buf.writeUtf(detail.name)
        buf.writeUtf(detail.profession)
        buf.writeVarInt(detail.level)
        buf.writeVarInt(detail.xp)
        buf.writeBoolean(detail.aboard)
        buf.writeByte(detail.duty.ordinal)
        buf.writeVarInt(detail.guns)
        buf.writeVarInt(detail.gunners)
        buf.writeVarInt(detail.fireParty)
        buf.writeUtf(detail.stationLabel)
        buf.writeVarInt(detail.gunOptions.size)
        for (option in detail.gunOptions) {
            buf.writeUtf(option.label)
            buf.writeUtf(option.occupant.take(MAX_OCCUPANT_NAME))
        }
        buf.writeVarInt(detail.offers.size)
        for (offer in detail.offers) {
            buf.writeItem(offer.costA)
            buf.writeItem(offer.costB)
            buf.writeItem(offer.result)
            buf.writeVarInt(offer.uses)
            buf.writeVarInt(offer.maxUses)
            buf.writeBoolean(offer.outOfStock)
        }
        buf.writeBoolean(detail.locked)
        buf.writeByte(detail.chargeOrdinal)
        buf.writeByte(detail.elevationIndex)
        buf.writeByte(detail.ammoBall)
        buf.writeByte(detail.ammoCharge)
        buf.writeVarInt(detail.ammoCount)
        return toArray(buf)
    }

    @Environment(EnvType.CLIENT)
    private fun decodeDetail(data: ByteArray): CrewManifest.Detail {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
        val villager = buf.readUUID()
        val name = buf.readUtf()
        val profession = buf.readUtf()
        val level = buf.readVarInt()
        val xp = buf.readVarInt()
        val aboard = buf.readBoolean()
        val duty = CrewDuty.byOrdinal(buf.readByte().toInt())
        val guns = buf.readVarInt()
        val gunners = buf.readVarInt()
        val fireParty = buf.readVarInt()
        val stationLabel = buf.readUtf(MAX_GUN_LABEL)
        val gunOptions = List(buf.readVarInt()) {
            CrewManifest.GunOption(buf.readUtf(MAX_GUN_LABEL), buf.readUtf(MAX_OCCUPANT_NAME))
        }
        val offers = List(buf.readVarInt()) {
            CrewManifest.Offer(
                costA = buf.readItem(),
                costB = buf.readItem(),
                result = buf.readItem(),
                uses = buf.readVarInt(),
                maxUses = buf.readVarInt(),
                outOfStock = buf.readBoolean()
            )
        }
        val locked = buf.readBoolean()
        val chargeOrdinal = buf.readByte().toInt()
        val elevationIndex = buf.readByte().toInt()
        val ammoBall = buf.readByte().toInt()
        val ammoCharge = buf.readByte().toInt()
        val ammoCount = buf.readVarInt()
        return CrewManifest.Detail(
            villager, name, profession, level, xp, offers, aboard, duty, guns, gunners, fireParty,
            stationLabel, gunOptions, locked, chargeOrdinal, elevationIndex, ammoBall, ammoCharge, ammoCount
        )
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
            val data = encodeRoutes(store)
            for (player in level.players()) {
                if (player.uuid in state.holders && canReceive(player, ROUTES_RL)) {
                    ServerPlayNetworking.send(player, ROUTES_RL, wrap(data))
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
        var data: ByteArray? = null
        for (player in level.players()) {
            if (!canReceive(player, ROUTES_RL)) continue
            here.add(player.uuid)
            if (player.uuid in state.holders) continue
            // Encoded at most once per broadcast, and only when somebody actually needs it.
            if (data == null) data = encodeRoutes(store)
            ServerPlayNetworking.send(player, ROUTES_RL, wrap(data))
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
    private fun canReceive(player: ServerPlayer, channel: ResourceLocation) =
        ServerPlayNetworking.canSend(player, channel)

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
            if (!canReceive(player, LIVE_RL)) continue
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

            ServerPlayNetworking.send(player, LIVE_RL, wrap(toArray(buf)))
        }

        // Players who aren't recording still need follower state (to draw a flown route), but only when there
        // is any, and only when nobody already sent it to them above.
        if (followers.isNotEmpty()) {
            val recordingPlayers = recorders.mapTo(HashSet()) { it.playerId }
            for (player in level.players()) {
                if (player.uuid in recordingPlayers) continue
                // A player who joined into a dimension where a ship is already flying a route hits this on their
                // first broadcast, which is exactly the window where the channel may not be declared yet.
                if (!canReceive(player, LIVE_RL)) continue
                // Built per player rather than once, because of the last field: which route the player's OWN
                // ship is flying. That is what lets SHIFT+H mean "hide the line I'm riding" instead of the
                // global show-all toggle, and only the server can resolve a player to their ship (and a
                // child of an armada to its parent).
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeVarInt(0)
                writeFollowers(buf, followers)
                buf.writeLong(localRoute(level, player))
                ServerPlayNetworking.send(player, LIVE_RL, wrap(toArray(buf)))
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
        val data = toArray(buf)
        for (id in players) {
            val player = level.server.playerList.getPlayer(id) ?: continue
            if (canReceive(player, LIVE_RL)) ServerPlayNetworking.send(player, LIVE_RL, wrap(data))
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
        ServerPlayNetworking.send(player, ROUTES_RL, wrap(encodeRoutes(store)))
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
