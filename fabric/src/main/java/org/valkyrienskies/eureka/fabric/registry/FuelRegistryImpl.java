package org.valkyrienskies.eureka.fabric.registry;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FuelValues;

public class FuelRegistryImpl extends org.valkyrienskies.eureka.registry.FuelRegistry {
    public FuelRegistryImpl() {
        INSTANCE = this;
    }

    @Override
    public int get(ItemStack stack, FuelValues fuelValues) {
        return fuelValues.burnDuration(stack);
    }
}
