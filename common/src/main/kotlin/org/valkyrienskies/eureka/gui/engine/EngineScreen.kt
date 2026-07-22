package org.valkyrienskies.eureka.gui.engine

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import org.valkyrienskies.eureka.EurekaMod

class EngineScreen(handler: EngineScreenMenu, playerInventory: Inventory, text: Component) :
    AbstractContainerScreen<EngineScreenMenu>(handler, playerInventory, text) {

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        val xP = (width - imageWidth) / 2
        val yP = (height - imageHeight) / 2

        // Every coordinate below is in the atlas's OWN 512x512 pixel space and draws 1:1 on screen.
        // The original path instead let blit assume Minecraft's classic 256x256 atlas and cancelled the
        // resulting error with a 2x pose scale -- it leaned on an implicit default, and it did not
        // survive the jump to a render layer where the pose is no longer a 3D PoseStack.
        fun layer(x: Int, y: Int, u: Int, v: Int, w: Int, h: Int) = guiGraphics.blit(
            RenderPipelines.GUI_TEXTURED, TEXTURE, xP + x, yP + y,
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
        if (menu.fuelLeft != 0) {
            // fuelTotal is 0 for the frame before the menu's synced data first arrives, and stays 0 on an
            // engine whose in-progress burn was saved before the field existed. Treat that as a full
            // charge instead of dividing by it: the old expression yielded -Infinity, which truncated to
            // Int.MIN_VALUE and threw the whole pile off-screen -- in game, "the coals never render".
            val burnt = if (menu.fuelTotal > 0)
                1f - (menu.fuelLeft.toFloat() / menu.fuelTotal.toFloat()).coerceIn(0f, 1f)
            else
                0f
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

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // super.renderLabels(poseStack, mouseX, mouseY)
    }

    companion object { // TEXTURE DATA -- all in the atlas's own 512x512 pixel space
        internal val TEXTURE = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "textures/gui/engine.png")

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
