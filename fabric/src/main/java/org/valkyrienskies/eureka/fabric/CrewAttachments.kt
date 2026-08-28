package org.valkyrienskies.eureka.fabric

import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.crew.CrewData

/**
 * The one persistent attachment behind [CrewData], and the wiring that hands it to :common.
 *
 * There used to be a second, a `crew_owner` mark on each villager. The roster moved to the ship's
 * crew-station helm so that breaking the wheel tears up the articles, which a mark on the villager could never
 * express -- destroying a block cannot reach into every villager that ever stood near it. See `CrewRoster`.
 */
object CrewAttachments {

    /**
     * How many crew a player may command on any one ship.
     *
     * `copyOnDeath` is load-bearing and not a nicety: respawning builds a NEW ServerPlayer, and the attachment
     * transfer drops every type that has not asked to survive death. Without it, dying would quietly
     * confiscate every Heart of the Sea the player had ever spent -- which is the kind of bug that only shows
     * up in someone else's world, weeks later.
     */
    private val SLOTS: AttachmentType<Int> = AttachmentRegistry.builder<Int>()
        .persistent(Codec.INT)
        .copyOnDeath()
        .buildAndRegister(ResourceLocation(EurekaMod.MOD_ID, "crew_slots"))

    @JvmStatic
    fun install() {
        // getAttachedOrElse, NOT getAttachedOrCreate: the latter WRITES, so every player who ever pressed
        // Sneak+C would get a persisted crew_slots entry they never earned. Reading without writing also means
        // raising crewSlotsBase in the config later applies retroactively to everyone who hasn't spent a heart.
        CrewData.slotsOf = { player ->
            (player as AttachmentTarget).getAttachedOrElse(SLOTS, EurekaConfig.SERVER.crewSlotsBase)
        }
        CrewData.setSlots = { player, n -> (player as AttachmentTarget).setAttached(SLOTS, n) }
    }
}
