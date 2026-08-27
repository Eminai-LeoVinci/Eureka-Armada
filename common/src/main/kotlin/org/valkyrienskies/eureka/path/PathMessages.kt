package org.valkyrienskies.eureka.path

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.eureka.EurekaConfig

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
         * and hold for `EurekaConfig.CLIENT.messageSeconds`. This has now been got wrong twice.
         */
        PROMPT(ChatFormatting.WHITE, 0xFFFFFFFF.toInt())
    }

    /**
     * What a message is ABOUT, which decides whether a given player wants to see it.
     *
     * ## Refusals are not in here, and that is the whole design
     * [ALWAYS] is the default on [send], so a line that explains why an order did not happen needs no
     * thought at its call site and cannot be silenced by anything in the config. That is what makes a
     * quiet HUD safe: turn off every switch there is and the mod still tells you why a button did
     * nothing. The switches only ever hide REPORTS -- lines saying something happened.
     *
     * ## Why a lambda per constant
     * The constructor parameter is required, so adding a topic without deciding what governs it does not
     * compile. A `when` over the constants would give a runtime `else` branch that silently swallows a
     * new one, and a map would give a null to default somewhere far from here. The lambda also defers the
     * config read to call time, so this enum's initialisation cannot race the config load.
     *
     * ## Read on the client, only
     * [shown] answers off `EurekaConfig.MESSAGES`, which is a per-player block. Asking it on a dedicated
     * server would answer off that server's inert copy -- nobody's preference. [PathHud] is the only
     * caller, and the filter lives there rather than in the packet handler because two message paths
     * never touch the wire at all (the route-visibility lines) and would otherwise escape the switch.
     */
    enum class Topic(private val flag: () -> Boolean) {
        /** Refusals, and anything not yet sorted. Never hidden. */
        ALWAYS({ true }),

        CREW_RECRUITING({ EurekaConfig.MESSAGES.crewRecruiting }),
        CREW_DEATHS({ EurekaConfig.MESSAGES.crewDeaths }),
        CREW_BERTHS({ EurekaConfig.MESSAGES.crewBerths }),
        CREW_MUSTER({ EurekaConfig.MESSAGES.crewMuster }),
        CREW_DUTIES({ EurekaConfig.MESSAGES.crewDuties }),
        CREW_FIRE_BRIGADE({ EurekaConfig.MESSAGES.crewFireBrigade }),
        CREW_MARKERS({ EurekaConfig.MESSAGES.crewMarkers }),
        CREW_STAND_DOWN({ EurekaConfig.MESSAGES.crewStandDown }),

        GUNNERY_BROADSIDE({ EurekaConfig.MESSAGES.gunneryBroadside }),
        GUNNERY_FIRE_AT_WILL({ EurekaConfig.MESSAGES.gunneryFireAtWill }),
        GUNNERY_STATIONS({ EurekaConfig.MESSAGES.gunneryStations }),
        GUNNERY_ORDERS({ EurekaConfig.MESSAGES.gunneryOrders }),
        GUNNERY_GUN_LOST({ EurekaConfig.MESSAGES.gunneryGunLost }),

        STORES_RESULTS({ EurekaConfig.MESSAGES.storesResults }),
        STORES_RECEIPTS({ EurekaConfig.MESSAGES.storesReceipts }),

        PIRATES_PURSUIT({ EurekaConfig.MESSAGES.piratesPursuit }),
        PIRATES_CONQUEST({ EurekaConfig.MESSAGES.piratesConquest }),
        PIRATES_ZONES({ EurekaConfig.MESSAGES.piratesZones }),

        FOLLOW_BINDING({ EurekaConfig.MESSAGES.followBinding }),
        FOLLOW_STATUS({ EurekaConfig.MESSAGES.followStatus }),

        ROUTES_RECORDING({ EurekaConfig.MESSAGES.routesRecording }),
        ROUTES_REPLAY({ EurekaConfig.MESSAGES.routesReplay }),
        ROUTES_VISIBILITY({ EurekaConfig.MESSAGES.routesVisibility }),

        CRUISE_STATUS({ EurekaConfig.MESSAGES.cruiseStatus }),

        SHIPWRIGHT_PLANS({ EurekaConfig.MESSAGES.shipwrightPlans }),
        SHIPWRIGHT_MATERIALS({ EurekaConfig.MESSAGES.shipwrightMaterials }),
        SHIPWRIGHT_DELIVERY({ EurekaConfig.MESSAGES.shipwrightDelivery }),
        SHIPWRIGHT_ALTERATIONS({ EurekaConfig.MESSAGES.shipwrightAlterations }),

        REPAIR_PROGRESS({ EurekaConfig.MESSAGES.repairProgress }),
        REPAIR_COMPLETE({ EurekaConfig.MESSAGES.repairComplete }),
        REPAIR_PARTIAL({ EurekaConfig.MESSAGES.repairPartial }),

        SALVAGE_DISMANTLE({ EurekaConfig.MESSAGES.salvageDismantle }),
        SALVAGE_CLAIMS({ EurekaConfig.MESSAGES.salvageClaims }),

        BOTTLE_MARKING({ EurekaConfig.MESSAGES.bottleMarking }),
        BOTTLE_CAPTURE({ EurekaConfig.MESSAGES.bottleCapture }),
        BOTTLE_RELEASE({ EurekaConfig.MESSAGES.bottleRelease }),

        BLUEPRINT_DRAFTING({ EurekaConfig.MESSAGES.blueprintDrafting }),
        ;

        /** Whether THIS player wants this message. Client-side only -- see the class KDoc. */
        val shown: Boolean get() = flag()
    }

    /** Installed by the loader's networking layer. Null until then, and on any loader that hasn't got one. */
    @Volatile
    @JvmStatic
    var sender: ((ServerPlayer, String, Kind, Topic, Double) -> Unit)? = null

    /**
     * Say [message] on the HUD.
     *
     * [topic] is what the line is ABOUT, and defaults to [Topic.ALWAYS] so that a refusal -- which is
     * most of what this function carries -- needs nothing said about it at the call site and can never be
     * switched off. Give a real topic only to a REPORT: a line saying something happened.
     *
     * [seconds] overrides how long this one line holds; 0 means "whatever the player configured", which
     * is now every caller. It survives as an argument because the wire already carries it and a future
     * line may want to linger, but there is no longer a message that sets it -- the timer became one
     * number for everything.
     */
    @JvmOverloads
    fun send(
        player: ServerPlayer,
        message: String,
        kind: Kind,
        topic: Topic = Topic.ALWAYS,
        seconds: Double = 0.0
    ) {
        val installed = sender
        if (installed != null) {
            installed(player, message, kind, topic, seconds)
        } else {
            // The topic is deliberately ignored here. This is a client with no HUD channel, so there is
            // nothing to read its preferences off -- and somebody who cannot be filtered is better
            // over-told than silenced.
            player.displayClientMessage(Component.literal(message).withStyle(kind.formatting), true)
        }
    }
}
