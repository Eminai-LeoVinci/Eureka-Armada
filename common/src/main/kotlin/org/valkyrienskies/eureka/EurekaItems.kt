package org.valkyrienskies.eureka

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import org.valkyrienskies.eureka.item.BottledShipItem
import org.valkyrienskies.eureka.registry.DeferredRegister
import org.valkyrienskies.eureka.registry.RegistrySupplier
import org.valkyrienskies.mod.common.itemProps

@Suppress("unused")
object EurekaItems {
    internal val ITEMS = DeferredRegister.create(EurekaMod.MOD_ID, Registries.ITEM)
    val TAB: ResourceKey<CreativeModeTab> =
        ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "eureka_tab"))

    /**
     * The empty bottle. Sneak and hit a ship's wheel with it to take the ship.
     *
     * Capture is a left-click rather than a use, so it cannot be confused with the Hearts of the Sea offering
     * that already rides on right-click; the interception lives in an AttackBlockCallback on the Fabric side.
     */
    val SHIP_BOTTLE: RegistrySupplier<Item> = ITEMS.register("ship_bottle") { Item(itemProps()) }

    /** The same bottle with a ship in it. Right-click a block to let it out. */
    val BOTTLED_SHIP: RegistrySupplier<Item> = ITEMS.register("bottled_ship") { BottledShipItem(itemProps()) }

    fun register() {
        // Declared above so they land before the block items; the creative tab walks this register in order.
        EurekaBlocks.registerItems(ITEMS)
        ITEMS.applyAll()
    }

    private infix fun Item.byName(name: String) = ITEMS.register(name) { this }
}
