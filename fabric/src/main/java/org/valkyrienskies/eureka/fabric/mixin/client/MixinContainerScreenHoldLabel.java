package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.crew.HoldTag;
import org.valkyrienskies.eureka.fabric.client.crew.HoldCheckboxes;
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
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int titleLabelX;

    @Shadow
    protected int titleLabelY;

    @Shadow
    public abstract AbstractContainerMenu getMenu();

    /** Matches vanilla's own title inset, so the two ends of the row line up. */
    @Unique
    private static final int VS_EUREKA_RIGHT_MARGIN = 8;

    @Unique
    private static final int VS_EUREKA_TITLE_Y = 6;

    /** A space's worth of air between vanilla's title and the number, so the two do not touch. */
    @Unique
    private static final int VS_EUREKA_TITLE_GAP = 4;

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

        // The number goes straight after vanilla's own title, in vanilla's own font and colour, so the row
        // reads as one string: "Barrel 2 - D1". It used to be drawn right-aligned with the noun repeated,
        // which said BARREL twice and spent a third of the row doing it.
        // getTitle() is a METHOD, so it resolves up the hierarchy where a field shadow would not: `title`
        // itself is declared on Screen, and shadowing it here is the exact failure this class warns about.
        final Screen self = (Screen) (Object) this;
        final int afterTitle = this.titleLabelX + font.width(self.getTitle()) + VS_EUREKA_TITLE_GAP;
        graphics.drawString(font, label, afterTitle, this.titleLabelY, VS_EUREKA_LABEL_COLOUR, false);

        HoldCheckboxes.render(
            graphics, font, this.imageWidth, VS_EUREKA_TITLE_Y,
            HoldLabelClient.INSTANCE.tagSetFor(menu.containerId)
        );
    }

    /**
     * A click on one of the three boxes.
     *
     * Injected cancellable at HEAD so a tick never also lands on whatever is behind it, and gated on the
     * same "is this a numbered hold" test the drawing uses -- an ordinary chest on land has no boxes and
     * therefore nothing here can consume its clicks.
     *
     * Mouse coordinates are SCREEN space and the boxes are laid out in PANEL space, so the panel origin is
     * subtracted before asking. That is the one conversion, and it is why the geometry lives in one place.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$clickHoldTag(final MouseButtonEvent event, final boolean doubleClick,
        final CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0) {
            return;
        }
        final double mouseX = event.x();
        final double mouseY = event.y();
        final AbstractContainerMenu menu = this.getMenu();
        if (!(menu instanceof ChestMenu) || HoldLabelClient.INSTANCE.labelFor(menu.containerId) == null) {
            return;
        }
        final HoldTag tag = HoldCheckboxes.hit(
            Minecraft.getInstance().font, this.imageWidth, VS_EUREKA_TITLE_Y,
            mouseX - this.leftPos, mouseY - this.topPos
        );
        if (tag == null) {
            return;
        }
        HoldLabelClient.INSTANCE.toggle(menu.containerId, tag);
        Minecraft.getInstance().getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F
            )
        );
        cir.setReturnValue(true);
    }
}
