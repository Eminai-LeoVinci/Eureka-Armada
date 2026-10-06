package org.valkyrienskies.eureka.fabric

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.entity.DeckSeat

/**
 * The sit key (X by default): sit down where you stand on a ship, SHIFT to stand.
 *
 * The client only asks; the packet carries nothing. The server seats the player only if VS2 is actually carrying
 * them on a ship ([DeckSeat.sitDown]), so a modified client can't seat itself anywhere it isn't standing.
 *
 * Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
 */
object SitDownFabric {

    private val SIT_RL = ResourceLocation(EurekaMod.MOD_ID, "sit_down")

    /** Server: answer sit requests. */
    fun registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(SIT_RL) { server, player, _, _, _ ->
            server.execute { DeckSeat.sitDown(player) }
        }
    }

    /** Client: the key, filed under VS2's own "Driving" category next to its ship keys. */
    fun registerClient() {
        val key = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.vs_eureka.ship_seat", InputConstants.KEY_X, "category.valkyrienskies.driving")
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (key.consumeClick()) {
                val player = client.player ?: continue
                if (!player.isPassenger) ClientPlayNetworking.send(SIT_RL, PacketByteBufs.empty())
            }
        }
    }
}
