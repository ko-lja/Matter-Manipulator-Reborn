package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class WorldPreviewCache implements AutoCloseable {
    static final long COMPILE_BUDGET_NANOS = 4_000_000L;
    static final int INSPECTION_BUDGET = 65_536;
    static final int STATUS_BUDGET = 32_768;
    static final long SETTLE_NANOS = 200_000_000L;

    record Request(Object contentKey, PreviewSourceSnapshot.Key source, BlockPos origin,
            PreviewPlacement.Params placement, ContentFactory factory) {

        Request at(BlockPos destination) {
            return destination.equals(origin) ? this
                    : new Request(contentKey, source, destination, placement, factory);
        }
    }

    interface ContentFactory {
        PreviewContent build(PreviewSourceSnapshot snapshot, PreviewContent previous);
    }

    private record ContentKey(Object request, int sourceVersion) {}

    private final CompiledPreview compiled = new CompiledPreview();
    private Object world;
    private PreviewSourceSnapshot snapshot;
    private ContentKey contentKey;
    private PreviewContent content = PreviewContent.EMPTY;
    private PreviewPlacement placement;
    private PreviewPlacement pendingPlacement;
    private BlockPos origin;
    private long originMovedAt;
    private PreviewRenderer.GuiPreviewSnapshot guiSnapshot = PreviewRenderer.GuiPreviewSnapshot.empty();
    private PreviewContent guiContent;

    void update(Minecraft minecraft, Request request, CompiledPreview.Options options, Vec3 camera,
            int refreshTicks) {
        RenderSystem.assertOnRenderThread();
        var level = minecraft.level;
        if (world != level) {
            close();
            world = level;
        }
        long gameTime = level.getGameTime();
        int sourceVersion = 0;
        if (request.source() != null) {
            if (snapshot == null || !snapshot.key.equals(request.source())) {
                snapshot = new PreviewSourceSnapshot(request.source());
            }
            long start = PreviewDebugStats.now();
            var modelData = level.getModelDataManager();
            if (snapshot.advance(level, pos -> modelData == null ? 1 : System.identityHashCode(modelData.getAt(pos)) | 1,
                    gameTime, refreshTicks, INSPECTION_BUDGET)) {
                PreviewDebugStats.inspected(start);
            }
            if (!snapshot.complete()) {
                draw(minecraft, options, camera);
                return;
            }
            sourceVersion = snapshot.version();
        } else {
            snapshot = null;
        }
        var key = new ContentKey(request.contentKey(), sourceVersion);
        if (!key.equals(contentKey)) {
            long start = PreviewDebugStats.now();
            content = request.factory().build(snapshot, content);
            contentKey = key;
            PreviewDebugStats.inspected(start);
        }
        if (!request.origin().equals(origin)) {
            originMovedAt = System.nanoTime();
        }
        origin = request.origin();
        compiled.setContent(content, PreviewLod.forBlocks(content.blocks), options);
        draw(minecraft, options, camera);
        if (request.placement() == null || content.isEmpty()) {
            placement = null;
            pendingPlacement = null;
            return;
        }
        long statusPeriod = (long) refreshTicks * Math.max(1, content.positions / STATUS_BUDGET);
        var statusKey = new PreviewPlacement.Key(content, origin, request.placement(), gameTime / statusPeriod);
        if (placement != null && placement.key.equals(statusKey)
                || System.nanoTime() - originMovedAt < SETTLE_NANOS) {
            return;
        }
        if (pendingPlacement == null || !pendingPlacement.key.equals(statusKey)) {
            pendingPlacement = new PreviewPlacement(statusKey);
        }
        long start = PreviewDebugStats.now();
        if (pendingPlacement.advance(level, minecraft.player, STATUS_BUDGET)) {
            placement = pendingPlacement;
            pendingPlacement = null;
            compiled.updateStatuses(placement);
            PreviewDebugStats.statusEvaluated(start);
        }
    }

    private void draw(Minecraft minecraft, CompiledPreview.Options options, Vec3 camera) {
        if (compiled.pending() && origin != null) {
            compiled.compile(minecraft, camera.subtract(Vec3.atLowerCornerOf(origin)), origin, COMPILE_BUDGET_NANOS);
        }
    }

    boolean hasGeometry() {
        return origin != null && compiled.sectionCount() > 0;
    }

    BlockPos origin() {
        return origin;
    }

    PreviewContent content() {
        return content;
    }

    PreviewPlacement placement() {
        return placement != null && placement.key.content() == content && placement.key.origin().equals(origin)
                ? placement : null;
    }

    void render(PoseStack pose, Frustum frustum, Vec3 camera) {
        if (origin != null) {
            compiled.render(pose, frustum, camera, origin, placement() != null);
        }
    }

    void renderCableOutlines(PoseStack pose, Frustum frustum, Vec3 camera, float red, float green, float blue,
            float alpha) {
        if (origin != null) {
            compiled.renderCableOutlines(pose, frustum, camera, origin, red, green, blue, alpha);
        }
    }

    CompiledPreview compiled() {
        return compiled;
    }

    PreviewRenderer.GuiPreviewSnapshot guiSnapshot(Minecraft minecraft, int limit) {
        if (guiContent == content) {
            return guiSnapshot;
        }
        guiContent = content;
        guiSnapshot = PreviewRenderer.GuiPreviewSnapshot.of(minecraft, content, limit);
        return guiSnapshot;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        compiled.close();
        PreviewMesh.releaseStaging();
        if (world != null) {
            PreviewDebugStats.finishSession();
        }
        world = null;
        snapshot = null;
        contentKey = null;
        content = PreviewContent.EMPTY;
        placement = null;
        pendingPlacement = null;
        origin = null;
        guiContent = null;
        guiSnapshot = PreviewRenderer.GuiPreviewSnapshot.empty();
    }
}
