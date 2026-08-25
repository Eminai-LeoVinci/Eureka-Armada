package org.valkyrienskies.eureka.path

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * How path feedback reaches a player.
 *
 * ## Why this isn't just the action bar
 * It was, and the action bar holds exactly ONE line: each new message overwrites the last. Engaging a route can
 * legitimately produce three in the same tick -- "following", "set a cruise speed", "stopped" -- and all a
 * player saw was the last one, flashing past. The replacement stacks them and holds each long enough to read.
 *
 * ## Why the indirection
 * The stacking display is a client HUD fed by a custom packet, and packets are the loader's business, not
 * `:common`'s. The loader installs [sender] during init; until it does -- or on a server whose client is
 * running a version of the mod without the HUD -- [send] falls back to the action bar, which is universally
 * understood. That fallback is the point: nothing here can leave a player with no feedback at all.
 */
object PathMessages {

    /** What a message means, which decides its colour and how long it stays up. */
    enum class Kind(val formatting: ChatFormatting, val argb: Int) {
        /** Ordinary confirmation: recording started, route saved, following. */
        GOOD(ChatFormatting.AQUA, 0xFF6BE0F0.toInt()),

        /** Something the player probably needs to act on, but nothing failed. */
        WARN(ChatFormatting.YELLOW, 0xFFFFD24A.toInt()),

        /** The action did not happen, or a route was dropped. */
        ERROR(ChatFormatting.RED, 0xFFFF6B6B.toInt()),

        /**
         * "Keep holding that." Re-sent every tick for as long as the gesture is in progress, so it takes the
         * HUD's single-slot channel rather than the stack -- forty identical lines would bury everything else.
         *
         * **Never use this for a one-shot.** The prompt slot expires a quarter second after its last refresh,
         * which is what makes it vanish the instant a key is released -- and what makes a message sent once
         * flash past unreadably. Anything said a single time belongs on [GOOD], [WARN] or [ERROR], which stack
         * and hold for `EurekaConfig.CLIENT.pathMessageSeconds`. This has now been got wrong twice.
         */
        PROMPT(ChatFormatting.WHITE, 0xFFFFFFFF.toInt())
    }

    /** Installed by the loader's networking layer. Null until then, and on any loader that hasn't got one. */
    @Volatile
    @JvmStatic
    var sender: ((ServerPlayer, String, Kind, Double) -> Unit)? = null

    /**
     * Say [message] on the HUD.
     *
     * [seconds] overrides how long this one line holds; 0 means "whatever the player configured". It is a
     * per-message argument rather than a new [Kind] on purpose -- kinds mean what a message IS, and the two
     * times timing has been smuggled into one of them it has gone wrong (see [Kind.PROMPT]). Some lines are
     * simply more perishable than others: a restock report is worth two seconds and worth getting out of the
     * way, and that is a property of the report, not of it being good news.
     */
    @JvmOverloads
    fun send(player: ServerPlayer, message: String, kind: Kind, seconds: Double = 0.0) {
        val installed = sender
        if (installed != null) {
            installed(player, message, kind, seconds)
        } else {
            player.displayClientMessage(Component.literal(message).withStyle(kind.formatting), true)
        }
    }
}
