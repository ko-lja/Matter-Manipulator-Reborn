package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.client.PreviewModels;
import lu.kolja.matter_manipulator.client.PasteFollow;
import lu.kolja.matter_manipulator.client.PendingSelection;
import lu.kolja.matter_manipulator.client.blueprint.BlueprintLibrary;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.build.BuildPlanner;
import lu.kolja.matter_manipulator.common.build.CablePathfinder;
import lu.kolja.matter_manipulator.common.build.CableRoute;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.MarkTarget;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.state.ToolState;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID, value = Dist.CLIENT)
public final class PreviewRenderer {

    private static final double FANCY_BOX_INFLATE = 0.012D;
    private static final double ANCHOR_BOX_INFLATE = 0.035D;
    private static final double MARK_BOX_INFLATE = 0.07D;
    private static final PreviewStyle MARK_STYLE = new PreviewStyle(1.0F, 1.0F, 1.0F, 0.08F, 0.85F, 0.0F);
    private static final float TAU =(float) (Math.PI * 2.0D);
    private static final PreviewStyle SOURCE_STYLE = new PreviewStyle(0.15F, 0.72F, 1.0F, 0.13F, 0.78F, 0.95F);
    private static final PreviewStyle DESTINATION_STYLE = new PreviewStyle(1.0F, 0.62F, 0.16F, 0.15F, 0.84F, 1.0F);
    private static final PreviewStyle ENDPOINT_STYLE = new PreviewStyle(0.38F, 1.0F, 0.42F, 0.12F, 0.78F, 0.9F);
    private static final PreviewStyle CABLE_STYLE = new PreviewStyle(0.14F, 1.0F, 0.72F, 0.10F, 0.62F, 0.75F);
    private static final PreviewStyle INVALID_STYLE = new PreviewStyle(1.0F, 0.18F, 0.18F, 0.16F, 0.9F, 1.0F);
    private static final PreviewStyle SEARCH_VISITED_STYLE = new PreviewStyle(0.5F, 0.28F, 1.0F, 0.025F, 0.18F, 0.0F);
    private static final PreviewStyle SEARCH_FRONTIER_STYLE = new PreviewStyle(1.0F, 0.74F, 0.2F, 0.08F, 0.42F, 0.0F);
    private static final MultiBufferSource.BufferSource FANCY_BUFFER = MultiBufferSource.immediateWithBuffers(
            new LinkedHashMap<>(Map.of(
                    RenderType.debugFilledBox(), new ByteBufferBuilder(RenderType.debugFilledBox().bufferSize()),
                    RenderType.lines(), new ByteBufferBuilder(RenderType.lines().bufferSize())
            )),
            new ByteBufferBuilder(256)
    );
    private static CablePreviewCache cablePreviewCache = CablePreviewCache.empty();
    private static CablePreviewKey lastReportedCableKey;
    private static long lastCableSearchStepTick = Long.MIN_VALUE;
    private static RequestInputs requestInputs;
    private static WorldPreviewCache.Request previewRequest;
    private static ItemStack previewHashStack = ItemStack.EMPTY;
    private static long previewHashTick = Long.MIN_VALUE;
    private static int previewStateRevision;
    private static CompoundTag previewStateSnapshot;
    private static int cableStateRevision = -1;
    private static BlockState cachedCableState;
    private static List<BlockPos> cachedWaypoints = List.of();
    private static CablePreviewTopology cableTopology = CablePreviewTopology.empty();
    private static Level activeWorld;
    private static final WorldPreviewCache WORLD_PREVIEW = new WorldPreviewCache();
    private static CableRangeCache cableRangeCache = CableRangeCache.empty();
    private static final int GUI_PREVIEW_LIMIT = 8_192;

    private PreviewRenderer() {}

    public static GuiPreviewSnapshot guiPreview(Minecraft minecraft, ItemStack stack) {
        if (minecraft.player == null || minecraft.level == null || !(stack.getItem() instanceof ManipulatorItem)
                || !RenderSystem.isOnRenderThread()) {
            return GuiPreviewSnapshot.empty();
        }
        return WORLD_PREVIEW.guiSnapshot(minecraft, GUI_PREVIEW_LIMIT);
    }

    public static GuiPreviewSnapshot blueprintPreview(Minecraft minecraft, Blueprint blueprint) {
        if (minecraft.level == null || !RenderSystem.isOnRenderThread()
                || blueprint.volume() > MMConfig.MAX_PREVIEW_INSPECTIONS.get()) {
            return GuiPreviewSnapshot.empty();
        }
        var content = PreviewSources.blueprint(blueprint, ToolTransform.IDENTITY, 1, true,
                MMConfig.MAX_PREVIEW_BLOCKS.get(), PreviewContent.EMPTY);
        return GuiPreviewSnapshot.of(minecraft, content, GUI_PREVIEW_LIMIT);
    }

    public static BlueprintSurface blueprintSurface(Minecraft minecraft, Blueprint blueprint, int maxInspections) {
        var content = PreviewSources.blueprint(blueprint, ToolTransform.IDENTITY, 1, true, maxInspections,
                PreviewContent.EMPTY);
        return new BlueprintSurface(minecraft, content);
    }

    public static void invalidateCablePreview() {
        cablePreviewCache = CablePreviewCache.empty();
        requestInputs = null;
        previewRequest = null;
        lastCableSearchStepTick = Long.MIN_VALUE;
        previewHashTick = Long.MIN_VALUE;
        cableStateRevision = -1;
        cableTopology = CablePreviewTopology.empty();
    }

    public static void clearWorldPreview() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(PreviewRenderer::clearWorldPreview);
            return;
        }
        WORLD_PREVIEW.close();
        invalidateCablePreview();
        cableRangeCache = CableRangeCache.empty();
        previewHashStack = ItemStack.EMPTY;
        previewStateSnapshot = null;
        cachedCableState = null;
        cachedWaypoints = List.of();
        lastReportedCableKey = null;
        activeWorld = null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearWorldPreview();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clearWorldPreview();
        }
    }

    @SubscribeEvent
    public static void onPreviewTick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        if (activeWorld != null && (minecraft.level != activeWorld || minecraft.player == null
                || !(minecraft.player.getMainHandItem().getItem() instanceof ManipulatorItem)
                && !(minecraft.player.getOffhandItem().getItem() instanceof ManipulatorItem))) {
            clearWorldPreview();
            return;
        }
        if (activeWorld != null && minecraft.player != null) {
            var stack = minecraft.player.getMainHandItem().getItem() instanceof ManipulatorItem
                    ? minecraft.player.getMainHandItem() : minecraft.player.getOffhandItem();
            var state = ToolState.get(stack);
            previewStateRevision(stack, minecraft.level);
            var anchors = PasteFollow.anchors(minecraft, stack, 1.0F);
            boolean hasInputs = state.mode() == ManipulatorMode.BLUEPRINT
                    ? state.blueprintRef().isPresent() && anchors.c().isPresent()
                    : state.mode() == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES
                    ? !cableWaypoints(state).isEmpty()
                    : anchors.a().isPresent() && anchors.b().isPresent()
                            && (state.mode() != ManipulatorMode.COPY && state.mode() != ManipulatorMode.MOVE
                                    || anchors.c().isPresent());
            if (!hasInputs) {
                clearWorldPreview();
            }
        }
    }

    public static boolean renderCableSearchProgress() {
        return MMConfig.SHOW_CABLE_SEARCH_PROGRESS.get();
    }

    public static void toggleCableSearchProgress() {
        MMConfig.SHOW_CABLE_SEARCH_PROGRESS.set(!renderCableSearchProgress());
        MMConfig.SHOW_CABLE_SEARCH_PROGRESS.save();
    }

    public static boolean cableSearchMessages() {
        return MMConfig.CABLE_SEARCH_MESSAGES.get();
    }

    public static void toggleCableSearchMessages() {
        MMConfig.CABLE_SEARCH_MESSAGES.set(!cableSearchMessages());
        MMConfig.CABLE_SEARCH_MESSAGES.save();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        PreviewDebugStats.frameStarted();
        try {
            renderFrame(event);
        } finally {
            PreviewDebugStats.frameRendered();
        }
    }

    private static void renderFrame(RenderLevelStageEvent event) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clearWorldPreview();
            return;
        }
        var stack = minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof ManipulatorItem)) {
            stack = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
        }
        if (!(stack.getItem() instanceof ManipulatorItem item)) {
            clearWorldPreview();
            return;
        }
        var state = ToolState.get(stack);
        previewStateRevision(stack, minecraft.level);
        var anchors = PasteFollow.anchors(minecraft, stack, event.getPartialTick().getGameTimeDeltaPartialTick(true));
        var a = anchors.a();
        var b = anchors.b();
        boolean edgesMode = state.mode() == ManipulatorMode.CABLE
                && state.cableRouteMode() == CableRouteMode.EDGES;
        var mark = nextMark(minecraft, state);
        boolean missingInputs = state.mode() == ManipulatorMode.BLUEPRINT
                ? state.blueprintRef().isEmpty() || anchors.c().isEmpty()
                : (!edgesMode && a.isEmpty()) || (edgesMode && cableWaypoints(state).isEmpty());
        if (missingInputs) {
            clearWorldPreview();
            if (mark.isPresent()) {
                var poseStack = event.getPoseStack();
                var cameraPos = event.getCamera().getPosition();
                poseStack.pushPose();
                poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
                renderMark(poseStack, mark.get());
                poseStack.popPose();
                FANCY_BUFFER.endBatch(RenderType.debugFilledBox());
                FANCY_BUFFER.endBatch(RenderType.lines());
            }
            return;
        }

        if (activeWorld != minecraft.level) {
            clearWorldPreview();
            activeWorld = minecraft.level;
        }

        boolean cableMode = state.mode() == ManipulatorMode.CABLE;
        if (edgesMode) {
            edgeCablePath(minecraft.level, state, minecraft.player);
        } else if (cableMode && b.isPresent()) {
            cablePath(minecraft.level, minecraft.player, a.get(), b.get(), cableState(state),
                    state.preferredCapturedSlot(BlockSlot.CABLE).orElse(null), state.removeMode());
        }

        var poseStack = event.getPoseStack();
        var cameraPos = event.getCamera().getPosition();
        var frustum = event.getFrustum();
        var path = cableMode ? cablePreviewCache.path() : List.<BlockPos>of();
        var request = previewRequest(minecraft, stack, state, anchors, item, path);
        if (request != null) {
            var options = new CompiledPreview.Options(MMConfig.GHOST_ALPHA.get(), cableMode,
                    minecraft.options.ambientOcclusion().get(), minecraft.options.graphicsMode().get());
            WORLD_PREVIEW.update(minecraft, request, options, cameraPos, MMConfig.WORLD_PREVIEW_REFRESH_TICKS.get());
            WORLD_PREVIEW.render(poseStack, frustum, cameraPos);
        } else {
            WORLD_PREVIEW.close();
        }

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        float time = previewTime();
        var fills = FANCY_BUFFER.getBuffer(RenderType.debugFilledBox());
        var lines = FANCY_BUFFER.getBuffer(RenderType.lines());
        var placement = request == null ? null : WORLD_PREVIEW.placement();
        if (!cableMode) {
            renderGlobalPreview(poseStack, fills, lines, state, anchors, a.orElse(null),
                    minecraft.player.blockPosition(), item, placement, time);
            var pending = PendingSelection.box(minecraft, stack, anchors, mark).orElse(null);
            if (pending != null) {
                var style = pending.refusal().isPresent() ? INVALID_STYLE
                        : state.mode() == ManipulatorMode.EXCHANGE ? DESTINATION_STYLE : SOURCE_STYLE;
                renderFancyBox(poseStack, fills, lines, pending.bounds(), style, time);
            }
        } else {
            renderCablePath(poseStack, fills, path, minecraft.player.blockPosition(), item, frustum, time);
            var waypoints = cableWaypoints(state);
            var lineStart = !edgesMode ? b.isEmpty() ? a.get() : null
                    : state.floatingPlacement() ? null : waypoints.get(waypoints.size() - 1);
            if (lineStart != null && mark.isPresent() && state.isSameDimension(minecraft.level)) {
                renderPendingCable(poseStack, lines, lineStart, mark.get(), minecraft.player.blockPosition(), item);
            }
            if (!edgesMode && renderCableSearchProgress() && cablePreviewCache.search() != null) {
                renderCableSearchProgress(poseStack, fills, lines, cablePreviewCache.search(), frustum, time);
            }
        }
        if (edgesMode) {
            renderCableWaypoints(poseStack, fills, lines, state, minecraft.player, time);
        } else {
            a.ifPresent(pos -> renderAnchor(poseStack, fills, lines, pos, SOURCE_STYLE, time));
        }
        if (cableMode && !edgesMode) {
            b.ifPresent(pos -> renderAnchor(poseStack, fills, lines, pos, CABLE_STYLE, time));
        } else if (!cableMode) {
            b.ifPresent(pos -> renderAnchor(poseStack, fills, lines, pos, ENDPOINT_STYLE, time));
        }
        if (!cableMode) {
            anchors.c().ifPresent(pos -> renderAnchor(poseStack, fills, lines, pos, DESTINATION_STYLE, time));
        }
        mark.ifPresent(pos -> renderMark(poseStack, pos));
        FANCY_BUFFER.endBatch(RenderType.debugFilledBox());
        poseStack.popPose();
        if (cableMode && request != null) {
            boolean inRange = cableWithinRange(path, minecraft.player.blockPosition(), item);
            var style = inRange ? CABLE_STYLE : INVALID_STYLE;
            WORLD_PREVIEW.renderCableOutlines(poseStack, frustum, cameraPos,
                    style.red(), style.green(), style.blue(), inRange ? 0.52F : 0.78F);
        }
        FANCY_BUFFER.endBatch(RenderType.lines());
    }

    public static Optional<BlockPos> nextMark(Minecraft minecraft, ToolState state) {
        if (minecraft.player.isCrouching()) {
            return Optional.empty();
        }
        var hit = minecraft.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit : null;
        return MarkTarget.next(minecraft.level, minecraft.player, state, hit);
    }

    private static void renderPendingCable(PoseStack poseStack, VertexConsumer lines, BlockPos a, BlockPos mark,
            BlockPos playerPos, ManipulatorItem item) {
        var style = BuildPlanner.checkRange(playerPos, item.tier().range(), List.of(a, mark)).isEmpty()
                ? CABLE_STYLE : INVALID_STYLE;
        renderLine(poseStack, lines, a.getX() + 0.5D, a.getY() + 0.5D, a.getZ() + 0.5D, mark.getX() + 0.5D,
                mark.getY() + 0.5D, mark.getZ() + 0.5D, style.red(), style.green(), style.blue(), style.edgeAlpha());
    }

    private static void renderMark(PoseStack poseStack, BlockPos pos) {
        renderFancyBox(poseStack, FANCY_BUFFER.getBuffer(RenderType.debugFilledBox()),
                FANCY_BUFFER.getBuffer(RenderType.lines()), new AABB(pos).inflate(MARK_BOX_INFLATE), MARK_STYLE,
                previewTime());
    }

    private static WorldPreviewCache.Request previewRequest(Minecraft minecraft, ItemStack stack, ToolState state,
            FloatingPlacement.Anchors anchors, ManipulatorItem item, List<BlockPos> path) {
        boolean translated = state.mode() == ManipulatorMode.COPY || state.mode() == ManipulatorMode.MOVE
                || state.mode() == ManipulatorMode.BLUEPRINT;
        var inputAnchors = translated ? new FloatingPlacement.Anchors(anchors.a(), anchors.b(), Optional.empty())
                : anchors;
        var blueprint = state.mode() != ManipulatorMode.BLUEPRINT ? null
                : state.blueprintRef().map(ref -> BlueprintLibrary.loadedForPreview(ref.hash(), ref.size()))
                        .orElse(null);
        var inputs = new RequestInputs(minecraft.level, state.mode(), previewStateRevision(stack, minecraft.level),
                inputAnchors, path, MMConfig.MAX_PREVIEW_BLOCKS.get(), MMConfig.MAX_CABLE_PREVIEW_BLOCKS.get(),
                MMConfig.MAX_PREVIEW_INSPECTIONS.get(), item.tier().canRemove(), blueprint);
        if (!inputs.equals(requestInputs)) {
            requestInputs = inputs;
            previewRequest = buildRequest(minecraft.level, state, inputAnchors, inputs, path);
        }
        if (!translated || previewRequest == null) {
            return previewRequest;
        }
        return anchors.c().map(previewRequest::at).orElse(null);
    }

    private record RequestInputs(Level level, ManipulatorMode mode, int revision, FloatingPlacement.Anchors anchors,
            List<BlockPos> path, int maxBlocks, int maxCableBlocks, int maxInspections, boolean canRemove,
            @Nullable Blueprint blueprint) {}

    private static WorldPreviewCache.Request buildRequest(Level level, ToolState state,
            FloatingPlacement.Anchors anchors, RequestInputs inputs, List<BlockPos> path) {
        var a = anchors.a().orElse(null);
        var b = anchors.b().orElse(null);
        var c = anchors.c().orElse(null);
        int limit = inputs.maxBlocks();
        var removeMode = state.removeMode();
        var mode = state.mode();
        switch (mode) {
            case GEOMETRY -> {
                if (a == null || b == null) {
                    return null;
                }
                var shape = state.shape();
                var slots = PreviewSources.geometrySlots(state);
                var size = b.subtract(a);
                var height = shape == ShapeType.CYLINDER && c != null ? c.subtract(a) : null;
                return new WorldPreviewCache.Request(new GeometryKey(shape, size, height, slots, limit), null, a,
                        new PreviewPlacement.Params(mode, removeMode, inputs.canRemove(), null, null),
                        (snapshot, previous) -> PreviewSources.geometry(slots, shape, BlockPos.ZERO, size, height,
                                limit, previous));
            }
            case CABLE -> {
                if (path.isEmpty()) {
                    return null;
                }
                var origin = path.get(0);
                var cableState = cableState(state);
                var captured = PreviewSources.preferredCapturedSlot(state, BlockSlot.CABLE).orElse(null);
                int cableLimit = inputs.maxCableBlocks();
                cableTopology = cableTopology.retain(path, MMConfig.maxQueuedBlocks());
                var topology = cableTopology;
                return new WorldPreviewCache.Request(new CableKey(path, cableState, captured, cableLimit, topology),
                        null, origin, new PreviewPlacement.Params(mode, removeMode, inputs.canRemove(), null, null),
                        (snapshot, previous) -> PreviewSources.cable(path, origin, cableState, captured, topology,
                                cableLimit, previous));
            }
            case EXCHANGE -> {
                if (a == null || b == null) {
                    return null;
                }
                var capturedTarget = state.capturedSlot(BlockSlot.EXCHANGE_TARGET)
                        .or(() -> state.capturedSlot(BlockSlot.VOLUME))
                        .filter(captured -> captured.state().isKnown())
                        .orElse(null);
                var target = capturedTarget == null
                        ? state.exchangeTarget().flatMap(BlockStateSpec::tryResolve)
                        : capturedTarget.state().tryResolve();
                var match = state.replaceWhitelist().flatMap(BlockStateSpec::tryResolve);
                var source = sourceKey(level, a, b, inputs.maxInspections());
                if (state.exchangeTarget().isEmpty() || target.isEmpty() || match.isEmpty() || source == null) {
                    return null;
                }
                var targetState = target.get();
                var matchState = match.get();
                return new WorldPreviewCache.Request(
                        new ExchangeKey(source, matchState, targetState, capturedTarget, limit), source, source.min(),
                        null, (snapshot, previous) -> PreviewSources.exchange(snapshot, matchState, targetState,
                                capturedTarget, limit, previous));
            }
            case COPY, MOVE -> {
                var source = a == null || b == null ? null : sourceKey(level, a, b, inputs.maxInspections());
                if (source == null) {
                    return null;
                }
                boolean move = mode == ManipulatorMode.MOVE;
                var transform = move ? state.transform().withStackCount(1) : state.transform();
                int stacks = transform.stackCount();
                return new WorldPreviewCache.Request(new TransformKey(mode, source, transform, limit), source, null,
                        new PreviewPlacement.Params(mode, removeMode, inputs.canRemove(),
                                move ? source.min() : null, move ? source.max() : null),
                        (snapshot, previous) -> PreviewSources.transformed(snapshot, transform, stacks,
                                PreviewSources.orientation(mode, transform),
                                PreviewLod.forBlocks(PreviewSources.transformedCount(snapshot, stacks)).usesModels(),
                                limit, previous));
            }
            case BLUEPRINT -> {
                var ref = state.blueprintRef().orElse(null);
                var blueprint = inputs.blueprint();
                if (ref == null || blueprint == null || blueprint.volume() > inputs.maxInspections()) {
                    return null;
                }
                var transform = state.transform();
                int stacks = transform.stackCount();
                var key = new BlueprintKey(ref.hash(), ref.size(), transform, limit);
                return new WorldPreviewCache.Request(key, null, null,
                        new PreviewPlacement.Params(mode, removeMode, inputs.canRemove(), null, null),
                        (snapshot, previous) -> PreviewSources.blueprint(blueprint, transform, stacks,
                                PreviewLod.forBlocks(PreviewSources.blueprintCount(blueprint, stacks)).usesModels(),
                                limit, previous));
            }
        }
        return null;
    }

    private static PreviewSourceSnapshot.Key sourceKey(Level level, BlockPos a, BlockPos b, int maxInspections) {
        var min = min(a, b);
        var max = max(a, b);
        return PreviewSourceSnapshot.volume(min, max) > maxInspections ? null
                : new PreviewSourceSnapshot.Key(level, min, max);
    }

    private record GeometryKey(ShapeType shape, BlockPos size, BlockPos height,
            Map<BlockSlot, Optional<PreviewSources.SlotPreview>> slots, int limit) {}

    private record CableKey(List<BlockPos> path, BlockState cableState, CapturedBlock captured, int limit,
            CablePreviewTopology topology) {}

    private record ExchangeKey(PreviewSourceSnapshot.Key source, BlockState match, BlockState target,
            CapturedBlock captured, int limit) {}

    private record TransformKey(ManipulatorMode mode, PreviewSourceSnapshot.Key source, ToolTransform transform,
            int limit) {}

    private record BlueprintKey(String hash, BlockPos size, ToolTransform transform, int limit) {}

    private static BlockState cableState(ToolState state) {
        if (cableStateRevision == previewStateRevision && cachedCableState != null) {
            return cachedCableState;
        }
        cableStateRevision = previewStateRevision;
        cachedWaypoints = List.copyOf(state.cableWaypoints());
        cachedCableState = PreviewSources.preferredCapturedSlot(state, BlockSlot.CABLE)
                .flatMap(captured -> captured.state().tryResolve())
                .or(() -> state.preferredSlot(BlockSlot.CABLE).flatMap(BlockStateSpec::tryResolve))
                .orElse(Blocks.GLASS.defaultBlockState());
        return cachedCableState;
    }

    private static List<BlockPos> cableWaypoints(ToolState state) {
        cableState(state);
        return cachedWaypoints;
    }

    private static List<BlockPos> cablePath(
            Level level,
            Player player,
            BlockPos start,
            BlockPos end,
            BlockState targetCable,
            @Nullable CapturedBlock template,
            RemoveMode removeMode
    ) {
        int maxPositions = MMConfig.maxQueuedBlocks();
        var key = new CablePreviewKey(
                level,
                copy(start),
                copy(end),
                CableRouteMode.PATHFIND,
                List.of(),
                targetCable,
                template,
                removeMode,
                maxPositions
        );
        if (!cablePreviewCache.matches(key)) {
            cablePreviewCache = CablePreviewCache.searching(
                    key,
                    CablePathfinder.start(level, player, start, end, targetCable, template, removeMode, maxPositions)
            );
            lastCableSearchStepTick = Long.MIN_VALUE;
        }
        long gameTime = level.getGameTime();
        if (cablePreviewCache.search() != null && gameTime != lastCableSearchStepTick) {
            lastCableSearchStepTick = gameTime;
            var search = cablePreviewCache.search();
            search.step(MMConfig.CABLE_PATH_STEPS_PER_TICK.get());
            if (search.isComplete()) {
                reportCableSearchComplete(key, search);
                cablePreviewCache = CablePreviewCache.complete(key, search.path());
            }
        }
        return cablePreviewCache.path();
    }

    private static List<BlockPos> edgeCablePath(Level level, ToolState state, Player player) {
        var waypoints = edgeWaypoints(state, player, true);
        if (waypoints.isEmpty()) {
            cablePreviewCache = CablePreviewCache.empty();
            return List.of();
        }
        var key = new CablePreviewKey(
                level,
                copy(waypoints.get(0)),
                copy(waypoints.get(waypoints.size() - 1)),
                CableRouteMode.EDGES,
                List.copyOf(waypoints),
                cableState(state),
                state.preferredCapturedSlot(BlockSlot.CABLE).orElse(null),
                state.removeMode(),
                MMConfig.maxQueuedBlocks()
        );
        if (!cablePreviewCache.matches(key)) {
            var route = CableRoute.expand(waypoints, MMConfig.maxQueuedBlocks());
            cablePreviewCache = CablePreviewCache.complete(key, route.positions());
        }
        return cablePreviewCache.path();
    }

    private static List<BlockPos> edgeWaypoints(
            ToolState state,
            Player player,
            boolean includeFloatingCandidate
    ) {
        var waypoints = cableWaypoints(state);
        if (!includeFloatingCandidate || !state.floatingPlacement() || waypoints.isEmpty()) {
            return waypoints;
        }
        var last = waypoints.get(waypoints.size() - 1);
        var candidate = CableRoute.snapToAxis(last, FloatingPlacement.lookTarget(player, state));
        if (!last.equals(candidate)) {
            waypoints = new ArrayList<>(waypoints);
            waypoints.add(candidate);
        }
        return waypoints;
    }

    private static void renderCableWaypoints(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            ToolState state,
            Player player,
            float time
    ) {
        var waypoints = cableWaypoints(state);
        for (int i = 0; i < waypoints.size(); i++) {
            var style = i == 0 ? SOURCE_STYLE : i + 1 == waypoints.size() ? ENDPOINT_STYLE : CABLE_STYLE;
            renderAnchor(poseStack, fills, lines, waypoints.get(i), style, time);
        }
        var previewWaypoints = edgeWaypoints(state, player, true);
        if (previewWaypoints.size() > waypoints.size()) {
            renderAnchor(
                    poseStack,
                    fills,
                    lines,
                    previewWaypoints.get(previewWaypoints.size() - 1),
                    DESTINATION_STYLE,
                    time
            );
        }
    }

    private static void reportCableSearchComplete(CablePreviewKey key, CablePathfinder.Search search) {
        var player = Minecraft.getInstance().player;
        if (player == null || key.equals(lastReportedCableKey)) {
            return;
        }
        lastReportedCableKey = key;
        if (!cableSearchMessages()) {
            return;
        }
        if (search.path().isEmpty()) {
            player.displayClientMessage(MMMessage.CABLE_PATH_FAILED.text(
                    search.elapsedMillis(),
                    search.visitedCount(),
                    search.failureDescription()
            ), false);
            return;
        }
        player.displayClientMessage(MMMessage.CABLE_PATH_FOUND.text(
                search.elapsedMillis(),
                search.path().size(),
                search.visitedCount()
        ), false);
    }

    public static void renderPreviewBlock(
            BlockRenderDispatcher blockRenderer,
            PoseStack poseStack,
            MultiBufferSource ghostBuffer,
            PreviewBlock preview
    ) {
        renderPreviewBlock(blockRenderer, poseStack, ghostBuffer, preview, Set.of());
    }

    public static void renderPreviewBlock(
            BlockRenderDispatcher blockRenderer,
            PoseStack poseStack,
            MultiBufferSource ghostBuffer,
            PreviewBlock preview,
            Set<Direction> hidden
    ) {
        if (preview.geometry() != null) {
            preview.geometry().render(poseStack, ghostBuffer);
            return;
        }
        var modelData = preview.modelData();
        if (!modelData.has(PreviewModels.RENDER_LEVEL)) {
            if (hidden.isEmpty() || preview.state().getRenderShape() != RenderShape.MODEL) {
                blockRenderer.renderSingleBlock(
                        preview.state(),
                        poseStack,
                        ghostBuffer,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        modelData,
                        null
                );
                return;
            }
            var state = preview.state();
            var model = new PreviewCulling.VisibleFaces(blockRenderer.getBlockModel(state), hidden);
            int color = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
            for (var renderType : model.getRenderTypes(state, RandomSource.create(42), modelData)) {
                blockRenderer.getModelRenderer().renderModel(poseStack.last(),
                        ghostBuffer.getBuffer(RenderTypeHelper.getEntityRenderType(renderType, false)), state, model,
                        (color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, modelData, renderType);
            }
            return;
        }

        BlockAndTintGetter renderLevel = modelData.get(PreviewModels.RENDER_LEVEL);
        if (renderLevel == null) {
            return;
        }
        var state = preview.state();
        var pos = preview.pos();
        long seed = state.getSeed(pos);
        var model = hidden.isEmpty() ? blockRenderer.getBlockModel(state)
                : new PreviewCulling.VisibleFaces(blockRenderer.getBlockModel(state), hidden);
        for (var renderType : model.getRenderTypes(state, RandomSource.create(seed), modelData)) {
            blockRenderer.getModelRenderer().tesselateBlock(
                    renderLevel,
                    model,
                    state,
                    pos,
                    poseStack,
                    ghostBuffer.getBuffer(renderType),
                    false,
                    RandomSource.create(seed),
                    seed,
                    OverlayTexture.NO_OVERLAY,
                    modelData,
                    renderType
            );
        }
    }

    private static boolean withinRange(BlockPos playerPos, ManipulatorItem item, BlockPos pos) {
        int range = item.tier().range();
        return range < 0 || playerPos.distSqr(pos) <= (double) range * range;
    }

    private static boolean allWithinRange(List<BlockPos> positions, BlockPos playerPos, ManipulatorItem item) {
        for (var pos : positions) {
            if (!withinRange(playerPos, item, pos)) {
                return false;
            }
        }
        return true;
    }

    private static boolean cableWithinRange(List<BlockPos> path, BlockPos playerPos, ManipulatorItem item) {
        int range = item.tier().range();
        if (cableRangeCache.matches(path, playerPos, range)) {
            return cableRangeCache.withinRange();
        }
        boolean withinRange = allWithinRange(path, playerPos, item);
        cableRangeCache = new CableRangeCache(path, playerPos.immutable(), range, withinRange);
        return withinRange;
    }

    private static boolean allWithinRange(AABB box, BlockPos playerPos, ManipulatorItem item) {
        var min = BlockPos.containing(box.minX, box.minY, box.minZ);
        var max = BlockPos.containing(box.maxX - 1.0D, box.maxY - 1.0D, box.maxZ - 1.0D);
        for (int x : new int[] {min.getX(), max.getX()}) {
            for (int y : new int[] {min.getY(), max.getY()}) {
                for (int z : new int[] {min.getZ(), max.getZ()}) {
                    if (!withinRange(playerPos, item, new BlockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void renderGlobalPreview(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            ToolState state,
            FloatingPlacement.Anchors anchors,
            @Nullable BlockPos a,
            BlockPos playerPos,
            ManipulatorItem item,
            PreviewPlacement placement,
            float time
    ) {
        switch (state.mode()) {
            case GEOMETRY -> anchors.b().ifPresent(pos -> {
                var selection = bounds(a, pos);
                renderFancyBox(poseStack, fills, lines, selection,
                        allWithinRange(selection, playerPos, item) ? SOURCE_STYLE : INVALID_STYLE, time);
            });
            case EXCHANGE -> anchors.b().ifPresent(pos -> {
                var selection = bounds(a, pos);
                renderFancyBox(poseStack, fills, lines, selection,
                        allWithinRange(selection, playerPos, item) ? DESTINATION_STYLE : INVALID_STYLE, time);
            });
            case COPY, MOVE -> {
                anchors.b().ifPresent(pos -> renderFancyBox(
                        poseStack,
                        fills,
                        lines,
                        bounds(a, pos),
                        SOURCE_STYLE,
                        time
                ));
                if (anchors.b().isPresent() && anchors.c().isPresent()) {
                    int stackCount = state.mode() == ManipulatorMode.MOVE ? 1 : state.transform().stackCount();
                    var destination = transformedBounds(a, anchors.b().get(), anchors.c().get(), state, stackCount);
                    boolean blocked = placement != null && placement.count(PreviewPlacement.BLOCKED) > 0;
                    renderFancyBox(poseStack, fills, lines, destination,
                            !blocked && allWithinRange(destination, playerPos, item) ? DESTINATION_STYLE : INVALID_STYLE,
                            time);
                }
            }
            case BLUEPRINT -> state.blueprintRef().ifPresent(ref -> anchors.c().ifPresent(c -> {
                var destination = transformedBounds(ref.size().offset(-1, -1, -1), c, state.transform(),
                        state.transform().stackCount());
                renderFancyBox(poseStack, fills, lines, destination,
                        allWithinRange(destination, playerPos, item) ? DESTINATION_STYLE : INVALID_STYLE, time);
            }));
            case CABLE -> {
            }
        }
    }

    private static void renderCablePath(
            PoseStack poseStack,
            VertexConsumer fills,
            List<BlockPos> path,
            BlockPos playerPos,
            ManipulatorItem item,
            Frustum frustum,
            float time
    ) {
        if (path.isEmpty() || !PreviewDebugStats.flowMarkersEnabled()) {
            return;
        }
        var style = cableWithinRange(path, playerPos, item) ? CABLE_STYLE : INVALID_STYLE;
        var shown = path.subList(0, Math.min(path.size(), MMConfig.MAX_CABLE_PREVIEW_BLOCKS.get()));
        long start = PreviewDebugStats.enabled() ? System.nanoTime() : 0;
        int markers = renderCableFlow(poseStack, fills, shown, style, frustum, time);
        PreviewDebugStats.cableFlowRendered(markers, PreviewDebugStats.enabled() ? System.nanoTime() - start : 0);
    }

    private static void renderCableSearchProgress(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            CablePathfinder.Search search,
            Frustum frustum,
            float time
    ) {
        for (var pos : search.visitedPositions(MMConfig.MAX_SEARCH_PROGRESS_BOXES.get())) {
            var bounds = new AABB(pos);
            if (!frustum.isVisible(bounds)) {
                continue;
            }
            renderBox(
                    poseStack,
                    lines,
                    bounds,
                    SEARCH_VISITED_STYLE.red(),
                    SEARCH_VISITED_STYLE.green(),
                    SEARCH_VISITED_STYLE.blue(),
                    SEARCH_VISITED_STYLE.edgeAlpha()
            );
        }
        for (var pos : search.queuedPositions(MMConfig.MAX_SEARCH_FRONTIER_BOXES.get())) {
            var bounds = new AABB(pos);
            if (frustum.isVisible(bounds)) {
                renderSoftBox(poseStack, fills, lines, bounds, SEARCH_FRONTIER_STYLE, time);
            }
        }
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())
        );
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
    }

    private static BlockPos copy(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static AABB bounds(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1,
                Math.max(a.getY(), b.getY()) + 1,
                Math.max(a.getZ(), b.getZ()) + 1
        );
    }

    private static AABB transformedBounds(
            BlockPos a,
            BlockPos b,
            BlockPos destination,
            ToolState state,
            int stackCount
    ) {
        return transformedBounds(max(a, b).subtract(min(a, b)), destination, state.transform(), stackCount);
    }

    private static AABB transformedBounds(BlockPos sourceSize, BlockPos destination, ToolTransform transform,
            int stackCount) {
        var transformedSize = transform.transformedSize(sourceSize);
        var lastOffset = transform.stackOffset(transformedSize, Math.max(0, stackCount - 1));
        return bounds(destination, destination.offset(lastOffset).offset(transformedSize));
    }

    private static float previewTime() {
        return (System.currentTimeMillis() % 2_500L) / 2_500.0F;
    }

    private static void renderAnchor(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            BlockPos pos,
            PreviewStyle style,
            float time
    ) {
        renderFancyBox(poseStack, fills, lines, new AABB(pos).inflate(ANCHOR_BOX_INFLATE), style, time);
        renderRulers(poseStack, lines, pos, style);
    }

    private static int renderCableFlow(
            PoseStack poseStack,
            VertexConsumer fills,
            List<BlockPos> path,
            PreviewStyle style,
            Frustum frustum,
            float time
    ) {
        int step = Math.max(1, (int) Math.ceil(
                path.size() / (double) MMConfig.MAX_CABLE_FLOW_MARKERS.get()
        ));
        int phase = (int) (time * step) % step;
        int rendered = 0;
        for (int i = phase; i < path.size(); i += step) {
            var pos = path.get(i);
            if (!frustum.isVisible(new AABB(pos))) {
                continue;
            }
            float wave = 0.5F + 0.5F * Mth.sin(time * TAU * 4.0F - i * 0.32F);
            float alpha = style.fillAlpha() * (0.85F + wave * 0.9F);
            double inset = 0.27D + wave * 0.05D;
            renderFilledBox(
                    poseStack,
                    fills,
                    pos.getX() + inset,
                    pos.getY() + inset,
                    pos.getZ() + inset,
                    pos.getX() + 1.0D - inset,
                    pos.getY() + 1.0D - inset,
                    pos.getZ() + 1.0D - inset,
                    style,
                    alpha
            );
            rendered++;
        }
        return rendered;
    }

    private static int previewStateRevision(ItemStack stack, Level level) {
        long gameTime = level.getGameTime();
        if (stack != previewHashStack || gameTime != previewHashTick) {
            previewHashStack = stack;
            previewHashTick = gameTime;
            var tag = ToolState.existingTag(stack);
            if (!Objects.equals(tag, previewStateSnapshot)) {
                previewStateSnapshot = tag == null ? null : tag.copy();
                previewStateRevision++;
            }
        }
        return previewStateRevision;
    }

    private static void renderFancyBox(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            AABB box,
            PreviewStyle style,
            float time
    ) {
        var inflated = box.inflate(FANCY_BOX_INFLATE);
        float pulse = pulse(time, inflated);
        renderFilledBox(poseStack, fills, inflated, style, style.fillAlpha() * pulse);
        renderBox(
                poseStack,
                lines,
                inflated,
                style.red(),
                style.green(),
                style.blue(),
                style.edgeAlpha() * (0.82F + pulse * 0.18F)
        );
        if (style.cornerAlpha() > 0.0F) {
            renderCornerTicks(
                    poseStack,
                    lines,
                    inflated,
                    style.red(),
                    style.green(),
                    style.blue(),
                    style.cornerAlpha() * (0.78F + pulse * 0.22F)
            );
        }
    }

    private static void renderSoftBox(
            PoseStack poseStack,
            VertexConsumer fills,
            VertexConsumer lines,
            AABB box,
            PreviewStyle style,
            float time
    ) {
        var inflated = box.inflate(FANCY_BOX_INFLATE);
        float pulse = pulse(time, inflated);
        renderFilledBox(poseStack, fills, inflated, style, style.fillAlpha() * pulse);
        renderBox(poseStack, lines, inflated, style.red(), style.green(), style.blue(), style.edgeAlpha());
    }

    private static void renderFilledBox(
            PoseStack poseStack,
            VertexConsumer fills,
            AABB box,
            PreviewStyle style,
            float alpha
    ) {
        if (alpha <= 0.0F) {
            return;
        }
        LevelRenderer.addChainedFilledBoxVertices(
                poseStack,
                fills,
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ,
                style.red(),
                style.green(),
                style.blue(),
                alpha
        );
    }

    private static void renderFilledBox(
            PoseStack poseStack,
            VertexConsumer fills,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            PreviewStyle style,
            float alpha
    ) {
        if (alpha <= 0.0F) {
            return;
        }
        LevelRenderer.addChainedFilledBoxVertices(
                poseStack,
                fills,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                style.red(),
                style.green(),
                style.blue(),
                alpha
        );
    }

    private static float pulse(float time, AABB box) {
        float phase = (float) ((box.minX + box.minY + box.minZ) * 0.35D);
        return 0.78F + 0.22F * (0.5F + 0.5F * Mth.sin(time * TAU + phase));
    }

    private static void renderCornerTicks(
            PoseStack poseStack,
            VertexConsumer lines,
            AABB box,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        double length = cornerLength(box);
        var xs = new double[] {box.minX, box.maxX};
        var ys = new double[] {box.minY, box.maxY};
        var zs = new double[] {box.minZ, box.maxZ};
        for (int xIndex = 0; xIndex < xs.length; xIndex++) {
            double xDirection = xIndex == 0 ? 1.0D : -1.0D;
            for (int yIndex = 0; yIndex < ys.length; yIndex++) {
                double yDirection = yIndex == 0 ? 1.0D : -1.0D;
                for (int zIndex = 0; zIndex < zs.length; zIndex++) {
                    double zDirection = zIndex == 0 ? 1.0D : -1.0D;
                    double x = xs[xIndex];
                    double y = ys[yIndex];
                    double z = zs[zIndex];
                    renderLine(poseStack, lines, x, y, z, x + xDirection * length, y, z, red, green, blue, alpha);
                    renderLine(poseStack, lines, x, y, z, x, y + yDirection * length, z, red, green, blue, alpha);
                    renderLine(poseStack, lines, x, y, z, x, y, z + zDirection * length, red, green, blue, alpha);
                }
            }
        }
    }

    private static double cornerLength(AABB box) {
        double xSize = box.maxX - box.minX;
        double ySize = box.maxY - box.minY;
        double zSize = box.maxZ - box.minZ;
        double shortest = Math.min(xSize, Math.min(ySize, zSize));
        double longest = Math.max(xSize, Math.max(ySize, zSize));
        double length = Math.max(0.3D, Math.min(2.25D, longest * 0.12D));
        return Math.min(shortest * 0.45D, length);
    }

    private static void renderRulers(PoseStack poseStack, VertexConsumer lines, BlockPos pos, PreviewStyle style) {
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        float alpha = style.edgeAlpha() * 0.18F;
        for (var direction : Direction.values()) {
            double startX = centerX + direction.getStepX() * 0.58D;
            double startY = centerY + direction.getStepY() * 0.58D;
            double startZ = centerZ + direction.getStepZ() * 0.58D;
            double endX = centerX + direction.getStepX() * MMConfig.PREVIEW_RULER_LENGTH.get();
            double endY = centerY + direction.getStepY() * MMConfig.PREVIEW_RULER_LENGTH.get();
            double endZ = centerZ + direction.getStepZ() * MMConfig.PREVIEW_RULER_LENGTH.get();
            renderLine(
                    poseStack,
                    lines,
                    startX,
                    startY,
                    startZ,
                    endX,
                    endY,
                    endZ,
                    style.red(),
                    style.green(),
                    style.blue(),
                    alpha
            );
        }
    }

    private static void renderLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0D) {
            return;
        }
        var pose = poseStack.last();
        float normalX = (float) (dx / length);
        float normalY = (float) (dy / length);
        float normalZ = (float) (dz / length);
        consumer.addVertex(pose, (float) startX, (float) startY, (float) startZ)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, normalX, normalY, normalZ);
        consumer.addVertex(pose, (float) endX, (float) endY, (float) endZ)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, normalX, normalY, normalZ);
    }

    private static void renderBox(
            PoseStack poseStack,
            VertexConsumer consumer,
            AABB box,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        LevelRenderer.renderLineBox(poseStack, consumer, box.inflate(0.002), red, green, blue, alpha);
    }

    private record PreviewStyle(float red, float green, float blue, float fillAlpha, float edgeAlpha, float cornerAlpha) {}

    private record CablePreviewKey(
            Level level,
            BlockPos start,
            BlockPos end,
            CableRouteMode routeMode,
            List<BlockPos> waypoints,
            BlockState targetCable,
            @Nullable CapturedBlock template,
            RemoveMode removeMode,
            int maxPreviewBlocks
    ) {}

    private record CablePreviewCache(CablePreviewKey key, List<BlockPos> path, CablePathfinder.Search search) {
        private static CablePreviewCache empty() {
            return new CablePreviewCache(null, List.of(), null);
        }

        private static CablePreviewCache searching(CablePreviewKey key, CablePathfinder.Search search) {
            return new CablePreviewCache(key, List.of(), search);
        }

        private static CablePreviewCache complete(CablePreviewKey key, List<BlockPos> path) {
            return new CablePreviewCache(key, List.copyOf(path), null);
        }

        private boolean matches(CablePreviewKey otherKey) {
            return key != null && key.equals(otherKey);
        }
    }

    private record CableRangeCache(
            List<BlockPos> path,
            BlockPos playerPos,
            int range,
            boolean withinRange
    ) {
        private static CableRangeCache empty() {
            return new CableRangeCache(List.of(), BlockPos.ZERO, 0, true);
        }

        private boolean matches(List<BlockPos> otherPath, BlockPos otherPlayerPos, int otherRange) {
            return path == otherPath && playerPos.equals(otherPlayerPos) && range == otherRange;
        }
    }

    public record GuiPreviewSnapshot(List<PreviewBlock> blocks, boolean truncated) {
        public GuiPreviewSnapshot {
            blocks = List.copyOf(blocks);
        }

        public static GuiPreviewSnapshot empty() {
            return new GuiPreviewSnapshot(List.of(), false);
        }

        static GuiPreviewSnapshot of(Minecraft minecraft, PreviewContent content, int limit) {
            var blocks = new ArrayList<PreviewBlock>();
            boolean truncated = content.truncated;
            var view = new PreviewContentLevel(content, minecraft.level, BlockPos.ZERO);
            var geometryDetail = geometryDetail(content);
            for (var section : content.sections()) {
                for (int index = 0; index < 4096; index++) {
                    var state = section.states[index];
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    if (blocks.size() >= limit) {
                        return new GuiPreviewSnapshot(blocks, true);
                    }
                    var pos = new BlockPos(section.originX + PreviewContent.Section.x(index),
                            section.originY + PreviewContent.Section.y(index),
                            section.originZ + PreviewContent.Section.z(index));
                    blocks.add(resolve(minecraft, view, section.visual(index), pos, state, geometryDetail));
                }
            }
            return new GuiPreviewSnapshot(blocks, truncated);
        }

        private static PreviewDetail geometryDetail(PreviewContent content) {
            return PreviewLod.forBlocks(content.blocks) == PreviewLod.FULL ? PreviewDetail.FULL : PreviewDetail.STATIC;
        }

        private static PreviewBlock resolve(Minecraft minecraft, PreviewContentLevel view,
                @Nullable PreviewVisual visual, BlockPos pos, BlockState state, PreviewDetail geometryDetail) {
            var geometry = PreviewStaticGeometry.record(minecraft.level, view, visual, pos, state, geometryDetail);
            var modelData = geometry != null ? ModelData.EMPTY
                    : PreviewModelData.resolve(minecraft.level, visual, pos, state, PreviewDetail.STATIC);
            return new PreviewBlock(pos, state, modelData, new AABB(pos), geometry);
        }
    }

    public static final class BlueprintSurface {
        private final Minecraft minecraft;
        private final PreviewContent content;
        private final PreviewContentLevel view;
        private final PreviewDetail geometryDetail;
        private final List<PreviewContent.Section> sections;
        private int scanned;
        private boolean found;
        private final List<PreviewBlock> blocks = new ArrayList<>();
        private final Map<BlockState, Boolean> opaqueCubes = new IdentityHashMap<>();

        private BlueprintSurface(Minecraft minecraft, PreviewContent content) {
            this.minecraft = minecraft;
            this.content = content;
            view = new PreviewContentLevel(content, minecraft.level, BlockPos.ZERO);
            geometryDetail = GuiPreviewSnapshot.geometryDetail(content);
            sections = List.copyOf(content.sections());
        }

        public boolean find(long deadline) {
            while (!found) {
                if (scanned == sections.size()) {
                    blocks.sort(Comparator.comparingInt(block -> -block.pos().getY()));
                    found = true;
                    break;
                }
                var section = sections.get(scanned++);
                for (int index = 0; index < 4096; index++) {
                    var state = section.states[index];
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    int x = section.originX + PreviewContent.Section.x(index);
                    int y = section.originY + PreviewContent.Section.y(index);
                    int z = section.originZ + PreviewContent.Section.z(index);
                    if (!block(x - 1, y, z) || !block(x + 1, y, z) || !block(x, y - 1, z) || !block(x, y + 1, z)
                            || !block(x, y, z - 1) || !block(x, y, z + 1)) {
                        blocks.add(new PreviewBlock(new BlockPos(x, y, z), state, ModelData.EMPTY));
                    }
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            return found;
        }

        private boolean block(int x, int y, int z) {
            var state = content.state(x, y, z);
            return state != null && !state.isAir();
        }

        public int size() {
            return blocks.size();
        }

        public int y(int index) {
            return blocks.get(index).pos().getY();
        }

        public PreviewBlock resolve(int index) {
            var block = blocks.get(index);
            var pos = block.pos();
            return GuiPreviewSnapshot.resolve(minecraft, view, content.visual(pos.getX(), pos.getY(), pos.getZ()), pos,
                    block.state(), geometryDetail);
        }

        public Set<Direction> hidden(int index) {
            var block = blocks.get(index);
            return PreviewCulling.hiddenSides(block.state(), block.pos(), view, neighbour -> {
                var state = content.state(neighbour.getX(), neighbour.getY(), neighbour.getZ());
                return state != null && opaqueCubes.computeIfAbsent(state,
                        ignored -> PreviewCulling.opaqueUnitCube(minecraft, state));
            });
        }
    }

    public record PreviewBlock(BlockPos pos, BlockState state, ModelData modelData, AABB bounds,
            @Nullable PreviewStaticGeometry geometry) {
        public PreviewBlock(BlockPos pos, BlockState state, ModelData modelData) {
            this(pos, state, modelData, new AABB(pos), null);
        }
    }

    record GhostBufferSource(MultiBufferSource delegate, int alpha) implements MultiBufferSource {
        GhostBufferSource(MultiBufferSource delegate) {
            this(delegate, MMConfig.GHOST_ALPHA.get());
        }
        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            var target = renderType == Sheets.solidBlockSheet()
                    || renderType == Sheets.cutoutBlockSheet()
                    || renderType == Sheets.translucentCullBlockSheet()
                    ? PreviewRenderTypes.ghost()
                    : renderType;
            return new AlphaVertexConsumer(delegate.getBuffer(target), alpha);
        }
    }

    private record AlphaVertexConsumer(VertexConsumer delegate, int previewAlpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, Math.min(alpha, previewAlpha));
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
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
