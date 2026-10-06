package org.valkyrienskies.eureka.client

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWGamepadState

/**
 * The D-pad, read straight off the hardware.
 *
 * Controller mods deliver buttons by emulating keybinds, and that delivery is theirs to arbitrate -- a
 * button that already carries one of their own actions may never reach a modded binding at all. The ship
 * controls that OWN a context (the wheel's zoom and altitude, Eureka's crouch layer) want the button
 * itself, unconditionally, so this polls GLFW's gamepad state directly: no keybind, no emulation, no
 * arbitration. GLFW ships the SDL controller database, so anything it recognises as a gamepad -- Switch
 * Pro pads included -- reports the same standard D-pad regardless of make.
 *
 * [poll] runs once at the START of each client tick (the platform hook), before every reader: the
 * mounted D-pad handling, the driving packet's ascend/descend, and any downstream mod's layer. Readers
 * elsewhere in the tick see one coherent frame of state; a reader whose hook happens to run before the
 * poll sees last tick's, which costs an edge one tick of latency and nothing else. Main thread only, as
 * all GLFW input calls are.
 */
object ShipGamepad {

    private val state: GLFWGamepadState = GLFWGamepadState.create()

    private var pad = NO_PAD
    private var rescanIn = 0

    private var held = 0
    private var edges = 0
    private var rightY = 0f

    /** Read the pad once for this tick. Call from the start-of-client-tick hook, nowhere else. */
    @JvmStatic
    fun poll() {
        val previous = held
        held = 0
        edges = 0
        rightY = 0f

        if (pad == NO_PAD || !GLFW.glfwJoystickPresent(pad)) {
            // Gone (or never found). Re-scan on a slow clock rather than walking all sixteen slots
            // every tick of every pad-less session.
            pad = NO_PAD
            if (--rescanIn > 0) return
            rescanIn = RESCAN_TICKS
            pad = findPad()
            if (pad == NO_PAD) return
        }

        if (GLFW.glfwJoystickIsGamepad(pad)) {
            // A pad GLFW's controller database recognises: the standard mapped D-pad and right stick.
            if (!GLFW.glfwGetGamepadState(pad, state)) return
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP).toInt() == GLFW.GLFW_PRESS) held = held or UP
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN).toInt() == GLFW.GLFW_PRESS) held = held or DOWN
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT).toInt() == GLFW.GLFW_PRESS) held = held or LEFT
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT).toInt() == GLFW.GLFW_PRESS) held = held or RIGHT
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER).toInt() == GLFW.GLFW_PRESS) held = held or LB
            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER).toInt() == GLFW.GLFW_PRESS) held = held or RB
            rightY = state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y)
        } else {
            // A pad the database does NOT know (third-party Switch pads report as a bare "Wireless
            // Gamepad" with no mapping). No mapping is needed for the parts we want: on virtually every
            // such controller the D-pad reports as the first HAT in a standard four-bit rose, and the
            // right stick's vertical is ordinarily the fourth axis (X, Y, then the right pair).
            val hats = GLFW.glfwGetJoystickHats(pad) ?: return
            if (hats.limit() < 1) return
            val hat = hats.get(0).toInt()
            if (hat and GLFW.GLFW_HAT_UP != 0) held = held or UP
            if (hat and GLFW.GLFW_HAT_DOWN != 0) held = held or DOWN
            if (hat and GLFW.GLFW_HAT_LEFT != 0) held = held or LEFT
            if (hat and GLFW.GLFW_HAT_RIGHT != 0) held = held or RIGHT
            val axes = GLFW.glfwGetJoystickAxes(pad)
            rightY = if (axes != null && axes.limit() > RAW_RIGHT_Y_AXIS) axes.get(RAW_RIGHT_Y_AXIS) else 0f
            // Bumpers on an unmapped pad: the shoulders are almost universally raw buttons 4 and 5.
            val buttons = GLFW.glfwGetJoystickButtons(pad)
            if (buttons != null) {
                if (buttons.limit() > RAW_LEFT_BUMPER &&
                    buttons.get(RAW_LEFT_BUMPER).toInt() == GLFW.GLFW_PRESS
                ) {
                    held = held or LB
                }
                if (buttons.limit() > RAW_RIGHT_BUMPER &&
                    buttons.get(RAW_RIGHT_BUMPER).toInt() == GLFW.GLFW_PRESS
                ) {
                    held = held or RB
                }
            }
        }
        edges = held and previous.inv()
        // A press also LATCHES for a short while, for consumers that can miss the edge tick: a controller
        // mod popping its own screen (radial, keyboard) over the press blinds a screen-gated consumer for
        // exactly the tick the edge lives, and the action died with it. The latch holds the press until
        // somebody consumes it or it goes stale.
        for (i in latch.indices) if (latch[i] > 0) latch[i]--
        if (edges and UP != 0) latch[0] = LATCH_TICKS
        if (edges and DOWN != 0) latch[1] = LATCH_TICKS
        if (edges and LEFT != 0) latch[2] = LATCH_TICKS
        if (edges and RIGHT != 0) latch[3] = LATCH_TICKS
    }

    /** Take a latched UP press: true at most once per physical press, within its few-tick lifetime. */
    @JvmStatic
    fun consumeUpPress(): Boolean = consume(0)

    @JvmStatic
    fun consumeDownPress(): Boolean = consume(1)

    @JvmStatic
    fun consumeLeftPress(): Boolean = consume(2)

    @JvmStatic
    fun consumeRightPress(): Boolean = consume(3)

    private fun consume(index: Int): Boolean {
        if (latch[index] <= 0) return false
        latch[index] = 0
        return true
    }

    /**
     * Clear every latched press. A screen that reads the D-pad itself calls this each of its ticks, so
     * the presses it answered can never ALSO fire a deck action the moment the screen closes.
     */
    @JvmStatic
    fun drainPresses() {
        for (i in latch.indices) latch[i] = 0
    }

    private val latch = IntArray(4)

    /** How long an unconsumed press stays claimable -- half a second, the life of a popup it hid behind. */
    private const val LATCH_TICKS = 10

    /**
     * The right stick's vertical deflection, -1 (pushed up) to +1 (pulled down), 0 at rest. Menus read
     * this as their scroll wheel.
     */
    @JvmStatic
    fun rightStickY(): Float = rightY

    /**
     * The best joystick on offer: a database-recognised gamepad if one exists, else the first joystick
     * with a hat to read a D-pad off.
     */
    private fun findPad(): Int {
        var hatPad = NO_PAD
        for (jid in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
            if (!GLFW.glfwJoystickPresent(jid)) continue
            if (GLFW.glfwJoystickIsGamepad(jid)) return jid
            if (hatPad == NO_PAD) {
                val hats = GLFW.glfwGetJoystickHats(jid)
                if (hats != null && hats.limit() > 0) hatPad = jid
            }
        }
        return hatPad
    }

    @JvmStatic
    fun dpadUp(): Boolean = held and UP != 0

    @JvmStatic
    fun dpadDown(): Boolean = held and DOWN != 0

    @JvmStatic
    fun dpadLeft(): Boolean = held and LEFT != 0

    @JvmStatic
    fun dpadRight(): Boolean = held and RIGHT != 0

    /** The press EDGE: true for the one polled tick a direction went down. What click actions key off. */
    @JvmStatic
    fun dpadUpPressed(): Boolean = edges and UP != 0

    @JvmStatic
    fun dpadDownPressed(): Boolean = edges and DOWN != 0

    @JvmStatic
    fun dpadLeftPressed(): Boolean = edges and LEFT != 0

    @JvmStatic
    fun dpadRightPressed(): Boolean = edges and RIGHT != 0

    @JvmStatic
    fun anyDpadPressed(): Boolean = edges and (UP or DOWN or LEFT or RIGHT) != 0

    /** The shoulder buttons, for cycling tabs in ship screens. Press edges, like the D-pad's. */
    @JvmStatic
    fun bumperLeftPressed(): Boolean = edges and LB != 0

    @JvmStatic
    fun bumperRightPressed(): Boolean = edges and RB != 0

    private const val NO_PAD = -1

    /** Two seconds between scans for a pad that is not there. Plugging one in mid-game just works. */
    private const val RESCAN_TICKS = 40

    private const val UP = 1
    private const val DOWN = 2
    private const val LEFT = 4
    private const val RIGHT = 8
    private const val LB = 16
    private const val RB = 32

    /** Where the right stick's vertical usually lands on an unmapped pad: X, Y, then the right pair. */
    private const val RAW_RIGHT_Y_AXIS = 3

    /** Where the shoulders usually land on an unmapped pad's raw buttons. */
    private const val RAW_LEFT_BUMPER = 4
    private const val RAW_RIGHT_BUMPER = 5
}
