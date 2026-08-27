package org.valkyrienskies.eureka.gui.engine

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.ship.ShipBearing

class EngineScreen(handler: EngineScreenMenu, playerInventory: Inventory, text: Component) :
    AbstractContainerScreen<EngineScreenMenu>(handler, playerInventory, text) {

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        // The layers below are stacked, and the two glass ones are translucent -- 35% alpha -- so they are
        // meant to TINT the coals behind them, not replace them. position_tex discards only a fully
        // transparent texel and writes everything else as-is, which leaves the tint entirely dependent on
        // the blend state we happen to inherit. With blending off the glass paints flat over the fire hole
        // and hides the coals and the container completely, while the fully transparent parts -- the
        // panel's window, the notch over the fuel slot -- still come out right, because those are
        // discarded rather than blended. Set it here. (1.21.11's GUI_TEXTURED pipeline owns its blend
        // state, which is why the armada original has no such block.)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        // Likewise don't inherit a tint from whatever drew last; the layers want the texture's own colours.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

        val xP = (width - imageWidth) / 2
        val yP = (height - imageHeight) / 2

        // Every coordinate below is in the atlas's OWN 512x512 pixel space and draws 1:1 on screen.
        // The original path instead let blit assume Minecraft's classic 256x256 atlas and cancelled the
        // resulting error with a 2x pose scale -- it leaned on an implicit default, and it did not
        // survive the jump to a render layer where the pose is no longer a 3D PoseStack.
        fun layer(x: Int, y: Int, u: Int, v: Int, w: Int, h: Int) = guiGraphics.blit(
            TEXTURE, xP + x, yP + y,
            u.toFloat(), v.toFloat(), w, h, TEXTURE_SIZE, TEXTURE_SIZE
        )

        // Container behind the coals, in its heated variant once the engine is properly lit.
        val (containerX, containerY) = if (menu.heatLevel > 1)
            Pair(HEATED_CONTAINER_X, HEATED_CONTAINER_Y)
        else
            Pair(CONTAINER_X, CONTAINER_Y)

        layer(FIRE_HOLE_X, FIRE_HOLE_Y, containerX, containerY, FIRE_HOLE_WIDTH, FIRE_HOLE_HEIGHT)

        // region COALS
        // Four stacked coal layers that sink as the charge burns down.
        //
        // Read off fuelBarPermille, NOT fuelLeft/fuelTotal: on 1.21.1 menu data slots travel as SHORTS,
        // and the raw fields are tick counts that overflow one -- they arrive as garbage here. The ratio
        // fits a short with room to spare.
        if (menu.fuelBarPermille != 0) {
            val burnt = 1f - (menu.fuelBarPermille.toFloat() / 1000f).coerceIn(0f, 1f)
            val t = burnt * COAL_MULTI_MAX

            fun coal(u: Int, v: Int, heightC: Int, mult: Float) = layer(
                FIRE_HOLE_X, FIRE_HOLE_Y + FIRE_HOLE_HEIGHT - heightC + (t * mult).toInt(),
                u, v, COAL_WIDTH, heightC
            )

            coal(COAL_4_X, COAL_4_Y, COAL_4_HEIGHT, COAL_4_MULT)
            coal(COAL_3_X, COAL_3_Y, COAL_3_HEIGHT, COAL_3_MULT)
            coal(COAL_2_X, COAL_2_Y, COAL_2_HEIGHT, COAL_2_MULT)
            coal(COAL_1_X, COAL_1_Y, COAL_1_HEIGHT, COAL_1_MULT)
        }
        // endregion

        // Glass in front of the fire hole, in its heated variant at full heat.
        val (glassX, glassY) = if (menu.heatLevel > 3)
            Pair(HEATED_GLASS_X, HEATED_GLASS_Y)
        else
            Pair(GLASS_X, GLASS_Y)

        layer(FIRE_HOLE_X, FIRE_HOLE_Y, glassX, glassY, FIRE_HOLE_WIDTH, FIRE_HOLE_HEIGHT)

        // Panel last -- its window area is transparent, so the fire hole shows through it.
        layer(0, 0, 0, 0, imageWidth, imageHeight)
    }

    /**
     * The engine's number, and nothing else.
     *
     * `super` is deliberately not called: the vanilla labels are the container name and "Inventory", and this
     * screen's art has no room reserved for either. What a captain actually needs here is which of their
     * engines this one is -- an engine room is a wall of identical fireboxes, and "Engine: 14/35" is the
     * difference between reporting a cold engine and reporting THAT cold engine.
     *
     * Drawn only when the engine is aboard a ship; a firebox on land is not one of anything.
     */
    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val number = ShipBearing.unpackNumber(menu.fittingNumber) ?: return
        guiGraphics.drawString(font, "Engine: $number", NUMBER_X, NUMBER_Y, NUMBER_COLOUR, false)
    }

    companion object {
        // Top-left of the panel, clear of the fire hole and the fuel slot.
        private const val NUMBER_X = 8
        private const val NUMBER_Y = 6
        // 0xFF..., NOT the bare 0x404040 that vanilla's own label calls use. GuiGraphics honours the alpha
        // byte, so a colour written without one is fully TRANSPARENT -- the text draws, occupies its space,
        // and is simply never seen. Every colour constant in this mod carries its alpha for this reason.
        private const val NUMBER_COLOUR = 0xFF404040.toInt()

        // TEXTURE DATA -- all in the atlas's own 512x512 pixel space
        internal val TEXTURE = ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "textures/gui/engine.png")

        private const val TEXTURE_SIZE = 512

        private const val FIRE_HOLE_X = 10
        private const val FIRE_HOLE_Y = 8

        private const val FIRE_HOLE_WIDTH = 156
        private const val FIRE_HOLE_HEIGHT = 68

        private const val HEATED_GLASS_X = 10
        private const val HEATED_GLASS_Y = 172
        private const val GLASS_X = 10
        private const val GLASS_Y = 244

        private const val HEATED_CONTAINER_X = 10
        private const val HEATED_CONTAINER_Y = 390
        private const val CONTAINER_X = 10
        private const val CONTAINER_Y = 318

        private const val COAL_4_X = 184
        private const val COAL_4_Y = 18
        private const val COAL_3_X = 184
        private const val COAL_3_Y = 80
        private const val COAL_2_X = 184
        private const val COAL_2_Y = 128
        private const val COAL_1_X = 184
        private const val COAL_1_Y = 166
        private const val COAL_WIDTH = 158
        private const val COAL_4_HEIGHT = 60
        private const val COAL_3_HEIGHT = 44
        private const val COAL_2_HEIGHT = 34
        private const val COAL_1_HEIGHT = 26

        private const val COAL_MULTI_MAX = COAL_1_HEIGHT.toFloat()
        private const val COAL_4_MULT = COAL_4_HEIGHT.toFloat() / COAL_MULTI_MAX
        private const val COAL_3_MULT = COAL_3_HEIGHT.toFloat() / COAL_MULTI_MAX
        private const val COAL_2_MULT = COAL_2_HEIGHT.toFloat() / COAL_MULTI_MAX
        private const val COAL_1_MULT = 1f
    }
}
