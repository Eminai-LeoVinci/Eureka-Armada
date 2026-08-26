package org.valkyrienskies.eureka.fabric;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.eureka.EurekaBlockEntities;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.EurekaConfigLoader;
import org.valkyrienskies.eureka.EurekaItems;
import org.valkyrienskies.eureka.EurekaMod;
import org.valkyrienskies.eureka.block.IWoodType;
import org.valkyrienskies.eureka.block.WoodType;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;
import org.valkyrienskies.eureka.client.EurekaSpeedHud;
import org.valkyrienskies.eureka.blockentity.renderer.WheelModels;
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

        EurekaMod.init();

        // "/vs get-ship-weight <ship> <floater|balloon>" + "/vs eureka-assembler <floater|balloon> <bool>"
        // -- SERVER commands; Brigadier merges these "vs" literals into VS2's root, and VS2's
        // vs_command_passthrough mixin lets the client send them.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ShipWeightCommand.INSTANCE.register(dispatcher);
            EurekaAssemblerCommand.INSTANCE.register(dispatcher);
        });
    }

    @Environment(EnvType.CLIENT)
    public static class Client implements ClientModInitializer {

        private static final Logger DBG_LOGGER = LoggerFactory.getLogger("EurekaClientDbg");
        private static int dbgClientTickCounter = 0;
        private static int dbgLastVehicleId = -2;

        @Override
        public void onInitializeClient() {
            EurekaMod.initClient();
            BlockEntityRenderers.register(
                EurekaBlockEntities.INSTANCE.getSHIP_HELM().get(),
                ShipHelmBlockEntityRenderer::new
            );

            // Top-center piloted-ship speed overlay, toggled by the helm menu's "Display Speed" checkbox.
            HudRenderCallback.EVENT.register(
                (guiGraphics, deltaTracker) -> EurekaSpeedHud.INSTANCE.render(guiGraphics));

            // DEBUG/TEST TOGGLE: "/vs cruise-cancel-debug <bool>". Registered as a CLIENT command so it merges into
            // the SAME client "vs" literal as VS2's ship-shadows/ship-emissive -- the tree Fabric's client parser
            // resolves FIRST. (A server-side addChild onto VS2's "vs" node does NOT parse: the client "vs" tree is
            // checked first and lacks the child, so it fails right after "vs".) debugCruiseCancel is a static on the
            // EurekaConfig object, so this write is seen by the integrated-server cruise logic in single-player.
            // Single-player only by design (a client command can't reach a remote dedicated server); matches the
            // ship-shadows pattern. Candidate for removal at final cleanup.
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                    ClientCommandManager.literal("vs")
                        .then(ClientCommandManager.literal("cruise-cancel-debug")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                    EurekaConfig.SERVER.setDebugCruiseCancel(enabled);
                                    EurekaConfigLoader.save();
                                    ctx.getSource().sendFeedback(
                                        Component.literal("Eureka cruise-cancel debug " + (enabled ? "enabled" : "disabled"))
                                    );
                                    return 1;
                                })))));

            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                final LocalPlayer player = mc.player;
                if (player == null) return;
                final Entity vehicle = player.getVehicle();
                final int curVehicleId = vehicle != null ? vehicle.getId() : -1;
                if (curVehicleId != dbgLastVehicleId) {
                    DBG_LOGGER.info(
                        "[VS-TD-DBG-CLIENT] vehicle CHANGE from={} to={} ({}) isPassenger={} pos=({},{},{})",
                        dbgLastVehicleId,
                        curVehicleId,
                        vehicle != null ? vehicle.getClass().getSimpleName() : "null",
                        player.isPassenger(),
                        player.getX(), player.getY(), player.getZ()
                    );
                    dbgLastVehicleId = curVehicleId;
                }
                dbgClientTickCounter++;
                if (dbgClientTickCounter % 20 != 0) return;
                if (vehicle == null && !player.isPassenger()) return;
                DBG_LOGGER.info(
                    "[VS-TD-DBG-CLIENT] tick vehicle={} isPassenger={} pos=({},{},{}) input=(f={},s={},shift={})",
                    vehicle != null ? vehicle.getClass().getSimpleName() + "#" + vehicle.getId() : "null",
                    player.isPassenger(),
                    player.getX(), player.getY(), player.getZ(),
                    player.input != null ? player.input.forwardImpulse : 0f,
                    player.input != null ? player.input.leftImpulse : 0f,
                    player.isShiftKeyDown()
                );
            });

            ModelLoadingPlugin.register(context -> {
                for (final IWoodType woodType : WoodType.getEntries()) {
                    context.addModels(ResourceLocation.fromNamespaceAndPath(
                            EurekaMod.MOD_ID,
                            "block/" + woodType.getSerializedName().toLowerCase() + "_ship_helm_wheel"
                    ));
                }
            });

            WheelModels.INSTANCE.setModelGetter(woodType ->
                    Minecraft.getInstance().getModelManager().getModel(
                            ResourceLocation.fromNamespaceAndPath(
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
            ResourceLocation packId = ResourceLocation.fromNamespaceAndPath(EurekaMod.MOD_ID, "retro_helms");
            ResourceManagerHelper.registerBuiltinResourcePack(packId, eureka, "Eureka retro helms", ResourcePackActivationType.NORMAL);
        }
    }

    public static class ModMenu implements ModMenuApi {
        @Override
        public ConfigScreenFactory<?> getModConfigScreenFactory() {
            // VS2 2.4.12 dropped its org.valkyrienskies.mod.compat.clothconfig helper.
            // No in-game config screen until the cloth-config integration is reinstated;
            // users still edit config/vs_eureka.json directly (EurekaConfigLoader picks it up).
            return (parent) -> null;
        }
    }
}
