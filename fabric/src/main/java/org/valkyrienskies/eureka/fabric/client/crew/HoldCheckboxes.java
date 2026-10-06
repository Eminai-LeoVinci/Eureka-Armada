package org.valkyrienskies.eureka.fabric.client.crew;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.valkyrienskies.eureka.crew.HoldTag;

/**
 * The hold tags on a chest or barrel screen (what this box is FOR), as a "Restock" dropdown in the title row's top
 * right corner: click it and the three tags drop down stacked, each a box a captain ticks.
 *
 * <p>The tags used to sit in the title row itself as three little checkboxes. That fitted beside "Chest 4 - D2" but
 * ran into "Large Chest 2 - D4", and shrinking them further would have made them unreadable. Folded into one button
 * they take a fixed, small width whatever the title says, and single chests, double chests and barrels all look
 * the same.
 *
 * <p>Laid out from the RIGHT edge, because the title on the left has no fixed width. The geometry lives here rather
 * than in the mixin because the drawing and the hit tests have to agree exactly, and the surest way to keep two
 * things in step is to have one of them.
 */
@Environment(EnvType.CLIENT)
public final class HoldCheckboxes {

    private static final String BUTTON_LABEL = "Restock";

    /** Top of the button, panel-relative: two pixels above vanilla's title text, which sits at y 6. */
    private static final int BUTTON_Y = 4;
    private static final int BUTTON_HEIGHT = 11;
    private static final int BUTTON_PAD = 3;
    private static final int ARROW_WIDTH = 5;
    private static final int RIGHT_MARGIN = 7;

    private static final int BOX = 7;
    private static final int ROW_HEIGHT = 11;
    private static final int PANEL_PAD = 4;
    private static final int GAP_BOX_TEXT = 4;

    /** Above slot items (drawn at about z 150-250) so the open list covers them. */
    private static final float PANEL_Z = 400f;

    /** Vanilla's own title colour, alpha included -- GuiGraphics honours it, and 0x404040 draws invisible. */
    private static final int TEXT_COLOUR = 0xFF404040;
    private static final int BUTTON_BORDER = 0xFF8B8B8B;
    private static final int BUTTON_FILL = 0xFFC6C6C6;
    private static final int BUTTON_FILL_HOT = 0xFFDADADA;
    private static final int PANEL_BORDER = 0xFF373737;
    private static final int PANEL_FILL = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADE = 0xFF8B8B8B;
    private static final int ROW_HOT = 0xFFDADADA;
    private static final int BOX_BORDER = 0xFF8B8B8B;
    private static final int BOX_ON = 0xFF3A6B3A;
    private static final int BOX_OFF = 0xFF2B2B2B;
    private static final int TICK = 0xFFE8E8E8;

    private HoldCheckboxes() {
    }

    // region Geometry, panel-relative

    private static int buttonWidth(final Font font) {
        return BUTTON_PAD + font.width(BUTTON_LABEL) + BUTTON_PAD + ARROW_WIDTH + BUTTON_PAD;
    }

    private static int buttonRight(final int imageWidth) {
        return imageWidth - RIGHT_MARGIN;
    }

    private static int buttonLeft(final Font font, final int imageWidth) {
        return buttonRight(imageWidth) - buttonWidth(font);
    }

    private static int panelWidth(final Font font) {
        int widest = 0;
        for (final HoldTag tag : HoldTag.values()) {
            widest = Math.max(widest, font.width(tag.getLabel()));
        }
        return Math.max(buttonWidth(font), PANEL_PAD + BOX + GAP_BOX_TEXT + widest + PANEL_PAD);
    }

    private static int panelLeft(final Font font, final int imageWidth) {
        return buttonRight(imageWidth) - panelWidth(font);
    }

    private static int panelTop() {
        return BUTTON_Y + BUTTON_HEIGHT + 1;
    }

    private static int panelBottom() {
        return panelTop() + 2 + HoldTag.values().length * ROW_HEIGHT + 1;
    }

    private static int rowTop(final int index) {
        return panelTop() + 2 + index * ROW_HEIGHT;
    }

    /** Is this panel-relative point on the button? */
    public static boolean onButton(final Font font, final int imageWidth, final double x, final double y) {
        return x >= buttonLeft(font, imageWidth) && x < buttonRight(imageWidth)
            && y >= BUTTON_Y && y < BUTTON_Y + BUTTON_HEIGHT;
    }

    /** Is this panel-relative point on the open list? */
    public static boolean inPanel(final Font font, final int imageWidth, final double x, final double y) {
        return x >= panelLeft(font, imageWidth) && x < buttonRight(imageWidth)
            && y >= panelTop() && y < panelBottom();
    }

    /** Which tag's row of the open list this panel-relative point is on, or null. */
    public static HoldTag rowAt(final Font font, final int imageWidth, final double x, final double y) {
        if (!inPanel(font, imageWidth, x, y)) {
            return null;
        }
        final HoldTag[] tags = HoldTag.values();
        for (int i = 0; i < tags.length; i++) {
            if (y >= rowTop(i) && y < rowTop(i) + ROW_HEIGHT) {
                return tags[i];
            }
        }
        return null;
    }

    // endregion

    /**
     * Draw the button, and the list under it when [open]. [mouseX]/[mouseY] are panel-relative, for the hover
     * highlight.
     */
    public static void render(final GuiGraphics graphics, final Font font, final int imageWidth,
        final java.util.Set<HoldTag> on, final boolean open, final double mouseX, final double mouseY) {
        renderButton(graphics, font, imageWidth, open || onButton(font, imageWidth, mouseX, mouseY), open);
        if (open) {
            renderPanel(graphics, font, imageWidth, on, mouseX, mouseY);
        }
    }

    private static void renderButton(final GuiGraphics graphics, final Font font, final int imageWidth,
        final boolean hot, final boolean open) {
        final int x0 = buttonLeft(font, imageWidth);
        final int x1 = buttonRight(imageWidth);
        final int y0 = BUTTON_Y;
        final int y1 = BUTTON_Y + BUTTON_HEIGHT;
        graphics.fill(x0, y0, x1, y1, BUTTON_BORDER);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hot ? BUTTON_FILL_HOT : BUTTON_FILL);
        graphics.drawString(font, BUTTON_LABEL, x0 + BUTTON_PAD, y0 + 2, TEXT_COLOUR, false);

        // A small triangle drawn row by row: down while shut, up while open.
        final int ax = x1 - BUTTON_PAD - ARROW_WIDTH;
        final int ay = y0 + 4;
        for (int row = 0; row < 3; row++) {
            final int inset = open ? 2 - row : row;
            graphics.fill(ax + inset, ay + row, ax + ARROW_WIDTH - inset, ay + row + 1, TEXT_COLOUR);
        }
    }

    private static void renderPanel(final GuiGraphics graphics, final Font font, final int imageWidth,
        final java.util.Set<HoldTag> on, final double mouseX, final double mouseY) {
        final int x0 = panelLeft(font, imageWidth);
        final int x1 = buttonRight(imageWidth);
        final int y0 = panelTop();
        final int y1 = panelBottom();

        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, PANEL_Z);

        // Vanilla's panel look: dark outline, light top-left edge, shaded bottom-right edge.
        graphics.fill(x0, y0, x1, y1, PANEL_BORDER);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_SHADE);
        graphics.fill(x0 + 1, y0 + 1, x1 - 2, y1 - 2, PANEL_LIGHT);
        graphics.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, PANEL_FILL);

        final HoldTag hot = rowAt(font, imageWidth, mouseX, mouseY);
        final HoldTag[] tags = HoldTag.values();
        for (int i = 0; i < tags.length; i++) {
            final HoldTag tag = tags[i];
            final int top = rowTop(i);
            if (tag == hot) {
                graphics.fill(x0 + 2, top, x1 - 2, top + ROW_HEIGHT, ROW_HOT);
            }
            final int bx = x0 + PANEL_PAD;
            final int by = top + 2;
            final boolean ticked = on.contains(tag);
            graphics.fill(bx, by, bx + BOX, by + BOX, BOX_BORDER);
            graphics.fill(bx + 1, by + 1, bx + BOX - 1, by + BOX - 1, ticked ? BOX_ON : BOX_OFF);
            if (ticked) {
                // A plain bar rather than a drawn tick: at five pixels across, a tick is mud.
                graphics.fill(bx + 2, by + 3, bx + BOX - 2, by + BOX - 2, TICK);
            }
            graphics.drawString(font, tag.getLabel(), bx + BOX + GAP_BOX_TEXT, top + 2, TEXT_COLOUR, false);
        }

        graphics.pose().popPose();
    }
}
