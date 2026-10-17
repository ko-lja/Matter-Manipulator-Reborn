package lu.kolja.matter_manipulator.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import java.util.function.Consumer;
import java.util.function.Function;

public final class IntegrationContext {

    private IntegrationContext() {}

    /**
     * Store links to peers in {@code selection} as offsets from {@code pos}, rotated with {@link
     * DirectionTransform#transformOffset}. Only {@link BlockIntegration#moveRefusal} gets a {@code transform}.
     */
    public record Capture(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            @Nullable BoundingBox selection,
            @Nullable DirectionTransform transform
    ) {
        public Capture(ServerLevel level, BlockPos pos, BlockState state, @Nullable BoundingBox selection) {
            this(level, pos, state, selection, null);
        }

        public Capture(ServerLevel level, BlockPos pos, BlockState state) {
            this(level, pos, state, null, null);
        }

        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }

        public boolean inSelection(BlockPos peer) {
            return selection != null && selection.isInside(peer);
        }
    }

    /** The block entity is fresh, or the live one for {@link PlacementPurpose#RECONFIGURE}. */
    public record SideEffects(ServerLevel level, BlockPos pos, BlockState state, PlacementPurpose purpose) {
        @Nullable
        public BlockEntity blockEntity() {
            return purpose == PlacementPurpose.RECONFIGURE ? level.getBlockEntity(pos) : null;
        }
    }

    public record Exchange(ServerLevel level, BlockPos pos, BlockState sourceState, BlockState targetState) {
        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }
    }

    public record Apply(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            ServerPlayer player,
            Consumer<ItemStack> refund,
            PlacementPurpose purpose,
            Consumer<Runnable> afterTick,
            Consumer<Component> rejection,
            Consumer<String> warning
    ) {
        public Apply(
                ServerLevel level,
                BlockPos pos,
                BlockState state,
                ServerPlayer player,
                Consumer<ItemStack> refund,
                PlacementPurpose purpose,
                Consumer<Runnable> afterTick
        ) {
            this(level, pos, state, player, refund, purpose, afterTick, reason -> {}, translationKey -> {});
        }

        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }

        /** Returns REJECTED_UNCHANGED. The player sees {@code reason} if every handler rejects a fresh placement. */
        public BlockIntegration.ApplyResult reject(Component reason) {
            rejection.accept(Objects.requireNonNull(reason, "reason"));
            return BlockIntegration.ApplyResult.REJECTED_UNCHANGED;
        }

        /**
         * Tells the player, once per {@code translationKey}, that the block differs from the capture. Ignored if the
         * handler rejects.
         */
        public void warn(String translationKey) {
            warning.accept(Objects.requireNonNull(translationKey, "translationKey"));
        }

        public void refund(ItemStack stack) {
            if (!stack.isEmpty()) {
                refund.accept(stack.copy());
            }
        }

        /**
         * Runs at the end of the server tick. Settle resource ownership before apply returns, because deferred
         * failures cannot roll back.
         */
        public void afterTick(Runnable work) {
            afterTick.accept(Objects.requireNonNull(work, "work"));
        }
    }

    public record Transform(BlockState state, DirectionTransform transform) {}

    /** The level is read-only. */
    public record Transfer(ServerLevel level, BlockPos pos, BlockState state) {}

    /** {@code committed} is false for a block restored by a rollback. */
    public record Transferred(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity blockEntity,
            boolean committed
    ) {}

    /** {@code destinations} maps each selection position to its destination and returns {@code null} outside it. */
    public record Relocation(
            BlockState state,
            ResourceKey<Level> dimension,
            BlockPos source,
            BlockPos destination,
            DirectionTransform transform,
            Function<BlockPos, @Nullable BlockPos> destinations
    ) {
        @Nullable
        public BlockPos destinationOf(BlockPos sourcePos) {
            return destinations.apply(sourcePos);
        }
    }

    public record Removal(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            ServerPlayer player,
            boolean collectDrops,
            Consumer<ItemStack> dropSink
    ) {
        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }
    }

    public record Placement(BlockState target, @Nullable BlockSnapshot captured) {}

    public record Preparation(BlockState target, @Nullable BlockSnapshot captured, PlacementPurpose purpose) {}

    public record Cable(
            ServerLevel level,
            BlockPos pos,
            BlockState target,
            Set<Direction> lineDirections,
            @Nullable BlockSnapshot template
    ) {
        public Cable {
            lineDirections = Set.copyOf(lineDirections);
        }

        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }

        public CompoundTag payload(ResourceLocation id) {
            if (template == null) {
                return new CompoundTag();
            }
            return template.payload(id);
        }
    }

    /** Also asked on the client, so {@code level} may be a client level. */
    public record CableReuse(
            BlockGetter level,
            BlockPos pos,
            BlockState existing,
            BlockState target,
            @Nullable BlockSnapshot template
    ) {
        @Nullable
        public BlockEntity blockEntity() {
            return level.getBlockEntity(pos);
        }

        public CompoundTag payload(ResourceLocation id) {
            return template == null ? new CompoundTag() : template.payload(id);
        }
    }

    /** Also asked on the client, like {@link CableReuse}. */
    public record CableSite(Level level, BlockPos pos, BlockState target, @Nullable BlockSnapshot template) {
        public CompoundTag payload(ResourceLocation id) {
            return template == null ? new CompoundTag() : template.payload(id);
        }
    }

    public record Storage(ServerPlayer player, ItemStack tool) {}

    public record Crafting(ServerPlayer player, ItemStack tool, List<ItemDemand> demands) {
        public Crafting {
            demands = List.copyOf(demands);
        }
    }
}
