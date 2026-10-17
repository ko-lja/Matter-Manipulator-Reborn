package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.integration.ae2.Ae2Integration;
import lu.kolja.matter_manipulator.common.integration.framedblocks.FramedBlocksIntegration;
import lu.kolja.matter_manipulator.common.integration.laserio.LaserIoIntegration;
import lu.kolja.matter_manipulator.common.integration.rechiseled.RechiseledIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.ItemDemand;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class IntegrationDispatcher {

    static volatile IntegrationRegistry registry;
    static volatile int generation;

    private IntegrationDispatcher() {}

    public static synchronized void initialize() {
        if (registry != null) {
            return;
        }
        var registrations = IntegrationRegistration.close();
        var builder = IntegrationRegistry.builder();
        try {
            var mods = ModList.get();
            if (mods.isLoaded("ae2")) {
                Ae2Integration.register(builder);
            }
            if (mods.isLoaded("framedblocks")) {
                FramedBlocksIntegration.register(builder);
            }
            if (mods.isLoaded("laserio")) {
                LaserIoIntegration.register(builder);
            }
            if (mods.isLoaded("rechiseled")) {
                RechiseledIntegration.register(builder);
            }
            registrations.forEach(registration -> registration.accept(builder));
            registry = builder.build();
            generation++;
        } finally {
            builder.freeze();
        }
        var mods = ModList.get();
        MatterManipulator.LOGGER.info(
                "Matter Manipulator integrations initialized: AE2={}, GTCEu={}, Flux={}, FramedBlocks={}, LaserIO={}, "
                        + "ProjectRed={}, LittleTiles={}, Rechiseled={}",
                mods.isLoaded("ae2"),
                mods.isLoaded("gtceu"),
                mods.isLoaded("fluxnetworks"),
                mods.isLoaded("framedblocks"),
                mods.isLoaded("laserio"),
                projectRedLoaded(mods),
                mods.isLoaded("littletiles"),
                mods.isLoaded("rechiseled")
        );
    }

    private static boolean projectRedLoaded(ModList mods) {
        return mods.isLoaded("cb_multipart") && mods.isLoaded("projectred_core");
    }

    public static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            initialize();
            var mods = ModList.get();
            if (mods.isLoaded("ae2")) {
                Ae2Integration.commonSetup();
            }
        });
    }

    public static CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state) {
        return get().capture(level, pos, state);
    }

    public static CompoundTag capture(ServerLevel level, BlockPos pos, BlockState state, @Nullable BoundingBox selection) {
        return get().capture(level, pos, state, selection);
    }

    public static CompoundTag captureExchange(
            ServerLevel level,
            BlockPos pos,
            BlockState sourceState,
            BlockState targetState
    ) {
        return get().captureExchange(level, pos, sourceState, targetState);
    }

    public static IntegrationApplyReport apply(
            ServerLevel level,
            BlockPos pos,
            ServerPlayer player,
            CompoundTag payloads,
            Consumer<ItemStack> refund,
            PlacementPurpose purpose
    ) {
        return get().apply(level, pos, player, payloads, refund, purpose);
    }

    public static boolean canReconfigure(BlockState state, CompoundTag existing, CompoundTag desired) {
        return get().canReconfigure(state, existing, desired);
    }

    @Nullable
    public static Set<BlockPos> additionalChangedPositions(
            ServerLevel level, BlockPos pos, BlockState state, CompoundTag payloads, PlacementPurpose purpose
    ) {
        return get().additionalChangedPositions(level, pos, state, payloads, purpose);
    }

    public static CompoundTag reconfiguration(CompoundTag existing, CompoundTag desired) {
        var output = new CompoundTag();
        for (var key : desired.keySet()) {
            var payload = desired.get(key);
            if (!payload.equals(existing.get(key))) {
                output.put(key, payload.copy());
            }
        }
        return output;
    }

    public static PreparedPlacement preparePlacement(
            ServerPlayer player,
            ItemStack tool,
            BlockState target,
            CapturedBlock captured
    ) {
        return get().preparePlacement(player, tool, target, captured);
    }

    public static PreparedPlacement preparePlacement(
            ServerPlayer player,
            ItemStack tool,
            BlockState target,
            CapturedBlock captured,
            PlacementPurpose purpose
    ) {
        return get().preparePlacement(player, tool, target, captured, purpose);
    }

    public static CompoundTag transform(BlockState state, CompoundTag payloads, ToolTransform transform) {
        return get().transform(state, payloads, transform);
    }

    public static CompoundTag transform(BlockState state, CompoundTag payloads, ToolTransform transform, boolean strict) {
        return get().transform(state, payloads, transform, strict);
    }

    public static CompoundTag portable(BlockState state, CompoundTag payloads, Runnable failed) {
        return get().portable(state, payloads, failed);
    }

    public static Optional<BlockState> transformState(BlockState state, ToolTransform transform) {
        return get().transformState(state, transform);
    }

    public static Optional<BlockState> transformStateApproximately(BlockState state, ToolTransform transform) {
        return get().transformStateApproximately(state, transform);
    }

    public static CompoundTag transformBlockEntity(BlockState state, CompoundTag blockEntity, ToolTransform transform) {
        return get().transformBlockEntity(state, blockEntity, transform);
    }

    public static boolean hasBlockEntityTransform(BlockState state) {
        return get().hasBlockEntityTransform(state);
    }

    public static CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
        return get().relocateBlockEntity(context, blockEntity);
    }

    public static boolean hasBlockEntityRelocation(BlockState state) {
        return get().hasBlockEntityRelocation(state);
    }

    public static boolean createsTransferredBlockEntity(BlockState state) {
        return get().createsTransferredBlockEntity(state);
    }

    @Nullable
    public static BlockEntity createTransferredBlockEntity(
            ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntity
    ) {
        return get().createTransferredBlockEntity(level, pos, state, blockEntity);
    }

    public static void afterTransfer(ServerLevel level, BlockPos pos, boolean committed) {
        get().afterTransfer(level, pos, committed);
    }

    @Nullable
    public static Component removalRefusal(BlockState state) {
        return get().removalRefusal(state);
    }

    @Nullable
    public static Component moveRefusal(ServerLevel level, BlockPos pos, BlockState state, BoundingBox selection,
                                        ToolTransform transform) {
        return get().moveRefusal(level, pos, state, selection, transform);
    }

    public static Set<BlockPos> moveGroupPeers(ServerLevel level, BlockPos pos, BlockState state, BoundingBox selection) {
        return get().moveGroupPeers(level, pos, state, selection);
    }

    public static void beforeRemove(
            ServerLevel level,
            BlockPos pos,
            ServerPlayer player,
            boolean collectDrops,
            Consumer<ItemStack> dropSink
    ) {
        get().beforeRemove(level, pos, player, collectDrops, dropSink);
    }

    public static boolean dropCarriesContents(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack drop) {
        return get().dropCarriesContents(level, pos, player, drop);
    }

    public static PlacementRequirements placementRequirements(BlockState target, CapturedBlock captured) {
        return get().placementRequirements(target, captured);
    }

    public static boolean isCable(BlockState state) {
        return get().isCable(state);
    }

    public static boolean canReuseCable(
            BlockGetter level, BlockPos pos, BlockState existing, BlockState target, @Nullable CapturedBlock template
    ) {
        return get().canReuseCable(level, pos, existing, target, template);
    }

    public static @Nullable Component cableRouteRefusal(
            Level level, BlockPos pos, BlockState target, @Nullable CapturedBlock template
    ) {
        return get().cableRouteRefusal(level, pos, target, template);
    }

    public static CablePreparation prepareCable(
            ServerLevel level,
            BlockPos pos,
            BlockState target,
            Set<Direction> lineDirections,
            CapturedBlock template
    ) {
        return get().prepareCable(level, pos, target, lineDirections, template);
    }

    public static ItemStack extractItem(ServerPlayer player, ItemStack tool, ItemStack required) {
        return get().extractItem(player, tool, required);
    }

    public static ItemStack insertItem(ServerPlayer player, ItemStack tool, ItemStack stack) {
        return get().insertItem(player, tool, stack);
    }

    public static ItemStack insertItem(ServerPlayer player, ItemStack tool, ItemStack stack, Set<Identifier> excluded) {
        return get().insertItem(player, tool, stack, excluded);
    }

    public static long availableItem(ServerPlayer player, ItemStack tool, ItemStack stack) {
        return get().availableItem(player, tool, stack);
    }

    public static List<ItemStack> storedVariants(ServerPlayer player, ItemStack tool, ItemStack like) {
        return get().storedVariants(player, tool, like);
    }

    public static List<ItemRequirement> collectPlacementRequirements(
            BlockState target,
            CapturedBlock captured,
            PlacementPurpose purpose
    ) {
        return get().collectPlacementRequirements(target, captured, purpose);
    }

    public static boolean requestCrafting(ServerPlayer player, ItemStack tool, List<ItemDemand> demands) {
        return get().requestCrafting(player, tool, demands);
    }

    public static void tickCrafting() {
        get().tickCrafting();
    }

    public static void clearServerState() {
        var current = registry;
        if (current != null) {
            current.stopCrafting();
        }
        IntegrationFailures.clear();
    }

    public static void playerLoggedOut(UUID player) {
        var current = registry;
        if (current != null) {
            current.forgetCrafting(player);
        }
    }

    public static int generation() {
        return generation;
    }

    public static boolean hasBlockIntegration(BlockState state) {
        return get().hasBlockIntegration(state);
    }

    private static IntegrationRegistry get() {
        if (registry == null) {
            throw new IllegalStateException("IntegrationDispatcher have not been initialized");
        }
        return registry;
    }
}
