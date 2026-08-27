package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.fabric.client.crew.HoldLabelClient;

/**
 * Draw a hold's number and tags on the vanilla chest screen.
 *
 * <p>Targets {@code AbstractContainerScreen.renderLabels} rather than {@code ContainerScreen.render}, for
 * two reasons: {@code ContainerScreen} does not declare {@code renderLabels} at all (so there would be
 * nothing to inject into there), and {@code renderLabels} runs with the pose already translated to the
 * panel's corner and BEFORE tooltips -- so the coordinates are panel-relative like every other label on the
 * screen, and the text cannot end up painted over a tooltip.
 *
 * <p>Filtered by MENU rather than by screen class, so it covers single chests, double chests and barrels --
 * all of which open a {@code ChestMenu} -- and nothing else. Drawn right-aligned on the title row, opposite
 * the container's own name.
 *
 * <p>Nothing is drawn unless the server has sent a label for THIS container id, which it only does for a box
 * aboard an assembled ship. A chest on land looks exactly as it always has.
 *
 * <p><b>The font is fetched, not shadowed.</b> {@code @Shadow} on a FIELD only searches the target class
 * itself -- unlike a shadowed METHOD, which resolves up the hierarchy -- and {@code font} is declared on
 * {@code Screen}, not on {@code AbstractContainerScreen}. Shadowing it here failed at APPLY with
 * "{@code @Shadow field field_22793 was not located in the target class class_465}", and that presents as
 * the game simply never starting: the mixin error happens before the crash reporter exists, so there is no
 * crash report at all, only a stack trace at the end of {@code latest.log}. {@code imageWidth} and
 * {@code getMenu()} ARE declared on the target, so those two are fine as shadows.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinContainerScreenHoldLabel {

    @Shadow
    protected int imageWidth;

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    /** Matches vanilla's own title inset, so the two ends of the row line up. */
    @Unique
    private static final int VS_EUREKA_RIGHT_MARGIN = 8;

    @Unique
    private static final int VS_EUREKA_TITLE_Y = 6;

    /**
     * 0xFF..., not a bare 0x404040: {@code GuiGraphics} honours the alpha byte, so a colour written without
     * one draws fully transparent -- present, correctly placed, and invisible.
     */
    @Unique
    private static final int VS_EUREKA_LABEL_COLOUR = 0xFF404040;

    @Inject(method = "renderLabels", at = @At("TAIL"))
    private void vs_eureka$drawHoldLabel(final GuiGraphics graphics, final int mouseX, final int mouseY,
        final CallbackInfo ci) {
        final AbstractContainerMenu menu = this.getMenu();
        if (!(menu instanceof ChestMenu)) {
            return;
        }
        final String label = HoldLabelClient.INSTANCE.labelFor(menu.containerId);
        if (label == null) {
            return;
        }
        // The same Font object the screen itself draws with -- Screen.font IS Minecraft's.
        final Font font = Minecraft.getInstance().font;
        final String text = label + HoldLabelClient.INSTANCE.tagsFor(menu.containerId);
        graphics.drawString(
            font, text,
            this.imageWidth - VS_EUREKA_RIGHT_MARGIN - font.width(text), VS_EUREKA_TITLE_Y,
            VS_EUREKA_LABEL_COLOUR, false
        );
    }
}
