package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

final class PreviewMesh implements AutoCloseable {
    private static final int STAGING_CAPACITY = 1 << 16;
    private static final int INDEX_USAGE = GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST;
    private static final Map<RenderType, ByteBufferBuilder> STAGING = new HashMap<>();
    private static final Map<RenderType, BufferBuilder> STAGED = new HashMap<>();
    private static ByteBufferBuilder upload;
    private static int liveBuffers;
    private final GpuBuffer vertexBuffer;
    private GpuBuffer indexBuffer;
    final RenderType type;
    final AABB bounds;
    private final int vertices;
    private final int indexCount;
    private final VertexFormat.IndexType indexType;
    private final MeshData.SortState sortState;
    private Vec3 sortedFrom;
    private boolean closed;

    private PreviewMesh(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, RenderType type, AABB bounds,
            MeshData.DrawState drawState, MeshData.SortState sortState, Vec3 sortedFrom) {
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.type = type;
        this.bounds = bounds;
        this.vertices = drawState.vertexCount();
        this.indexCount = drawState.indexCount();
        this.indexType = drawState.indexType();
        this.sortState = sortState;
        this.sortedFrom = sortedFrom;
    }

    static BufferBuilder stage(RenderType type) {
        RenderSystem.assertOnRenderThread();
        var builder = STAGED.get(type);
        if (builder == null) {
            var bytes = STAGING.computeIfAbsent(type, ignored -> new ByteBufferBuilder(STAGING_CAPACITY));
            var unbuilt = bytes.build();
            if (unbuilt != null) {
                unbuilt.close();
            }
            builder = new BufferBuilder(bytes, type.mode(), type.format());
            STAGED.put(type, builder);
        }
        return builder;
    }

    static boolean drainStaged(BiConsumer<RenderType, ByteBuffer> vertices) {
        boolean drained = false;
        var staged = STAGED.entrySet().iterator();
        while (staged.hasNext()) {
            var entry = staged.next();
            staged.remove();
            try (var mesh = entry.getValue().build()) {
                if (mesh != null) {
                    vertices.accept(entry.getKey(), mesh.vertexBuffer());
                    drained = true;
                }
            }
        }
        return drained;
    }

    static void discardStaged() {
        drainStaged((type, vertices) -> {});
    }

    static void releaseStaging() {
        RenderSystem.assertOnRenderThread();
        discardStaged();
        STAGING.values().forEach(ByteBufferBuilder::close);
        STAGING.clear();
        if (upload != null) {
            upload.close();
            upload = null;
        }
    }

    static int stagingBuffers() {
        return STAGING.size() + (upload == null ? 0 : 1);
    }

    static PreviewMesh upload(RenderType type, AABB bounds, ByteBuffer bytes, Vec3 viewpoint) {
        RenderSystem.assertOnRenderThread();
        int vertexSize = type.format().getVertexSize();
        int length = bytes.remaining();
        if (length == 0 || length % vertexSize != 0) {
            throw new IllegalArgumentException("Preview mesh of " + length + " bytes is not whole " + type + " vertices");
        }
        int vertices = length / vertexSize;
        var staging = uploadStaging();
        MemoryUtil.memByteBuffer(staging.reserve(length), length).put(bytes.duplicate());
        var mode = type.mode();
        var drawState = new MeshData.DrawState(type.format(), vertices, mode.indexCount(vertices), mode,
                VertexFormat.IndexType.least(vertices));
        boolean compileSorted = PreviewRenderTypes.compileSorted(type);
        var device = RenderSystem.getDevice();
        GpuBuffer vertexBuffer = null;
        try (var mesh = new MeshData(staging.build(), drawState)) {
            var sortState = compileSorted ? mesh.sortQuads(staging, sorting(viewpoint)) : null;
            vertexBuffer = device.createBuffer(() -> "Preview " + type + " vertices", GpuBuffer.USAGE_VERTEX,
                    mesh.vertexBuffer());
            liveBuffers++;
            GpuBuffer indexBuffer = null;
            var indices = mesh.indexBuffer();
            if (indices != null) {
                indexBuffer = device.createBuffer(() -> "Preview " + type + " indices", INDEX_USAGE, indices);
                liveBuffers++;
            }
            PreviewDebugStats.meshUploaded(compileSorted);
            return new PreviewMesh(vertexBuffer, indexBuffer, type, bounds, drawState, sortState,
                    compileSorted ? viewpoint : null);
        } catch (RuntimeException | Error exception) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
                liveBuffers--;
            }
            throw exception;
        }
    }

    static RenderPass beginPass(RenderPipeline pipeline) {
        var minecraft = Minecraft.getInstance();
        var target = minecraft.getMainRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride
                : target.getDepthTextureView();
        var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Matter Manipulator preview " + pipeline.getLocation(), color, OptionalInt.empty(), depth,
                OptionalDouble.empty());
        pass.setPipeline(pipeline);
        RenderSystem.bindDefaultUniforms(pass);
        var samplers = pipeline.getSamplers();
        if (samplers.contains("Sampler0")) {
            pass.bindTexture("Sampler0",
                    minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                    PreviewRenderTypes.blockSampler());
        }
        if (samplers.contains("Sampler2")) {
            pass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        return pass;
    }

    void draw(RenderPass pass, GpuBufferSlice transform) {
        if (closed) {
            return;
        }
        pass.setUniform("DynamicTransforms", transform);
        pass.setVertexBuffer(0, vertexBuffer);
        if (indexBuffer != null) {
            pass.setIndexBuffer(indexBuffer, indexType);
        } else {
            var sequential = RenderSystem.getSequentialBuffer(type.mode());
            pass.setIndexBuffer(sequential.getBuffer(indexCount), sequential.type());
        }
        pass.drawIndexed(0, 0, indexCount, 1);
    }

    boolean needsResort(Vec3 viewpoint) {
        return sortState != null && PreviewRenderTypes.TRANSLUCENT_SORTING.shouldResort(
                viewpoint.distanceToSqr(sortedFrom), viewpoint.distanceToSqr(bounds.getCenter()));
    }

    void resort(Vec3 viewpoint) {
        RenderSystem.assertOnRenderThread();
        long start = PreviewDebugStats.now();
        var indices = sortState.buildSortedIndexBuffer(uploadStaging(), sorting(viewpoint));
        if (indices != null) {
            try (indices) {
                var bytes = indices.byteBuffer();
                if (bytes.remaining() == indexBuffer.size()) {
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(indexBuffer.slice(), bytes);
                } else {
                    indexBuffer.close();
                    indexBuffer = RenderSystem.getDevice().createBuffer(() -> "Preview " + type + " indices",
                            INDEX_USAGE, bytes);
                }
            }
        }
        sortedFrom = viewpoint;
        PreviewDebugStats.translucentResorted(vertices / 4, PreviewDebugStats.now() - start);
    }

    int vertices() {
        return vertices;
    }

    static int liveBuffers() {
        return liveBuffers;
    }

    private static ByteBufferBuilder uploadStaging() {
        if (upload == null) {
            upload = new ByteBufferBuilder(STAGING_CAPACITY);
        }
        return upload;
    }

    private static VertexSorting sorting(Vec3 viewpoint) {
        return VertexSorting.byDistance((float) viewpoint.x, (float) viewpoint.y, (float) viewpoint.z);
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) {
            return;
        }
        closed = true;
        vertexBuffer.close();
        liveBuffers--;
        if (indexBuffer != null) {
            indexBuffer.close();
            liveBuffers--;
        }
    }
}
