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

/**
 * A small stack of path messages above the hotbar, plus a single-slot prompt line.
 *
 * The action bar this replaces holds one message and is overwritten by the next, so a burst of three arriving
 * on the same tick showed only the third -- for a few frames. Here they queue instead: newest at the bottom,
 * each holding for several seconds and fading out on its own clock.
 *
 * ## Two channels, because they behave differently
 * [add] is for events -- things that happened once and are worth reading. They STACK.
 *
 * [prompt] is for "keep holding this key", which is re-sent every tick for as long as the key is down. Stacking
 * that would bury everything else under forty identical lines in two seconds, so it gets one slot that the
 * newest write replaces and that clears itself shortly after the writes stop.
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

    /** Lines are dropped oldest-first past this, so a runaway sender can't fill the screen. */
    private const val MAX_ENTRIES = 6

    /** Fade duration at the end of a message's life. */
    private const val FADE_MS = 700L

    /** How long a prompt survives its last refresh. Two client ticks plus slack. */
    private const val PROMPT_LINGER_MS = 250L

    private const val LINE_HEIGHT = 11

    /** Bottom of the stack, measured up from the bottom of the screen -- clear of the hotbar and action bar. */
    private const val BOTTOM_MARGIN = 84

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "path_messages"),
            HudElement { graphics, delta -> render(graphics, delta) }
        )
    }

    /** Queue an event message. */
    fun add(text: Component, argb: Int) {
        val life = (EurekaConfig.CLIENT.pathMessageSeconds.coerceIn(1.0, 30.0) * 1000.0).toLong()
        entries.add(Entry(text, argb, System.currentTimeMillis(), life))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Set (or refresh) the single prompt line. Call every tick while the prompt should be up. */
    fun prompt(text: Component) {
        promptText = text
        promptUntilMs = System.currentTimeMillis() + PROMPT_LINGER_MS
    }

    fun clear() {
        entries.clear()
        promptText = null
    }

    private fun render(graphics: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.player == null) return

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
