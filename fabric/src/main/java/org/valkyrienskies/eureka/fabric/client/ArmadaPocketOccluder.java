package org.valkyrienskies.eureka.fabric.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Stops the world's water from drawing inside a submarine, by writing depth for the pocket before the world's
 * translucent pass.
 *
 * <h2>Why depth and not a shader</h2>
 * The sea surface that cuts through a partly submerged hull lives in the world's chunk mesh, which does not
 * re-bake as a ship moves through it, so it cannot be culled at bake time. Upstream's first attempt
 * (`ShipWaterPocketShaderInjector` on VS2's `1.20.1/new_airpockets`) regex-patched a discard into Embeddium's
 * chunk shaders; that is why it never worked under Iris, which supplies its own programs. The approach here is
 * m3t4f1v3's `ShipPocketWorldWaterOccluder` from `1.20.1/new_airpockets_fasterer`, ported to 1.21.11's Blaze3D:
 * touch only the shared depth buffer and the colour-write mask, which every chunk pipeline respects -- vanilla,
 * Sodium and Iris alike.
 *
 * <h2>Why a cube per voxel, and why interior faces are kept</h2>
 * The trick works because a water fragment inside the pocket must have occluder depth in front of it. With one
 * cube per pocket voxel, the ray from the camera to a fragment inside voxel V necessarily crosses V's near face
 * first, so V's depth wins. A single hull-shaped shell would NOT do: with the camera inside the pocket, no shell
 * face lies between the eye and the water, and the plane draws anyway. So the mesh is deliberately un-optimised
 * -- no merging, no interior-face removal. Culling is off so it holds whether the camera is outside a given cube
 * (front faces) or inside it (back faces), and a negative depth bias pulls it toward the camera so water at the
 * same position loses GL_LEQUAL.
 *
 * <h2>Known consequence, and the point of this build</h2>
 * Depth is one value per pixel. Looking out of a window, the pocket voxels between the eye and the glass have
 * already written near depth, so the sea OUTSIDE is farther and fails the same test -- the occluder is expected
 * to hide the exterior ocean too, which is why upstream also ships a boundary liquid overlay and an exterior fog
 * pass. Rather than assume how bad that looks, {@link #setDebug} draws the occluder geometry in magenta with
 * colour writes on, and the whole pass toggles at runtime, so one session can compare all three states.
 *
 * <p>Ported from LGPL-3.0 Valkyrien Skies 2 (m3t4f1v3's `new_airpockets_fasterer` branch) into this GPL-3.0
 * project, which the LGPL expressly permits.
 */
@Environment(EnvType.CLIENT)
public final class ArmadaPocketOccluder {

    private ArmadaPocketOccluder() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("ArmadaPocketOccluder");

    /** Ceiling on pocket voxels meshed per ship. A cube each is 24 vertices, so this is ~3M vertices. */
    private static final int MAX_VOXELS = 131_072;

    /** Rebuild cadence for a ship that HAS sub air. The pocket only changes when someone edits the ship. */
    private static final long REBUILD_INTERVAL_MS = 3_000L;

    /**
     * Rebuild cadence for a ship that turned out to have no sub air at all. Almost every ship in a world is an
     * ordinary one, and a rebuild walks the entire block AABB -- at the normal cadence a harbour full of ships
     * would re-scan all of them every three seconds and hitch. They get one cheap scan, then are left alone.
     */
    private static final long EMPTY_REBUILD_INTERVAL_MS = 30_000L;

    private static boolean enabled = true;
    private static boolean debug = false;
    /** Set on the first failure; a broken occluder must never take the frame (or the launch) down. */
    private static boolean broken = false;

    private static RenderPipeline occludePipeline;
    private static RenderPipeline debugPipeline;

    private static final Map<Long, ShipMesh> MESHES = new HashMap<>();
    private static ClientLevel lastLevel = null;

    /** One ship's pocket, as GPU geometry in coordinates local to {@link #minX}/{@link #minY}/{@link #minZ}. */
    private static final class ShipMesh {
        private GpuBuffer vertexBuffer;
        private int indexCount;
        private int minX;
        private int minY;
        private int minZ;
        // MUST NOT be Long.MIN_VALUE: the due check is `now - builtAtMs >= interval`, and
        // `now - Long.MIN_VALUE` overflows past Long.MAX_VALUE into a NEGATIVE number, so the first
        // rebuild never fires and the occluder silently draws nothing forever. Zero means "never built"
        // and reads as ~57 years overdue.
        private long builtAtMs = 0L;
        /** Last scan found no sub air, so back off hard -- see {@link #EMPTY_REBUILD_INTERVAL_MS}. */
        private boolean wasEmpty = false;
        /** World Y the mesh was built at; the submerged set changes as the hull rises and dives. */
        private double builtAtY = Double.NaN;

        private void close() {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                vertexBuffer = null;
            }
            indexCount = 0;
        }
    }

    // region toggles

    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled && !broken;
    }

    public static void setDebug(final boolean value) {
        debug = value;
        // Turning the visualisation on while the pass is disabled draws nothing, which reads exactly like a
        // broken occluder. It cost a whole test run once; debug now implies enabled.
        if (value) {
            enabled = true;
        }
    }

    public static boolean isDebug() {
        return debug;
    }

    /**
     * Human-readable state for the toggle commands. Reports the VOXEL COUNT, not just the map size: an entry
     * exists for every loaded ship whether or not it has any sub air, so "meshes=1" alone cannot distinguish
     * "one sub is meshed" from "one ordinary ship is loaded and there is nothing to draw".
     */
    public static String describe() {
        int withGeometry = 0;
        int indices = 0;
        for (final ShipMesh m : MESHES.values()) {
            if (m.indexCount > 0) {
                withGeometry++;
                indices += m.indexCount;
            }
        }
        return "occluder=" + (broken ? "BROKEN" : enabled) + " debug=" + debug
            + " ships=" + MESHES.size() + " meshed=" + withGeometry + " indices=" + indices
            + (withGeometry == 0 ? " (NOTHING TO DRAW -- run /armada subair <ship> all first)" : "");
    }

    // endregion

    /** Register the per-frame pass. Called once from the Fabric client initializer. */
    public static void register() {
        // AFTER_ENTITIES runs inside the level pass after solid and entities but BEFORE terrain translucent --
        // the correct slot for a depth pre-pass, and the same Iris-captured pass VS2's influence-border
        // renderer already draws in successfully.
        WorldRenderEvents.AFTER_ENTITIES.register((WorldRenderEvents.AfterEntities) ArmadaPocketOccluder::render);
    }

    private static void render(final WorldRenderContext context) {
        if (!enabled || broken) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }
        try {
            renderInner(context, level);
        } catch (final Throwable t) {
            broken = true;
            clear();
            LOGGER.error("Pocket occluder failed and has been disabled for this session", t);
        }
    }

    private static void renderInner(final WorldRenderContext context, final ClientLevel level) {
        final PoseStack poseStack = context.matrices();
        if (poseStack == null) {
            return;
        }
        final RenderPipeline pipeline = pipeline();
        if (pipeline == null) {
            return;
        }
        final Vec3 cam =
            ((CameraPositionDuck) Minecraft.getInstance().gameRenderer.getMainCamera()).vs_eureka$cameraPosition();
        if (cam == null) {
            return;
        }
        final long now = System.currentTimeMillis();

        // Gather first so the render pass itself does only binds and draws -- building a mesh mid-pass would
        // mean mapping a buffer while a pass is open.
        final java.util.List<ShipMesh> ready = new java.util.ArrayList<>();
        final java.util.List<Matrix4f> modelViews = new java.util.ArrayList<>();
        // At most one ship is re-meshed per frame: two big hulls falling due on the same frame would stall it.
        boolean rebuiltThisFrame = false;
        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final ShipMesh mesh = MESHES.computeIfAbsent(ship.getId(), id -> new ShipMesh());
            final long due = mesh.wasEmpty ? EMPTY_REBUILD_INTERVAL_MS : REBUILD_INTERVAL_MS;
            // Height matters as much as time: only submerged voxels are meshed, so surfacing or diving
            // changes the set immediately even though the blocks did not.
            final double shipY = ship.getTransform().getPositionInWorld().y();
            final boolean moved = Double.isNaN(mesh.builtAtY) || Math.abs(shipY - mesh.builtAtY) > 0.5;
            if (!rebuiltThisFrame && (moved || now - mesh.builtAtMs >= due)) {
                rebuild(level, ship, mesh, now, shipY);
                rebuiltThisFrame = true;
            }
            if (mesh.vertexBuffer == null || mesh.indexCount <= 0) {
                continue;
            }
            final ShipTransform xform = (ship instanceof final ClientShip cs)
                ? cs.getRenderTransform() : ship.getTransform();

            poseStack.pushPose();
            try {
                // The mesh is stored relative to its own minimum corner and the offset is restored HERE, through
                // the double-precision path. Shipyard coordinates run to ~28 million, where a float model matrix
                // has lost whole blocks of precision -- baking world-space vertices would visibly wobble.
                VSClientGameUtils.transformRenderWithShip(
                    xform, poseStack, mesh.minX, mesh.minY, mesh.minZ, cam.x, cam.y, cam.z);
                modelViews.add(new Matrix4f(poseStack.last().pose()));
                ready.add(mesh);
            } finally {
                poseStack.popPose();
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        final DynamicUniforms uniforms = RenderSystem.getDynamicUniforms();
        final DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[ready.size()];
        final Vector4f tint = debug
            ? new Vector4f(1.0f, 0.0f, 1.0f, 1.0f)   // magenta, so misplaced geometry is obvious
            : new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        for (int i = 0; i < ready.size(); i++) {
            transforms[i] = new DynamicUniforms.Transform(
                modelViews.get(i), tint, new Vector3f(), new Matrix4f());
        }
        final var slices = uniforms.writeTransforms(transforms);

        int maxIndexCount = 0;
        for (final ShipMesh m : ready) {
            maxIndexCount = Math.max(maxIndexCount, m.indexCount);
        }
        final RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        final GpuBuffer indexBuffer = seq.getBuffer(maxIndexCount);
        final VertexFormat.IndexType indexType = seq.type();

        // Honour Iris's target overrides exactly as vanilla does, so the depth we write is the depth the
        // shaderpack's translucent pass will test against.
        final RenderTarget target = OutputTarget.MAIN_TARGET.getRenderTarget();
        final GpuTextureView color = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        final GpuTextureView depth = !target.useDepth ? null
            : (RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView());
        if (depth == null) {
            return; // nothing to write to; the whole point is the depth buffer
        }

        final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
            () -> "armada_pocket_occluder", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            for (int i = 0; i < ready.size(); i++) {
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.setVertexBuffer(0, ready.get(i).vertexBuffer);
                pass.setIndexBuffer(indexBuffer, indexType);
                pass.drawIndexed(0, 0, ready.get(i).indexCount, 1);
            }
        }
    }

    /** Re-mesh one ship's sub air. Cheap to call often: a ship with no sub air just clears and returns. */
    private static void rebuild(final ClientLevel level, final LoadedShip ship, final ShipMesh mesh,
        final long now, final double shipY) {
        mesh.builtAtMs = now;
        mesh.builtAtY = shipY;
        mesh.wasEmpty = true; // assume nothing until a voxel is actually found
        mesh.close();

        final var aabb = ship.getShipAABB();
        if (aabb == null) {
            return;
        }
        final var subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final org.joml.Matrix4dc shipToWorld = ship.getTransform().getShipToWorld();
        final org.joml.Vector3d worldPos = new org.joml.Vector3d();

        mesh.minX = aabb.minX();
        mesh.minY = aabb.minY();
        mesh.minZ = aabb.minZ();

        int voxels = 0;
        try (ByteBufferBuilder byteBuilder = new ByteBufferBuilder(1024)) {
            final BufferBuilder builder =
                new BufferBuilder(byteBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int x = aabb.minX(); x <= aabb.maxX(); x++) {
                for (int y = aabb.minY(); y <= aabb.maxY(); y++) {
                    for (int z = aabb.minZ(); z <= aabb.maxZ(); z++) {
                        cursor.set(x, y, z);
                        // No hasChunkAt guard here: on the CLIENT getBlockState never generates a chunk (it
                        // answers air for anything missing), and the guard is one more way for the scan to
                        // silently find nothing in shipyard space.
                        if (level.getBlockState(cursor).getBlock() != subAir) {
                            continue;
                        }
                        // ONLY voxels that are actually submerged. Depth is one value per pixel, so a cube
                        // occludes every translucent fragment behind it -- including the open sea beyond the
                        // hull. Meshing the dry half of the pocket is what cut the ocean away at the bottom of
                        // the screen when looking down past a surfaced ship. A voxel sitting in air has no
                        // water to hide, so it has no business writing depth.
                        shipToWorld.transformPosition(x + 0.5, y + 0.5, z + 0.5, worldPos);
                        if (level.getFluidState(BlockPos.containing(worldPos.x, worldPos.y, worldPos.z))
                            .isEmpty()) {
                            continue;
                        }
                        if (++voxels > MAX_VOXELS) {
                            LOGGER.warn("Ship {} has more than {} pocket voxels; occluder mesh truncated",
                                ship.getId(), MAX_VOXELS);
                            break;
                        }
                        emitCube(builder, x - mesh.minX, y - mesh.minY, z - mesh.minZ);
                    }
                }
            }
            if (voxels == 0) {
                // With debug on, say so out loud and include the AABB actually walked -- "found nothing" and
                // "never looked" are indistinguishable otherwise, which is what wasted the last test run.
                if (debug) {
                    LOGGER.info("Pocket occluder: ship {} scanned [{},{},{}]..[{},{},{}] and found no SUBMERGED "
                            + "sub air (a fully surfaced ship is expected to mesh nothing)",
                        ship.getId(), aabb.minX(), aabb.minY(), aabb.minZ(),
                        aabb.maxX(), aabb.maxY(), aabb.maxZ());
                }
                return;
            }
            final MeshData data = builder.build();
            if (data == null) {
                return;
            }
            try (data) {
                mesh.vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "armada_pocket_occluder_verts", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
                mesh.indexCount = data.drawState().indexCount();
                mesh.wasEmpty = false;
                LOGGER.info("Pocket occluder: ship {} meshed {} sub-air voxels ({} indices)",
                    ship.getId(), voxels, mesh.indexCount);
            }
        }
    }

    /** All six faces of one voxel. Interior faces are deliberately kept -- see the class doc. */
    private static void emitCube(final BufferBuilder b, final int x, final int y, final int z) {
        final float x0 = x;
        final float y0 = y;
        final float z0 = z;
        final float x1 = x + 1.0f;
        final float y1 = y + 1.0f;
        final float z1 = z + 1.0f;
        final int argb = 0xFFFFFFFF;

        // -Y / +Y
        quad(b, argb, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0);
        quad(b, argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        // -Z / +Z
        quad(b, argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        quad(b, argb, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        // -X / +X
        quad(b, argb, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        quad(b, argb, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
    }

    private static void quad(final BufferBuilder b, final int argb,
        final float ax, final float ay, final float az, final float bx, final float by, final float bz,
        final float cx, final float cy, final float cz, final float dx, final float dy, final float dz) {
        b.addVertex(ax, ay, az).setColor(argb);
        b.addVertex(bx, by, bz).setColor(argb);
        b.addVertex(cx, cy, cz).setColor(argb);
        b.addVertex(dx, dy, dz).setColor(argb);
    }

    private static RenderPipeline pipeline() {
        if (debug) {
            if (debugPipeline == null) {
                debugPipeline = buildPipeline("armada/pocket_occluder_debug", true);
            }
            return debugPipeline;
        }
        if (occludePipeline == null) {
            occludePipeline = buildPipeline("armada/pocket_occluder", false);
        }
        return occludePipeline;
    }

    private static RenderPipeline buildPipeline(final String location, final boolean colorWrite) {
        return RenderPipeline.builder()
            .withLocation(location)
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withCull(false)
            .withColorWrite(colorWrite)
            .withDepthWrite(true)
            // Negative bias pulls our depth toward the camera so a water face at the same world position
            // loses GL_LEQUAL. Drivers disagree about tiny offsets, hence several units.
            .withDepthBias(-2.0f, -8.0f)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .build();
    }

    /** Drop every cached mesh (level change, toggle off, or after a failure). */
    public static void clear() {
        for (final ShipMesh m : MESHES.values()) {
            m.close();
        }
        MESHES.clear();
        lastLevel = null;
    }
}
