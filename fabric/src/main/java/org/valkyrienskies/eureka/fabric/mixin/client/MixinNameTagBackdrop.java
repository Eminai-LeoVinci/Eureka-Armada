package org.valkyrienskies.eureka.fabric.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.valkyrienskies.eureka.fabric.client.CrewPlates;

/**
 * Paints a crew member's name-tag backdrop dark cyan instead of black.
 *
 * <p>{@code NameTagFeatureRenderer$Storage.add} computes one background colour for every plate in the game --
 * {@code (backgroundOpacity * 255) << 24}, pure black at whatever the player's Text Background Opacity says --
 * and has no entity in scope to vary it by. So rather than reimplement the method, this modifies the one
 * argument that carries it into each submit record. {@link CrewPlates#tint} keeps vanilla's alpha byte and
 * replaces only the colour, so a crew plate is exactly as solid as every other name tag on screen and only the
 * hue tells them apart.
 *
 * <p>No ordinal: {@code add} builds up to three submit records per plate (an opaque near pass, a see-through
 * pass, or one of each) and all of them should agree about what colour the box is. The pass that passes zero --
 * the one with no box at all -- is left alone inside {@code tint}.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.NameTagFeatureRenderer$Storage")
public class MixinNameTagBackdrop {

    @ModifyArg(
        method = "add",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeStorage$NameTagSubmit;<init>"
                + "(Lorg/joml/Matrix4f;FFLnet/minecraft/network/chat/Component;IIID)V"
        ),
        index = 6
    )
    private int vs_eureka$crewPlateColour(final int backgroundColor) {
        return CrewPlates.tint(backgroundColor);
    }
}
