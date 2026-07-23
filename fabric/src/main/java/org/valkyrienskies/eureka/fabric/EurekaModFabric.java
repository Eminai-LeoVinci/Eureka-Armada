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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.valkyrienskies.eureka.EurekaBlockEntities;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.EurekaConfigLoader;
import org.valkyrienskies.eureka.EurekaItems;
import org.valkyrienskies.eureka.EurekaMod;
import org.valkyrienskies.eureka.armada.ArmadaBindings;
import org.valkyrienskies.eureka.armada.ArmadaClientBonds;
import org.valkyrienskies.eureka.armada.ArmadaCommand;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;
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

        EurekaMod.init();

        // "/vs get-ship-weight <ship> <floater|balloon>" + "/vs eureka-assembler <floater|balloon> <bool>"
        // -- SERVER commands; Brigadier merges these "vs" literals into VS2's root, and VS2's
        // vs_command_passthrough mixin lets the client send them.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ShipWeightCommand.INSTANCE.register(dispatcher);
            EurekaAssemblerCommand.INSTANCE.register(dispatcher);
            // "/armada bind|unbind|list" -- its own root literal, not under /vs.
            ArmadaCommand.INSTANCE.register(dispatcher);
        });

        // Register the S2C armada-bond snapshot payload (both sides need the codec).
        ArmadaNetworkingFabric.INSTANCE.registerCommon();

        // Per server-world tick: (1) re-establish persisted armada bonds after a world reload -- the child-side
        // bind is saved on the ArmadaShipControl attachment, but the runtime follow provider that positions each
        // child isn't, so reconcile re-installs it once the parent is loaded (skips already-following children in
        // O(1)); (2) broadcast the current bonds to clients so the client-side render-follow can smooth child
        // ships (self-throttles, and only sends while a dimension actually has bonds).
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            ArmadaBindings.INSTANCE.reconcile(level);
            ArmadaNetworkingFabric.INSTANCE.broadcastBonds(level);
        });
    }

    @Environment(EnvType.CLIENT)
    public static class Client implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
            EurekaMod.initClient();

            // Armada: receive bond snapshots, and each client tick keep the render-follow provider installed on
            // every bound child so it renders glued to its parent's smooth pose (fixes the child-ship stutter).
            ArmadaNetworkingFabric.INSTANCE.registerClient();
            ClientTickEvents.END_CLIENT_TICK.register(client -> ArmadaClientBonds.INSTANCE.tick());

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

            Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                EurekaItems.INSTANCE.getTAB(),
                CreativeTabs.INSTANCE.create()
            );

            ModContainer eureka = FabricLoader.getInstance().getModContainer(EurekaMod.MOD_ID)
                    .orElseThrow(() -> new IllegalStateException("Eureka's ModContainer couldn't be found!"));
            Identifier packId = Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "retro_helms");
            ResourceManagerHelper.registerBuiltinResourcePack(packId, eureka, "Eureka retro helms", ResourcePackActivationType.NORMAL);
        }
    }

    public static class ModMenu implements ModMenuApi {
        @Override
        public ConfigScreenFactory<?> getModConfigScreenFactory() {
            // 1.21.11: VS2 dropped its org.valkyrienskies.mod.compat.clothconfig helper.
            // No config screen until the cloth-config integration is reinstated.
            return (parent) -> null;
        }
    }
}
