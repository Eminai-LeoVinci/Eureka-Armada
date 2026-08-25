package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaMod
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small stack of path messages above the hotbar, a single-slot prompt line, and the hold ring at the
 * crosshair.
 *
 * The action bar this replaces holds one message and is overwritten by the next, so a burst of three arriving
 * on the same tick showed only the third -- for a few frames. Here they queue instead: newest at the bottom,
 * each holding for several seconds and fading out on its own clock.
 *
 * ## Three channels, because they behave differently
 * [add] is for events -- things that happened once and are worth reading. They STACK.
 *
 * [prompt] is for "keep holding this key", which is re-sent every tick for as long as the key is down. Stacking
 * that would bury everything else under forty identical lines in two seconds, so it gets one slot that the
 * newest write replaces and that clears itself shortly after the writes stop.
 *
 * [setHoldProgress] is the ring that fills round the crosshair while SHIFT+R or SHIFT+P is held down. It is at
 * the crosshair rather than down with the text because it is not something to READ -- it answers "how much
 * longer" at a glance, without moving your eyes off what the ship is about to hit.
 */
@Environment(EnvType.CLIENT)
object PathHud {

    private class Entry(val text: Component, val argb: Int, val bornMs: Long, val lifeMs: Long)

    // Written from the network thread's client-executor task and read on the render thread. Copy-on-write
    // because the list is tiny and read far more often than written; the renderer must never see a torn list.
    private val entries = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var promptText: Component? = null

    @Volatile
    private var promptUntilMs = 0L

    /** How full the hold ring is, 0 to 1. Written each client tick by [PathKeybinds], read on the render thread. */
    @Volatile
    private var holdProgress = 0.0f

    /** Lines are dropped oldest-first past this, so a runaway sender can't fill the screen. */
    private const val MAX_ENTRIES = 6

    /** Fade duration at the end of a message's life. */
    private const val FADE_MS = 700L

    /** How long a prompt survives its last refresh. Two client ticks plus slack. */
    private const val PROMPT_LINGER_MS = 250L

    private const val LINE_HEIGHT = 11

    /** Bottom of the stack, measured up from the bottom of the screen -- clear of the hotbar and action bar. */
    private const val BOTTOM_MARGIN = 84

    /**
     * Ring radius in GUI pixels. The crosshair is 15x15, so anything past 8 clears it; 9 leaves a hair of gap
     * rather than appearing to be part of it.
     */
    private const val RING_RADIUS = 9.0

    /** Enough dots that a 9-pixel radius reads as a line rather than as beads. */
    private const val RING_SEGMENTS = 56

    private const val RING_TRACK_ARGB = 0x3866CCFF
    private const val RING_FILL_ARGB = 0xE066CCFF.toInt()

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_messages"),
            HudElement { graphics, delta -> render(graphics, delta) }
        )
    }

    /**
     * Queue an event message.
     *
     * [seconds] overrides how long this one holds; 0 -- the default for everything that does not ask -- means
     * the player's own `pathMessageSeconds`. A restock report is deliberately short: it is a receipt, useful
     * for the moment it takes to read and clutter afterwards, and it fires often enough that leaving it up
     * for six seconds would push everything else off the HUD.
     */
    @JvmOverloads
    fun add(text: Component, argb: Int, seconds: Float = 0.0f) {
        val configured = EurekaConfig.CLIENT.pathMessageSeconds
        val held = if (seconds > 0.0f) seconds.toDouble() else configured
        val life = (held.coerceIn(0.5, 30.0) * 1000.0).toLong()
        entries.add(Entry(text, argb, System.currentTimeMillis(), life))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Set (or refresh) the single prompt line. Call every tick while the prompt should be up. */
    fun prompt(text: Component) {
        promptText = text
        promptUntilMs = System.currentTimeMillis() + PROMPT_LINGER_MS
    }

    /** Set how full the hold ring is, 0 to 1. Called every client tick; 0 means "don't draw it". */
    fun setHoldProgress(progress: Float) {
        holdProgress = progress.coerceIn(0.0f, 1.0f)
    }

    fun clear() {
        entries.clear()
        promptText = null
        holdProgress = 0.0f
    }

    private fun render(graphics: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.player == null) return

        drawHoldRing(graphics, holdProgress)

        val now = System.currentTimeMillis()
        entries.removeAll { now - it.bornMs >= it.lifeMs }

        val prompt = promptText?.takeIf { now < promptUntilMs }
        if (entries.isEmpty() && prompt == null) return

        val font = mc.font
        val centreX = graphics.guiWidth() / 2
        var y = graphics.guiHeight() - BOTTOM_MARGIN

        // The prompt sits at the bottom of the group, closest to the crosshair, because it is the one line
        // that is asking the player to do something right now.
        if (prompt != null) {
            drawLine(graphics, font, prompt, centreX, y, 0xFFFFFFFF.toInt(), 1.0f)
            y -= LINE_HEIGHT
        }

        // Newest at the bottom, so a burst reads bottom-up in the order it arrived and older lines drift up
        // and out rather than shoving the newest one around.
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val age = now - entry.bornMs
            val remaining = entry.lifeMs - age
            val alpha = if (remaining >= FADE_MS) 1.0f else (remaining.toFloat() / FADE_MS).coerceIn(0.0f, 1.0f)
            drawLine(graphics, font, entry.text, centreX, y, entry.argb, alpha)
            y -= LINE_HEIGHT
        }
    }

    /**
     * The hold ring: a dim circle of dots round the crosshair, brightening clockwise from twelve as the key is
     * held, closed when the action fires.
     *
     * Drawn as single pixels rather than as an arc primitive because [GuiGraphics.fill] is the whole toolkit
     * here -- no shader, no mesh, nothing to keep in sync with a resource pack, and at this radius a ring of
     * 56 dots is indistinguishable from a stroked circle.
     *
     * The unfilled track is drawn too, not just the filled part. Without it there is no way to tell a hold
     * that has barely started from one nearly done: a lone bright arc has no scale.
     */
    private fun drawHoldRing(graphics: GuiGraphics, progress: Float) {
        if (progress <= 0.0f) return

        val centreX = graphics.guiWidth() / 2
        val centreY = graphics.guiHeight() / 2

        for (i in 0 until RING_SEGMENTS) {
            val fraction = i.toFloat() / RING_SEGMENTS
            // From twelve o'clock, clockwise. Screen Y grows downward, so a plain sine sweeps that way already.
            val angle = fraction * 2.0 * Math.PI - Math.PI / 2.0
            val x = centreX + Math.round(cos(angle) * RING_RADIUS).toInt()
            val y = centreY + Math.round(sin(angle) * RING_RADIUS).toInt()
            val argb = if (fraction < progress) RING_FILL_ARGB else RING_TRACK_ARGB
            graphics.fill(x, y, x + 1, y + 1, argb)
        }
    }

    private fun drawLine(
        graphics: GuiGraphics,
        font: net.minecraft.client.gui.Font,
        text: Component,
        centreX: Int,
        y: Int,
        argb: Int,
        alpha: Float
    ) {
        val width = font.width(text)
        val x = centreX - width / 2

        // Backdrop, because these are read against sky, water and terrain in turn and white-on-white is
        // unreadable exactly when a "route lost" message matters most. Fades with the text.
        val backdropAlpha = (alpha * 96).toInt().coerceIn(0, 255)
        graphics.fill(x - 3, y - 2, x + width + 3, y + 9, backdropAlpha shl 24)

        val textAlpha = (alpha * 255).toInt().coerceIn(0, 255)
        graphics.drawString(font, text, x, y, (argb and 0x00FFFFFF) or (textAlpha shl 24), true)
    }
}
