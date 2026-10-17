package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.integration.ClientIntegrationDispatcher;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.client.PreviewModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CompiledPreview implements AutoCloseable {
    private static final List<RenderType> MODEL_LAYERS = List.of(RenderTypes.solidMovingBlock(),
            RenderTypes.cutoutMovingBlock(), RenderTypes.translucentMovingBlock(), PreviewRenderTypes.ghost());
    private static final List<RenderType> OPAQUE_ORDER = List.of(RenderTypes.solidMovingBlock(),
            RenderTypes.cutoutMovingBlock());
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ScratchBuffers SCRATCH = new ScratchBuffers();
    private static final Set<Identifier> FAILED_MODELS = new HashSet<>();
    private static final float[] REPLACE_COLOR = {1.0F, 0.62F, 0.16F, 0.22F};
    private static final float[] REMOVE_COLOR = {1.0F, 0.32F, 0.32F, 0.28F};
    private static final float[] SKIP_COLOR = {0.55F, 0.55F, 0.6F, 0.3F};
    private static final float[] BLOCKED_COLOR = {1.0F, 0.1F, 0.1F, 0.5F};
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    record Options(int alpha, boolean cable, Object ambientOcclusion, Object graphics) {}

    private final Map<Long, SectionMeshes> sections = new HashMap<>();
    private final Map<Long, Overlay> overlays = new HashMap<>();
    private boolean layered;
    private final Set<Long> dirty = new LinkedHashSet<>();
    private final Map<BlockState, Boolean> opaqueCubes = new IdentityHashMap<>();
    private final Map<BlockState, SimpleLook> simpleLooks = new IdentityHashMap<>();
    private final Matrix4f modelView = new Matrix4f();
    private PreviewContent content = PreviewContent.EMPTY;
    private PreviewLod lod = PreviewLod.FULL;
    private Options options;
    private PreviewPlacement overlayPlacement;
    private SectionJob job;

    PreviewContent content() {
        return content;
    }

    PreviewLod lod() {
        return lod;
    }

    void setContent(PreviewContent next, PreviewLod nextLod, Options nextOptions) {
        RenderSystem.assertOnRenderThread();
        if (next == content && nextLod == lod && nextOptions.equals(options)) {
            return;
        }
        boolean levelChanged = nextLod != lod || !nextOptions.equals(options);
        content = next;
        lod = nextLod;
        options = nextOptions;
        var removed = new ArrayList<Long>();
        for (var entry : sections.entrySet()) {
            if (next.section(entry.getKey()) == null) {
                removed.add(entry.getKey());
            }
        }
        for (var key : removed) {
            sections.remove(key).close();
        }
        var compiled = new HashMap<Long, Compiled>();
        sections.forEach((key, meshes) -> compiled.put(key, meshes.compiled()));
        dirty.clear();
        dirty.addAll(dirtySections(next, nextLod, nextOptions, compiled));
        if (job != null && (levelChanged || next.section(job.section.key) != job.section
                || !job.compiled.current(Compiled.of(next, job.section, nextLod, nextOptions)))) {
            job = null;
        }
        if (job != null) {
            dirty.remove(job.section.key);
        }
    }

    boolean pending() {
        return job != null || !dirty.isEmpty();
    }

    int sectionCount() {
        return sections.size();
    }

    int compile(Minecraft minecraft, Vec3 viewpoint, BlockPos tintOrigin, long budgetNanos) {
        RenderSystem.assertOnRenderThread();
        long deadline = System.nanoTime() + budgetNanos;
        int compiled = 0;
        var level = new PreviewContentLevel(content, minecraft.level, tintOrigin);
        while (job != null || !dirty.isEmpty()) {
            if (job == null) {
                long key = nearest(viewpoint);
                dirty.remove(key);
                job = new SectionJob(Compiled.of(content, content.section(key), lod, options));
            }
            long start = System.nanoTime();
            boolean finished = switch (lod) {
                case FULL, STATIC -> models(minecraft, level, job, deadline);
                case SIMPLE -> {
                    simple(minecraft, job.section, job.geometry);
                    yield true;
                }
                case MASSIVE -> {
                    massive(job.section, job.geometry);
                    yield true;
                }
            };
            job.nanos += System.nanoTime() - start;
            if (!finished) {
                break;
            }
            var replacement = finish(job, viewpoint);
            job = null;
            var previous = sections.put(replacement.section().key, replacement);
            if (previous != null) {
                previous.close();
            }
            compiled++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return compiled;
    }

    private static final class SectionJob {
        final Compiled compiled;
        final PreviewContent.Section section;
        final Map<RenderType, CpuMesh> geometry = new LinkedHashMap<>();
        final List<Integer> outlines = new ArrayList<>();
        int cursor;
        long nanos;

        SectionJob(Compiled compiled) {
            this.compiled = compiled;
            section = compiled.section();
        }
    }

    private long nearest(Vec3 viewpoint) {
        long best = 0L;
        double bestDistance = Double.MAX_VALUE;
        for (var key : dirty) {
            double distance = viewpoint.distanceToSqr(SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8,
                    SectionPos.sectionToBlockCoord(SectionPos.y(key)) + 8,
                    SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = key;
            }
        }
        return best;
    }

    private SectionMeshes finish(SectionJob job, Vec3 viewpoint) {
        long start = System.nanoTime();
        var section = job.section;
        var geometry = job.geometry;
        var outlines = job.outlines;
        if (!outlines.isEmpty()) {
            var consumer = direct(RenderTypes.lines());
            var pose = new PoseStack();
            for (var index : outlines) {
                int x = PreviewContent.Section.x(index);
                int y = PreviewContent.Section.y(index);
                int z = PreviewContent.Section.z(index);
                PreviewRenderer.renderLineBox(pose, consumer, x - 0.01, y - 0.01, z - 0.01, x + 1.01, y + 1.01,
                        z + 1.01, 1.0F, 0.62F, 0.16F, 0.42F);
            }
            finishDirect(geometry);
        }
        CpuMesh cable = null;
        if (options.cable()) {
            var consumer = direct(RenderTypes.lines());
            var pose = new PoseStack();
            for (int index = 0; index < 4096; index++) {
                if (section.states[index] != null) {
                    int x = PreviewContent.Section.x(index);
                    int y = PreviewContent.Section.y(index);
                    int z = PreviewContent.Section.z(index);
                    PreviewRenderer.renderLineBox(pose, consumer, x - 0.002, y - 0.002, z - 0.002, x + 1.002,
                            y + 1.002, z + 1.002, 1.0F, 1.0F, 1.0F, 1.0F);
                }
            }
            var lines = new CpuMesh();
            if (PreviewMesh.drainStaged((type, vertices) -> lines.append(vertices, type.format().getVertexSize()))) {
                cable = lines;
            }
        }
        long compiled = System.nanoTime();
        long tessellation = job.nanos + compiled - start;
        var meshes = new ArrayList<PreviewMesh>();
        PreviewMesh cableMesh = null;
        var sectionViewpoint = viewpoint.subtract(section.originX, section.originY, section.originZ);
        try {
            for (var entry : geometry.entrySet()) {
                meshes.add(entry.getValue().upload(entry.getKey(), sectionViewpoint));
            }
            if (cable != null) {
                cableMesh = cable.upload(RenderTypes.lines(), sectionViewpoint);
            }
        } catch (RuntimeException | Error exception) {
            meshes.forEach(PreviewMesh::close);
            throw exception;
        }
        int vertices = meshes.stream().mapToInt(PreviewMesh::vertices).sum();
        PreviewDebugStats.meshCompiled(section.blocks, section.blocks, vertices, meshes.size(), outlines.size(),
                tessellation, System.nanoTime() - compiled);
        return new SectionMeshes(job.compiled, meshes, cableMesh);
    }

    private boolean models(Minecraft minecraft, PreviewContentLevel level, SectionJob job, long deadline) {
        var section = job.section;
        var geometry = job.geometry;
        var outlines = job.outlines;
        var detail = lod == PreviewLod.FULL ? PreviewDetail.FULL : PreviewDetail.STATIC;
        int alpha = options.alpha();
        var renderer = new ModelBlockRenderer(false, false, minecraft.getBlockColors());
        var models = minecraft.getModelManager().getBlockStateModelSet();
        var pose = new PoseStack();
        var buffer = new PreviewRenderer.GhostBufferSource(SCRATCH, alpha);
        for (; job.cursor < 4096; job.cursor++) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            int index = job.cursor;
            var state = section.states[index];
            if (state == null || state.isAir()) {
                continue;
            }
            int x = PreviewContent.Section.x(index);
            int y = PreviewContent.Section.y(index);
            int z = PreviewContent.Section.z(index);
            var pos = new BlockPos(section.originX + x, section.originY + y, section.originZ + z);
            boolean opaque = alpha == 255 && opaqueCube(minecraft, state);
            if (opaque && enclosed(minecraft, pos)) {
                continue;
            }
            pose.pushPose();
            try {
                pose.translate(x, y, z);
                var drawn = PreviewModelData.renderStatic(minecraft.level, level, section.visual(index), pos, state,
                        detail, pose, layer -> new FullBright(buffer.getBuffer(PreviewRenderTypes.ghost())));
                if (drawn != ClientIntegrationDispatcher.StaticRender.NONE) {
                    if (drawn == ClientIntegrationDispatcher.StaticRender.FAILED || !SCRATCH.appendTo(geometry)) {
                        outlines.add(index);
                    }
                    continue;
                }
                var modelData = PreviewModelData.resolve(minecraft.level, section.visual(index), pos, state, detail);
                if (state.getRenderShape() != RenderShape.MODEL && !PreviewModelData.hasProperties(modelData)) {
                    outlines.add(index);
                    continue;
                }
                modelData = inContent(modelData, level);
                PreviewDebugStats.modelRendered();
                var view = level.withModelData(pos, modelData);
                if (opaque) {
                    renderOpaque(minecraft, renderer, models.get(state), pose, buffer, state, pos, view);
                } else {
                    PreviewRenderer.renderPreviewBlock(renderer, pose, buffer, level,
                            new PreviewRenderer.PreviewBlock(pos, state, modelData));
                }
                if (!SCRATCH.appendTo(geometry) && modelIsEmpty(models.get(state), view, pos, state)) {
                    outlines.add(index);
                }
            } catch (RuntimeException | LinkageError exception) {
                outlines.add(index);
                var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (FAILED_MODELS.add(id)) {
                    MatterManipulator.LOGGER.warn("Skipping incompatible preview model for {}", id, exception);
                }
            } finally {
                SCRATCH.discard();
                pose.popPose();
            }
        }
        return true;
    }

    private boolean enclosed(Minecraft minecraft, BlockPos pos) {
        for (var direction : DIRECTIONS) {
            var neighbour = content.state(pos.getX() + direction.getStepX(), pos.getY() + direction.getStepY(),
                    pos.getZ() + direction.getStepZ());
            if (neighbour == null || !opaqueCube(minecraft, neighbour)) {
                return false;
            }
        }
        return true;
    }

    private void renderOpaque(Minecraft minecraft, ModelBlockRenderer renderer, BlockStateModel model, PoseStack pose,
            MultiBufferSource buffer, BlockState state, BlockPos pos, PreviewContentLevel view) {
        var visible = new PreviewCulling.VisibleFaces(model, PreviewCulling.hiddenSides(state, pos, view, neighbour -> {
            var neighbourState = content.state(neighbour.getX(), neighbour.getY(), neighbour.getZ());
            return neighbourState != null && opaqueCube(minecraft, neighbourState);
        }));
        PreviewRenderer.tesselate(renderer, pose, buffer, view, pos, state, visible);
    }

    private record FullBright(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(LightCoordsUtil.FULL_BRIGHT & 0xFFFF, LightCoordsUtil.FULL_BRIGHT >> 16);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }
    }

    static ModelData inContent(ModelData data, PreviewContentLevel level) {
        if (data.getProperties().isEmpty()) {
            return data;
        }
        ModelData.Builder result = null;
        for (var property : data.getProperties()) {
            var value = data.get(property);
            Object replacement = null;
            if (value instanceof PreviewModels.SingleBlockPreviewLevel single) {
                replacement = level.withBlockEntity(single.previewPos(), single.previewBlockEntity());
            } else if (value instanceof ModelData nested) {
                var converted = inContent(nested, level);
                replacement = converted == nested ? null : converted;
            }
            if (replacement != null) {
                result = result == null ? data.derive() : result;
                put(result, property, replacement);
            }
        }
        return result == null ? data : result.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(ModelData.Builder builder, ModelProperty<T> property, Object value) {
        builder.with(property, (T) value);
    }

    private boolean opaqueCube(Minecraft minecraft, BlockState state) {
        return opaqueCubes.computeIfAbsent(state, ignored -> PreviewCulling.opaqueUnitCube(minecraft, state));
    }

    private record SimpleLook(TextureAtlasSprite sprite, float red, float green, float blue, AABB box,
            boolean fullCube) {}

    private SimpleLook simpleLook(Minecraft minecraft, BlockState state) {
        return simpleLooks.computeIfAbsent(state, ignored -> {
            var sprite = minecraft.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
            var tint = minecraft.getBlockColors().getTintSource(state, 0);
            int color = tint == null ? -1 : tint.color(state);
            if (color == -1) {
                color = 0xFFFFFF;
            }
            var shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            var box = shape.isEmpty() ? new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75) : shape.bounds();
            return new SimpleLook(sprite, (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F,
                    (color & 255) / 255.0F, box, fullCube(state));
        });
    }

    private void simple(Minecraft minecraft, PreviewContent.Section section, Map<RenderType, CpuMesh> geometry) {
        var consumer = direct(PreviewRenderTypes.simple());
        float alpha = options.alpha() / 255.0F;
        for (int index = 0; index < 4096; index++) {
            var state = section.states[index];
            if (state == null || state.isAir()) {
                continue;
            }
            var look = simpleLook(minecraft, state);
            int x = PreviewContent.Section.x(index);
            int y = PreviewContent.Section.y(index);
            int z = PreviewContent.Section.z(index);
            var box = look.box();
            for (var direction : DIRECTIONS) {
                if (look.fullCube() && fullCubeNeighbour(content, section, x, y, z, direction)) {
                    continue;
                }
                float shade = shade(direction);
                face(consumer, direction, x + (float) box.minX, y + (float) box.minY, z + (float) box.minZ,
                        x + (float) box.maxX, y + (float) box.maxY, z + (float) box.maxZ,
                        look.red() * shade, look.green() * shade, look.blue() * shade, alpha, look.sprite());
            }
        }
        finishDirect(geometry);
    }

    static boolean fullCubeNeighbour(PreviewContent content, PreviewContent.Section section, int x, int y, int z,
            Direction direction) {
        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        BlockState neighbour;
        if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
            neighbour = section.states[PreviewContent.Section.index(nx, ny, nz)];
        } else {
            neighbour = content.state(section.originX + nx, section.originY + ny, section.originZ + nz);
        }
        return neighbour != null && fullCube(neighbour);
    }

    static boolean fullCube(BlockState state) {
        return !state.isAir() && state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == Shapes.block();
    }

    private void massive(PreviewContent.Section section, Map<RenderType, CpuMesh> geometry) {
        var bricks = new Brick[64];
        for (int index = 0; index < 4096; index++) {
            var state = section.states[index];
            if (state == null || state.isAir()) {
                continue;
            }
            int x = PreviewContent.Section.x(index);
            int y = PreviewContent.Section.y(index);
            int z = PreviewContent.Section.z(index);
            int brickIndex = (x >> 2) | (z >> 2) << 2 | (y >> 2) << 4;
            var brick = bricks[brickIndex];
            if (brick == null) {
                brick = new Brick(state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col);
                bricks[brickIndex] = brick;
            }
            brick.add(x, y, z);
        }
        var consumer = direct(PreviewRenderTypes.massive());
        float alpha = options.alpha() / 255.0F;
        int minX = 16;
        int minY = 16;
        int minZ = 16;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (int brickIndex = 0; brickIndex < 64; brickIndex++) {
            var brick = bricks[brickIndex];
            if (brick == null) {
                continue;
            }
            minX = Math.min(minX, brick.minX);
            minY = Math.min(minY, brick.minY);
            minZ = Math.min(minZ, brick.minZ);
            maxX = Math.max(maxX, brick.maxX + 1);
            maxY = Math.max(maxY, brick.maxY + 1);
            maxZ = Math.max(maxZ, brick.maxZ + 1);
            int bx = brickIndex & 3;
            int bz = brickIndex >> 2 & 3;
            int by = brickIndex >> 4;
            for (var direction : DIRECTIONS) {
                if (brick.full()) {
                    int nx = bx + direction.getStepX();
                    int ny = by + direction.getStepY();
                    int nz = bz + direction.getStepZ();
                    if (nx >= 0 && nx < 4 && ny >= 0 && ny < 4 && nz >= 0 && nz < 4) {
                        var neighbour = bricks[nx | nz << 2 | ny << 4];
                        if (neighbour != null && neighbour.full()) {
                            continue;
                        }
                    }
                }
                float shade = shade(direction);
                face(consumer, direction, brick.minX, brick.minY, brick.minZ, brick.maxX + 1, brick.maxY + 1,
                        brick.maxZ + 1, (brick.color >> 16 & 255) / 255.0F * shade,
                        (brick.color >> 8 & 255) / 255.0F * shade, (brick.color & 255) / 255.0F * shade, alpha, null);
            }
        }
        finishDirect(geometry);
        if (maxX == 0) {
            return;
        }
        PreviewRenderer.renderLineBox(new PoseStack(), direct(RenderTypes.lines()), minX, minY, minZ, maxX, maxY,
                maxZ, 1.0F, 0.62F, 0.16F, 0.5F);
        finishDirect(geometry);
    }

    private static final class Brick {
        final int color;
        int minX = 16;
        int minY = 16;
        int minZ = 16;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        int count;

        Brick(int color) {
            this.color = color;
        }

        void add(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            count++;
        }

        boolean full() {
            return count == 64;
        }
    }

    private static float shade(Direction direction) {
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    static void face(VertexConsumer consumer, Direction direction, float x0, float y0, float z0, float x1, float y1,
            float z1, float red, float green, float blue, float alpha, TextureAtlasSprite sprite) {
        var corners = switch (direction) {
            case DOWN -> new float[] {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1};
            case UP -> new float[] {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
            case NORTH -> new float[] {x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0};
            case SOUTH -> new float[] {x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1};
            case WEST -> new float[] {x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1};
            case EAST -> new float[] {x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0};
        };
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = corners[vertex * 3];
            float y = corners[vertex * 3 + 1];
            float z = corners[vertex * 3 + 2];
            if (sprite == null) {
                consumer.addVertex(x, y, z).setColor(red, green, blue, alpha);
            } else {
                consumer.addVertex(x, y, z, ARGB.colorFromFloat(alpha, red, green, blue),
                        vertex < 2 ? sprite.getU0() : sprite.getU1(),
                        vertex == 0 || vertex == 3 ? sprite.getV0() : sprite.getV1(),
                        OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, direction.getStepX(),
                        direction.getStepY(), direction.getStepZ());
            }
        }
    }

    private static VertexConsumer direct(RenderType type) {
        PreviewMesh.discardStaged();
        return PreviewMesh.stage(type);
    }

    private static void finishDirect(Map<RenderType, CpuMesh> geometry) {
        PreviewMesh.drainStaged((type, vertices) -> geometry.computeIfAbsent(type, ignored -> new CpuMesh())
                .append(vertices, type.format().getVertexSize()));
    }

    void updateStatuses(PreviewPlacement placement) {
        RenderSystem.assertOnRenderThread();
        if (placement == overlayPlacement) {
            return;
        }
        overlayPlacement = placement;
        var removed = new ArrayList<Long>();
        for (var key : overlays.keySet()) {
            if (placement.key.content().section(key) == null) {
                removed.add(key);
            }
        }
        for (var key : removed) {
            overlays.remove(key).close();
        }
        for (var section : placement.key.content().sections()) {
            var statuses = placement.statuses(section.key);
            var overlay = overlays.get(section.key);
            if (overlay != null && overlay.lod == lod && Arrays.equals(overlay.statuses, statuses)) {
                continue;
            }
            if (overlay != null) {
                overlay.close();
            }
            overlays.put(section.key, new Overlay(statuses, lod, overlayMesh(section, statuses)));
        }
    }

    private PreviewMesh overlayMesh(PreviewContent.Section section, byte[] statuses) {
        if (statuses == null) {
            return null;
        }
        var consumer = direct(PreviewRenderTypes.status());
        int cell = lod == PreviewLod.MASSIVE ? 4 : 1;
        var worst = cell == 1 ? statuses : worstPerBrick(statuses);
        for (int index = 0; index < 4096; index++) {
            var color = statusColor(worst[index]);
            if (color == null) {
                continue;
            }
            int x = PreviewContent.Section.x(index) * cell;
            int y = PreviewContent.Section.y(index) * cell;
            int z = PreviewContent.Section.z(index) * cell;
            for (var direction : DIRECTIONS) {
                int nx = x / cell + direction.getStepX();
                int ny = y / cell + direction.getStepY();
                int nz = z / cell + direction.getStepZ();
                int limit = 16 / cell;
                if (nx >= 0 && nx < limit && ny >= 0 && ny < limit && nz >= 0 && nz < limit
                        && worst[PreviewContent.Section.index(nx, ny, nz)] == worst[index]) {
                    continue;
                }
                face(consumer, direction, x - 0.01F, y - 0.01F, z - 0.01F, x + cell + 0.01F, y + cell + 0.01F,
                        z + cell + 0.01F, color[0], color[1], color[2], color[3], null);
            }
        }
        var mesh = new CpuMesh();
        if (!PreviewMesh.drainStaged((type, vertices) -> mesh.append(vertices, type.format().getVertexSize()))) {
            return null;
        }
        return mesh.upload(PreviewRenderTypes.status(), Vec3.ZERO);
    }

    private static byte[] worstPerBrick(byte[] statuses) {
        var worst = new byte[4096];
        for (int index = 0; index < 4096; index++) {
            byte status = statuses[index];
            if (status == 0) {
                continue;
            }
            int brick = PreviewContent.Section.index(PreviewContent.Section.x(index) >> 2,
                    PreviewContent.Section.y(index) >> 2, PreviewContent.Section.z(index) >> 2);
            if (severity(status) > severity(worst[brick])) {
                worst[brick] = status;
            }
        }
        return worst;
    }

    private static int severity(byte status) {
        return switch (status) {
            case PreviewPlacement.BLOCKED -> 5;
            case PreviewPlacement.SKIP -> 4;
            case PreviewPlacement.REMOVE -> 3;
            case PreviewPlacement.REPLACE -> 2;
            case 0 -> 0;
            default -> 1;
        };
    }

    private static float[] statusColor(byte status) {
        return switch (status) {
            case PreviewPlacement.REPLACE -> REPLACE_COLOR;
            case PreviewPlacement.REMOVE -> REMOVE_COLOR;
            case PreviewPlacement.SKIP -> SKIP_COLOR;
            case PreviewPlacement.BLOCKED -> BLOCKED_COLOR;
            default -> null;
        };
    }

    void render(PoseStack pose, Frustum frustum, Vec3 camera, BlockPos origin, boolean statuses) {
        RenderSystem.assertOnRenderThread();
        double offsetX = origin.getX() - camera.x;
        double offsetY = origin.getY() - camera.y;
        double offsetZ = origin.getZ() - camera.z;
        var visible = new ArrayList<Placed>();
        for (var entry : sections.values()) {
            for (var mesh : entry.meshes) {
                if (mesh.type != RenderTypes.lines() && visible(frustum, mesh, entry.section(), origin)) {
                    visible.add(new Placed(mesh, entry.section()));
                }
            }
        }
        for (var type : OPAQUE_ORDER) {
            draw(pose, visible, type, null, offsetX, offsetY, offsetZ, WHITE);
        }
        for (var type : List.of(PreviewRenderTypes.simple(), PreviewRenderTypes.massive())) {
            draw(pose, visible, type, PreviewRenderTypes.depthPrepass(type), offsetX, offsetY, offsetZ, WHITE);
            draw(pose, visible, type, null, offsetX, offsetY, offsetZ, WHITE);
        }
        drawTranslucent(pose, visible, camera, origin, offsetX, offsetY, offsetZ);
        if (statuses) {
            var overlayMeshes = new ArrayList<Placed>();
            for (var entry : overlays.entrySet()) {
                var section = content.section(entry.getKey());
                var mesh = entry.getValue().mesh;
                if (mesh != null && section != null && visible(frustum, mesh, section, origin)) {
                    overlayMeshes.add(new Placed(mesh, section));
                }
            }
            layered = true;
            try {
                draw(pose, overlayMeshes, PreviewRenderTypes.status(), null, offsetX, offsetY, offsetZ, WHITE);
            } finally {
                layered = false;
            }
        }
        var lines = new ArrayList<Placed>();
        for (var entry : sections.values()) {
            for (var mesh : entry.meshes) {
                if (mesh.type == RenderTypes.lines() && visible(frustum, mesh, entry.section(), origin)) {
                    lines.add(new Placed(mesh, entry.section()));
                }
            }
        }
        draw(pose, lines, RenderTypes.lines(), null, offsetX, offsetY, offsetZ, WHITE);
    }

    void renderCableOutlines(PoseStack pose, Frustum frustum, Vec3 camera, BlockPos origin,
            float red, float green, float blue, float alpha) {
        var cable = new ArrayList<Placed>();
        for (var entry : sections.values()) {
            if (entry.cable != null && visible(frustum, entry.cable, entry.section(), origin)) {
                cable.add(new Placed(entry.cable, entry.section()));
            }
        }
        if (cable.isEmpty()) {
            return;
        }
        draw(pose, cable, RenderTypes.lines(), null, origin.getX() - camera.x, origin.getY() - camera.y,
                origin.getZ() - camera.z, new Vector4f(channel(red), channel(green), channel(blue), channel(alpha)));
    }

    private static float channel(float color) {
        return (int) (color * 255) / 255.0F;
    }

    private record Placed(PreviewMesh mesh, PreviewContent.Section section) {}

    private static boolean visible(Frustum frustum, PreviewMesh mesh, PreviewContent.Section section,
            BlockPos origin) {
        return frustum.isVisible(mesh.bounds.move(origin.getX() + section.originX, origin.getY() + section.originY,
                origin.getZ() + section.originZ));
    }

    private void draw(PoseStack pose, List<Placed> meshes, RenderType type, RenderPipeline renderAs, double offsetX,
            double offsetY, double offsetZ, Vector4fc color) {
        var drawn = new ArrayList<PreviewMesh>();
        var transforms = new ArrayList<GpuBufferSlice>();
        for (var placed : meshes) {
            if (placed.mesh().type == type) {
                drawn.add(placed.mesh());
                transforms.add(transform(pose, placed.section(), offsetX, offsetY, offsetZ, color));
            }
        }
        if (drawn.isEmpty()) {
            return;
        }
        try (var pass = PreviewMesh.beginPass(renderAs == null ? type.pipeline() : renderAs)) {
            for (int i = 0; i < drawn.size(); i++) {
                drawn.get(i).draw(pass, transforms.get(i));
            }
        }
    }

    private GpuBufferSlice transform(PoseStack pose, PreviewContent.Section section, double offsetX, double offsetY,
            double offsetZ, Vector4fc color) {
        modelView.set(RenderSystem.getModelViewMatrix()).mul(pose.last().pose()).translate(
                (float) (offsetX + section.originX), (float) (offsetY + section.originY),
                (float) (offsetZ + section.originZ));
        if (layered) {
            RenderSystem.getProjectionType().applyLayeringTransform(modelView, 1.0F);
        }
        return RenderSystem.getDynamicUniforms().writeTransform(modelView, color, NO_OFFSET, IDENTITY);
    }

    private void drawTranslucent(PoseStack pose, List<Placed> visible, Vec3 camera, BlockPos origin, double offsetX,
            double offsetY, double offsetZ) {
        var translucent = new ArrayList<Placed>();
        for (var placed : visible) {
            if (PreviewRenderTypes.compileSorted(placed.mesh().type)) {
                translucent.add(placed);
            }
        }
        if (translucent.isEmpty()) {
            return;
        }
        var distances = new HashMap<Placed, Double>();
        for (var placed : translucent) {
            distances.put(placed, viewpoint(placed.section(), camera, origin).distanceToSqr(
                    placed.mesh().bounds.getCenter()));
        }
        translucent.sort((a, b) -> Double.compare(distances.get(b), distances.get(a)));
        int resorts = 0;
        for (int i = translucent.size() - 1; i >= 0 && resorts < PreviewTranslucentSorting.MAX_RESORTS_PER_FRAME; i--) {
            var placed = translucent.get(i);
            var viewpoint = viewpoint(placed.section(), camera, origin);
            if (placed.mesh().needsResort(viewpoint)) {
                placed.mesh().resort(viewpoint);
                resorts++;
            }
        }
        var transforms = new ArrayList<GpuBufferSlice>(translucent.size());
        for (var placed : translucent) {
            transforms.add(transform(pose, placed.section(), offsetX, offsetY, offsetZ, WHITE));
        }
        RenderType current = null;
        RenderPass pass = null;
        try {
            for (int i = 0; i < translucent.size(); i++) {
                var placed = translucent.get(i);
                if (placed.mesh().type != current) {
                    if (pass != null) {
                        pass.close();
                    }
                    current = placed.mesh().type;
                    pass = PreviewMesh.beginPass(current.pipeline());
                }
                placed.mesh().draw(pass, transforms.get(i));
            }
        } finally {
            if (pass != null) {
                pass.close();
            }
        }
    }

    private static Vec3 viewpoint(PreviewContent.Section section, Vec3 camera, BlockPos origin) {
        return new Vec3(camera.x - origin.getX() - section.originX, camera.y - origin.getY() - section.originY,
                camera.z - origin.getZ() - section.originZ);
    }

    int meshCount() {
        return sections.values().stream().mapToInt(SectionMeshes::meshCount).sum();
    }

    int vertexCount() {
        return sections.values().stream().flatMap(entry -> entry.meshes.stream()).mapToInt(PreviewMesh::vertices).sum();
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        sections.values().forEach(SectionMeshes::close);
        sections.clear();
        overlays.values().forEach(Overlay::close);
        overlays.clear();
        dirty.clear();
        job = null;
        opaqueCubes.clear();
        simpleLooks.clear();
        content = PreviewContent.EMPTY;
        options = null;
        overlayPlacement = null;
    }

    record Compiled(PreviewContent.Section section, List<PreviewContent.Section> neighbours, PreviewLod lod,
            Options options) {
        static Compiled of(PreviewContent content, PreviewContent.Section section, PreviewLod lod, Options options) {
            var neighbours = new ArrayList<PreviewContent.Section>(6);
            for (var direction : DIRECTIONS) {
                neighbours.add(content.section(SectionPos.offset(section.key, direction)));
            }
            return new Compiled(section, neighbours, lod, options);
        }

        boolean current(Compiled other) {
            if (section != other.section || lod != other.lod || !options.equals(other.options)) {
                return false;
            }
            for (int i = 0; i < neighbours.size(); i++) {
                if (neighbours.get(i) != other.neighbours.get(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    static Set<Long> dirtySections(PreviewContent next, PreviewLod lod, Options options, Map<Long, Compiled> compiled) {
        var dirty = new LinkedHashSet<Long>();
        for (var section : next.sections()) {
            var previous = compiled.get(section.key);
            if (previous == null || !previous.current(Compiled.of(next, section, lod, options))) {
                dirty.add(section.key);
            }
        }
        return dirty;
    }

    private record SectionMeshes(Compiled compiled, List<PreviewMesh> meshes, PreviewMesh cable) {
        PreviewContent.Section section() {
            return compiled.section();
        }

        int meshCount() {
            return meshes.size() + (cable == null ? 0 : 1);
        }

        void close() {
            meshes.forEach(PreviewMesh::close);
            if (cable != null) {
                cable.close();
            }
        }
    }

    private record Overlay(byte[] statuses, PreviewLod lod, PreviewMesh mesh) {
        void close() {
            if (mesh != null) {
                mesh.close();
            }
        }
    }

    private static final class CpuMesh {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        void append(ByteBuffer vertices, int stride) {
            for (int offset = vertices.position(); offset < vertices.limit(); offset += stride) {
                float x = vertices.getFloat(offset);
                float y = vertices.getFloat(offset + 4);
                float z = vertices.getFloat(offset + 8);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            var copy = new byte[vertices.remaining()];
            vertices.get(copy);
            bytes.writeBytes(copy);
        }

        PreviewMesh upload(RenderType type, Vec3 viewpoint) {
            var bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.001);
            return PreviewMesh.upload(type, bounds, ByteBuffer.wrap(bytes.toByteArray()), viewpoint);
        }
    }

    private static boolean modelIsEmpty(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state) {
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(level, pos, state, RandomSource.create(42L), parts);
        for (var part : parts) {
            if (!part.getQuads(null).isEmpty()) {
                return false;
            }
            for (var direction : Direction.values()) {
                if (!part.getQuads(direction).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class ScratchBuffers implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (!MODEL_LAYERS.contains(type)) {
                throw new IllegalArgumentException("Unsupported preview layer " + type);
            }
            return PreviewMesh.stage(type);
        }

        boolean appendTo(Map<RenderType, CpuMesh> geometry) {
            return PreviewMesh.drainStaged((type, vertices) -> geometry.computeIfAbsent(type, ignored -> new CpuMesh())
                    .append(vertices, type.format().getVertexSize()));
        }

        void discard() {
            PreviewMesh.discardStaged();
        }
    }
}
