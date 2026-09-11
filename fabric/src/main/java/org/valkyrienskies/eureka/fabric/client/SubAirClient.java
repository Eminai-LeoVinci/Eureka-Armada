package org.valkyrienskies.eureka.fabric.client;

import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.eureka.armada.SubAir;

/**
 * The client-side questions the submarine rendering asks, in one place.
 *
 * <p>{@link #cameraSubmergedInPocket()} is the predicate behind the underwater look from inside a hull: the
 * camera is in sub air AND the world at that spot is water. A sub sitting on the surface answers false --
 * there is no sea outside the windows to fog.
 *
 * <p>{@link #shaderPackInUse()} asks Iris, reflectively, so there is no compile-time dependency and no
 * class-loading of anything Iris when it is not installed.
 */
@Environment(EnvType.CLIENT)
public final class SubAirClient {

    private SubAirClient() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("SubAirClient");

    /** Decided once per frame by the fog renderer hook and read by the water-fog hook right after it. */
    private static boolean fogPocket = false;

    public static boolean cameraSubmergedInPocket() {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.gameRenderer == null) {
            return false;
        }
        final Vec3 pos = ((CameraPositionDuck) mc.gameRenderer.getMainCamera()).vs_eureka$cameraPosition();
        if (pos == null) {
            return false;
        }
        if (!level.getFluidState(BlockPos.containing(pos.x, pos.y, pos.z)).is(FluidTags.WATER)) {
            return false;
        }
        return cameraInSubAir(level, pos);
    }

    /**
     * Is this world position inside some hull's sub air, judged in the RENDER pose of each ship -- the pose the
     * hull is drawn in? {@link SubAir#isShielded} reads the ticked physics pose, which trails the rendered one by
     * up to a tick of movement; for the camera that lag showed as a moving sub reading its own camera as outside
     * for a frame, and the shaderpack flashing its underwater look (and its several-second water transitions).
     */
    public static boolean cameraInSubAir(final ClientLevel level, final Vec3 pos) {
        final Block subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
        final Vector3d local = new Vector3d();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final LoadedShip ship : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final ShipTransform xform;
            if (ship instanceof final ClientShip cs) {
                final AABBdc box = cs.getRenderAABB();
                if (pos.x < box.minX() - 1.0 || pos.x > box.maxX() + 1.0 || pos.y < box.minY() - 1.0
                    || pos.y > box.maxY() + 1.0 || pos.z < box.minZ() - 1.0 || pos.z > box.maxZ() + 1.0) {
                    continue;
                }
                xform = cs.getRenderTransform();
            } else {
                xform = ship.getTransform();
            }
            xform.getWorldToShip().transformPosition(pos.x, pos.y, pos.z, local);
            cursor.set((int) Math.floor(local.x), (int) Math.floor(local.y), (int) Math.floor(local.z));
            if (level.getBlockState(cursor).getBlock() == subAir) {
                return true;
            }
        }
        return false;
    }

    /**
     * A block census of every loaded ship -- sub air, water, plain air, everything else -- plus what the WORLD
     * holds at the camera and one block under it. Diagnostic: a hull that was assembled before the water fix
     * still carries the sea it swallowed then, and that shows up here as ship water.
     */
    public static String census() {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) {
            return "no level";
        }
        final StringBuilder out = new StringBuilder();
        final net.minecraft.world.level.block.Block subAir = org.valkyrienskies.eureka.EurekaBlocks.INSTANCE.getSUB_AIR().get();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (final org.valkyrienskies.core.api.ships.LoadedShip ship
            : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final var aabb = ship.getShipAABB();
            if (aabb == null) {
                continue;
            }
            int sub = 0;
            int water = 0;
            int air = 0;
            int other = 0;
            for (int x = aabb.minX(); x <= aabb.maxX(); x++) {
                for (int y = aabb.minY(); y <= aabb.maxY(); y++) {
                    for (int z = aabb.minZ(); z <= aabb.maxZ(); z++) {
                        cursor.set(x, y, z);
                        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cursor);
                        if (state.getBlock() == subAir) {
                            sub++;
                        } else if (!state.getFluidState().isEmpty()) {
                            water++;
                        } else if (state.isAir()) {
                            air++;
                        } else {
                            other++;
                        }
                    }
                }
            }
            out.append("ship ").append(ship.getId()).append(" [").append(ship.getSlug()).append("]: subAir=")
                .append(sub).append(" WATER=").append(water).append(" air=").append(air)
                .append(" blocks=").append(other).append("; ");
        }
        final Vec3 pos = mc.gameRenderer == null ? null
            : ((CameraPositionDuck) mc.gameRenderer.getMainCamera()).vs_eureka$cameraPosition();
        if (pos != null) {
            final BlockPos at = BlockPos.containing(pos.x, pos.y, pos.z);
            out.append("world at camera: ").append(describe(level, at))
                .append(", 2 below: ").append(describe(level, at.below(2)))
                .append(", 4 below: ").append(describe(level, at.below(4)));
        }
        return out.toString();
    }

    private static String describe(final ClientLevel level, final BlockPos pos) {
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        final String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.getFluidState().isEmpty() ? block : block + "(fluid)";
    }

    public static void setFogPocket(final boolean value) {
        fogPocket = value;
    }

    public static boolean fogPocket() {
        return fogPocket;
    }

    // region Iris

    private static boolean irisChecked = false;
    private static Object irisApi = null;
    private static Method irisInUse = null;
    private static Method irisShadowPass = null;

    private static void resolveIris() {
        if (irisChecked) {
            return;
        }
        irisChecked = true;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
                irisShadowPass = api.getMethod("isRenderingShadowPass");
            } catch (final Throwable t) {
                LOGGER.warn("Iris is installed but its API could not be reached; treating shaders as off", t);
                irisApi = null;
                irisInUse = null;
                irisShadowPass = null;
            }
        }
    }

    /** True when an Iris shaderpack is active. False without Iris, and false if the API cannot be reached. */
    public static boolean shaderPackInUse() {
        resolveIris();
        if (irisInUse == null) {
            return false;
        }
        try {
            return (Boolean) irisInUse.invoke(irisApi);
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * True while Iris draws its shadow map. It renders the world's translucent layer a second time for that,
     * so a hook on that layer fires twice per frame under a pack; nothing of ours belongs in the shadow pass.
     */
    public static boolean renderingShadowPass() {
        resolveIris();
        if (irisShadowPass == null) {
            return false;
        }
        try {
            return (Boolean) irisShadowPass.invoke(irisApi);
        } catch (final Throwable t) {
            return false;
        }
    }

    // endregion
}
