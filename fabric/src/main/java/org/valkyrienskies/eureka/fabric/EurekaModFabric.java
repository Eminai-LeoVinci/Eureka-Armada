package org.valkyrienskies.eureka.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.model.BakedModelManagerHelper;
import net.fabricmc.fabric.api.client.model.ModelLoadingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.valkyrienskies.eureka.EurekaBlockEntities;
import org.valkyrienskies.eureka.EurekaConfigLoader;
import org.valkyrienskies.eureka.EurekaItems;
import org.valkyrienskies.eureka.EurekaMod;
import org.valkyrienskies.eureka.block.IWoodType;
import org.valkyrienskies.eureka.block.WoodType;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;
import org.valkyrienskies.eureka.blockentity.renderer.WheelModels;
import org.valkyrienskies.eureka.client.EurekaSpeedHud;
import org.valkyrienskies.eureka.command.EurekaAssemblerCommand;
import org.valkyrienskies.eureka.command.ShipWeightCommand;
import org.valkyrienskies.eureka.fabric.registry.FuelRegistryImpl;
import org.valkyrienskies.eureka.registry.CreativeTabs;
import org.valkyrienskies.mod.fabric.common.ValkyrienSkiesModFabric;

public class EurekaModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // force VS2 to load before eureka
        new ValkyrienSkiesModFabric().onInitialize();

        new FuelRegistryImpl();

        // Load config/vs_eureka.json into the ADVANCED/VANILLA/CLIENT singletons (replaces the old
        // Forge-Config-API TOML spec, which couldn't express the dual preset + client save()).
        EurekaConfigLoader.loadOrCreate();

        EurekaMod.init();

        // "/vs eureka-assembler <floater|balloon> <bool>" -- a SERVER command; Brigadier merges this "vs"
        // literal into VS2's root, and VS2's vs_command_passthrough mixin forwards it from the client.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            EurekaAssemblerCommand.INSTANCE.register(dispatcher);
            // "/vs get-ship-weight <ship> <floater|balloon>" -- mass + needed-vs-placed floaters/balloons
            // from live EurekaConfig; same VS2 vs_command_passthrough forwarding as the assembler command.
            ShipWeightCommand.INSTANCE.register(dispatcher);
        });
    }

    @Environment(EnvType.CLIENT)
    public static class Client implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
            EurekaMod.initClient();
            BlockEntityRenderers.register(
                    EurekaBlockEntities.INSTANCE.getSHIP_HELM().get(),
                    ShipHelmBlockEntityRenderer::new
            );

            // Top-center piloted-ship Speed/Altitude/Heading overlay, gated by the helm menu's display
            // checkboxes (EurekaConfig.CLIENT). 1.20.1 HudRenderCallback delivers (guiGraphics, tickDelta).
            HudRenderCallback.EVENT.register(
                    (guiGraphics, tickDelta) -> EurekaSpeedHud.INSTANCE.render(guiGraphics));

            ModelLoadingRegistry.INSTANCE.registerModelProvider((manager, out) -> {
                for (final IWoodType woodType : WoodType.getEntries()) {
                    out.accept(new ResourceLocation(
                        EurekaMod.MOD_ID,
                        "block/" + woodType.getSerializedName().toLowerCase() + "_ship_helm_wheel"
                    ));
                }
            });

            WheelModels.INSTANCE.setModelGetter(woodType ->
                BakedModelManagerHelper.getModel(Minecraft.getInstance().getModelManager(),
                    new ResourceLocation(
                            EurekaMod.MOD_ID,
                            "block/" + woodType.getSerializedName().toLowerCase() + "_ship_helm_wheel"
                    )));

            Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                EurekaItems.INSTANCE.getTAB(),
                CreativeTabs.INSTANCE.create()
            );

            ModContainer eureka = FabricLoader.getInstance().getModContainer(EurekaMod.MOD_ID)
                    .orElseThrow(() -> new IllegalStateException("Eureka's ModContainer couldn't be found!"));
            ResourceLocation packId = new ResourceLocation(EurekaMod.MOD_ID, "retro_helms");
            ResourceManagerHelper.registerBuiltinResourcePack(packId, eureka, "Eureka retro helms", ResourcePackActivationType.NORMAL);
        }
    }
}
