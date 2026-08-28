package org.valkyrienskies.eureka.registry

import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.EurekaItems

object CreativeTabs {
    fun create(): CreativeModeTab {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.eureka"))
            .icon { ItemStack(EurekaBlocks.OAK_SHIP_HELM.get().asItem()) }
            .displayItems { _, output ->
                EurekaItems.ITEMS.forEach {
                    // A Bottled Ship is never a thing you PICK UP -- it is what a bottle becomes when it
                    // catches a hull, and an empty one out of the creative menu holds nothing at all. It
                    // stays fully registered, so `/give` and the capture path both still make one; it just
                    // is not offered. This being the mod's only tab, being in no tab also takes it out of
                    // creative SEARCH, which is the whole of what was wanted. Same idiom as the render-only
                    // blocks skipped in EurekaBlocks.registerItems.
                    if (it.name == "bottled_ship") return@forEach
                    output.accept(it.get())
                }
            }
            .build()
    }
}
