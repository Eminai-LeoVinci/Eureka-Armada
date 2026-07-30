package org.valkyrienskies.eureka.fabric;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import org.valkyrienskies.eureka.armada.ArmadaCommand;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;
import org.valkyrienskies.eureka.fabric.client.ArmadaPocketOccluder;
import org.valkyrienskies.eureka.fabric.client.PathHud;
import org.valkyrienskies.eureka.fabric.client.PathKeybinds;
import org.valkyrienskies.eureka.fabric.client.PathRenderer;
import org.valkyrienskies.eureka.follow.ShipFollows;
import org.valkyrienskies.eureka.path.ClientPathState;
import org.valkyrienskies.eureka.path.PathCommand;
import org.valkyrienskies.eureka.path.ShipPaths;
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
            // "/armada route list|info|rename|delete|stop" -- merges onto the same "armada" literal.
            PathCommand.INSTANCE.register(dispatcher);
        });

        // Register the S2C armada-bond snapshot payload (both sides need the codec).
        ArmadaNetworkingFabric.INSTANCE.registerCommon();

        // Ship paths: the C2S hotkey action packet and the two S2C snapshots, plus the server-side handler.
        PathNetworkingFabric.INSTANCE.registerCommon();
        PathNetworkingFabric.INSTANCE.registerServer();

        // Per server-world tick: (1) re-establish persisted armada bonds after a world reload -- the child-side
        // bind is saved on the ArmadaShipControl attachment, but the runtime weld that holds each child isn't, so
        // reconcile re-welds it once the parent is loaded (skips already-welded children in O(1)); (2) broadcast
        // the current bonds to clients (self-throttles, and only sends while a dimension actually has bonds).
        // The armada collides with the world through the weld itself, so there is no per-tick collision solver here.
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            ArmadaBindings.INSTANCE.reconcile(level);
            ArmadaNetworkingFabric.INSTANCE.broadcastBonds(level);
            // Ship paths: re-arm any ship whose saved route binding outlived its follower (a world reload, or a
            // ship that drifted out of simulation and back), then advance any recording (sampling the keel,
            // arming and closing the loop) and steer any ship following a route.
            ShipPaths.INSTANCE.tick(level);
            // Ship following (Sneak+F): hold each pursuing ship on station beside its leader. Separate from the
            // route follower above because the two are mutually exclusive on any one hull -- they'd fight over
            // the wheel -- but they share the same guidance plumbing on EurekaShipControl.
            ShipFollows.INSTANCE.tick(level);
            PathNetworkingFabric.INSTANCE.broadcast(level);
        });

        // Ship paths are held in singletons, which in single player outlive the world -- quitting to the title
        // screen stops the server but leaves them standing. Dropping them here is what makes logging back in look
        // like a fresh load, which is the whole basis of the saved-binding resume. See ShipPaths.reset.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ShipPaths.INSTANCE.reset();
            // Pursuits are runtime-only, so this isn't just tidying -- it IS how a follow ends when you log out.
            // Without it a ship in the next world that happened to take a follower's id would set off after a
            // leader from the last one.
            ShipFollows.INSTANCE.reset();
            PathNetworkingFabric.INSTANCE.resetServer();
        });
    }

    @Environment(EnvType.CLIENT)
    public static class Client implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
            EurekaMod.initClient();

            // Armada: receive bond snapshots so the client knows which ships share an armada (used by the
            // ship-mounted camera so the formation doesn't shove the view). Child ships are real physics bodies
            // now, so VS renders and interpolates them natively -- no client-side render-follow needed.
            ArmadaNetworkingFabric.INSTANCE.registerClient();

            // Ship paths: receive route geometry and live recording state, register the five SHIFT hotkeys,
            // and draw routes/snap markers in the world.
            PathNetworkingFabric.INSTANCE.registerClient();
            PathKeybinds.INSTANCE.register();
            PathRenderer.INSTANCE.register();
            PathHud.INSTANCE.register();
            ClientPathState.INSTANCE.setShowAll(EurekaConfig.CLIENT.getShowAllPaths());

            // The overlay is a singleton too, and every route in it belongs to the world we just left. Without
            // this, the next world draws the last one's lines until a snapshot happens to replace them -- and any
            // route hidden with SHIFT+O stays hidden into a world where that id means something else.
            ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ClientPathState.INSTANCE.clear()
            );

            // Submarines: write depth for every sub-air voxel before the world's translucent pass so the sea
            // surface stops drawing inside a hull. Toggled live by "/vs pocket-occluder" so one session can
            // compare off / on / debug without a relaunch.
            ArmadaPocketOccluder.register();

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
            // NOTE: these MUST hang off the client "vs" literal, not "armada". Fabric resolves the CLIENT
            // command tree first, so registering a client "armada" root would shadow the server's /armada
            // tree and break bind/unbind/subair entirely.
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
                                })))
                        .then(ClientCommandManager.literal("pocket-occluder")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ArmadaPocketOccluder.setEnabled(BoolArgumentType.getBool(ctx, "enabled"));
                                    ctx.getSource().sendFeedback(
                                        Component.literal("Pocket occluder: " + ArmadaPocketOccluder.describe()));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("pocket-occluder-debug")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ArmadaPocketOccluder.setDebug(BoolArgumentType.getBool(ctx, "enabled"));
                                    ctx.getSource().sendFeedback(
                                        Component.literal("Pocket occluder: " + ArmadaPocketOccluder.describe()));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("pocket-occluder-status")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(
                                    Component.literal("Pocket occluder: " + ArmadaPocketOccluder.describe()));
                                return 1;
                            }))));

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
