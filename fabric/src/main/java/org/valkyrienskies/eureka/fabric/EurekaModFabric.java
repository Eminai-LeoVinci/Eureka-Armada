package org.valkyrienskies.eureka.fabric;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderType;
import org.valkyrienskies.eureka.EurekaBlockEntities;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.EurekaConfigLoader;
import org.valkyrienskies.eureka.EurekaEntities;
import org.valkyrienskies.eureka.EurekaItems;
import org.valkyrienskies.eureka.EurekaMod;
import org.valkyrienskies.eureka.armada.ArmadaBindings;
import org.valkyrienskies.eureka.armada.ArmadaCommand;
import org.valkyrienskies.eureka.blockentity.renderer.CannonBlockEntityRenderer;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;
import org.valkyrienskies.eureka.blueprint.BlueprintPages;
import org.valkyrienskies.eureka.cannon.CannonShot;
import org.valkyrienskies.eureka.crew.CrewDuties;
import org.valkyrienskies.eureka.crew.FireAtWill;
import org.valkyrienskies.eureka.crew.FireBrigade;
import org.valkyrienskies.eureka.crew.GunStations;
import org.valkyrienskies.eureka.shipwright.ShipwrightTalk;
import org.valkyrienskies.eureka.crew.GunnerMounts;
import org.valkyrienskies.eureka.fabric.client.blueprint.BlueprintScreen;
import org.valkyrienskies.eureka.fabric.client.shipwright.ShipwrightScreen;
import org.valkyrienskies.eureka.shipwright.ShipwrightMenu;
import org.valkyrienskies.eureka.fabric.client.CannonRangeRenderer;
import org.valkyrienskies.eureka.fabric.client.PathHud;
import org.valkyrienskies.eureka.fabric.client.PathKeybinds;
import org.valkyrienskies.eureka.fabric.client.PathRenderer;
import org.valkyrienskies.eureka.fabric.client.PirateZoneRenderer;
import org.valkyrienskies.eureka.fabric.client.WreckBoxRenderer;
import org.valkyrienskies.eureka.follow.ShipFollows;
import org.valkyrienskies.eureka.path.ClientPathState;
import org.valkyrienskies.eureka.path.PathCommand;
import org.valkyrienskies.eureka.path.ShipPaths;
import org.valkyrienskies.eureka.client.EurekaSpeedHud;
import org.valkyrienskies.eureka.command.ArmadaTuningCommand;
import org.valkyrienskies.eureka.command.EurekaAssemblerCommand;
import org.valkyrienskies.eureka.command.PirateCommand;
import org.valkyrienskies.eureka.pirate.PirateGunnery;
import org.valkyrienskies.eureka.pirate.PirateShips;
import org.valkyrienskies.eureka.ship.ShipFoundering;
import org.valkyrienskies.eureka.ship.ShipWreck;
import org.valkyrienskies.eureka.command.MaterialCommand;
import org.valkyrienskies.eureka.command.ShipTemplateCommand;
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

        // Sneak + right-click a wheel with an empty Ship Bottle to take the ship. Registered BEFORE the crew
        // hooks on purpose: both want crouch+right-click on a helm, and whichever registers first gets to
        // decide. This one only claims the click when the hand holds a bottle, so the Heart of the Sea
        // offering still sees everything else.
        BottleRegistrationsFabric.INSTANCE.register();

        // The Crewman's job site and its trades. Must follow EurekaMod.init(): the POI walks each helm block's
        // state definition, and its ticket count is read from the config that init() loads.
        CrewRegistrationsFabric.register();

        // Sneak + right-click a cannon with a torch to fire it. Does not compete with the two above -- they
        // want a helm, this wants a cannon -- but it has to exist for the same reason they do: vanilla skips
        // the block entirely when a crouching player has a full hand.
        CannonRegistrationsFabric.INSTANCE.register();

        // "/vs get-ship-weight <ship> <floater|balloon>" + "/vs eureka-assembler <floater|balloon> <bool>"
        // -- SERVER commands; Brigadier merges these "vs" literals into VS2's root, and VS2's
        // vs_command_passthrough mixin lets the client send them.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ShipWeightCommand.INSTANCE.register(dispatcher);
            EurekaAssemblerCommand.INSTANCE.register(dispatcher);
            // "/vs template save|load|list" -- DEV ONLY, remove before release. Proves the ship
            // serialization round trip that blueprints, bottled ships and pirate worldgen all rest on.
            ShipTemplateCommand.INSTANCE.register(dispatcher);
            // DEV ONLY: the shipwright material classifier bench -- strip with the ROADMAP 6c sweep.
            MaterialCommand.INSTANCE.register(dispatcher);
            // "/vs pirate set-mark ..." -- DEV ONLY, remove before release. The pirate machinery's harness.
            PirateCommand.INSTANCE.register(dispatcher);
            // "/armada bind|unbind|list" -- its own root literal, not under /vs.
            ArmadaCommand.INSTANCE.register(dispatcher);
            // "/armada route list|info|rename|delete|stop" -- merges onto the same "armada" literal.
            PathCommand.INSTANCE.register(dispatcher);
            // "/armada cannons|cannonballs ..." -- live gunnery tuning; merges onto the same "armada" literal.
            ArmadaTuningCommand.INSTANCE.register(dispatcher);
        });

        // Register the S2C armada-bond snapshot payload (both sides need the codec).
        ArmadaNetworkingFabric.INSTANCE.registerCommon();

        // The shipwright's screen: a shelf out, an action back. Also claims the right-click on a shipwright,
        // which must not reach vanilla's trade screen -- the profession sells nothing, and an unclaimed click
        // is what makes the villager shake its head.
        ShipwrightNetworkingFabric.INSTANCE.registerCommon();
        ShipwrightNetworkingFabric.INSTANCE.registerServer();

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
            // Crew duties: walk any broadside in progress one gun further along. Self-silences to a map check.
            CrewDuties.INSTANCE.tick(level);
            // Fire at Will: any ship under the standing order lays her own guns on the nearest raider.
            // Self-silences to a flag check on each loaded hull.
            FireAtWill.INSTANCE.tick(level);
            // The fire party: steer every firefighter already running at their flame (every tick, so arrival
            // and a moving deck are never missed), and look for new fires on a once-a-second clock.
            FireBrigade.INSTANCE.tick(level);
            // Gun stations: glue each stationed gunner to his seat (their seats sit in non-ticking shipyard
            // chunks, so nothing else will), and once a second reconcile the seats against the crew ledger.
            GunStations.INSTANCE.tick(level);
            // Mob gun crews: once a second, make every tagged mob's mount agree with its papers -- the
            // re-seat after a relog or a template placement, and the release after a disassembly.
            GunnerMounts.INSTANCE.tick(level);
            // Shipwrights serving a captain: hold each one at his counter while the book is open, so he
            // cannot stroll out of reach mid-sale. Self-silences to a map check.
            ShipwrightTalk.INSTANCE.tick(level);
            // Pirate ships: proximity zones around dormant hulls, the 20-second warning, wake-up and the
            // hand-off into ShipFollows. Self-silences to a map check while no pirate wheel is loaded.
            PirateShips.INSTANCE.tick(level);
            // Helm-less ships foundering -- every ship's, not just the pirates': the water probes physTick
            // cannot make, the settle watch, and the seabed/ground breakup.
            ShipFoundering.INSTANCE.tick(level);
            // The wreck-box overlay's snapshot. Every tick rather than ShipFoundering's one-in-twenty,
            // because the whole point of the overlay is watching the moment the box goes live. Costs one
            // boolean read while "/vs wreck-box" is off, which is always, outside a debugging session.
            ShipWreck.INSTANCE.publish(level);
            PathNetworkingFabric.INSTANCE.broadcast(level);
        });

        // Pirate rarity and the hull mix are worldgen DATA, which a config cannot normally reach. This
        // writes the configured values over the loaded registries while the registries exist and no level
        // does, which is the one moment both are true. See PirateWorldgen.
        ServerLifecycleEvents.SERVER_STARTING.register(PirateWorldgen.INSTANCE::apply);

        // Ship paths are held in singletons, which in single player outlive the world -- quitting to the title
        // screen stops the server but leaves them standing. Dropping them here is what makes logging back in look
        // like a fresh load, which is the whole basis of the saved-binding resume. See ShipPaths.reset.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ShipPaths.INSTANCE.reset();
            // Pursuits are runtime-only, so this isn't just tidying -- it IS how a follow ends when you log out.
            // Without it a ship in the next world that happened to take a follower's id would set off after a
            // leader from the last one.
            ShipFollows.INSTANCE.reset();
            // Same reasoning: a volley in flight is runtime-only, and in single player this singleton outlives
            // the world it belonged to.
            CrewDuties.INSTANCE.reset();
            // Likewise a firefighter mid-run: their claims are entity uuids from the stopped server.
            FireBrigade.INSTANCE.reset();
            // The seat map holds entity references from the stopped server; the ledger rebuilds it next world.
            GunStations.INSTANCE.reset();
            // Same shape of map, rebuilt from entity tags instead of a ledger.
            GunnerMounts.INSTANCE.reset();
            // Reports hold block-entity references and chases hold ship ids from the stopped server; the
            // helm reports rebuild everything within a tick of the next world loading.
            PirateShips.INSTANCE.reset();
            ShipFoundering.INSTANCE.reset();
            PathNetworkingFabric.INSTANCE.resetServer();
        });
    }

    @Environment(EnvType.CLIENT)
    public static class Client implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
            EurekaMod.initClient();

            // Reading a blueprint is a purely client-side affair -- the page travels whole in the item's own
            // component -- but the item lives in :common, which cannot name a Screen. Same indirection as
            // PathMessages: common declares the hook, the client entrypoint fills it in.
            BlueprintPages.setOpener(page -> {
                BlueprintScreen.Companion.open(page);
                return kotlin.Unit.INSTANCE;
            });

            // Same indirection for the shipwright's book, which arrives as a packet rather than off an item.
            ShipwrightNetworkingFabric.INSTANCE.registerClient();
            ShipwrightMenu.setOpener(shelf -> {
                ShipwrightScreen.Companion.open(shelf);
                return kotlin.Unit.INSTANCE;
            });

            // Armada: receive bond snapshots so the client knows which ships share an armada (used by the
            // ship-mounted camera so the formation doesn't shove the view). Child ships are real physics bodies
            // now, so VS renders and interpolates them natively -- no client-side render-follow needed.
            ArmadaNetworkingFabric.INSTANCE.registerClient();

            // Ship paths: receive route geometry and live recording state, register the five SHIFT hotkeys,
            // and draw routes/snap markers in the world.
            PathNetworkingFabric.INSTANCE.registerClient();
            PathKeybinds.INSTANCE.register();
            PathRenderer.INSTANCE.register();
            // Pirate proximity zones as wireframe spheres, off until "/vs pirate-zones true". DEV ONLY.
            PirateZoneRenderer.INSTANCE.register();
            // The cannon engage-range wireframe, its sibling in every respect.
            CannonRangeRenderer.INSTANCE.register();
            // The wreck collision box: green while it is only a plan, red once it IS the ship's collision.
            // Off until "/vs wreck-box true". DEV ONLY.
            WreckBoxRenderer.INSTANCE.register();
            PathHud.INSTANCE.register();

            // The Shipwright's Bench is built into the CUTOUT layer, not SOLID. Its model carries the
            // stonecutter's saw blade and a couple of knives, whose textures are mostly transparent -- and
            // the solid layer discards alpha, so on SOLID they render as opaque grey slabs. Every other
            // block this mod adds is opaque, which is why this is the only entry.
            //
            // 1.21.1 still ships Fabric API's BlockRenderLayerMap, so the accessor-mixin workaround the
            // 1.21.11 branch needs (the module was removed there) is simply not required here.
            BlockRenderLayerMap.INSTANCE.putBlock(
                EurekaBlocks.INSTANCE.getSHIPWRIGHTS_BENCH().get(), RenderType.cutout());
            ClientPathState.INSTANCE.setShowAll(EurekaConfig.CLIENT.getShowAllPaths());

            // The overlay is a singleton too, and every route in it belongs to the world we just left. Without
            // this, the next world draws the last one's lines until a snapshot happens to replace them -- and any
            // route hidden with SHIFT+H stays hidden into a world where that id means something else.
            ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ClientPathState.INSTANCE.clear()
            );

            // Submarines: the 1.21.11 branch registers ArmadaPocketOccluder here -- a depth pre-pass over
            // sub-air voxels so the sea surface stops drawing inside a hull under shaders. It is written
            // against the modern Blaze3D (GpuBuffer/RenderPass), which 1.21.1 does not have, and it was
            // still unverified even there -- so this branch ships without it. Submarine polish gap, noted
            // in the campaign ledger.

            BlockEntityRenderers.register(
                EurekaBlockEntities.INSTANCE.getSHIP_HELM().get(),
                ShipHelmBlockEntityRenderer::new
            );

            // The cannon's pitching barrel, drawn the same way the helm's wheel is -- the blockstate
            // models only carry the static carriage.
            BlockEntityRenderers.register(
                EurekaBlockEntities.INSTANCE.getCANNON().get(),
                CannonBlockEntityRenderer::new
            );

            // A thrown Ship Bottle draws as the item it is carrying, which ThrownShipBottle decides tick by
            // tick -- the sprite swaps from empty to full at the instant the ship goes in, and that swap is the
            // whole visual payoff. ThrownItemRenderer asks the entity for its stack every frame, so nothing
            // else has to be wired for that to happen.
            EntityRenderers.register(
                EurekaEntities.INSTANCE.getTHROWN_BOTTLE().get(),
                ThrownItemRenderer::new
            );

            // A cannonball flies as whichever grade of shot was loaded, so copper and netherite are told
            // apart in the air. Same mechanism as the bottle: the entity reports its own stack each frame.
            EntityRenderers.register(
                EurekaEntities.INSTANCE.getCANNON_SHOT().get(),
                ThrownItemRenderer::new
            );

            // A shot past the loaded-chunk line stops being ticked by the client, but keeps receiving
            // the server's per-tick positions -- and the renderer draws every entity lerped from the
            // xo/yo/zo anchors, which only setOldPosAndRot() advances and only ticking calls. Frozen
            // anchor + moving position = every frame sweeping the ball between the loaded-chunk line
            // and wherever it really is: a rubber-band ghost that reads as several balls at once. So
            // after the world's own entity ticking, advance exactly the shots vanilla starved, anchor
            // first and then tick, in the same order tickNonPassenger does it -- the anchor half is
            // the whole cure, and skipping it was the difference between this working and not. Near
            // shots ticked normally this tick and are skipped, so nothing ever runs twice.
            ClientTickEvents.END_WORLD_TICK.register(clientLevel -> {
                for (final var candidate : clientLevel.entitiesForRendering()) {
                    if (!(candidate instanceof CannonShot shot)) {
                        continue;
                    }
                    if (shot.getLastClientTick() != clientLevel.getGameTime()) {
                        shot.setOldPosAndRot();
                        shot.tick();
                    }
                }
            });



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
                        // "/vs cannonball-render-distance [blocks]": how far this CLIENT draws cannonballs.
                        // Genuinely client-local (a draw cull, not a server value), so unlike the toggles
                        // around it this one also works on a dedicated server. The server entity tracking
                        // still caps visibility at 1024 blocks regardless (MixinCannonShotTracking unlinks it from view distance).
                        .then(ClientCommandManager.literal("cannonball-render-distance")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(Component.literal(
                                    "Cannonball render distance: " + EurekaConfig.CLIENT.getCannonShotRenderDistance()
                                        + " blocks (server tracking caps visibility at 1024)"));
                                return 1;
                            })
                            .then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    int blocks = IntegerArgumentType.getInteger(ctx, "blocks");
                                    EurekaConfig.CLIENT.setCannonShotRenderDistance(blocks);
                                    EurekaConfigLoader.save();
                                    ctx.getSource().sendFeedback(Component.literal(
                                        "Cannonball render distance set to " + blocks
                                            + " blocks (server tracking caps visibility at 1024)"));
                                    return 1;
                                })))
                        // "/vs pirate-zones <bool>": draw the pirate proximity spheres. The flag lives on the
                        // COMMON PirateShips object so the integrated-server tick publishes snapshots the render
                        // thread can read. Single-player only, like every toggle above. DEV ONLY, strip-listed.
                        .then(ClientCommandManager.literal("pirate-zones")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                    PirateShips.setPublishZones(enabled);
                                    ctx.getSource().sendFeedback(
                                        Component.literal("Pirate zones " + (enabled ? "shown" : "hidden")));
                                    return 1;
                                })))
                        // "/vs wreck-box <bool>": draw the box that measures how deep a hull buries herself
                        // when she comes apart. GREEN on a sound ship, RED once she is a wreck. Same
                        // single-player static-flag shape as pirate-zones. DEV ONLY, strip-listed.
                        .then(ClientCommandManager.literal("wreck-box")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                    ShipWreck.INSTANCE.setPublishBoxes(enabled);
                                    ctx.getSource().sendFeedback(Component.literal(enabled
                                        ? "Wreck boxes shown -- green: sound hull, red: a wreck, and the depth she will bury to"
                                        : "Wreck boxes hidden"));
                                    return 1;
                                })))
                        // "/vs cannon-range <bool>": draw every chasing pirate's engage-range sphere, plus one
                        // around whatever armed ship the player stands on -- the gunnery bench's picture.
                        // Same shape as pirate-zones in every respect. DEV ONLY, strip-listed.
                        .then(ClientCommandManager.literal("cannon-range")
                            .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
                                    PirateGunnery.setPublishRanges(enabled);
                                    ctx.getSource().sendFeedback(
                                        Component.literal("Cannon ranges " + (enabled ? "shown" : "hidden")));
                                    return 1;
                                })))));

            Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                EurekaItems.INSTANCE.getTAB(),
                CreativeTabs.INSTANCE.create()
            );
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
