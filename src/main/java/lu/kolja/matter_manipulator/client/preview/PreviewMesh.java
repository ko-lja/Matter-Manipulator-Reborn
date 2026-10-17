package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

final class PreviewMesh implements AutoCloseable {
    private static final int STAGING_CAPACITY = 1 << 16;
    private static final Map<RenderType, ByteBufferBuilder> STAGING = new HashMap<>();
    private static final Map<RenderType, BufferBuilder> STAGED = new HashMap<>();
    private static ByteBufferBuilder upload;
    private static int liveBuffers;
    private final VertexBuffer buffer;
    final RenderType type;
    final AABB bounds;
    private final int vertices;
    private final MeshData.SortState sortState;
    private Vec3 sortedFrom;
    private boolean closed;

    private PreviewMesh(VertexBuffer buffer, RenderType type, AABB bounds, int vertices,
            MeshData.SortState sortState, Vec3 sortedFrom) {
        this.buffer = buffer;
        this.type = type;
        this.bounds = bounds;
        this.vertices = vertices;
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
        var mesh = new MeshData(staging.build(), new MeshData.DrawState(type.format(), vertices,
                mode.indexCount(vertices), mode, VertexFormat.IndexType.least(vertices)));
        boolean compileSorted = PreviewRenderTypes.compileSorted(type);
        VertexBuffer buffer = null;
        try {
            var sortState = compileSorted ? mesh.sortQuads(staging, sorting(viewpoint)) : null;
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            var uploading = mesh;
            mesh = null;
            buffer.upload(uploading);
            liveBuffers++;
            PreviewDebugStats.meshUploaded(compileSorted);
            return new PreviewMesh(buffer, type, bounds, vertices, sortState, compileSorted ? viewpoint : null);
        } catch (RuntimeException | Error exception) {
            if (buffer != null) {
                buffer.close();
            }
            throw exception;
        } finally {
            if (mesh != null) {
                mesh.close();
            }
            VertexBuffer.unbind();
        }
    }

    void draw(Matrix4f modelView) {
        if (closed) {
            return;
        }
        buffer.bind();
        buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
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
            try {
                buffer.bind();
                buffer.uploadIndexBuffer(indices);
            } finally {
                VertexBuffer.unbind();
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
        if (!closed) {
            closed = true;
            buffer.close();
            liveBuffers--;
        }
    }
}
