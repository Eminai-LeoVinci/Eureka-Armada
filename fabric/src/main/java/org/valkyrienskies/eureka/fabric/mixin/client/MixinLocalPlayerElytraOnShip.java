package org.valkyrienskies.eureka.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * Lets an elytra start, and keep gliding, while a ship is carrying you.
 *
 * <p>While a ship carries a player, VS2 marks every position packet the client sends as "on the ground", whatever
 * the player is actually doing. That keeps a deck-stander from piling up fall distance, but the server also uses
 * that flag for elytra. A player falling beside a ship who presses jump starts gliding on their own screen and asks
 * the server to start, and the server, believing them grounded, refuses: a few frames of the glide, then a plain
 * fall. Even a glide that does start is ended by the next "on the ground" packet. Armada's airborne carry
 * (ShipInfluence) keeps players carried for as long as they are inside a ship's influence border, so this
 * would happen every time near a ship.
 *
 * <p>Two corrections, both client side:
 * <ul>
 *   <li>Position packets: never claim "on the ground" while the player is gliding, creative-flying or rising,
 *   unless they really are. This wraps the same send VS2 wraps, INSIDE VS2's wrapper (lower priority applies
 *   first, and the first-applied wrapper is the innermost), so it sees and corrects the packet VS2 built.</li>
 *   <li>The start-gliding request: just before it goes out, send one plain "airborne" status update (it carries no
 *   position), so the server checks the request against where the player really is.</li>
 * </ul>
 * Our VS2 build did the first inside VS2 itself.
 */
@Mixin(value = LocalPlayer.class, priority = 900)
public abstract class MixinLocalPlayerElytraOnShip {

    @WrapOperation(
        method = "sendPosition",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V")
    )
    private void vs_eureka$keepAirborneHonest(final ClientPacketListener connection, final Packet<?> packet,
        final Operation<Void> original) {
        final LocalPlayer self = (LocalPlayer) (Object) this;
        if (packet instanceof final ServerboundMovePlayerPacket move && move.isOnGround() && !self.onGround()
            && (self.isFallFlying() || self.getAbilities().flying || self.getDeltaMovement().y > 0.0)) {
            original.call(connection, vs_eureka$withOnGround(move, false));
            return;
        }
        original.call(connection, packet);
    }

    @WrapOperation(
        method = "aiStep",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V")
    )
    private void vs_eureka$airborneBeforeGlide(final ClientPacketListener connection, final Packet<?> packet,
        final Operation<Void> original) {
        final LocalPlayer self = (LocalPlayer) (Object) this;
        if (packet instanceof final ServerboundPlayerCommandPacket command
            && command.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            && !self.onGround()
            && ((IEntityDraggingInformationProvider) self).getDraggingInformation().isEntityBeingDraggedByAShip()) {
            original.call(connection, new ServerboundMovePlayerPacket.StatusOnly(false));
        }
        original.call(connection, packet);
    }

    /** The same packet with a different on-ground flag, keeping exactly the position and rotation it carried. */
    private static ServerboundMovePlayerPacket vs_eureka$withOnGround(final ServerboundMovePlayerPacket move,
        final boolean onGround) {
        if (move.hasPosition() && move.hasRotation()) {
            return new ServerboundMovePlayerPacket.PosRot(move.getX(0.0), move.getY(0.0), move.getZ(0.0),
                move.getYRot(0.0f), move.getXRot(0.0f), onGround);
        } else if (move.hasPosition()) {
            return new ServerboundMovePlayerPacket.Pos(move.getX(0.0), move.getY(0.0), move.getZ(0.0), onGround);
        } else if (move.hasRotation()) {
            return new ServerboundMovePlayerPacket.Rot(move.getYRot(0.0f), move.getXRot(0.0f), onGround);
        }
        return new ServerboundMovePlayerPacket.StatusOnly(onGround);
    }
}
