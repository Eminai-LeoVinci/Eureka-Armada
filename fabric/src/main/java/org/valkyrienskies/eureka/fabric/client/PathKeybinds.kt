package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.fabric.PathNetworkingFabric
import org.valkyrienskies.eureka.path.ClientPathState
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.client.ShipGamepad
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
 * ## CTRL is a modifier, not a fifth binding -- but a controller gets one anyway
 * A new [KeyMapping] only reaches a profile whose `options.txt` has never seen it, so shipping one means every
 * existing install has to be told to go and bind it by hand. A modifier on a key that is already bound arrives
 * working. It also keeps the pair honest: the two are the same gesture on the same key asking for two modes of
 * the same thing, and the hold means "let go" for both.
 *
 * The exception is [replay], UNBOUND by default, which is CTRL for anyone who has no CTRL: a raw window scan
 * can never see a gamepad, so a controller player binds this and holds it with Play. Being unbound, it costs
 * existing keyboard installs nothing -- which is the whole objection to a fifth binding answered.
 *
 * This is what freed up SHIFT+S and SHIFT+C, and getting rid of `S` in particular was worth doing on its own:
 * it is vanilla's walk-backwards key, and sneak-walking backwards along a deck near a ledge is exactly what
 * people do, so a stop key living there was one slip away from firing every time.
 *
 * ## Controllers
 * Four things make the whole file gamepad-clean. Sneak is taken from the binding, the shift flag, or the
 * crouch POSE ([sneakHeld]) -- the last is true whenever the player is visibly crouched, however a controller
 * mod expressed it. Crouched and on foot, the D-pad is read straight off the hardware (VS2's `ShipGamepad`,
 * no keybind delivery required): D-Left crew-all, D-Up follow, D-Down broadside. While crouching, a press that
 * reaches one of our bindings by ANY route CLAIMS its whole button (the crouch layer in
 * [suppressVanillaCollisions]): a pad button usually carries a vanilla action of its own, both halves arrive
 * on one physical press, and crouch is what says which one was meant. And for pads driving the actions
 * through bound buttons instead, `hotkeysNeedSneak` (client config) drops the Sneak requirement entirely --
 * though without crouch armed, a double-bound button fires BOTH its meanings, so that mode wants the ship
 * actions on buttons of their own.
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

    private val CATEGORY_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "paths")

    private lateinit var record: KeyMapping
    private lateinit var play: KeyMapping
    private lateinit var show: KeyMapping
    private lateinit var follow: KeyMapping
    private lateinit var crew: KeyMapping
    private lateinit var broadside: KeyMapping
    private lateinit var replay: KeyMapping

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
        // `G` for guns, and the ONLY binding here with no sneak on it -- see the note on `tick`. Unbound in
        // vanilla, and it goes through the same collision scan as the rest if anything else takes it.
        broadside = bind("broadside", GLFW.GLFW_KEY_G)
        // The controller's CTRL, unbound by default -- see the class note. Held with Play, never pressed alone.
        replay = bind("replay", GLFW.GLFW_KEY_UNKNOWN)

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
        if (client.player == null) return

        // A controller mod's D-pad actions are NATIVE -- chat on up, a radial on right -- not keybinds,
        // so no click drain can reach them, and each opens a SCREEN over the very combo being pressed:
        // the layer below goes quiet the moment any screen is up, so crouch+D-Up became "chat opens,
        // follow dies". Close what the pad's own press opened (vanilla chat or anything of the
        // controller mod's) and carry on with the tick. The few-tick grace catches taps, whose screens
        // arrive after the button is already back up -- and the pad reads below consume LATCHED presses,
        // so a press that spent a few frames hidden under such a screen still lands when it clears.
        if (padGuard > 0) padGuard--
        if (ShipGamepad.dpadUp() || ShipGamepad.dpadDown() ||
            ShipGamepad.dpadLeft() || ShipGamepad.dpadRight()
        ) {
            padGuard = 5
        }
        val screen = client.screen
        if (screen != null) {
            if (padGuard > 0 && sneakHeld(client) &&
                (screen is ChatScreen || screen.javaClass.name.startsWith("dev.isxander"))
            ) {
                client.setScreen(null)
            }
            if (client.screen != null) return
        }
        if (!sneakHeld(client)) return

        for (mapping in client.options.keyMappings) {
            if (mapping === record || mapping === play || mapping === show ||
                mapping === follow || mapping === crew || mapping === broadside || mapping === replay
            ) continue
            // An unbound mapping can never be pressed, but it WOULD `same` every other unbound one -- and
            // `replay` ships unbound -- so skip them before the scan rather than trusting the match.
            if (mapping.isUnbound) continue
            // `same` compares the bound key, so this re-evaluates after any rebind with no state to keep.
            if (ours.none { mapping.same(it) }) continue
            while (mapping.consumeClick()) Unit
        }

        // The crouch layer, for controllers. A pad button often carries a vanilla action of its own -- D-pad
        // Left ships meaning Pick Block -- and a controller mod pressing both halves of a double-bound button
        // on one physical press is invisible to the key scan above, because the two are bound to different
        // KEYS. So while crouching, a press that reaches one of our bindings claims the WHOLE button: the ship
        // action is consumed here at the head of the tick (parked in a pending flag for `tick` to act on), and
        // every other click still queued this tick -- vanilla's or another mod's -- is taken to be the other
        // half of that button and drained before handleKeybinds can fire it. Crouching is what arms the layer,
        // which is exactly the chord it has always meant: crouch says "the next press is for the ship".
        //
        // The D-pad half reads the hardware itself (VS2's ShipGamepad) rather than any keybind, so it works
        // whether or not a controller mod deigns to deliver emulated presses: crouched and on foot, D-Left
        // signs on the crew, D-Up follows the ship you're looking at, D-Down orders the broadside. Standing
        // only --
        // while mounted the wheel owns the D-pad (zoom and altitude, handled VS2-side), and a seated player
        // cannot crouch anyway. The keybind route feeds the same pending flags, so a press that arrives by
        // BOTH routes (pad read + a delivered keybind) is still exactly one action.
        if (client.player?.vehicle == null) {
            // Consume-latched reads, not edges: a press whose edge tick was spent hidden under the
            // controller mod's popup is still here to be taken once the popup is gone.
            // D-Left is the pad's crew-all: it hires the whole deck, and still toggles one villager or opens
            // one wheel's articles when the crosshair is on either. See ACTION_CREW_ALL.
            if (ShipGamepad.consumeLeftPress()) pendingCrewAll = true
            if (ShipGamepad.consumeUpPress()) pendingFollow = true
            if (ShipGamepad.consumeDownPress()) pendingBroadside = true
        }
        while (follow.consumeClick()) pendingFollow = true
        while (crew.consumeClick()) claimCrew(client)
        while (show.consumeClick()) pendingShow = true
        val claimed = pendingFollow || pendingCrew || pendingCrewAll || pendingShow || pendingBroadside ||
            keyHeld(client, record) || keyHeld(client, play)
        if (!claimed) return
        for (mapping in client.options.keyMappings) {
            if (ours.any { it === mapping }) continue
            while (mapping.consumeClick()) Unit
        }
    }

    /**
     * Ship actions claimed at the head of the tick by the crouch layer, acted on at the tail. Flags rather
     * than acting immediately, so both routes into an action -- the layer and the plain end-of-tick read --
     * land in one place, in the same order, once.
     */
    private var pendingFollow = false
    private var pendingCrew = false
    private var pendingCrewAll = false
    private var pendingShow = false
    private var pendingBroadside = false

    /**
     * Take one press of the crew key, deciding there and then which of the two crew actions it was.
     *
     * CTRL is read as the press is CONSUMED rather than latched at its leading edge, which is what the
     * tap-or-hold gestures do -- they have to, because a hold spans many ticks and the modifier could be let
     * go of anywhere inside it. This is a single click with no duration, so the moment it is taken is the
     * only moment there is, and holding CTRL+Sneak and tapping C reads exactly as the player meant it.
     */
    private fun claimCrew(client: Minecraft) {
        if (isControlDown(client)) pendingCrewAll = true else pendingCrew = true
    }

    /** Ticks since the D-pad was last held -- the window in which a popup appearing is the pad's own echo. */
    private var padGuard = 0

    private val ours: List<KeyMapping> by lazy { listOf(record, play, show, follow, crew, broadside, replay) }

    /**
     * Whether sneak is held, by any device. The binding is what a keyboard physically holds; the shift flag
     * is what most input paths set; the crouch POSE is the one read that is true whenever the player is
     * visibly crouched, however a controller mod chose to express it -- toggle-sneak included, where no
     * input is held and (depending on the mod) no flag may be either.
     */
    private fun sneakHeld(client: Minecraft): Boolean =
        client.options.keyShift.isDown || client.player?.isShiftKeyDown == true ||
            client.player?.isCrouching == true

    // 1.21.1: a key category is a plain translation-key STRING (the modern branch registers a Category object).
    private val category: String = "key.categories.${EurekaMod.MOD_ID}.paths"

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

        // The one binding polled before the sneak gate, because it must NOT have sneak on it: a helm dismounts
        // any rider holding shift, so a SHIFT hotkey can only be pressed from the deck -- and a broadside is
        // ordered from the wheel, mid-turn, by the person doing the steering. Read here, ahead of the early
        // return below, so it works seated as well as standing.
        //
        // Firing at nothing is harmless: the server refuses unless the player is aboard a ship with guns and
        // gunners, so a stray press ashore costs a line of text at worst.
        // Both halves are read EVERY tick, never short-circuited: keyPressed is an edge detector that
        // remembers last tick's key state, so skipping it on the tick consumeClick answers leaves that
        // memory stale and it reports a second edge on the NEXT tick. That put two broadside orders on
        // the wire for one press of G -- one tick apart, too fast to be a human double-tap -- and the
        // duplicate volley then leapfrogged the real one, refusing on every gun it had already fired.
        val broadsideClicked = broadside.consumeClick()
        val broadsideEdge = keyPressed(client, broadside)
        if (broadsideClicked || broadsideEdge) {
            PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_BROADSIDE)
        }

        // Every path hotkey is a SHIFT combination, so nothing here can fire during ordinary play -- unless
        // this client has said so: a controller profile turns hotkeysNeedSneak off and binds the actions to
        // buttons of their own, where the chord was the only thing the gate was buying.
        val sneaking = sneakHeld(client) || !EurekaConfig.CLIENT.hotkeysNeedSneak

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

        // Claims made by the crouch layer at the head of the tick land regardless of whether sneak survived
        // to the tail -- letting go of crouch a fraction after the button is an ordinary way to end a chord,
        // and a claim already SPENT the press, so dropping it here would eat the input outright.
        // A press arriving here rather than through the layer joins the same flags, so both routes are folded
        // in one place below and an action can never be counted twice.
        if (sneaking) {
            // Read both halves every tick -- see the broadside note above; short-circuiting the edge
            // detector makes these fire twice for one press, which on a toggle reads as doing nothing.
            val followClicked = follow.consumeClick()
            val followEdge = keyPressed(client, follow)
            if (followClicked || followEdge) pendingFollow = true
            val crewClicked = crew.consumeClick()
            val crewEdge = keyPressed(client, crew)
            if (crewClicked || crewEdge) claimCrew(client)
            val showClicked = show.consumeClick()
            val showEdge = keyPressed(client, show)
            if (showClicked || showEdge) pendingShow = true
        } else if (!pendingFollow && !pendingCrew && !pendingCrewAll && !pendingShow && !pendingBroadside) {
            drainClicks(show, follow, crew)
            return
        }

        val doFollow = pendingFollow
        val doCrew = pendingCrew
        val doCrewAll = pendingCrewAll
        val doShow = pendingShow
        val doBroadside = pendingBroadside
        pendingFollow = false
        pendingCrew = false
        pendingCrewAll = false
        pendingShow = false
        pendingBroadside = false

        // Single presses, not holds: each needs the crosshair on a target, and asking someone to hold a key
        // steady on a ship that is moving relative to them would be the fiddliest part of the whole feature.
        // Each is also self-undoing -- following the ship you already chase breaks off, signing on someone is
        // undone by pressing it at them again -- so a misfire costs one more press, not a different key. The
        // server decides what a crew press meant ("recruit" or "show me the roster"): it is the only side that
        // can see what the crosshair is on.
        if (doFollow) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_FOLLOW_SHIP)
        if (doCrew) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_CREW)
        // CTRL held, or the pad's D-Left: sign on everyone the ship is carrying. Still the individual toggle
        // when the crosshair is on a villager -- the server decides, as it does for the plain crew key.
        if (doCrewAll) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_CREW_ALL)
        if (doShow) toggleShowAll(client)
        // The pad's broadside, claimed under crouch on deck: the same order the G key gives, and the server
        // applies the same refusals (aboard, guns, gunners).
        if (doBroadside) PathNetworkingFabric.sendAction(PathNetworkingFabric.ACTION_BROADSIDE)
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
        if (armed && keyHeld(client, gesture.mapping)) {
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
            if (progress >= PROMPT_AT) PathHud.prompt(Component.literal(prompt), PathMessages.Topic.ALWAYS)
            return if (progress >= RING_AT) progress else 0.0f
        }

        // The release edge. A hold that already fired has spent this press; anything else is a tap.
        if (gesture.held > 0 && !gesture.fired) onTap()
        gesture.held = 0
        gesture.fired = false
        return 0.0f
    }

    /**
     * Whether the replay modifier is held: either CTRL key, or the bindable [replay] stand-in for pads.
     *
     * CTRL is read from the window rather than through a [KeyMapping], because vanilla has no binding for it,
     * and not through `Screen.hasControlDown` because 1.21.11 removed that alongside `hasShiftDown` (see
     * `PATH_RECORDING_NOTES.md`). `InputConstants.isKeyDown` is what those helpers called anyway -- and it is
     * also why CTRL alone could never serve a controller, which no window scan can see.
     */

    /** Physical state of the keys behind [ours], remembered so a press can be told from a hold. */
    private val keyWasDown = HashMap<String, Boolean>()

    /**
     * Whether this binding's key is physically down, read from the window rather than from the KeyMapping.
     *
     * Minecraft keeps exactly ONE KeyMapping per key: the lookup it dispatches presses through is rebuilt by
     * iterating a hash map, so when two bindings share a key the winner is decided by hash order and the
     * loser never sees the key at all -- no press, no click, no isDown, and nothing to log. On this instance
     * vanilla won both Play (shared with Social Interactions) and Follow (shared with Swap Offhand), so those
     * two hotkeys were silently dead, while Record -- which shares R with Iris's reload and happened to win --
     * worked perfectly. That draw differs per instance and per version, which is exactly why the same code
     * behaves differently on 1.21.11.
     *
     * Reading the hardware makes our side of the chord independent of that draw. The conflicting binding is
     * still drained by suppressVanillaCollisions while crouching, so the vanilla action it would otherwise
     * have run does not also fire -- and outside the chord, that binding keeps working normally.
     *
     * Mouse buttons and unbound mappings have no key to scan and fall back to the mapping itself.
     */
    private fun keyHeld(client: Minecraft, mapping: KeyMapping): Boolean {
        if (mapping.isDown) return true
        val key = KeyBindingHelper.getBoundKeyOf(mapping)
        if (key.type != InputConstants.Type.KEYSYM || key.value == InputConstants.UNKNOWN.value) return false
        return InputConstants.isKeyDown(client.window.window, key.value)
    }

    /**
     * True on the tick this binding's key goes down, whichever route it arrived by.
     *
     * Edge-triggered off [keyHeld] and latched per mapping, so several call sites may poll the same binding
     * in one tick and the press still counts exactly once.
     */
    private fun keyPressed(client: Minecraft, mapping: KeyMapping): Boolean {
        val now = keyHeld(client, mapping)
        val was = keyWasDown.put(mapping.name, now) ?: false
        return now && !was
    }

    private fun isControlDown(client: Minecraft): Boolean =
        replay.isDown ||
            InputConstants.isKeyDown(client.window.window, GLFW.GLFW_KEY_LEFT_CONTROL) ||
            InputConstants.isKeyDown(client.window.window, GLFW.GLFW_KEY_RIGHT_CONTROL)

    private fun reset() {
        recordGesture.held = 0
        recordGesture.fired = false
        recordGesture.ctrl = false
        playGesture.held = 0
        playGesture.fired = false
        playGesture.ctrl = false
        pendingFollow = false
        pendingCrew = false
        pendingCrewAll = false
        pendingShow = false
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
            PathHud.add(
                Component.literal(if (shown) "Showing '$name'" else "Hid '$name'"),
                SHOW_ARGB, PathMessages.Topic.ROUTES_VISIBILITY
            )
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
            SHOW_ARGB, PathMessages.Topic.ROUTES_VISIBILITY
        )
    }

    private const val SHOW_ARGB = 0xFF9BE38A.toInt()

    /** Swallow presses that happened without shift so they can't fire later when shift goes down. */
    private fun drainClicks(vararg mappings: KeyMapping) {
        for (mapping in mappings) while (mapping.consumeClick()) Unit
    }

}
