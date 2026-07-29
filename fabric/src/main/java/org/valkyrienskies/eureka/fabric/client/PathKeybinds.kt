package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.fabric.PathNetworkingFabric
import org.valkyrienskies.eureka.path.ClientPathState

/**
 * The five SHIFT hotkeys that drive path recording, all polled from the client tick.
 *
 * ## Why SHIFT, and why standing on deck
 * A helm dismounts any rider holding shift (see `ShipHelmBlockEntity` -- it has to, because shipyard chunks
 * never tick entities so vanilla's sneak-to-dismount never fires there). So these hotkeys are usable ONLY while
 * standing on the deck, never while seated at the wheel.
 *
 * That shapes the whole workflow, and the workflow is built around it: start recording from the deck, THEN sit
 * down and fly the route, and let the loop close itself when you get back. It also means SHIFT+C cannot collide
 * with VS2's cruise key, since cruise only acts on a seated seat controller.
 *
 * ## The one real collision: S
 * `S` is vanilla's walk-backwards key, and sneak-walking backwards along a deck is exactly what people do near
 * a ledge. A plain press would stop the ship every time. So stop and cancel -- the two destructive actions --
 * require a short HOLD, which movement never produces by accident. Record, play and show are single presses;
 * none of their keys is bound to anything by default.
 */
@Environment(EnvType.CLIENT)
object PathKeybinds {

    private val CATEGORY_ID: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "paths")

    private lateinit var record: KeyMapping
    private lateinit var play: KeyMapping
    private lateinit var stop: KeyMapping
    private lateinit var cancel: KeyMapping
    private lateinit var show: KeyMapping

    /** Client ticks a destructive key must be held. 8 ticks ~ 400 ms. */
    private const val HOLD_TICKS = 8

    private var stopHeld = 0
    private var cancelHeld = 0

    fun register() {
        record = bind("record", GLFW.GLFW_KEY_R)
        play = bind("play", GLFW.GLFW_KEY_P)
        stop = bind("stop", GLFW.GLFW_KEY_S)
        cancel = bind("cancel", GLFW.GLFW_KEY_C)
        show = bind("show", GLFW.GLFW_KEY_O)

        // Vanilla suppression has to run BEFORE Minecraft.handleKeybinds, which END_CLIENT_TICK is far too late
        // for -- by then the colliding action has already happened.
        ClientTickEvents.START_CLIENT_TICK.register { client -> suppressVanillaCollisions(client) }
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /**
     * Swallow the clicks of any VANILLA binding that shares a key with one of ours, while sneak is held.
     *
     * SHIFT+P is the case that showed up: `P` is vanilla's Social Interactions key, and vanilla does not care
     * that sneak is down, so playing a route also fired it -- "Social interactions are only available in
     * Multiplayer worlds" in singleplayer, and the actual screen opening in multiplayer.
     *
     * Done by scanning for collisions rather than hard-coding Social Interactions, so it keeps holding after a
     * rebind in either direction: rebind ours off `P` and vanilla's key works normally again with sneak held;
     * rebind ours onto some other occupied key and that one is covered too.
     *
     * Only CLICKS are drained. Movement and other held keys read `isDown`, which is untouched -- so
     * sneak-walking backwards still walks backwards even though `S` is our stop key.
     */
    private fun suppressVanillaCollisions(client: Minecraft) {
        if (client.player == null || client.screen != null) return
        if (!client.options.keyShift.isDown) return

        for (mapping in client.options.keyMappings) {
            if (mapping === record || mapping === play || mapping === stop ||
                mapping === cancel || mapping === show
            ) continue
            // `same` compares the bound key, so this re-evaluates after any rebind with no state to keep.
            if (ours.none { mapping.same(it) }) continue
            while (mapping.consumeClick()) Unit
        }
    }

    private val ours: List<KeyMapping> by lazy { listOf(record, play, stop, cancel, show) }

    /** 1.21.11: KeyMapping's 3rd arg is a Category keyed by Identifier, registered once and shared. */
    private val category: KeyMapping.Category by lazy { KeyMapping.Category.register(CATEGORY_ID) }

    private fun bind(name: String, key: Int): KeyMapping = KeyBindingHelper.registerKeyBinding(
        KeyMapping("key.vs_eureka.path_$name", key, category)
    )

    private fun tick(client: Minecraft) {
        if (client.player == null || client.screen != null) {
            stopHeld = 0
            cancelHeld = 0
            return
        }

        // Every path hotkey is a SHIFT combination, so nothing here can fire during ordinary play. Read through
        // the sneak BINDING rather than the raw shift key, so a player who has rebound sneak gets hotkeys that
        // still match the key they actually think of as shift.
        if (!client.options.keyShift.isDown) {
            drainClicks()
            stopHeld = 0
            cancelHeld = 0
            return
        }

        if (record.consumeClick()) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_RECORD_START)
        if (play.consumeClick()) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_PLAY)

        if (show.consumeClick()) toggleShowAll(client)

        stopHeld = hold(client, stop, stopHeld, "Hold to stop the ship…") {
            PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_STOP)
        }
        cancelHeld = hold(client, cancel, cancelHeld, "Hold to discard the recording…") {
            PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_RECORD_CANCEL)
        }
    }

    /**
     * Count a held key up to [HOLD_TICKS] and fire once at the top, showing a prompt while it counts.
     *
     * Returns the new hold count. Fires on the tick the threshold is crossed and then latches (the count keeps
     * climbing but the action does not repeat) until the key is released.
     */
    private inline fun hold(
        client: Minecraft,
        mapping: KeyMapping,
        held: Int,
        prompt: String,
        action: () -> Unit
    ): Int {
        if (!mapping.isDown) return 0
        val next = held + 1
        when {
            next == HOLD_TICKS -> action()
            next < HOLD_TICKS -> PathHud.prompt(Component.literal(prompt))
        }
        return next
    }

    /**
     * SHIFT+O, which means one of two things depending on where you are standing.
     *
     * Aboard a ship that is flying a route, it toggles THAT route's line -- because the one place a route line
     * is least wanted is out the window of the ship following it, and that was previously the one case with no
     * way to turn it off (a route being flown drew unconditionally).
     *
     * Anywhere else it is the global show-everything toggle, which is what you want when picking a route to fly
     * rather than flying one.
     */
    private fun toggleShowAll(client: Minecraft) {
        val local = ClientPathState.localRouteId
        if (local != 0L) {
            val hidden = ClientPathState.toggleHidden(local)
            val name = ClientPathState.routes[local]?.name ?: "this route"
            PathHud.add(
                Component.literal(if (hidden) "Hid '$name' -- the ship keeps following it" else "Showing '$name'"),
                SHOW_ARGB
            )
            return
        }

        val enabled = !ClientPathState.showAll
        ClientPathState.showAll = enabled
        EurekaConfig.CLIENT.showAllPaths = enabled
        EurekaConfigLoader.save()

        if (enabled) {
            // Pull the current set in case a route was recorded while we weren't looking.
            PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_REQUEST_ROUTES)
            // "Show me everything" has to mean everything, or a route hidden from a cockpit stays invisible
            // with no obvious way back -- you would have to work out which ship to go and stand on.
            ClientPathState.hiddenRoutes.clear()
        }
        PathHud.add(
            Component.literal(
                if (enabled) "Showing ${ClientPathState.routes.size} saved route(s)" else "Saved routes hidden"
            ),
            SHOW_ARGB
        )
    }

    private const val SHOW_ARGB = 0xFF9BE38A.toInt()

    /** Swallow presses that happened without shift so they can't fire later when shift goes down. */
    private fun drainClicks() {
        while (record.consumeClick()) Unit
        while (play.consumeClick()) Unit
        while (show.consumeClick()) Unit
        while (stop.consumeClick()) Unit
        while (cancel.consumeClick()) Unit
    }
}
