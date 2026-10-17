package lu.kolja.matter_manipulator.client.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.preview.PreviewModelData;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class ClientIntegrationRegistry {

    static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private final Map<Block, List<Entry>> exact = new HashMap<>();
    private final List<Entry> predicates = new ArrayList<>();
    private final Map<BlockState, List<Entry>> matchesByState = new ConcurrentHashMap<>();

    private ClientIntegrationRegistry(Builder builder) {
        for (var entry : builder.entries) {
            if (entry.predicate() != null) {
                predicates.add(entry);
                continue;
            }
            for (var block : entry.exactBlocks()) {
                exact.computeIfAbsent(block, ignored -> new ArrayList<>()).add(entry);
            }
        }
        exact.replaceAll((ignored, value) -> List.copyOf(value));
    }

    public ModelData create(
            Level level,
            BlockPos pos,
            BlockState state,
            CapturedBlock captured,
            Set<Direction> lineDirections,
            PreviewDetail detail
    ) {
        var entries = matching(state);
        if (entries.isEmpty()) {
            return ModelData.EMPTY;
        }
        var context = new ClientIntegrationContext.CapturedPreview(level, pos, state,
                captured == null ? null : captured.snapshot(), Set.copyOf(lineDirections), detail);
        var combined = ModelData.EMPTY;
        for (var entry : entries) {
            try {
                var modelData = Objects.requireNonNull(entry.handler().create(context), "Integration preview model data");
                if (PreviewModelData.hasProperties(modelData)) {
                    combined = merge(combined, modelData);
                }
            } catch (RuntimeException | LinkageError exception) {
                report(entry.id(), "captured_preview", state, exception);
            }
        }
        return combined;
    }

    public ModelData fromWorld(
            Level level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos previewPos,
            BlockState previewState,
            ToolTransform transform,
            PreviewDetail detail
    ) {
        var entries = matching(previewState);
        if (entries.isEmpty()) {
            return ModelData.EMPTY;
        }
        var context = new ClientIntegrationContext.WorldPreview(
                level,
                sourcePos,
                sourceState,
                previewPos,
                previewState,
                transform,
                detail
        );
        var combined = ModelData.EMPTY;
        for (var entry : entries) {
            try {
                var modelData = Objects.requireNonNull(entry.handler().fromWorld(context), "Integration world model data");
                if (PreviewModelData.hasProperties(modelData)) {
                    combined = merge(combined, modelData);
                }
            } catch (RuntimeException | LinkageError exception) {
                report(entry.id(), "world_preview", previewState, exception);
            }
        }
        return combined;
    }

    public ClientIntegrationDispatcher.StaticRender renderStatic(
            Level level,
            BlockAndTintGetter view,
            BlockPos pos,
            BlockState state,
            @Nullable CapturedBlock captured,
            @Nullable BlockPos sourcePos,
            DirectionTransform transform,
            Set<Direction> lineDirections,
            PreviewDetail detail,
            PoseStack pose,
            Function<ChunkSectionLayer, VertexConsumer> buffers
    ) {
        var entries = matching(state);
        if (entries.isEmpty()) {
            return ClientIntegrationDispatcher.StaticRender.NONE;
        }
        var context = new ClientIntegrationContext.StaticPreview(level, view, pos, state,
                captured == null ? null : captured.snapshot(), sourcePos, transform, lineDirections, detail);
        boolean drawn = false;
        for (var entry : entries) {
            try {
                for (var layer : ChunkSectionLayer.values()) {
                    var handlerPose = new PoseStack();
                    handlerPose.last().pose().set(pose.last().pose());
                    handlerPose.last().normal().set(pose.last().normal());
                    drawn |= entry.handler().renderStatic(context, handlerPose, buffers.apply(layer), layer);
                }
            } catch (RuntimeException | LinkageError exception) {
                report(entry.id(), "static_render", state, exception);
                return ClientIntegrationDispatcher.StaticRender.FAILED;
            }
        }
        return drawn ? ClientIntegrationDispatcher.StaticRender.DRAWN : ClientIntegrationDispatcher.StaticRender.NONE;
    }

    public ItemStack previewIcon(CapturedBlock captured) {
        var state = captured.state().tryResolve().orElse(null);
        if (state == null) {
            return ItemStack.EMPTY;
        }
        var entries = matching(state);
        if (!entries.isEmpty()) {
            var snapshot = captured.snapshot();
            for (var entry : entries) {
                try {
                    var icon = entry.handler().previewIcon(snapshot);
                    if (icon != null && !icon.isEmpty()) {
                        return icon;
                    }
                } catch (RuntimeException | LinkageError exception) {
                    report(entry.id(), "preview_icon", state, exception);
                }
            }
        }
        return new ItemStack(state.getBlock());
    }

    private static ModelData merge(ModelData base, ModelData contribution) {
        var builder = base.derive();
        for (var property : contribution.getProperties()) {
            copyProperty(builder, contribution, property);
        }
        return builder.build();
    }

    private static <T> void copyProperty(ModelData.Builder builder, ModelData source, ModelProperty<T> property) {
        builder.with(property, source.get(property));
    }

    private List<Entry> matching(BlockState state) {
        return matchesByState.computeIfAbsent(state, this::findMatching);
    }

    private List<Entry> findMatching(BlockState state) {
        var matches = new LinkedHashMap<Identifier, Entry>();
        for (var entry : exact.getOrDefault(state.getBlock(), List.of())) {
            matches.put(entry.id(), entry);
        }
        for (var entry : predicates) {
            try {
                if (entry.predicate().test(state)) {
                    matches.put(entry.id(), entry);
                }
            } catch (RuntimeException | LinkageError exception) {
                report(entry.id(), "match", state, exception);
            }
        }
        return matches.values().stream().sorted(Comparator.comparingInt(Entry::order)).toList();
    }

    private static void report(Identifier id, String operation, BlockState state, Throwable exception) {
        var key = id + ":" + operation;
        if (REPORTED_FAILURES.add(key)) {
            MatterManipulator.LOGGER.error(
                    "Client integration {} failed during {} for {}; using the default preview",
                    id,
                    operation,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    exception
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    static final class Builder implements ClientIntegrationRegistrar {

        private final List<Entry> entries = new ArrayList<>();
        private final Set<Identifier> ids = new HashSet<>();
        private boolean frozen;

        @Override
        public void preview(Identifier id, ClientPreviewIntegration handler, Block... blocks) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            var selected = Set.copyOf(Arrays.asList(blocks));
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("Exact selector for " + id + " cannot be empty");
            }
            requireUnique(id);
            entries.add(new Entry(id, handler, selected, null, entries.size()));
        }

        @Override
        public void preview(Identifier id, ClientPreviewIntegration handler, Predicate<BlockState> selector) {
            ensureMutable();
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(selector, "selector");
            requireUnique(id);
            entries.add(new Entry(id, handler, Set.of(), selector, entries.size()));
        }

        public ClientIntegrationRegistry build() {
            ensureMutable();
            frozen = true;
            return new ClientIntegrationRegistry(this);
        }

        private void requireUnique(Identifier id) {
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate client integration id: " + id);
            }
        }

        void freeze() {
            frozen = true;
        }

        private void ensureMutable() {
            if (frozen) {
                throw new IllegalStateException("Client integration registry is already frozen");
            }
        }
    }

    private record Entry(
            Identifier id,
            ClientPreviewIntegration handler,
            Set<Block> exactBlocks,
            Predicate<BlockState> predicate,
            int order
    ) {}
}
