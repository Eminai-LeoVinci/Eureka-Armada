package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import com.mojang.blaze3d.platform.InputConstants
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
import kotlin.math.max

/**
 * The four SHIFT hotkeys that drive path recording, route playback and ship following, polled from the client
 * tick.
 *
 * ## Why SHIFT, and why standing on deck
 * A helm dismounts any rider holding shift (see `ShipHelmBlockEntity` -- it has to, because shipyard chunks
 * never tick entities so vanilla's sneak-to-dismount never fires there). So these hotkeys are usable ONLY while
 * standing on the deck, never while seated at the wheel.
 *
 * That shapes the whole workflow, and the workflow is built around it: start recording from the deck, THEN sit
 * down and fly the route, and let the loop close itself when you get back.
 *
 * ## Two of them are two actions each
 * SHIFT+R and SHIFT+P both carry an ordinary action and a destructive one, told apart by how long the key is
 * held rather than by which key it is:
 *
 * | key | tap | hold |
 * |---|---|---|
 * | SHIFT+R | start recording | discard the recording |
 * | SHIFT+P | fly the line / pause / resume | release the ship from its route |
 * | CTRL+SHIFT+P | replay the recording / pause / resume | release the ship from its route |
 *
 * ## CTRL is a modifier, not a fifth binding
 * Deliberately. A new [KeyMapping] only reaches a profile whose `options.txt` has never seen it, so shipping
 * one means every existing install has to be told to go and bind it by hand. A modifier on a key that is
 * already bound arrives working. It also keeps the pair honest: the two are the same gesture on the same key
 * asking for two modes of the same thing, and the hold means "let go" for both.
 *
 * This is what freed up SHIFT+S and SHIFT+C, and getting rid of `S` in particular was worth doing on its own:
 * it is vanilla's walk-backwards key, and sneak-walking backwards along a deck near a ledge is exactly what
 * people do, so a stop key living there was one slip away from firing every time.
 *
 * The destructive half is never something you can arrive at by accident, because a hold is not a thing hands do
 * by mistake, and [PathHud] draws a ring round the crosshair while it counts so it is never a surprise either.
 *
 * ## Tap fires on RELEASE, not on press
 * It has to: at the moment the key goes down there is no way to know yet which of the two actions is meant.
 * That is also why these two read [KeyMapping.isDown] rather than `consumeClick` -- a click is an edge with no
 * duration, and duration is the whole distinction here.
 *
 * ## Collisions
 * Where our keys collide with a vanilla binding it is one that reads CLICKS, which [suppressVanillaCollisions]
 * simply drains. Nothing here reads a vanilla `isDown`, so movement is untouched.
 */
@Environment(EnvType.CLIENT)
object PathKeybinds {

    private val CATEGORY_ID: Identifier = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "paths")

    private lateinit var record: KeyMapping
    private lateinit var play: KeyMapping
    private lateinit var show: KeyMapping
    private lateinit var follow: KeyMapping
    private lateinit var crew: KeyMapping

    /**
     * One key's worth of "is this a tap or a hold?".
     *
     * [fired] is what stops a hold repeating: once the threshold is crossed the action runs once and the count
     * keeps climbing harmlessly, and it is also what tells the release edge that the tap has already been
     * spoken for.
     */
    private class Gesture(val mapping: KeyMapping) {
        var held = 0
        var fired = false

        /**
         * Whether CTRL was down when this press STARTED.
         *
         * Latched at the leading edge rather than read when the tap fires, for the same reason the gesture
         * survives sneak being released early: letting go of a modifier a fraction before the key it modifies
         * is an ordinary way to end a chord, and reading CTRL at release would silently turn a replay into a
         * plain play. The leading edge is also the only moment at which the player's intent is unambiguous.
         */
        var ctrl = false
    }

    private lateinit var recordGesture: Gesture
    private lateinit var playGesture: Gesture

    /** Fraction of the hold at which the "keep holding" line appears under the ring. */
    private const val PROMPT_AT = 0.35f

    /** Fraction below which no ring is drawn at all, so an ordinary tap doesn't flash one up. */
    private const val RING_AT = 0.1f

    fun register() {
        record = bind("record", GLFW.GLFW_KEY_R)
        play = bind("play", GLFW.GLFW_KEY_P)
        // `H` for hide, which is what this key is reached for nine times out of ten.
        show = bind("show", GLFW.GLFW_KEY_H)
        // `F` is vanilla's swap-offhand. That collision is handled for free by suppressVanillaCollisions, which
        // scans for whatever shares a key with one of `ours` rather than hard-coding any particular binding.
        follow = bind("follow", GLFW.GLFW_KEY_F)
        // `C` for crew. Unbound in vanilla today, but it goes through the same collision scan as the rest, so a
        // rebind or another mod landing on it is covered without anything here changing.
        crew = bind("crew", GLFW.GLFW_KEY_C)

        recordGesture = Gesture(record)
        playGesture = Gesture(play)

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
     * Only CLICKS are drained. Movement and other held keys read `isDown`, which is untouched.
     */
    private fun suppressVanillaCollisions(client: Minecraft) {
        if (client.player == null || client.screen != null) return
        if (!client.options.keyShift.isDown) return

        for (mapping in client.options.keyMappings) {
            if (mapping === record || mapping === play || mapping === show ||
                mapping === follow || mapping === crew
            ) continue
            // `same` compares the bound key, so this re-evaluates after any rebind with no state to keep.
            if (ours.none { mapping.same(it) }) continue
            while (mapping.consumeClick()) Unit
        }
    }

    private val ours: List<KeyMapping> by lazy { listOf(record, play, show, follow, crew) }

    /** 1.21.11: KeyMapping's 3rd arg is a Category keyed by Identifier, registered once and shared. */
    private val category: KeyMapping.Category by lazy { KeyMapping.Category.register(CATEGORY_ID) }

    private fun bind(name: String, key: Int): KeyMapping = KeyBindingHelper.registerKeyBinding(
        KeyMapping("key.vs_eureka.path_$name", key, category)
    )

    /** Client ticks a destructive action must be held for. */
    private fun holdTicks(): Int =
        (EurekaConfig.CLIENT.pathHoldSeconds.coerceIn(0.25, 10.0) * 20.0).toInt().coerceAtLeast(1)

    private fun tick(client: Minecraft) {
        if (client.player == null || client.screen != null) {
            reset()
            return
        }

        // Every path hotkey is a SHIFT combination, so nothing here can fire during ordinary play. Read through
        // the sneak BINDING rather than the raw shift key, so a player who has rebound sneak gets hotkeys that
        // still match the key they actually think of as shift.
        val sneaking = client.options.keyShift.isDown

        // The two gestures run whether or not sneak is still down, because letting go of shift first is a
        // perfectly ordinary way to end a chord and the tap has to survive it. `sneaking` only gates whether
        // the count can START or CONTINUE; a false reads as a release, which is exactly right.
        val ring = max(
            gesture(
                recordGesture, sneaking, client, "Hold to discard the recording…",
                onTap = { PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_RECORD_START) },
                onHold = { PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_RECORD_CANCEL) }
            ),
            gesture(
                playGesture, sneaking, client, "Hold to release the ship from its route…",
                // CTRL asks for the recording's own speed and stops; without it the route is a line to steer
                // along and the throttle stays the pilot's, exactly as it always was.
                onTap = {
                    PathNetworkingFabric.sendAction(
                        if (playGesture.ctrl) PathNetworkingFabric.ACTION_PLAY_REPLAY
                        else PathNetworkingFabric.ACTION_PLAY
                    )
                },
                // The hold means "let go of the route" either way -- there is no version of that which the
                // modifier could sensibly change.
                onHold = { PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_STOP) }
            )
        )
        PathHud.setHoldProgress(ring)

        // These two are driven by isDown, so nothing ever reads their click queue. Drained anyway, or a press
        // made before sneak went down would still be sitting there the next time something did read it.
        drainClicks(record, play)

        if (!sneaking) {
            drainClicks(show, follow, crew)
            return
        }

        // A single press, not a hold: it needs the crosshair on a target, and asking someone to hold a key steady
        // on a ship that is moving relative to them would be the fiddliest part of the whole feature. It is also
        // self-undoing -- pressing it again on the ship you are already chasing breaks off -- so a misfire costs
        // one more press rather than needing a different key to put right.
        if (follow.consumeClick()) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_FOLLOW_SHIP)

        // Also a single press, for the same reasons as follow: it wants the crosshair on a person, and signing
        // someone on is undone by pressing it at them again. The server decides whether this press meant
        // "recruit" or "show me the roster" -- it is the only side that can see what the crosshair is on.
        if (crew.consumeClick()) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_CREW)

        if (show.consumeClick()) toggleShowAll(client)
    }

    /**
     * Advance one tap-or-hold key and return how full its ring should be, 0 to 1.
     *
     * [armed] false means sneak has been let go of, which is treated as a release: a tap in flight still lands,
     * and a hold that hadn't finished is abandoned. Letting go of shift a fraction before the key is a normal
     * way to end a chord, so losing the tap there would feel like a dropped input. The cost is the other
     * reading -- abandoning a hold by releasing shift first fires the tap -- which is the cheaper mistake.
     *
     * A screen opening goes through [reset] instead and fires nothing at all, which is the right answer for an
     * input the player has clearly stopped giving.
     */
    private inline fun gesture(
        gesture: Gesture,
        armed: Boolean,
        client: Minecraft,
        prompt: String,
        onTap: () -> Unit,
        onHold: () -> Unit
    ): Float {
        if (armed && gesture.mapping.isDown) {
            // The leading edge, and the only tick the modifier is read on -- see Gesture.ctrl.
            if (gesture.held == 0) gesture.ctrl = isControlDown(client)
            gesture.held++
            if (!gesture.fired && gesture.held >= holdTicks()) {
                gesture.fired = true
                onHold()
            }
            // Left full while the key is still down after firing, so letting go is what clears it -- a ring
            // that vanished on the instant the action ran would read as the hold having been dropped.
            if (gesture.fired) return 1.0f

            val progress = gesture.held.toFloat() / holdTicks()
            if (progress >= PROMPT_AT) PathHud.prompt(Component.literal(prompt))
            return if (progress >= RING_AT) progress else 0.0f
        }

        // The release edge. A hold that already fired has spent this press; anything else is a tap.
        if (gesture.held > 0 && !gesture.fired) onTap()
        gesture.held = 0
        gesture.fired = false
        return 0.0f
    }

    /**
     * Whether either CTRL key is down.
     *
     * Read from the window rather than through a [KeyMapping], because vanilla has no binding for it, and not
     * through `Screen.hasControlDown` because 1.21.11 removed that alongside `hasShiftDown` (see
     * `PATH_RECORDING_NOTES.md`). `InputConstants.isKeyDown` is what those helpers called anyway.
     */
    private fun isControlDown(client: Minecraft): Boolean =
        InputConstants.isKeyDown(client.window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(client.window, GLFW.GLFW_KEY_RIGHT_CONTROL)

    private fun reset() {
        recordGesture.held = 0
        recordGesture.fired = false
        recordGesture.ctrl = false
        playGesture.held = 0
        playGesture.fired = false
        playGesture.ctrl = false
        PathHud.setHoldProgress(0.0f)
    }

    /**
     * SHIFT+H, which means one of two things depending on where you are standing.
     *
     * Aboard a ship that is flying a route, it toggles THAT route's line -- because the one place a route line
     * is least wanted is out the window of the ship following it.
     *
     * Anywhere else -- ashore, or on a ship with no route of its own -- it is all of them at once.
     *
     * The global half asks what is actually ON SCREEN rather than what some flag says, and that is the whole
     * fix rather than a nicety. A route being flown draws for a reason of its own, so a key that merely flipped
     * `showAll` could ADD lines when pressed to hide them, and could never clear a route anyone was flying --
     * which left walking to each ship in turn as the only way to get a clear view.
     */
    private fun toggleShowAll(client: Minecraft) {
        val local = ClientPathState.localRouteId
        if (local != 0L) {
            val shown = ClientPathState.toggleVisible(local)
            val name = ClientPathState.routes[local]?.name ?: "this route"
            // Just the route name: hiding a line obviously doesn't stop the ship flying it, and saying so every
            // time was noise.
            PathHud.add(Component.literal(if (shown) "Showing '$name'" else "Hid '$name'"), SHOW_ARGB)
            return
        }

        val hiding = ClientPathState.anyVisible()
        if (hiding) {
            ClientPathState.hideEverything()
        } else {
            ClientPathState.showEverything()
            // Pull the current set in case a route was recorded while we weren't looking.
            PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_REQUEST_ROUTES)
        }

        // Written back as it always was -- though note nothing reads showAllPaths at client init, so today this
        // records the intent rather than surviving a restart. Which routes are HIDDEN deliberately never
        // persists: hiding is a "not right now" gesture, not a property of the route.
        EurekaConfig.CLIENT.showAllPaths = ClientPathState.showAll
        EurekaConfigLoader.save()

        PathHud.add(
            Component.literal(
                if (hiding) "All routes hidden" else "Showing ${ClientPathState.routes.size} route(s)"
            ),
            SHOW_ARGB
        )
    }

    private const val SHOW_ARGB = 0xFF9BE38A.toInt()

    /** Swallow presses that happened without shift so they can't fire later when shift goes down. */
    private fun drainClicks(vararg mappings: KeyMapping) {
        for (mapping in mappings) while (mapping.consumeClick()) Unit
    }
}
