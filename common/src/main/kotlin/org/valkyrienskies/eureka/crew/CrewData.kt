package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.eureka.EurekaConfig

/**
 * Where a captain's berths live, behind two function properties the loader layer fills in.
 *
 * Berths are a Fabric data attachment, and :common has fabric-loader on its classpath but not Fabric API -- so
 * `AttachmentType` cannot be named here. Same shape as `PathMessages.sender`, and for the same reason. The
 * fallbacks are not decoration: they are what makes this module behave sanely if `CrewAttachments.install()`
 * ever fails to run, rather than NPEing on the first Sneak+C.
 *
 * Berths live on the PLAYER, so they are per player per save and follow a captain between ships and
 * dimensions. Bought one at a time with Hearts of the Sea.
 *
 * The other half of the model -- WHO is signed on -- deliberately does not live here. It belongs to the
 * ship's crew-station helm; see [CrewRoster] and [CrewStations]. Berths are a fact about a captain, articles
 * are a fact about a wheel, and keeping them in different places is what makes breaking the wheel tear up the
 * articles without touching anyone's berths.
 */
object CrewData {

    /** How many berths [player] has. Never writes -- see the note on the initializer in `CrewAttachments`. */
    @Volatile
    @JvmField
    var slotsOf: (ServerPlayer) -> Int = { EurekaConfig.SERVER.crewSlotsBase }

    @Volatile
    @JvmField
    var setSlots: (ServerPlayer, Int) -> Unit = { _, _ -> }

    /** Berths [player] has, clamped to the configured ceiling in case the cap was lowered after the fact. */
    @JvmStatic
    fun slots(player: ServerPlayer): Int =
        slotsOf(player).coerceIn(0, EurekaConfig.SERVER.crewSlotsMax)
}
