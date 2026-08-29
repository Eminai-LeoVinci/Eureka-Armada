package org.valkyrienskies.eureka.fabric.client.crew;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.valkyrienskies.eureka.crew.HoldTag;

/**
 * The three hold checkboxes on a chest or barrel screen: what this box is FOR.
 *
 * <p>This replaces a right-aligned string that read "Barrel 2 - D1 [Shot, Powder]". Two things were wrong
 * with it. It repeated the word BARREL, which vanilla's own title already says two inches to the left, so a
 * third of the row was spent saying nothing; and the tags were a READOUT of what the box happened to
 * contain, re-derived every time the screen closed, so a captain could not say what a box was for -- only
 * observe what had last been put in it.
 *
 * <p>So the number now goes after vanilla's title ("Barrel 2 - D1", one string, one font) and the tags
 * become boxes a captain ticks. The geometry lives here rather than in the mixin because the drawing and
 * the hit test have to agree exactly, and the surest way to keep two things in step is to have one of them.
 *
 * <p>Laid out from the RIGHT edge, because the title on the left has no fixed width -- "Large Chest" and
 * "Barrel" are different sizes and the boxes must not drift with them.
 */
@Environment(EnvType.CLIENT)
public final class HoldCheckboxes {

    /** The box itself: small enough to sit inside a title row, big enough to hit. */
    public static final int BOX = 7;

    /** Label scale. The row is only ten pixels tall, so full-size text beside a 7px box would not fit. */
    public static final float TEXT_SCALE = 0.65f;

    private static final int GAP_BOX_TEXT = 2;
    private static final int GAP_ITEMS = 5;
    private static final int RIGHT_MARGIN = 8;

    /** Vanilla's own title colour, alpha included -- GuiGraphics honours it, and 0x404040 draws invisible. */
    private static final int TEXT_COLOUR = 0xFF404040;
    private static final int BORDER = 0xFF8B8B8B;
    private static final int FILL_ON = 0xFF3A6B3A;
    private static final int TICK = 0xFFE8E8E8;

    private HoldCheckboxes() {
    }

    /** The width one tag's box plus label occupies. */
    private static int itemWidth(final Font font, final HoldTag tag) {
        return BOX + GAP_BOX_TEXT + Math.round(font.width(tag.getLabel()) * TEXT_SCALE);
    }

    /** Total width of all three, gaps included. */
    public static int totalWidth(final Font font) {
        int total = 0;
        final HoldTag[] tags = HoldTag.values();
        for (int i = 0; i < tags.length; i++) {
            total += itemWidth(font, tags[i]);
            if (i < tags.length - 1) {
                total += GAP_ITEMS;
            }
        }
        return total;
    }

    /** Panel-relative x of the leftmost box, given the panel's width. */
    public static int originX(final Font font, final int imageWidth) {
        return imageWidth - RIGHT_MARGIN - totalWidth(font);
    }

    /** Panel-relative x of one tag's BOX. */
    public static int boxX(final Font font, final int imageWidth, final HoldTag tag) {
        int x = originX(font, imageWidth);
        for (final HoldTag other : HoldTag.values()) {
            if (other == tag) {
                return x;
            }
            x += itemWidth(font, other) + GAP_ITEMS;
        }
        return x;
    }

    /** Which tag's box contains this panel-relative point, or null. */
    public static HoldTag hit(final Font font, final int imageWidth, final int y0,
        final double localX, final double localY) {
        if (localY < y0 || localY >= y0 + BOX) {
            return null;
        }
        for (final HoldTag tag : HoldTag.values()) {
            final int x = boxX(font, imageWidth, tag);
            if (localX >= x && localX < x + BOX) {
                return tag;
            }
        }
        return null;
    }

    /** Draw all three, ticked according to [on]. */
    public static void render(final GuiGraphics graphics, final Font font, final int imageWidth, final int y0,
        final java.util.Set<HoldTag> on) {
        for (final HoldTag tag : HoldTag.values()) {
            final int x = boxX(font, imageWidth, tag);
            final boolean ticked = on.contains(tag);

            graphics.fill(x, y0, x + BOX, y0 + BOX, BORDER);
            graphics.fill(x + 1, y0 + 1, x + BOX - 1, y0 + BOX - 1, ticked ? FILL_ON : 0xFF2B2B2B);
            if (ticked) {
                // A plain bar rather than a drawn tick: at five pixels across, a tick is mud.
                graphics.fill(x + 2, y0 + 3, x + BOX - 2, y0 + BOX - 2, TICK);
            }

            // Pose is a Matrix3x2fStack in 1.21.11: scale first, then divide the coordinates by the
            // scale rather than multiplying them, which is the same idiom the helm menu already uses.
            graphics.pose().pushMatrix();
            graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
            final float tx = (x + BOX + GAP_BOX_TEXT) / TEXT_SCALE;
            final float ty = (y0 + 1) / TEXT_SCALE;
            graphics.drawString(font, tag.getLabel(), Math.round(tx), Math.round(ty), TEXT_COLOUR, false);
            graphics.pose().popMatrix();
        }
    }
}
