package org.valkyrienskies.eureka.fabric;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import kotlin.jvm.functions.Function0;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.valkyrienskies.eureka.registry.DeferredRegister;
import org.valkyrienskies.eureka.registry.RegistrySupplier;
import org.valkyrienskies.mod.common.RegistrationContext;

public class DeferredRegisterImpl<T> implements DeferredRegister<T> {
    private final String modId;
    private final Registry<T> registry;
    private final ResourceKey<? extends Registry<T>> registryKey;
    private final List<RegistrySupplier<T>> everMade = new ArrayList<>();

    public DeferredRegisterImpl(final String modId, final ResourceKey<Registry<T>> registry) {
        this.modId = modId;
        this.registryKey = registry;
        this.registry = (Registry<T>) BuiltInRegistries.REGISTRY.getValue(registry.identifier());
    }

    @NotNull
    @Override
    public <I extends T> RegistrySupplier<I> register(
            @NotNull final String name,
            @NotNull final Function0<? extends I> builder) {
        final Identifier id = Identifier.fromNamespaceAndPath(modId, name);
        // 1.21.2+: a block/item must know its registry id before construction (BlockBehaviour's
        // constructor calls effectiveDrops()). Install it so blockProps()/itemProps() pick it up.
        RegistrationContext.currentId.set(ResourceKey.create(registryKey, id));
        final I result;
        try {
            result = Registry.register(registry, id, builder.invoke());
        } finally {
            RegistrationContext.currentId.remove();
        }

        final RegistrySupplier<I> r = new RegistrySupplier<I>() {

            @NotNull
            @Override
            public String getName() {
                return name;
            }

            @Override
            public I get() {
                return result;
            }
        };

        everMade.add((RegistrySupplier<T>) r);
        return r;
    }

    @Override
    public void applyAll() {

    }

    @NotNull
    @Override
    public Iterator<RegistrySupplier<T>> iterator() {
        return everMade.iterator();
    }
}
