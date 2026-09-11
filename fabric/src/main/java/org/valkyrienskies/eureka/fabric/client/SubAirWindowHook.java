package org.valkyrienskies.eureka.fabric.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.EurekaConfigLoader;
import org.valkyrienskies.mod.common.render.ShipRenderHooks;

/**
 * Under a shaderpack, makes a submarine's windows read as the sea's edge from inside.
 *
 * <p>A shaderpack draws the underwater look itself, from one answer per frame ("is the eye in water") and,
 * for everything else, from what it finds in its own buffers: a pixel is "behind water" when the nearest
 * translucent surface on it carries the pack's water material. Inside a dry hull the eye is in air, so the
 * only way the sea past the glass can look like sea is for a water surface to lie between eye and seafloor.
 * Just inside the window is exactly where that surface belongs. This hook tells VS2's ship renderer (see
 * {@link ShipRenderHooks}) to lay a sheet there: one extra quad a hair off the window's inner face, carrying
 * {@code minecraft:water}'s material, the cabin's own block light, and the sky light the sea has at the
 * window's depth.
 * The pack then absorbs and fogs everything beyond it along the ray and leaves the cabin alone, while the
 * glass itself keeps its own material and texture (relabelling the pane as water erased its frame: packs draw
 * water with no albedo). The sheets are drawn only while the camera is inside a hull's dry air, so from the
 * sea a window is glass and nothing more -- and only when asked for ({@code submarineWindowSheets}): most packs
 * give a sheet their full water look, reflections and ripple included, which reads as an aquarium wall from
 * inside. Without the sheets the interior is simply air to the pack and the glass is glass, which is the look
 * preferred so far; the marking still runs, so the sheets can be switched on without a re-bake.
 *
 * <p>Which faces: those of a see-through block (glass and its kin; not a door or bars, which are opaque
 * enough to wobble badly with a water film on them) that point INTO sub air, provided the far side of the
 * block leads to the outside -- a pane between two dry rooms stays glass. Such a window is a CANDIDATE
 * whether or not it is under the world's waterline right now: above it the answer is "leave everything as
 * baked", but it is still an answer, so VS2 remembers the section as one whose bake depends on the sea and
 * relights it when {@link ArmadaPocketOccluder} reports the hull a block higher or lower. (A window first
 * baked while surfaced would otherwise stay glass for good once the hull dived.)
 *
 * <p>Whether any of this reaches the screen is VS2's call ({@link ShipRenderHooks#isActive()}: its route
 * through the pack's water program must be live and the pack must map water at all); until it does, the
 * camera keeps the pack believing the eye is under water (see {@code MixinCameraSubAir}).
 *
 * <p>The hook itself lives in the nested {@link Impl} so that this class loads against a VS2 that predates
 * the registry: only {@code Impl} names VS2's types, and the failure to load IT is what {@link #register()}
 * catches.
 */
@Environment(EnvType.CLIENT)
public final class SubAirWindowHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("SubAirWindowHook");
    /** Sky light 14+ on a water surface makes this pack family mirror the sky; the sea is never that bright. */
    private static final int MAX_SKY = 13;
    /** How far to look outward from a window for the outside before giving up. */
    private static final int OUTSIDE_WALK = 4;

    private static boolean registered;
    private static long facesMarked;
    private static long blocksMarked;
    private static long candidates;

    private SubAirWindowHook() {
    }

    /** Register with VS2. A VS2 without the hook registry is tolerated: the legacy look stays. */
    public static void register() {
        try {
            Impl.registerSelf();
            registered = true;
            LOGGER.info("Sub-air window hook registered with VS2: a water sheet lies inside each window under shaders");
        } catch (final LinkageError e) {
            registered = false;
            LOGGER.warn("VS2 is too old for the sub-air window hook; submarines keep the whole-screen underwater "
                + "look under shaders ({})", e.toString());
        }
    }

    /** Whether window water reaches the screen right now (registered, VS2's route live, the switch on). */
    public static boolean active() {
        if (!registered || !EurekaConfig.CLIENT.getSubmarineWindowWater()
            || EurekaConfig.CLIENT.getSubmarineShaderLegacyUnderwater()) {
            return false;
        }
        try {
            return ShipRenderHooks.isActive();
        } catch (final LinkageError e) {
            return false;
        }
    }

    /**
     * Show or hide the sheets this frame. They are baked with the hull, so this is the per-frame switch: on
     * while the camera is inside a hull's dry air, off otherwise -- from the sea a window is plain glass, and
     * a sheet seen edge-on through a corner from outside would only be a dark wedge.
     */
    public static void setCameraInside(final boolean inside) {
        if (!registered) {
            return;
        }
        try {
            ShipRenderHooks.setOverlaysVisible(inside && EurekaConfig.CLIENT.getSubmarineWindowSheets());
        } catch (final LinkageError ignored) {
            // no registry, nothing to show
        }
    }

    /** Show or hide the sheets without touching the bake ({@code /vs sub-sheets}); the choice is saved. */
    public static void setSheetsEnabled(final boolean enabled) {
        EurekaConfig.CLIENT.setSubmarineWindowSheets(enabled);
        EurekaConfigLoader.save();
    }

    private static boolean sheetsShown() {
        if (!registered) {
            return false;
        }
        try {
            return ShipRenderHooks.overlaysVisible();
        } catch (final LinkageError e) {
            return false;
        }
    }

    /** After a toggle: the answers are baked into the ship meshes, so have VS2 bake them again. */
    public static void rebakeAll() {
        if (!registered) {
            return;
        }
        try {
            ShipRenderHooks.rebakeAll();
        } catch (final LinkageError ignored) {
            // no registry, nothing baked with our answers
        }
    }

    /** One clause for {@code /vs sub-status}. */
    public static String describe() {
        return "windowWater=" + (registered ? (active() ? "active" : "idle") : "no VS2 support")
            + " sheetsEnabled=" + EurekaConfig.CLIENT.getSubmarineWindowSheets() + " sheetsShown=" + sheetsShown() + " candidates=" + candidates + " blocksMarked=" + blocksMarked
            + " facesMarked=" + facesMarked;
    }

    /**
     * Glass and its kin: a block the sea is seen through. Opaque-textured non-occluders (doors, iron bars)
     * are not. Glass PANES come as {@code IronBarsBlock}s (vanilla's and Connected Glass's alike) on the
     * cutout layer, so they are told apart from bars by name.
     */
    static boolean seeThrough(final BlockState state) {
        final Block block = state.getBlock();
        if (block instanceof HalfTransparentBlock) {
            return true;
        }
        if (ItemBlockRenderTypes.getChunkRenderType(state) == ChunkSectionLayer.TRANSLUCENT) {
            return true;
        }
        return block instanceof IronBarsBlock && BuiltInRegistries.BLOCK.getKey(block).getPath().contains("glass");
    }

    /**
     * From the window, walk away from the pocket through see-through blocks: plain shipyard air, or water
     * the ship itself holds (a breach), is the outside; sub air is another dry room; anything else is hull.
     */
    static boolean leadsOutside(final ClientLevel level, final BlockPos pos, final Direction out,
        final Block subAir) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(pos);
        for (int step = 0; step < OUTSIDE_WALK; step++) {
            cursor.move(out);
            final BlockState s = level.getBlockState(cursor);
            if (s.getBlock() == subAir) {
                return false;
            }
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                return true;
            }
            if (!seeThrough(s)) {
                return false;
            }
        }
        return false;
    }

    /** The hook proper -- the only code here that names VS2's registry types. */
    private static final class Impl implements ShipRenderHooks.BlockHook {

        static void registerSelf() {
            ShipRenderHooks.register(new Impl());
        }

        @Override
        public ShipRenderHooks.FaceOverrides forBlock(final ClientLevel level, final BlockPos pos,
            final BlockState state, final Matrix4dc shipToWorld) {
            if (!EurekaConfig.CLIENT.getSubmarineWindowWater()
                || EurekaConfig.CLIENT.getSubmarineShaderLegacyUnderwater()) {
                return null;
            }
            if (!seeThrough(state)) {
                return null;
            }
            // Which faces point into the pocket with the outside behind them. Decided first, and from the
            // ship's blocks alone, so the answer's EXISTENCE never depends on where the hull happens to float.
            final Block subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
            final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int faces = 0;
            for (final Direction d : Direction.values()) {
                cursor.setWithOffset(pos, d);
                if (level.getBlockState(cursor).getBlock() == subAir && leadsOutside(level, pos, d.getOpposite(), subAir)) {
                    faces |= 1 << d.ordinal();
                }
            }
            if (faces == 0) {
                return null;
            }
            candidates++;
            final ShipRenderHooks.FaceOverrides o = new ShipRenderHooks.FaceOverrides();
            // A window above the waterline is glass: there is no sea past it to be the edge of. The answer is
            // still returned (everything left as baked) so the section is relit once the hull dives.
            final Vector3d world = new Vector3d();
            shipToWorld.transformPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, world);
            final BlockPos worldPos = BlockPos.containing(world.x, world.y, world.z);
            if (!level.getFluidState(worldPos).is(FluidTags.WATER)) {
                return o;
            }
            // The sea's own light at the hull, at this depth: vanilla water loses a level of sky per block.
            final int light = LightTexture.pack(0, Math.min(MAX_SKY, level.getBrightness(LightLayer.SKY, worldPos)));
            for (int d = 0; d < 6; d++) {
                if ((faces & (1 << d)) == 0) {
                    continue;
                }
                o.material[d] = Blocks.WATER.defaultBlockState();
                o.lightmap[d] = light;
                facesMarked++;
            }
            blocksMarked++;
            return o;
        }
    }
}
