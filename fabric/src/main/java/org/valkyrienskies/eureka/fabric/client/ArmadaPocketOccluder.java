package org.valkyrienskies.eureka.fabric.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.EurekaMod;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.render.ShipRenderHooks;
import org.valkyrienskies.mod.mixinducks.client.world.ClientChunkCacheDuck;

/**
 * Removes the world's water from inside a submarine's dry pocket -- and only from inside it.
 *
 * <h2>The problem</h2>
 * The sea surface that cuts through a hull at the waterline lives in the world's chunk mesh, which does not
 * re-bake as a ship moves through it, so it cannot be culled at bake time, and every chunk pipeline (vanilla,
 * Sodium, Iris) draws it. From inside the cabin that is a sheet of water at knee height.
 *
 * <h2>Why not a depth pre-pass</h2>
 * The first builds of this (after m3t4f1v3's {@code ShipPocketWorldWaterOccluder}) wrote near depth for every
 * submerged pocket voxel before the translucent pass so water inside the pocket z-failed. Depth is one value per
 * pixel, so that culled EVERYTHING behind a pocket face along the ray: the sea beyond the far wall from
 * inside, the surface above the roof of a shallow submerged hull, the sea past a hull looked through from
 * outside. It also had to decide per voxel whether it was "submerged", and that set flickered as the hull
 * bobbed across voxel boundaries.
 *
 * <h2>What this does instead: undo the water, per pixel, only where it landed inside the pocket</h2>
 * <ol>
 *   <li>{@link #beginWorldTranslucent}: copy the live colour and depth to SAVED. Draw the pocket's exterior
 *       faces (all of them, no culling) into two private depth textures: FRONT keeps the nearest face per pixel
 *       (LESS into a buffer cleared to far), BACK the farthest (GREATER into a buffer cleared to near). Along
 *       any ray FRONT..BACK is the span of the pocket. With the camera itself inside the pocket FRONT is
 *       cleared to 0 instead, so the span starts at the eye.</li>
 *   <li>The world's translucent terrain draws exactly as it normally would. Nothing of ours is in the live
 *       depth buffer.</li>
 *   <li>{@link #endWorldTranslucent}: copy the live depth to CURRENT and run one full-screen pass. A pixel
 *       whose CURRENT is nearer than SAVED had translucent geometry land on it; if that depth lies within
 *       FRONT..BACK the fragment was inside the pocket, and the pixel gets SAVED colour and SAVED depth back.
 *       Every other pixel is untouched.</li>
 * </ol>
 * Water outside the pocket is never affected, in any direction, so a hull can be looked through from outside
 * and the surface above a shallow submerged roof stays. The one loss is the water BEHIND an in-pocket fragment
 * along the same ray (the sea surface beyond the far wall, seen through the interior plane): its contribution
 * was blended under the interior fragment and the restore removes both. The old pass lost that too.
 *
 * <p>The pocket mesh is the exterior boundary of the ship's sub air, in ship space, rebuilt on a slow cadence
 * from a read of the ship's blocks that is spread over frames; it no longer depends on where the water is, so
 * a bobbing hull never re-meshes, and the read is only started once the ship's shipyard chunks have arrived
 * (a missing chunk reads as air and would pass for "no sub air" for the next half minute). Per frame the work is two
 * copies, two boundary draws and one full-screen pass, and only while some pocket actually straddles the
 * water's surface -- a hull riding above the sea or fully under it costs nothing.
 *
 * <p>Under a shaderpack the colour target is the pack's own set of buffers, which a single-attachment restore
 * cannot put back; there the same FRONT/EXIT bookkeeping steers the world's water into two draws instead --
 * beyond the pockets, then in front of them -- so nothing lands inside one to begin with (see
 * {@link #beginShaderInner}). Non-convex pockets along a ray (an L-shaped hull) are treated as their convex
 * span, which can remove water in the gap between two arms; and while the camera is inside one pocket a
 * second pocket on screen starts its span at the eye too. Both are accepted.
 *
 * <p>Ported ideas from LGPL-3.0 Valkyrien Skies 2 into this GPL-3.0 project, which the LGPL expressly permits.
 */
@Environment(EnvType.CLIENT)
public final class ArmadaPocketOccluder {

    private ArmadaPocketOccluder() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("ArmadaPocketOccluder");
    private static final int MAX_VOXELS = 131_072;
    /** How often a ship's pocket boundary is re-read from its blocks. Movement never triggers a rebuild. */
    private static final long REBUILD_INTERVAL_MS = 3_000L;
    /**
     * Ships with no sub air at all (almost every ship) are re-checked far less often: the scan walks the whole
     * block AABB, and a harbour full of ordinary boats should not pay for submarines.
     */
    private static final long EMPTY_REBUILD_INTERVAL_MS = 30_000L;
    /** How soon a read is retried when one of the ship's shipyard chunks has not arrived on the client yet. */
    private static final long CHUNK_RETRY_MS = 1_000L;
    /**
     * Blocks read per frame. A read that needs more carries on next frame, from where it stopped: a warship's
     * box is millions of blocks, and reading it in one frame was a visible hitch every 30 s per big ship.
     */
    private static final int SCAN_BUDGET = 16_384;
    /** Floor between two window relights of one ship (see relightOnDepthChange). */
    private static final long RELIGHT_MIN_MS = 250L;
    /** How long a read waits for a ship's shipyard chunks before reading whatever has arrived. */
    private static final long CHUNK_WAIT_MAX_MS = 5_000L;
    /** How far the water's surface may sit outside the pocket's world extent and still count as inside it. */
    private static final double SURFACE_MARGIN = 0.5;

    /** Debug view: the restore pass paints the pixels it puts back bright green instead of the saved colour. */
    private static boolean debug = false;
    /** Set on the first failure; a broken occluder must never take the frame (or the launch) down. */
    private static boolean broken = false;
    /** Set on the first failure of the pocket scan (no GL involved); separate from {@link #broken}. */
    private static boolean tickBroken = false;

    private static RenderPipeline frontPipeline;
    private static RenderPipeline backPipeline;
    private static RenderPipeline restorePipeline;
    private static RenderPipeline restoreDebugPipeline;
    // The shaderpack path's pipelines (see beginShaderInner).
    private static RenderPipeline exitPipeline;
    private static RenderPipeline seedAPipeline;
    private static RenderPipeline seedBPipeline;
    private static RenderPipeline restoreBPipeline;

    private static final Map<Long, ShipMesh> MESHES = new HashMap<>();
    private static ClientLevel lastLevel = null;

    // Scratch textures, sized like the live targets: SAVED colour + depth (before the translucent pass),
    // CURRENT depth (after it), FRONT and BACK (the pocket span per pixel).
    private static GpuTexture savedColor;
    private static GpuTextureView savedColorView;
    private static GpuTexture savedDepth;
    private static GpuTextureView savedDepthView;
    private static GpuTexture currentDepth;
    private static GpuTextureView currentDepthView;
    private static GpuTexture frontDepth;
    private static GpuTextureView frontDepthView;
    private static GpuTexture backDepth;
    private static GpuTextureView backDepthView;
    // Shaderpack path only: EXIT (the nearest face a ray leaves a pocket through) and AFTER_A (the depth once the
    // "beyond" draw is done).
    private static GpuTexture exitDepth;
    private static GpuTextureView exitDepthView;
    private static GpuTexture afterADepth;
    private static GpuTextureView afterADepthView;

    /** The full-screen quad the restore pass draws, in clip space. Built once. */
    private static GpuBuffer quadBuffer;

    // Per-frame state between begin and end.
    private static final List<ShipMesh> FRAME_MESHES = new ArrayList<>();
    private static GpuBufferSlice[] frameTransforms;
    private static GpuBuffer frameIndexBuffer;
    private static VertexFormat.IndexType frameIndexType;
    private static boolean frameActive = false;
    private static boolean frameCameraInside = false;
    /** This frame took the shaderpack path (endShaderInner rather than endInner). */
    private static boolean frameShader = false;
    /** The shaderpack path is inside its own, second draw of the world's translucent layer. */
    private static boolean reentrant = false;
    /** That second draw is running: the depth test is turned to GREATER for it (see the Sodium mixin). */
    private static boolean beyondPass = false;
    private static long framesDrawn = 0;
    private static long framesRestored = 0;
    private static long framesShader = 0;
    private static int lastActiveShips = 0;

    private static final class ShipMesh {
        private GpuBuffer vertexBuffer;
        private int indexCount;
        // Shipyard corner the vertices are stored relative to (float precision would wobble at 28 million).
        private int minX;
        private int minY;
        private int minZ;
        // The pocket's extent in ship space, exclusive maximum: [minX, maxX) etc. Drives the per-frame test.
        private int maxX;
        private int maxY;
        private int maxZ;
        /** When this ship's blocks are next read; 0 = as soon as possible. */
        private long nextScanMs = 0L;
        /** A read in progress across frames; null when idle. The mesh in use stays up until it completes. */
        private Scan scan;
        /** The pocket's top and bottom below the surface when its windows were last relit (NaN = never). */
        private double relitTop = Double.NaN;
        private double relitBottom = Double.NaN;
        private long lastRelightMs;
        /** When a read first found a shipyard chunk missing; 0 = not waiting. Bounds the wait. */
        private long chunkWaitSince;
        private boolean warnedTruncated;
        private int voxels;
        private int faces;

        private void close() {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            indexCount = 0;
            voxels = 0;
            faces = 0;
        }
    }

    /** One time-sliced read of a ship's shipyard box: what has been found so far, and where to resume. */
    private static final class Scan {
        private final LongOpenHashSet voxels = new LongOpenHashSet();
        private final int x0;
        private final int y0;
        private final int z0;
        private final int x1;
        private final int y1;
        private final int z1;
        private int x;
        private int y;
        private int z;
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private Scan(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x = x0;
            this.y = y0;
            this.z = z0;
        }

        private boolean done() {
            return x > x1;
        }
    }

    // region switches + status

    public static boolean isEnabled() {
        return EurekaConfig.CLIENT.getSubmarineOccluder() && !broken;
    }

    public static void setDebug(final boolean value) {
        debug = value;
        if (value) {
            EurekaConfig.CLIENT.setSubmarineOccluder(true);
        }
    }

    public static boolean isDebug() {
        return debug;
    }

    /** One line for {@code /vs sub-status}. */
    public static String describe() {
        int withGeometry = 0;
        int faces = 0;
        int voxels = 0;
        for (final ShipMesh m : MESHES.values()) {
            if (m.indexCount > 0) {
                withGeometry++;
                faces += m.faces;
                voxels += m.voxels;
            }
        }
        return "occluder=" + (broken ? "BROKEN" : EurekaConfig.CLIENT.getSubmarineOccluder())
            + " debug=" + debug + " shaderpack=" + SubAirClient.shaderPackInUse()
            + " ships=" + MESHES.size() + " withPocket=" + withGeometry
            + " pocketVoxels=" + voxels + " boundaryFaces=" + faces
            + " activeAtSurface=" + lastActiveShips + " cameraInside=" + frameCameraInside
            + " framesDrawn=" + framesDrawn + " framesShader=" + framesShader + " framesRestored=" + framesRestored
            + (withGeometry == 0 ? " (no sub air in any loaded ship)"
                : lastActiveShips == 0 ? " (no pocket straddles the water's surface right now)" : "");
    }

    public static void register() {
        LOGGER.info("Pocket occluder armed (enabled={} in the client config)",
            EurekaConfig.CLIENT.getSubmarineOccluder());
    }

    // endregion

    // region frame hooks

    /**
     * Right before the world's translucent chunk layer draws. {@code renderer}, {@code matrices} and
     * {@code sampler} are Sodium's {@code SodiumWorldRenderer}, {@code ChunkRenderMatrices} and the layer's
     * sampler, typed loosely so this class never names Sodium in a signature: under a shaderpack the occluder
     * draws the layer a second time itself with them.
     */
    public static void beginWorldTranslucent(final Object renderer, final Object matrices,
        final double x, final double y, final double z, final Object sampler) {
        if (reentrant) {
            return; // our own second draw of the layer: not a new frame
        }
        frameActive = false;
        frameShader = false;
        lastActiveShips = 0;
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }
        // Iris draws the world's translucent layer a second time for its shadow map; nothing here belongs in it.
        if (SubAirClient.renderingShadowPass()) {
            return;
        }
        // Every frame, every pipeline: the pocket meshes and, under a shaderpack, the windows' light. On its own
        // latch: a GL failure in the occluder must not take the scan, which needs no GL, down with it.
        if (!tickBroken) {
            try {
                tickPockets(level);
            } catch (final Throwable t) {
                tickBroken = true;
                LOGGER.error("Pocket scan failed and has been disabled for this session", t);
            }
        }
        if (!occluderLive()) {
            return;
        }
        try {
            if (SubAirClient.shaderPackInUse()) {
                // Experimental under a pack: the double draw is its own switch (see EurekaConfig).
                if (EurekaConfig.CLIENT.getSubmarineShaderOccluder()) {
                    beginShaderInner(level, renderer, matrices, x, y, z, sampler);
                }
            } else {
                beginInner(level);
            }
        } catch (final Throwable t) {
            fail(t);
        }
    }

    /**
     * Whether the occluder itself draws this frame: on, not broken, no shaderpack (the colour target is then
     * the pack's own MRT set, which a one-attachment restore cannot put back consistently) and not Fabulous
     * graphics (Sodium draws the translucent layer into the level renderer's own target then, not the main one).
     */
    private static boolean occluderLive() {
        // Fabulous graphics: Sodium draws the translucent layer into the level renderer's own translucent
        // target, not the main one. A restore of the main target would undo nothing and still count a frame.
        return !broken && EurekaConfig.CLIENT.getSubmarineOccluder() && !Minecraft.useShaderTransparency();
    }

    /** Whether the camera stands in some hull's sub air this frame (the ticked pose; a frame's lag at a hatch). */
    private static boolean cameraInPocket(final ClientLevel level) {
        final Vec3 cam =
            ((CameraPositionDuck) Minecraft.getInstance().gameRenderer.getMainCamera()).vs_eureka$cameraPosition();
        return cam != null && SubAirClient.cameraInSubAir(level, cam);
    }

    /**
     * Keep every loaded ship's pocket mesh current (a slice of one ship's blocks per frame), drop the meshes of
     * ships that are gone, and -- while a shaderpack shows a hull's windows as the sea's edge -- have VS2 relight
     * those windows when the hull has moved a block up or down.
     */
    private static void tickPockets(final ClientLevel level) {
        final boolean windowWater = SubAirWindowHook.active();
        // The window sheets show only from inside a hull's dry air: from the sea a window is plain glass.
        SubAirWindowHook.setCameraInside(windowWater && cameraInPocket(level));
        if (!windowWater && !occluderLive()) {
            return; // nothing consumes the meshes right now; the scans resume the moment something does
        }
        final long now = System.currentTimeMillis();
        final Vector3d scratch = new Vector3d();
        final LongOpenHashSet seen = new LongOpenHashSet();
        boolean scannedThisFrame = false;
        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            seen.add(ship.getId());
            final ShipMesh mesh = MESHES.computeIfAbsent(ship.getId(), id -> new ShipMesh());
            // At most one ship is read per frame, and never more than SCAN_BUDGET blocks of it.
            if (!scannedThisFrame && (mesh.scan != null || now >= mesh.nextScanMs)) {
                scan(level, ship, mesh, now);
                scannedThisFrame = true;
            }
            if (windowWater && mesh.indexCount > 0) {
                relightOnDepthChange(level, ship, mesh, now, scratch);
            }
        }
        // Ships that are gone (unloaded, bottled, sunk) take their buffers with them.
        if (MESHES.size() > seen.size()) {
            final var it = MESHES.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, ShipMesh> entry = it.next();
                if (!seen.contains(entry.getKey().longValue())) {
                    entry.getValue().close();
                    it.remove();
                }
            }
        }
    }

    /**
     * Under a shaderpack a window's inner face is baked as water lit by the sea at the window's depth (see
     * {@link SubAirWindowHook}). That light is baked, so once the pocket sits a block higher or lower than
     * when its sections were last baked, VS2 is asked to re-bake the ship's hooked sections in place. The
     * block of hysteresis and the floor between requests keep a hull bobbing on a swell from re-baking every
     * wave; a diving sub relights once per block of depth.
     */
    private static void relightOnDepthChange(final ClientLevel level, final LoadedShip ship, final ShipMesh mesh,
        final long now, final Vector3d scratch) {
        if (now - mesh.lastRelightMs < RELIGHT_MIN_MS) {
            return;
        }
        // The floor applies to every path out of here, so a hull nowhere near water is probed 4x a second, not
        // every frame.
        mesh.lastRelightMs = now;
        final ShipTransform xform = (ship instanceof final ClientShip cs)
            ? cs.getRenderTransform() : ship.getTransform();
        // The pocket's world extent from its eight ship-space corners: both its top and its bottom are watched,
        // so a hull that pitches (bow windows deeper, stern windows shallower) relights too, not only one that
        // moves as a whole.
        final Matrix4dc shipToWorld = xform.getShipToWorld();
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumX = 0.0;
        double sumZ = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            shipToWorld.transformPosition(
                (corner & 1) == 0 ? mesh.minX : mesh.maxX,
                (corner & 2) == 0 ? mesh.minY : mesh.maxY,
                (corner & 4) == 0 ? mesh.minZ : mesh.maxZ, scratch);
            minY = Math.min(minY, scratch.y);
            maxY = Math.max(maxY, scratch.y);
            sumX += scratch.x;
            sumZ += scratch.z;
        }
        final double surface = waterSurface(level, sumX / 8.0, sumZ / 8.0, maxY + 1.0, minY - 1.0);
        if (Double.isNaN(surface)) {
            return;
        }
        final double top = surface - maxY;
        final double bottom = surface - minY;
        if (!Double.isNaN(mesh.relitTop)
            && Math.abs(top - mesh.relitTop) < 1.0 && Math.abs(bottom - mesh.relitBottom) < 1.0) {
            return;
        }
        final var aabb = ship.getShipAABB();
        if (aabb == null) {
            return;
        }
        final int relit = ShipRenderHooks.relightHooked(
            aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ());
        if (relit == 0) {
            return; // nothing baked with a window answer yet (or no windows at all): ask again after the floor
        }
        mesh.relitTop = top;
        mesh.relitBottom = bottom;
        LOGGER.debug("Pocket depth: ship {} spans {}..{} blocks under the surface; {} window sections relit",
            ship.getId(), String.format("%.1f", top), String.format("%.1f", bottom), relit);
    }

    /** Right after the world's translucent chunk layer drew. */
    public static void endWorldTranslucent() {
        if (reentrant || !frameActive) {
            return;
        }
        frameActive = false;
        try {
            if (frameShader) {
                endShaderInner();
            } else {
                endInner();
            }
        } catch (final Throwable t) {
            fail(t);
        }
    }

    /** Whether the occluder's own "beyond" draw of the translucent layer is running (the Sodium mixin asks). */
    public static boolean beyondPassActive() {
        return beyondPass;
    }

    private static void fail(final Throwable t) {
        broken = true;
        clear();
        LOGGER.error("Pocket occluder failed and has been disabled for this session", t);
    }

    /**
     * The part both paths share: which pockets straddle the surface this frame, their transforms, the scratch
     * textures, the boundary index buffer and whether the camera is inside one. False = nothing to do.
     */
    private static boolean prepareFrame(final ClientLevel level) {
        final Vec3 cam =
            ((CameraPositionDuck) Minecraft.getInstance().gameRenderer.getMainCamera()).vs_eureka$cameraPosition();
        if (cam == null) {
            return false;
        }
        final PoseStack poseStack = new PoseStack();
        // The camera ROTATION for this frame. On 1.21.11 it is not on any pose stack handed around: the level
        // pass pushes it onto RenderSystem's model-view stack, and every world draw composes it in front of its
        // own pose (VS2's ship mesh cache does exactly this).
        final Matrix4f camModelView = new Matrix4f(RenderSystem.getModelViewMatrix());

        FRAME_MESHES.clear();
        final List<Matrix4f> modelViews = new ArrayList<>();
        boolean cameraInside = false;
        final Vector3d scratch = new Vector3d();
        final BlockPos.MutableBlockPos camCursor = new BlockPos.MutableBlockPos();
        final var subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final ShipMesh mesh = MESHES.get(ship.getId()); // kept current by tickPockets
            if (mesh == null || mesh.vertexBuffer == null || mesh.indexCount <= 0) {
                continue;
            }
            // Only a pocket the water's surface actually passes through has anything to undo. A hull riding
            // above the sea, or fully under it, is skipped -- and costs nothing.
            final ShipTransform xform = (ship instanceof final ClientShip cs)
                ? cs.getRenderTransform() : ship.getTransform();
            if (!straddlesSurface(level, xform.getShipToWorld(), mesh, scratch)) {
                continue;
            }
            // Is the camera inside THIS pocket? Decided in the render pose the faces are drawn in. The ticked
            // physics pose (what SubAir.isShielded reads) trails it by up to a tick of movement, and with the
            // camera against a wall or the roof that was enough to flip FRONT's clear value against the faces
            // for a frame -- the interior plane flashed in a moving sub with the third-person camera.
            xform.getWorldToShip().transformPosition(cam.x, cam.y, cam.z, scratch);
            camCursor.set((int) Math.floor(scratch.x), (int) Math.floor(scratch.y), (int) Math.floor(scratch.z));
            if (level.getBlockState(camCursor).getBlock() == subAir) {
                cameraInside = true;
            }
            poseStack.pushPose();
            try {
                // The mesh is stored relative to its own minimum corner and the offset is restored HERE, through
                // the double-precision path. Shipyard coordinates run to ~28 million, where a float model matrix
                // has lost whole blocks of precision -- baking world-space vertices would visibly wobble.
                VSClientGameUtils.transformRenderWithShip(
                    xform, poseStack, mesh.minX, mesh.minY, mesh.minZ, cam.x, cam.y, cam.z);
                modelViews.add(new Matrix4f(camModelView).mul(poseStack.last().pose()));
                FRAME_MESHES.add(mesh);
            } finally {
                poseStack.popPose();
            }
        }
        lastActiveShips = FRAME_MESHES.size();
        if (FRAME_MESHES.isEmpty()) {
            return false;
        }

        final GpuTextureView depth = liveDepth();
        final GpuTextureView color = liveColor();
        if (depth == null || color == null) {
            return false;
        }
        if (!ensureScratch(depth, color)) {
            return false;
        }
        ensureQuad();

        final DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();
        final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[FRAME_MESHES.size()];
        for (int i = 0; i < FRAME_MESHES.size(); i++) {
            transforms[i] = new DynamicUniforms.Transform(
                modelViews.get(i), new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), new Vector3f(), new Matrix4f());
        }
        frameTransforms = uniforms.writeTransforms(transforms);

        int maxIndexCount = 0;
        for (final ShipMesh m : FRAME_MESHES) {
            maxIndexCount = Math.max(maxIndexCount, m.indexCount);
        }
        final RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        frameIndexBuffer = seq.getBuffer(Math.max(maxIndexCount, 6));
        frameIndexType = seq.type();

        // Camera inside a pocket: the span starts at the eye, so FRONT is left at 0 (nothing passes LESS
        // against 0) and every pocket pixel restores from the eye out to its back face. Handles the water line
        // crossing the voxel the camera stands in, where no pocket face lies between eye and plane.
        frameCameraInside = cameraInside;
        return true;
    }

    /** No shaderpack: the per-pixel colour + depth restore described in the class comment. */
    private static void beginInner(final ClientLevel level) {
        if (!prepareFrame(level)) {
            return;
        }
        final GpuTextureView depth = liveDepth();
        final GpuTextureView color = liveColor();
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // 1. Remember colour and depth as they stand before the world's water draws.
        copyTexture(encoder, color.texture(), savedColor);
        copyTexture(encoder, depth.texture(), savedDepth);
        // 2. The pocket span per pixel: nearest boundary face into FRONT, farthest into BACK. Colour writes are
        //    off in these pipelines and there is no colour clear, so the colour attachment is never touched;
        //    SAVED colour is bound rather than the live target because Blaze3D caches one framebuffer per depth
        //    texture ON THE COLOUR VIEW, keyed by the depth texture's GL id, and frees it only with that view.
        //    Cached on the live view, a FRONT/BACK texture recreated after a level change could inherit the id
        //    of the one just deleted and land every boundary draw in a stale framebuffer -- the occluder went
        //    quietly inert. SAVED colour lives and dies with FRONT and BACK.
        drawBoundary(encoder, "armada_pocket_front", savedColorView, frontDepthView,
            frameCameraInside ? 0.0 : 1.0, frontPipeline());
        drawBoundary(encoder, "armada_pocket_back", savedColorView, backDepthView, 0.0, backPipeline());
        frameActive = true;
        framesDrawn++;
    }

    private static void endInner() {
        final GpuTextureView depth = liveDepth();
        final GpuTextureView color = liveColor();
        if (depth == null || color == null) {
            return;
        }
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        // The depth as the translucent pass left it. Sampled by the restore shader, which cannot read the
        // attachment it is writing.
        copyTexture(encoder, depth.texture(), currentDepth);
        // Fetched afresh, and before the pass opens (growing the shared buffer maps it, which an open pass
        // forbids): the handle taken before the world's water drew is not worth carrying across that draw.
        final RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer quadIndices = seq.getBuffer(6);
        final VertexFormat.IndexType quadIndexType = seq.type();
        try (RenderPass pass = encoder.createRenderPass(() -> "armada_pocket_restore", color, OptionalInt.empty(),
            depth, OptionalDouble.empty())) {
            pass.setPipeline(debug ? restoreDebugPipeline() : restorePipeline());
            final GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("SavedColor", savedColorView, sampler);
            pass.bindTexture("SavedDepth", savedDepthView, sampler);
            pass.bindTexture("CurrentDepth", currentDepthView, sampler);
            pass.bindTexture("FrontDepth", frontDepthView, sampler);
            pass.bindTexture("BackDepth", backDepthView, sampler);
            pass.setVertexBuffer(0, quadBuffer);
            pass.setIndexBuffer(quadIndices, quadIndexType);
            pass.drawIndexed(0, 0, 6, 1);
        }
        framesRestored++;
    }

    /**
     * Shaderpack path. The pack's colour targets are its own -- several of them, in its own framebuffer -- so
     * no colour is put back here. Instead the world's translucent layer is drawn TWICE, with the depth buffer
     * steering each draw so that nothing ever lands inside a pocket:
     * <ol>
     *   <li>FRONT (nearest boundary face, as always) and EXIT (nearest face a ray LEAVES a pocket through: the
     *       boundary quads face inward, so that is the nearest front-facing one) into their own textures.</li>
     *   <li>Seed the live depth to EXIT on every pixel that looks out of a pocket (its exit before the opaque
     *       scene), 1.0 everywhere else, and draw the layer with a GREATER test: only the sea BEYOND the pocket
     *       -- the surface past a window -- can pass, and it is blended into the pack's targets normally.</li>
     *   <li>Seed the live depth to the pocket's ENTRY on pocket pixels (0 with the camera inside) and the saved
     *       depth elsewhere, then let Sodium's own draw run: only water in front of the pocket can pass.</li>
     *   <li>After it (endShaderInner): pocket pixels the second draw left at the seed take the first draw's
     *       depth (or the scene's); every other pixel keeps the second draw's.</li>
     * </ol>
     * The pack then composites a scene in which the water inside the hull was simply never drawn. Known limits:
     * the first draw keeps the FARTHEST fragment beyond an exit (two surfaces stacked beyond a window blend in
     * draw order, and a surface hidden behind opaque terrain beyond the window is not hidden from it), and a
     * second pocket beyond a gap along the same ray is not cleared.
     */
    private static void beginShaderInner(final ClientLevel level, final Object renderer, final Object matrices,
        final double x, final double y, final double z, final Object sampler) {
        if (!prepareFrame(level)) {
            return;
        }
        final GpuTextureView depth = liveDepth();
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        final RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer quadIndices = seq.getBuffer(6);
        final VertexFormat.IndexType quadIndexType = seq.type();

        copyTexture(encoder, depth.texture(), savedDepth);
        drawBoundary(encoder, "armada_pocket_front", savedColorView, frontDepthView,
            frameCameraInside ? 0.0 : 1.0, frontPipeline());
        drawBoundary(encoder, "armada_pocket_exit", savedColorView, exitDepthView, 1.0, exitPipeline());

        // Seed for the "beyond" draw.
        try (RenderPass pass = encoder.createRenderPass(() -> "armada_pocket_seed_a", savedColorView,
            OptionalInt.empty(), depth, OptionalDouble.of(1.0))) {
            pass.setPipeline(seedAPipeline());
            pass.bindTexture("SavedDepth", savedDepthView, nearest);
            pass.bindTexture("ExitDepth", exitDepthView, nearest);
            pass.setVertexBuffer(0, quadBuffer);
            pass.setIndexBuffer(quadIndices, quadIndexType);
            pass.drawIndexed(0, 0, 6, 1);
        }
        // The "beyond" draw: the layer again, through Sodium (and Iris) exactly as the real one, with the depth
        // test turned to GREATER by MixinShaderChunkRendererPocketDepth once Sodium has set its state.
        beyondPass = true;
        reentrant = true;
        try {
            ((SodiumWorldRenderer) renderer).drawChunkLayer(ChunkSectionLayerGroup.TRANSLUCENT,
                (ChunkRenderMatrices) matrices, x, y, z, (GpuSampler) sampler);
        } finally {
            reentrant = false;
            beyondPass = false;
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
        }
        copyTexture(encoder, depth.texture(), afterADepth);

        // Seed for Sodium's own draw, which follows this hook.
        try (RenderPass pass = encoder.createRenderPass(() -> "armada_pocket_seed_b", savedColorView,
            OptionalInt.empty(), depth, OptionalDouble.of(1.0))) {
            pass.setPipeline(seedBPipeline());
            pass.bindTexture("SavedDepth", savedDepthView, nearest);
            pass.bindTexture("FrontDepth", frontDepthView, nearest);
            pass.bindTexture("ExitDepth", exitDepthView, nearest);
            pass.setVertexBuffer(0, quadBuffer);
            pass.setIndexBuffer(quadIndices, quadIndexType);
            pass.drawIndexed(0, 0, 6, 1);
        }
        frameShader = true;
        frameActive = true;
        framesShader++;
    }

    private static void endShaderInner() {
        final GpuTextureView depth = liveDepth();
        if (depth == null) {
            return;
        }
        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        copyTexture(encoder, depth.texture(), currentDepth);
        final GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        final RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer quadIndices = seq.getBuffer(6);
        final VertexFormat.IndexType quadIndexType = seq.type();
        try (RenderPass pass = encoder.createRenderPass(() -> "armada_pocket_restore_b", savedColorView,
            OptionalInt.empty(), depth, OptionalDouble.of(1.0))) {
            pass.setPipeline(restoreBPipeline());
            pass.bindTexture("SavedDepth", savedDepthView, nearest);
            pass.bindTexture("FrontDepth", frontDepthView, nearest);
            pass.bindTexture("ExitDepth", exitDepthView, nearest);
            pass.bindTexture("AfterADepth", afterADepthView, nearest);
            pass.bindTexture("CurrentDepth", currentDepthView, nearest);
            pass.setVertexBuffer(0, quadBuffer);
            pass.setIndexBuffer(quadIndices, quadIndexType);
            pass.drawIndexed(0, 0, 6, 1);
        }
        framesRestored++;
    }

    private static void drawBoundary(final CommandEncoder encoder, final String label, final GpuTextureView color,
        final GpuTextureView depth, final double clearDepth, final RenderPipeline pipeline) {
        try (RenderPass pass = encoder.createRenderPass(() -> label, color, OptionalInt.empty(), depth,
            OptionalDouble.of(clearDepth))) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < FRAME_MESHES.size(); i++) {
                pass.setUniform("DynamicTransforms", frameTransforms[i]);
                pass.setVertexBuffer(0, FRAME_MESHES.get(i).vertexBuffer);
                pass.setIndexBuffer(frameIndexBuffer, frameIndexType);
                pass.drawIndexed(0, 0, FRAME_MESHES.get(i).indexCount, 1);
            }
        }
    }

    // endregion

    // region the surface test

    /**
     * Does the water's surface pass through this pocket's world-space extent? The eight corners of the pocket's
     * ship-space box give its world extent (conservative for a rolled hull); the surface height is read in the
     * column under the pocket's centre. No water there at all -- a hull in the air, or on land -- answers no.
     */
    private static boolean straddlesSurface(final ClientLevel level, final Matrix4dc shipToWorld,
        final ShipMesh mesh, final Vector3d scratch) {
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumX = 0.0;
        double sumZ = 0.0;
        for (int corner = 0; corner < 8; corner++) {
            shipToWorld.transformPosition(
                (corner & 1) == 0 ? mesh.minX : mesh.maxX,
                (corner & 2) == 0 ? mesh.minY : mesh.maxY,
                (corner & 4) == 0 ? mesh.minZ : mesh.maxZ, scratch);
            minY = Math.min(minY, scratch.y);
            maxY = Math.max(maxY, scratch.y);
            sumX += scratch.x;
            sumZ += scratch.z;
        }
        final double surface = waterSurface(level, sumX / 8.0, sumZ / 8.0, maxY, minY);
        if (Double.isNaN(surface)) {
            return false;
        }
        return surface > minY - SURFACE_MARGIN && surface < maxY + SURFACE_MARGIN;
    }

    /**
     * The height of the water's surface in one column, or NaN if there is no water between {@code lowY} and a
     * good way above {@code highY}. Starts at the pocket's top: in water, walk up to the surface; in air, walk
     * down to the water. Either way the answer is the top of the water body nearest the pocket.
     */
    private static double waterSurface(final ClientLevel level, final double x, final double z,
        final double highY, final double lowY) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        int y = (int) Math.floor(highY);
        cursor.set(bx, y, bz);
        if (level.getFluidState(cursor).is(FluidTags.WATER)) {
            // Under water: climb until the column turns to something else; the last water block is the top.
            for (int step = 0; step < 64; step++) {
                cursor.set(bx, y + 1, bz);
                if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
                    break;
                }
                y++;
            }
            cursor.set(bx, y, bz);
            final FluidState top = level.getFluidState(cursor);
            return y + top.getHeight(level, cursor);
        }
        // In air: descend to the water, but not much past the pocket's bottom.
        final int floor = (int) Math.floor(lowY) - 1;
        for (; y >= floor; y--) {
            cursor.set(bx, y, bz);
            final FluidState fluid = level.getFluidState(cursor);
            if (fluid.is(FluidTags.WATER)) {
                return y + fluid.getHeight(level, cursor);
            }
        }
        return Double.NaN;
    }

    // endregion

    // region targets + scratch

    /** Honour the target overrides exactly as vanilla does. */
    private static GpuTextureView liveDepth() {
        final RenderTarget target = OutputTarget.MAIN_TARGET.getRenderTarget();
        if (RenderSystem.outputDepthTextureOverride != null) {
            return RenderSystem.outputDepthTextureOverride;
        }
        return target.useDepth ? target.getDepthTextureView() : null;
    }

    private static GpuTextureView liveColor() {
        final RenderTarget target = OutputTarget.MAIN_TARGET.getRenderTarget();
        return RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
    }

    /** Scratch textures sized and formatted like the live ones; rebuilt when either changes. */
    private static boolean ensureScratch(final GpuTextureView depth, final GpuTextureView color) {
        final GpuTexture liveDepthTex = depth.texture();
        final GpuTexture liveColorTex = color.texture();
        final int w = liveDepthTex.getWidth(0);
        final int h = liveDepthTex.getHeight(0);
        if (w <= 0 || h <= 0 || liveColorTex.getWidth(0) != w || liveColorTex.getHeight(0) != h) {
            return false;
        }
        if (savedDepth != null && savedDepth.getWidth(0) == w && savedDepth.getHeight(0) == h
            && savedDepth.getFormat() == liveDepthTex.getFormat()
            && savedColor != null && savedColor.getFormat() == liveColorTex.getFormat()) {
            return true;
        }
        closeScratch();
        final int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;
        savedColor = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_saved_color", usage, liveColorTex.getFormat(), w, h, 1, 1);
        savedColorView = RenderSystem.getDevice().createTextureView(savedColor);
        savedDepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_saved_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        savedDepthView = RenderSystem.getDevice().createTextureView(savedDepth);
        currentDepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_current_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        currentDepthView = RenderSystem.getDevice().createTextureView(currentDepth);
        frontDepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_front_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        frontDepthView = RenderSystem.getDevice().createTextureView(frontDepth);
        backDepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_back_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        backDepthView = RenderSystem.getDevice().createTextureView(backDepth);
        exitDepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_exit_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        exitDepthView = RenderSystem.getDevice().createTextureView(exitDepth);
        afterADepth = RenderSystem.getDevice().createTexture(
            () -> "armada_pocket_after_a_depth", usage, liveDepthTex.getFormat(), w, h, 1, 1);
        afterADepthView = RenderSystem.getDevice().createTextureView(afterADepth);
        LOGGER.info("Pocket occluder scratch {}x{} (colour {}, depth {})", w, h,
            liveColorTex.getFormat(), liveDepthTex.getFormat());
        return true;
    }

    private static void copyTexture(final CommandEncoder encoder, final GpuTexture from, final GpuTexture to) {
        encoder.copyTextureToTexture(from, to, 0, 0, 0, 0, 0, from.getWidth(0), from.getHeight(0));
    }

    private static void ensureQuad() {
        if (quadBuffer != null) {
            return;
        }
        try (ByteBufferBuilder byteBuilder = new ByteBufferBuilder(256)) {
            final BufferBuilder builder =
                new BufferBuilder(byteBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0f, -1.0f, 0.0f);
            builder.addVertex(1.0f, -1.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            builder.addVertex(-1.0f, 1.0f, 0.0f);
            try (MeshData data = builder.buildOrThrow()) {
                quadBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "armada_pocket_restore_quad", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
            }
        }
    }

    private static void closeScratch() {
        savedColorView = closeView(savedColorView);
        savedColor = closeTexture(savedColor);
        savedDepthView = closeView(savedDepthView);
        savedDepth = closeTexture(savedDepth);
        currentDepthView = closeView(currentDepthView);
        currentDepth = closeTexture(currentDepth);
        frontDepthView = closeView(frontDepthView);
        frontDepth = closeTexture(frontDepth);
        backDepthView = closeView(backDepthView);
        backDepth = closeTexture(backDepth);
        exitDepthView = closeView(exitDepthView);
        exitDepth = closeTexture(exitDepth);
        afterADepthView = closeView(afterADepthView);
        afterADepth = closeTexture(afterADepth);
    }

    private static GpuTextureView closeView(final GpuTextureView view) {
        if (view != null) {
            view.close();
        }
        return null;
    }

    private static GpuTexture closeTexture(final GpuTexture texture) {
        if (texture != null) {
            texture.close();
        }
        return null;
    }

    // endregion

    // region mesh

    /**
     * Read up to {@link #SCAN_BUDGET} more blocks of a ship's shipyard box -- starting a read if none is in
     * progress -- and turn a finished read into the pocket's exterior boundary, in ship space.
     */
    private static void scan(final ClientLevel level, final LoadedShip ship, final ShipMesh mesh, final long now) {
        if (mesh.scan == null) {
            final var aabb = ship.getShipAABB();
            if (aabb == null) {
                mesh.close();
                mesh.nextScanMs = now + EMPTY_REBUILD_INTERVAL_MS;
                return;
            }
            // Every shipyard chunk under the box should have arrived. On the client a chunk that has not reads
            // as air (VS2 hands out an empty placeholder, and ClientLevel.hasChunk answers true for anything), and
            // one read like that used to stamp a freshly loaded submarine "no sub air" for 30 s: the sea in the
            // cabin right after logging in, teleporting or assembling. So the chunks are checked against VS2's
            // own store of received ship chunks -- for a bounded while: a corner chunk the server never sends
            // must not hold the scan up for good.
            if (now - mesh.chunkWaitSince < CHUNK_WAIT_MAX_MS || mesh.chunkWaitSince == 0L) {
                final var shipChunks = ((ClientChunkCacheDuck) level.getChunkSource()).vs$getShipChunks();
                for (int cx = aabb.minX() >> 4; cx <= aabb.maxX() >> 4; cx++) {
                    for (int cz = aabb.minZ() >> 4; cz <= aabb.maxZ() >> 4; cz++) {
                        if (!shipChunks.containsKey(ChunkPos.asLong(cx, cz))) {
                            if (mesh.chunkWaitSince == 0L) {
                                mesh.chunkWaitSince = now;
                            }
                            mesh.nextScanMs = now + CHUNK_RETRY_MS;
                            return;
                        }
                    }
                }
            }
            mesh.chunkWaitSince = 0L;
            mesh.scan = new Scan(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ());
        }

        // Pass 1, a slice at a time: which voxels are sub air. Packed BlockPos longs: the set is what makes
        // "exterior" cheap in pass 2.
        final Scan s = mesh.scan;
        final var subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int budget = SCAN_BUDGET; budget > 0 && !s.done(); budget--) {
            cursor.set(s.x, s.y, s.z);
            if (level.getBlockState(cursor).getBlock() == subAir) {
                s.voxels.add(BlockPos.asLong(s.x, s.y, s.z));
                s.minX = Math.min(s.minX, s.x);
                s.minY = Math.min(s.minY, s.y);
                s.minZ = Math.min(s.minZ, s.z);
                s.maxX = Math.max(s.maxX, s.x);
                s.maxY = Math.max(s.maxY, s.y);
                s.maxZ = Math.max(s.maxZ, s.z);
                if (s.voxels.size() >= MAX_VOXELS) {
                    if (!mesh.warnedTruncated) {
                        mesh.warnedTruncated = true;
                        LOGGER.warn("Ship {} has more than {} pocket voxels; occluder mesh truncated",
                            ship.getId(), MAX_VOXELS);
                    }
                    s.x = s.x1 + 1;
                    break;
                }
            }
            if (++s.z > s.z1) {
                s.z = s.z0;
                if (++s.y > s.y1) {
                    s.y = s.y0;
                    s.x++;
                }
            }
        }
        if (!s.done()) {
            return; // the rest next frame; the mesh in use stays up meanwhile
        }
        mesh.scan = null;
        finish(ship, mesh, s, now);
    }

    /** A finished read becomes the boundary mesh -- or nothing, for a ship without sub air. */
    private static void finish(final LoadedShip ship, final ShipMesh mesh, final Scan s, final long now) {
        mesh.close();
        if (s.voxels.isEmpty()) {
            mesh.nextScanMs = now + EMPTY_REBUILD_INTERVAL_MS;
            if (debug) {
                LOGGER.info("Pocket occluder: ship {} scanned [{},{},{}]..[{},{},{}] and found no sub air",
                    ship.getId(), s.x0, s.y0, s.z0, s.x1, s.y1, s.z1);
            }
            return;
        }
        mesh.nextScanMs = now + REBUILD_INTERVAL_MS;
        mesh.minX = s.minX;
        mesh.minY = s.minY;
        mesh.minZ = s.minZ;
        mesh.maxX = s.maxX + 1;
        mesh.maxY = s.maxY + 1;
        mesh.maxZ = s.maxZ + 1;

        // Pass 2: one quad per voxel face that borders something other than sub air. That is the pocket's
        // boundary, and nothing else: interior faces would only add candidates that can never be the nearest
        // or farthest along a ray.
        int faces = 0;
        try (ByteBufferBuilder byteBuilder = new ByteBufferBuilder(4096)) {
            final BufferBuilder builder =
                new BufferBuilder(byteBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            final var it = s.voxels.iterator();
            while (it.hasNext()) {
                final long packed = it.nextLong();
                final int x = BlockPos.getX(packed);
                final int y = BlockPos.getY(packed);
                final int z = BlockPos.getZ(packed);
                faces += emitExteriorFaces(builder, s.voxels, x, y, z, x - s.minX, y - s.minY, z - s.minZ);
            }
            final MeshData data = builder.build();
            if (data == null) {
                return;
            }
            try (data) {
                mesh.vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "armada_pocket_boundary_verts", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
                mesh.indexCount = data.drawState().indexCount();
                mesh.voxels = s.voxels.size();
                mesh.faces = faces;
                LOGGER.debug("Pocket occluder: ship {} has {} sub-air voxels, {} boundary faces",
                    ship.getId(), s.voxels.size(), faces);
            }
        }
    }

    /** The faces of one voxel whose neighbour is not sub air, at mesh-local coordinates. Returns how many. */
    private static int emitExteriorFaces(final BufferBuilder b, final LongOpenHashSet voxels,
        final int wx, final int wy, final int wz, final int x, final int y, final int z) {
        final float x0 = x;
        final float y0 = y;
        final float z0 = z;
        final float x1 = x + 1.0f;
        final float y1 = y + 1.0f;
        final float z1 = z + 1.0f;
        final int argb = 0xFFFFFFFF;
        int faces = 0;
        if (!voxels.contains(BlockPos.asLong(wx, wy - 1, wz))) {
            quad(b, argb, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
            faces++;
        }
        if (!voxels.contains(BlockPos.asLong(wx, wy + 1, wz))) {
            quad(b, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
            faces++;
        }
        if (!voxels.contains(BlockPos.asLong(wx, wy, wz - 1))) {
            quad(b, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
            faces++;
        }
        if (!voxels.contains(BlockPos.asLong(wx, wy, wz + 1))) {
            quad(b, argb, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
            faces++;
        }
        if (!voxels.contains(BlockPos.asLong(wx - 1, wy, wz))) {
            quad(b, argb, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
            faces++;
        }
        if (!voxels.contains(BlockPos.asLong(wx + 1, wy, wz))) {
            quad(b, argb, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
            faces++;
        }
        return faces;
    }

    private static void quad(final BufferBuilder b, final int argb,
        final float ax, final float ay, final float az, final float bx, final float by, final float bz,
        final float cx, final float cy, final float cz, final float dx, final float dy, final float dz) {
        b.addVertex(ax, ay, az).setColor(argb);
        b.addVertex(bx, by, bz).setColor(argb);
        b.addVertex(cx, cy, cz).setColor(argb);
        b.addVertex(dx, dy, dz).setColor(argb);
    }

    // endregion

    // region pipelines

    private static RenderPipeline frontPipeline() {
        if (frontPipeline == null) {
            frontPipeline = buildBoundaryPipeline("armada/pocket_front", DepthTestFunction.LESS_DEPTH_TEST);
        }
        return frontPipeline;
    }

    private static RenderPipeline backPipeline() {
        if (backPipeline == null) {
            backPipeline = buildBoundaryPipeline("armada/pocket_back", DepthTestFunction.GREATER_DEPTH_TEST);
        }
        return backPipeline;
    }

    private static RenderPipeline restorePipeline() {
        if (restorePipeline == null) {
            restorePipeline = buildRestorePipeline("armada/pocket_restore", false);
        }
        return restorePipeline;
    }

    private static RenderPipeline restoreDebugPipeline() {
        if (restoreDebugPipeline == null) {
            restoreDebugPipeline = buildRestorePipeline("armada/pocket_restore_debug", true);
        }
        return restoreDebugPipeline;
    }

    private static RenderPipeline exitPipeline() {
        if (exitPipeline == null) {
            exitPipeline = buildBoundaryPipeline("armada/pocket_exit", DepthTestFunction.LESS_DEPTH_TEST,
                Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "core/pocket_exit"));
        }
        return exitPipeline;
    }

    private static RenderPipeline seedAPipeline() {
        if (seedAPipeline == null) {
            seedAPipeline = buildDepthPassPipeline("armada/pocket_seed_a", "core/pocket_seed_a",
                "SavedDepth", "ExitDepth");
        }
        return seedAPipeline;
    }

    private static RenderPipeline seedBPipeline() {
        if (seedBPipeline == null) {
            seedBPipeline = buildDepthPassPipeline("armada/pocket_seed_b", "core/pocket_seed_b",
                "SavedDepth", "FrontDepth", "ExitDepth");
        }
        return seedBPipeline;
    }

    private static RenderPipeline restoreBPipeline() {
        if (restoreBPipeline == null) {
            restoreBPipeline = buildDepthPassPipeline("armada/pocket_restore_b", "core/pocket_restore_b",
                "SavedDepth", "FrontDepth", "ExitDepth", "AfterADepth", "CurrentDepth");
        }
        return restoreBPipeline;
    }

    /**
     * Depth-only write of the boundary into a private depth texture. No culling, so both faces of the boundary
     * take part: with LESS the nearest one wins (FRONT), with GREATER the farthest (BACK). No bias -- these
     * never compete with the water in the live buffer, they only bound its depth.
     */
    private static RenderPipeline buildBoundaryPipeline(final String location, final DepthTestFunction test) {
        return buildBoundaryPipeline(location, test, null);
    }

    /** As above, with an own fragment shader (the EXIT pass keeps only the faces turned toward the camera). */
    private static RenderPipeline buildBoundaryPipeline(final String location, final DepthTestFunction test,
        final Identifier fragmentShader) {
        final RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader("core/position_color");
        if (fragmentShader == null) {
            builder.withFragmentShader("core/position_color");
        } else {
            builder.withFragmentShader(fragmentShader);
        }
        return builder
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(test)
            .withCull(false)
            .withColorWrite(false)
            .withDepthWrite(true)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .build();
    }

    /**
     * The full-screen restore. The fragment shader decides per pixel and writes SAVED depth through
     * gl_FragDepth; GREATER (SAVED is never nearer than what the water left) is the depth test that both lets
     * that write through and keeps depth writes on at all -- NO_DEPTH_TEST turns them off. No blending: the
     * saved colour REPLACES what the water blended.
     */
    private static RenderPipeline buildRestorePipeline(final String location, final boolean debugTint) {
        final RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "core/pocket_restore"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "core/pocket_restore"))
            .withSampler("SavedColor")
            .withSampler("SavedDepth")
            .withSampler("CurrentDepth")
            .withSampler("FrontDepth")
            .withSampler("BackDepth")
            .withDepthTestFunction(DepthTestFunction.GREATER_DEPTH_TEST)
            .withCull(false)
            .withColorWrite(true)
            .withDepthWrite(true)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS);
        if (debugTint) {
            builder.withShaderDefine("POCKET_DEBUG");
        }
        return builder.build();
    }

    /**
     * A full-screen depth-only pass of the shaderpack path: its fragment shader decides the depth per pixel and
     * writes it through gl_FragDepth. The pass clears the target to 1.0 first, so LESS lets every value below
     * 1.0 through (and keeps depth writes on -- NO_DEPTH_TEST turns them off); a pixel that should hold 1.0
     * simply discards. Colour writes are off: under a shaderpack the colour is the pack's business.
     */
    private static RenderPipeline buildDepthPassPipeline(final String location, final String fragmentShader,
        final String... samplers) {
        final RenderPipeline.Builder builder = RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader(Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "core/pocket_restore"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, fragmentShader));
        for (final String sampler : samplers) {
            builder.withSampler(sampler);
        }
        return builder
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withCull(false)
            .withColorWrite(false)
            .withDepthWrite(true)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .build();
    }

    // endregion

    /** Drop every cached mesh and scratch texture (level change, or after a failure). */
    public static void clear() {
        for (final ShipMesh m : MESHES.values()) {
            m.close();
        }
        MESHES.clear();
        FRAME_MESHES.clear();
        frameActive = false;
        frameShader = false;
        reentrant = false;
        beyondPass = false;
        lastActiveShips = 0;
        closeScratch();
        if (quadBuffer != null) {
            quadBuffer.close();
            quadBuffer = null;
        }
        lastLevel = null;
    }
}
