package lu.kolja.matter_manipulator.common.integration.gtceu;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.block.PipeBlock;
import com.gregtechceu.gtceu.api.block.property.GTBlockStateProperties;
import com.gregtechceu.gtceu.api.blockentity.IPaintable;
import com.gregtechceu.gtceu.api.blockentity.PipeBlockEntity;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMufflableMachine;
import com.gregtechceu.gtceu.api.machine.steam.SteamWorkableMachine;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pipenet.IPipeNode;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.item.behavior.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.trait.AutoOutputTrait;
import com.gregtechceu.gtceu.common.machine.trait.ProgrammableCircuitSlotTrait;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import com.mojang.serialization.DynamicOps;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GTIntegration {

    public static final ResourceLocation PIPE_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "pipe");
    public static final ResourceLocation MACHINE_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "machine");
    public static final ResourceLocation MUFFLER_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "muffler");
    public static final ResourceLocation CIRCUIT_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "circuit");
    public static final ResourceLocation PAINT_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "paint");
    public static final ResourceLocation COVER_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "covers");
    public static final ResourceLocation MAINTENANCE_ID = ResourceLocation.fromNamespaceAndPath("gtceu", "maintenance");

    private static final String PIPE_CONNECTIONS = "pipe_connections";
    private static final String PIPE_BLOCKED_CONNECTIONS = "pipe_blocked_connections";
    private static final String PAINTING_COLOR = "painting_color";
    private static final String FACING_DIR = "front_facing";
    private static final String ITEM_OUTPUT_SIDE = "output_direction_item";
    private static final String ITEM_AUTO_OUTPUT = "item_auto_output";
    private static final String ALLOW_ITEM_IN_FROM_OUT = "allow_input_from_output_item";
    private static final String FLUID_OUTPUT_SIDE = "output_direction_fluid";
    private static final String FLUID_AUTO_OUTPUT = "fluid_auto_output";
    private static final String ALLOW_FLUID_IN_FROM_OUT = "allow_input_from_output_fluid";
    private static final String STEAM_OUTPUT_SIDE = "output_direction_steam";
    private static final String MUFFLED = "muffled";
    private static final String CIRCUIT = "circuit_config";
    private static final String NONE_DIRECTION = "null";
    private static final String COVERS = "covers";
    private static final String COVER_ID_TAG = "id";
    private static final String COVER_ITEM = "item";
    private static final String COVER_DATA = "data";
    private static final String COVER_ADDITIONAL = "additional";
    private static final String CIRCUIT_ITEM = "circuit_item";
    private static final String MAINTENANCE_TAPE = "maintenance_tape";
    private static final String DURATION_MULTIPLIER = "duration_multiplier";
    private static final String FUNDED_ITEM = "_funded_item";
    private static final String FUNDED_ITEMS = "_funded_items";

    private GTIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        var pipe = new PipeIntegration();
        registrar.block(PIPE_ID, pipe, GTIntegration::isPipe);
        registrar.cable(PIPE_ID, pipe, GTIntegration::isPipe);
        registrar.block(MACHINE_ID, new MachineIntegration(), GTIntegration::isGtceuBlock);
        registrar.block(MUFFLER_ID, new MufflerIntegration(), GTIntegration::isGtceuBlock);
        registrar.block(CIRCUIT_ID, new CircuitIntegration(), GTIntegration::isGtceuBlock);
        registrar.block(PAINT_ID, new PaintIntegration(), GTIntegration::isGtceuBlock);
        registrar.block(COVER_ID, new CoverIntegration(), GTIntegration::isGtceuBlock);
        registrar.block(MAINTENANCE_ID, new MaintenanceHatchIntegration(), GTIntegration::isGtceuBlock);
    }

    private static boolean isPipe(BlockState state) {
        return state.getBlock() instanceof PipeBlock<?, ?, ?>;
    }

    private static boolean isGtceuBlock(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("gtceu");
    }

    private record PipeIntegration() implements BlockIntegration, CableIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return capturePipe(context.blockEntity());
        }

        @Override
        public CompoundTag captureExchange(IntegrationContext.Exchange context) {
            return capturePipe(context.blockEntity());
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof PipeBlockEntity<?, ?> pipe)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return pipe.getConnections() == payload.getInt(PIPE_CONNECTIONS)
                        && pipe.getBlockedConnections() == payload.getInt(PIPE_BLOCKED_CONNECTIONS)
                        ? ApplyResult.APPLIED
                        : ApplyResult.REJECTED_UNCHANGED;
            }
            if (payload.contains(PIPE_CONNECTIONS)) {
                int connections = payload.getInt(PIPE_CONNECTIONS);
                applyPipeConnections(pipe, connections);
                pipe.setConnections(connections);
            }
            if (payload.contains(PIPE_BLOCKED_CONNECTIONS)) {
                int blockedConnections = payload.getInt(PIPE_BLOCKED_CONNECTIONS);
                applyBlockedConnections(pipe, blockedConnections);
                pipe.setBlockedConnections(blockedConnections);
            }
            context.afterTick(() -> finalizePipeConnections(pipe));
            return ApplyResult.APPLIED;
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            var copy = payload.copy();
            if (copy.contains(PIPE_CONNECTIONS)) {
                copy.putInt(PIPE_CONNECTIONS,
                        GTNbtTransform.transformDirectionMask(copy.getInt(PIPE_CONNECTIONS), context.transform()));
            }
            if (copy.contains(PIPE_BLOCKED_CONNECTIONS)) {
                copy.putInt(
                        PIPE_BLOCKED_CONNECTIONS,
                        GTNbtTransform.transformDirectionMask(copy.getInt(PIPE_BLOCKED_CONNECTIONS), context.transform())
                );
            }
            return copy;
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            return GTNbtTransform.transformPipeConnections(blockEntity, context.transform());
        }

        @Override
        public CablePlacementData prepare(IntegrationContext.Cable context) {
            var tag = capturePipe(context.blockEntity());
            int connections = tag.getInt(PIPE_CONNECTIONS);
            int blockedConnections = tag.getInt(PIPE_BLOCKED_CONNECTIONS);
            for (var direction : context.lineDirections()) {
                int sideMask = 1 << direction.ordinal();
                connections |= sideMask;
                blockedConnections &= ~sideMask;
            }
            if (connections != 0) {
                tag.putInt(PIPE_CONNECTIONS, connections);
            }
            if (tag.contains(PIPE_BLOCKED_CONNECTIONS)) {
                tag.putInt(PIPE_BLOCKED_CONNECTIONS, blockedConnections);
            }
            return CablePlacementData.afterPath(tag);
        }

        @Override
        public boolean canReuse(IntegrationContext.CableReuse context) {
            return context.existing().getBlock() == context.target().getBlock();
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return true;
        }

        @Override
        public Set<BlockPos> additionalChangedPositions(IntegrationContext.SideEffects context, CompoundTag payload) {
            if (!payload.contains(PIPE_CONNECTIONS) || !(context.state().getBlock() instanceof PipeBlock<?, ?, ?> block)) {
                return Set.of();
            }
            var live = context.blockEntity() instanceof PipeBlockEntity<?, ?> pipe ? pipe : null;
            if (context.purpose() == PlacementPurpose.RECONFIGURE && live == null) {
                return Set.of();
            }
            int connections = payload.getInt(PIPE_CONNECTIONS);
            var neighbours = new HashSet<BlockPos>();
            for (var side : Direction.values()) {
                boolean opens = PipeBlockEntity.isConnected(connections, side);
                if ((live != null && live.isConnected(side)) == opens) {
                    continue;
                }
                var neighbourPos = context.pos().relative(side);
                if (!context.level().isLoaded(neighbourPos)) {
                    neighbours.add(neighbourPos);
                    continue;
                }
                if (!(context.level().getBlockEntity(neighbourPos) instanceof IPipeNode<?, ?> neighbour)) {
                    continue;
                }
                var facing = side.getOpposite();
                boolean changes = opens
                        ? neighbour.getPipeType().getClass() == block.pipeType.getClass() && !neighbour.isConnected(facing)
                        : !passesThrough(live, side) && neighbour.isConnected(facing)
                                && neighbour.getCoverContainer().getCoverAtSide(facing) == null;
                if (changes) {
                    neighbours.add(neighbourPos);
                }
            }
            return neighbours;
        }

        private static boolean passesThrough(PipeBlockEntity<?, ?> pipe, Direction side) {
            var cover = pipe.getCoverContainer().getCoverAtSide(side);
            return cover != null && cover.canPipePassThrough();
        }
    }

    private record MachineIntegration() implements BlockIntegration {

        @Override
        public boolean dropCarriesContents(IntegrationContext.Removal context, ItemStack drop) {
            return context.blockEntity() instanceof MetaMachine
                    && drop.is(context.state().getBlock().asItem())
                    && !drop.isComponentsPatchEmpty();
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (!(context.blockEntity() instanceof MetaMachine machine)) {
                return tag;
            }
            tag.putString(FACING_DIR, directionToString(machine.getFrontFacing()));
            var output = machine.getTrait(AutoOutputTrait.class);
            if (output != null && output.supportsAutoOutputItems() && output.getItemOutputDirection() != null) {
                tag.putString(ITEM_OUTPUT_SIDE, directionToString(output.getItemOutputDirection()));
                tag.putBoolean(ITEM_AUTO_OUTPUT, output.isAutoOutputItems());
                tag.putBoolean(ALLOW_ITEM_IN_FROM_OUT, output.allowsItemInputFromOutputSide());
            }
            if (output != null && output.supportsAutoOutputFluids() && output.getFluidOutputDirection() != null) {
                tag.putString(FLUID_OUTPUT_SIDE, directionToString(output.getFluidOutputDirection()));
                tag.putBoolean(FLUID_AUTO_OUTPUT, output.isAutoOutputFluids());
                tag.putBoolean(ALLOW_FLUID_IN_FROM_OUT, output.allowsFluidInputFromOutputSide());
            }
            if (machine instanceof SteamWorkableMachine steam && steam.getOutputFacing() != null) {
                tag.putString(STEAM_OUTPUT_SIDE, directionToString(steam.getOutputFacing()));
            }
            return tag;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag tag) {
            if (!(context.blockEntity() instanceof MetaMachine machine)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (tag.contains(FACING_DIR)) {
                var direction = stringToDirection(tag.getString(FACING_DIR));
                if (direction != null) {
                    machine.setFrontFacing(direction);
                }
            }
            var output = machine.getTrait(AutoOutputTrait.class);
            if (output != null) {
                if (tag.contains(ITEM_OUTPUT_SIDE)) {
                    output.setItemOutputDirection(stringToDirection(tag.getString(ITEM_OUTPUT_SIDE)));
                }
                if (tag.contains(ITEM_AUTO_OUTPUT)) {
                    output.setAllowAutoOutputItems(tag.getBoolean(ITEM_AUTO_OUTPUT));
                }
                if (tag.contains(ALLOW_ITEM_IN_FROM_OUT) && output.supportsAutoOutputItems()) {
                    output.setAllowItemInputFromOutputSide(tag.getBoolean(ALLOW_ITEM_IN_FROM_OUT));
                }
                if (tag.contains(FLUID_OUTPUT_SIDE)) {
                    output.setFluidOutputDirection(stringToDirection(tag.getString(FLUID_OUTPUT_SIDE)));
                }
                if (tag.contains(FLUID_AUTO_OUTPUT)) {
                    output.setAllowAutoOutputFluids(tag.getBoolean(FLUID_AUTO_OUTPUT));
                }
                if (tag.contains(ALLOW_FLUID_IN_FROM_OUT) && output.supportsAutoOutputFluids()) {
                    output.setAllowFluidInputFromOutputSide(tag.getBoolean(ALLOW_FLUID_IN_FROM_OUT));
                }
            }
            if (machine instanceof SteamWorkableMachine steam && tag.contains(STEAM_OUTPUT_SIDE)) {
                var side = stringToDirection(tag.getString(STEAM_OUTPUT_SIDE));
                if (side != null) {
                    steam.setOutputFacing(side);
                }
            }
            updateMachine(machine);
            return ApplyResult.APPLIED;
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            var copy = payload.copy();
            transformDirectionString(copy, context.transform(), FACING_DIR);
            transformDirectionString(copy, context.transform(), ITEM_OUTPUT_SIDE);
            transformDirectionString(copy, context.transform(), FLUID_OUTPUT_SIDE);
            transformDirectionString(copy, context.transform(), STEAM_OUTPUT_SIDE);
            return copy;
        }

        @Override
        public @Nullable BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
            var state = context.state();
            if (!(state.getBlock() instanceof MetaMachineBlock block) || block.getRotationState() == RotationState.NONE) {
                return transformed;
            }
            var frontProperty = block.getRotationState().property;
            var front = state.getValue(frontProperty);
            var newFront = context.transform().transform(front);
            if (!frontProperty.getPossibleValues().contains(newFront)) {
                return null;
            }
            var result = state.setValue(frontProperty, newFront);
            if (!state.hasProperty(GTBlockStateProperties.UPWARDS_FACING)) {
                return result;
            }
            var upwards = GTNbtTransform.transformUpwardsFacing(
                    front, state.getValue(GTBlockStateProperties.UPWARDS_FACING), context.transform());
            return result.setValue(GTBlockStateProperties.UPWARDS_FACING, upwards);
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            return transformsBlockEntity(context.state())
                    ? GTNbtTransform.transformOutputFacings(blockEntity, context.transform())
                    : blockEntity;
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return state.getBlock() instanceof MetaMachineBlock;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return true;
        }
    }

    private record MufflerIntegration() implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (context.blockEntity() instanceof IMufflableMachine mufflable) {
                tag.putBoolean(MUFFLED, mufflable.isMuffled());
            }
            return tag;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (payload.contains(MUFFLED) && context.blockEntity() instanceof IMufflableMachine mufflable) {
                mufflable.setMuffled(payload.getBoolean(MUFFLED));
                updateMachine(context.blockEntity());
            }
            return ApplyResult.APPLIED;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return true;
        }
    }

    private record CoverIntegration() implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var coverable = coverable(context.blockEntity());
            var output = new CompoundTag();
            if (coverable == null) {
                return output;
            }
            var registries = context.level().registryAccess();
            var covers = new CompoundTag();
            for (var side : Direction.values()) {
                var cover = coverable.getCoverAtSide(side);
                if (cover == null) {
                    continue;
                }
                var coverTag = new CompoundTag();
                var id = GTRegistries.COVERS.getKey(cover.coverDefinition);
                if (id == null) {
                    continue;
                }
                coverTag.putString(COVER_ID_TAG, id.toString());
                coverTag.put(COVER_ITEM, cover.getAttachItem().saveOptional(registries));
                coverTag.put(COVER_DATA, cover.getSyncDataHolder().serializeNBT(registries));
                var additional = new ListTag();
                cover.getAdditionalDrops().forEach(stack -> additional.add(stack.saveOptional(registries)));
                coverTag.put(COVER_ADDITIONAL, additional);
                covers.put(side.getName(), coverTag);
            }
            if (!covers.isEmpty()) {
                output.put(COVERS, covers);
            }
            return output;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            var registries = registries();
            var output = payload.copy();
            output.put(COVERS, new CompoundTag());
            var plan = PlacementPreparationPlan.builder(output);
            var covers = payload.getCompound(COVERS);
            for (var sideName : covers.getAllKeys()) {
                var cover = covers.getCompound(sideName).copy();
                var coverItem = ItemStack.parseOptional(registries, cover.getCompound(COVER_ITEM));
                var parent = plan.component(ItemRequirement.exact(coverItem), (prepared, acquired) -> {
                    if (!acquired.isEmpty()) {
                        var funded = cover.copy();
                        var items = new ListTag();
                        items.add(acquired.saveOptional(registries));
                        funded.put(FUNDED_ITEMS, items);
                        prepared.getCompound(COVERS).put(sideName, funded);
                    }
                });
                for (var value : cover.getList(COVER_ADDITIONAL, Tag.TAG_COMPOUND)) {
                    var configured = ItemStack.parseOptional(registries, (CompoundTag) value);
                    var base = new ItemStack(configured.getItem(), configured.getCount());
                    parent.component(ItemRequirement.withFallback(configured, base), (prepared, acquired) -> {
                        var funded = prepared.getCompound(COVERS).getCompound(sideName);
                        if (acquired.isEmpty()) {
                            removeStackFromTag(registries, funded.getCompound(COVER_DATA), configured);
                        } else {
                            funded.getList(FUNDED_ITEMS, Tag.TAG_COMPOUND).add(acquired.saveOptional(registries));
                        }
                    });
                }
            }
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            var coverable = coverable(context.blockEntity());
            if (coverable == null || context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var registries = context.level().registryAccess();
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return matchesTransferred(registries, coverable, payload)
                        ? ApplyResult.APPLIED
                        : ApplyResult.REJECTED_UNCHANGED;
            }
            boolean partiallyApplied = false;
            var covers = payload.getCompound(COVERS);
            for (var sideName : covers.getAllKeys()) {
                var side = Direction.byName(sideName);
                var coverTag = covers.getCompound(sideName);
                var id = ResourceLocation.tryParse(coverTag.getString(COVER_ID_TAG));
                var definition = id == null ? null : GTRegistries.COVERS.get(id);
                if (side == null || definition == null) {
                    refundFunded(context, coverTag);
                    partiallyApplied = true;
                    continue;
                }
                var stack = ItemStack.parseOptional(registries, coverTag.getCompound(COVER_ITEM));
                if (coverable.placeCoverOnSide(side, stack, definition, context.player())) {
                    var cover = coverable.getCoverAtSide(side);
                    if (cover != null) {
                        cover.getSyncDataHolder().deserializeNBT(registries, coverTag.getCompound(COVER_DATA));
                        cover.getSyncDataHolder().resyncAllFields();
                        cover.scheduleRenderUpdate();
                    }
                } else {
                    refundFunded(context, coverTag);
                    partiallyApplied = true;
                }
            }
            coverable.notifyBlockUpdate();
            coverable.scheduleNeighborShapeUpdate();
            var blockEntity = context.blockEntity();
            if (blockEntity instanceof MetaMachine) {
                updateMachine(blockEntity);
            } else if (blockEntity instanceof PipeBlockEntity<?, ?> pipe) {
                context.afterTick(() -> finalizePipeConnections(pipe));
            } else if (blockEntity != null) {
                blockEntity.setChanged();
                context.level().sendBlockUpdated(
                        context.pos(),
                        context.state(),
                        context.state(),
                        Block.UPDATE_CLIENTS
                );
            }
            return partiallyApplied ? ApplyResult.PARTIAL_RESOURCES_SETTLED : ApplyResult.APPLIED;
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            var output = payload.copy();
            var transformed = new CompoundTag();
            var covers = payload.getCompound(COVERS);
            for (var sideName : covers.getAllKeys()) {
                var side = Direction.byName(sideName);
                if (side != null) {
                    var cover = covers.getCompound(sideName).copy();
                    cover.put(COVER_DATA, GTNbtTransform.transformCoverData(
                            cover.getString(COVER_ID_TAG), cover.getCompound(COVER_DATA), context.transform()));
                    transformed.put(context.transform().transform(side).getName(), cover);
                }
            }
            output.put(COVERS, transformed);
            return output;
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            return transformsBlockEntity(context.state())
                    ? GTNbtTransform.transformCovers(blockEntity, context.transform())
                    : blockEntity;
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            var block = state.getBlock();
            return block instanceof MetaMachineBlock || block instanceof PipeBlock<?, ?, ?>;
        }
    }

    private record CircuitIntegration() implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            var circuit = circuitSlot(context.blockEntity());
            if (circuit == null || !circuit.isEnabled()) {
                return tag;
            }
            var stack = circuit.storage.getStackInSlot(0);
            int configuration = IntCircuitBehaviour.getCircuitConfiguration(stack);
            if (configuration != 0) {
                tag.putInt(CIRCUIT, configuration);
                tag.put(CIRCUIT_ITEM, stack.saveOptional(context.level().registryAccess()));
            }
            return tag;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            if (!payload.contains(CIRCUIT_ITEM, Tag.TAG_COMPOUND)) {
                payload.remove(CIRCUIT);
                return PlacementPreparationPlan.unchanged(payload);
            }
            var registries = registries();
            var configured = ItemStack.parseOptional(registries, payload.getCompound(CIRCUIT_ITEM));
            var base = new ItemStack(configured.getItem(), configured.getCount());
            var plan = PlacementPreparationPlan.builder(payload);
            plan.component(ItemRequirement.withFallback(configured, base), (prepared, acquired) -> {
                if (acquired.isEmpty()) {
                    prepared.remove(CIRCUIT);
                    prepared.remove(CIRCUIT_ITEM);
                } else {
                    prepared.put(FUNDED_ITEM, acquired.saveOptional(registries));
                }
            });
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var circuit = circuitSlot(context.blockEntity());
            if (payload.contains(CIRCUIT) && circuit != null) {
                circuit.storage.setStackInSlot(0, IntCircuitBehaviour.stack(payload.getInt(CIRCUIT)));
                updateMachine(context.blockEntity());
            } else if (payload.contains(CIRCUIT)) {
                context.refund(ItemStack.parseOptional(context.level().registryAccess(), payload.getCompound(FUNDED_ITEM)));
                return ApplyResult.PARTIAL_RESOURCES_SETTLED;
            }
            return ApplyResult.APPLIED;
        }

        private static @Nullable ProgrammableCircuitSlotTrait circuitSlot(@Nullable BlockEntity blockEntity) {
            return blockEntity instanceof MetaMachine machine ? machine.getTrait(ProgrammableCircuitSlotTrait.class) : null;
        }
    }

    private record PaintIntegration() implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (context.blockEntity() instanceof IPaintable paintable) {
                tag.putInt(PAINTING_COLOR, paintable.getPaintingColor());
            }
            return tag;
        }

        @Override
        public CompoundTag captureExchange(IntegrationContext.Exchange context) {
            if (!isPipe(context.sourceState()) || !isPipe(context.targetState())) {
                return new CompoundTag();
            }
            var tag = new CompoundTag();
            if (context.blockEntity() instanceof IPaintable paintable) {
                tag.putInt(PAINTING_COLOR, paintable.getPaintingColor());
            }
            return tag;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            var blockEntity = context.blockEntity();
            if (payload.contains(PAINTING_COLOR) && blockEntity instanceof IPaintable paintable) {
                paintable.setPaintingColor(payload.getInt(PAINTING_COLOR));
                blockEntity.setChanged();
                if (blockEntity instanceof MetaMachine) {
                    updateMachine(blockEntity);
                } else if (blockEntity instanceof PipeBlockEntity<?, ?> pipe) {
                    context.afterTick(() -> finalizePipeConnections(pipe));
                }
            }
            return ApplyResult.APPLIED;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return true;
        }
    }

    private record MaintenanceHatchIntegration() implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return context.blockEntity() instanceof MaintenanceHatchPartMachine hatch
                    ? captureMaintenance(context.level().registryAccess(), hatch)
                    : new CompoundTag();
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            if (!payload.contains(MAINTENANCE_TAPE, Tag.TAG_COMPOUND)) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var registries = registries();
            var tape = ItemStack.parseOptional(registries, payload.getCompound(MAINTENANCE_TAPE));
            var plan = PlacementPreparationPlan.builder(payload);
            plan.component(ItemRequirement.exact(tape), (prepared, acquired) -> {
                if (acquired.isEmpty()) {
                    prepared.remove(MAINTENANCE_TAPE);
                } else {
                    prepared.put(FUNDED_ITEM, acquired.saveOptional(registries));
                }
            });
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof MaintenanceHatchPartMachine hatch)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var registries = context.level().registryAccess();
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return captureMaintenance(registries, hatch).equals(payload) ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE
                    && !Objects.equals(captureMaintenance(registries, hatch).get(MAINTENANCE_TAPE), payload.get(MAINTENANCE_TAPE))) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (hatch.isConfigurable() && payload.contains(DURATION_MULTIPLIER, Tag.TAG_FLOAT)) {
                hatch.setDurationMultiplier(Mth.clamp(payload.getFloat(DURATION_MULTIPLIER), 0.9f, 1.1f));
            }
            var result = ApplyResult.APPLIED;
            if (payload.contains(FUNDED_ITEM, Tag.TAG_COMPOUND)) {
                var funded = ItemStack.parseOptional(registries, payload.getCompound(FUNDED_ITEM));
                var remainder = tapeSlot(hatch).storage.insertItem(0, funded, false);
                if (!remainder.isEmpty()) {
                    context.refund(remainder);
                    result = ApplyResult.PARTIAL_RESOURCES_SETTLED;
                }
            }
            updateMachine(hatch);
            return result;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return Objects.equals(existing.get(MAINTENANCE_TAPE), desired.get(MAINTENANCE_TAPE));
        }
    }

    private static CompoundTag captureMaintenance(HolderLookup.Provider registries, MaintenanceHatchPartMachine hatch) {
        var tag = new CompoundTag();
        var tape = tapeSlot(hatch).storage.getStackInSlot(0);
        if (!tape.isEmpty()) {
            tag.put(MAINTENANCE_TAPE, tape.save(registries));
        }
        if (hatch.isConfigurable()) {
            tag.putFloat(DURATION_MULTIPLIER, hatch.getDurationMultiplier());
        }
        return tag;
    }

    private static NotifiableItemStackHandler tapeSlot(MaintenanceHatchPartMachine hatch) {
        return hatch.getTraitOrThrow(NotifiableItemStackHandler.class);
    }

    private static CompoundTag capturePipe(BlockEntity blockEntity) {
        var tag = new CompoundTag();
        if (blockEntity instanceof PipeBlockEntity<?, ?> pipe) {
            tag.putInt(PIPE_CONNECTIONS, pipe.getConnections());
            tag.putInt(PIPE_BLOCKED_CONNECTIONS, pipe.getBlockedConnections());
        }
        return tag;
    }

    private static void applyPipeConnections(PipeBlockEntity<?, ?> pipe, int connections) {
        for (var direction : Direction.values()) {
            pipe.setConnection(direction, PipeBlockEntity.isConnected(connections, direction), false);
        }
    }

    private static void applyBlockedConnections(PipeBlockEntity<?, ?> pipe, int blockedConnections) {
        for (var direction : Direction.values()) {
            pipe.setBlocked(direction, PipeBlockEntity.isFaceBlocked(blockedConnections, direction));
        }
    }

    private static void finalizePipeConnections(PipeBlockEntity<?, ?> pipe) {
        var level = pipe.getLevel();
        var pos = pipe.getBlockPos();
        if (level == null || pipe.isRemoved() || level.getBlockEntity(pos) != pipe) {
            return;
        }
        pipe.getSyncDataHolder().resyncAllFields();
        pipe.setChanged();
        pipe.notifyBlockUpdate();
        level.sendBlockUpdated(pos, pipe.getBlockState(), pipe.getBlockState(), Block.UPDATE_CLIENTS);
        for (var direction : Direction.values()) {
            level.neighborChanged(pos.relative(direction), pipe.getPipeBlock(), pos);
        }
        pipe.scheduleRenderUpdate();
    }

    private static @Nullable ICoverable coverable(@Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof PipeBlockEntity<?, ?> pipe) {
            return pipe.getCoverContainer();
        }
        return blockEntity instanceof MetaMachine machine ? machine.getCoverContainer() : null;
    }

    private static boolean matchesTransferred(HolderLookup.Provider registries, ICoverable coverable, CompoundTag payload) {
        var covers = payload.getCompound(COVERS);
        for (var side : Direction.values()) {
            var cover = coverable.getCoverAtSide(side);
            if (!covers.contains(side.getName(), Tag.TAG_COMPOUND)) {
                if (cover != null) {
                    return false;
                }
                continue;
            }
            var expected = covers.getCompound(side.getName());
            if (cover == null || cover.attachedSide != side) {
                return false;
            }
            var id = GTRegistries.COVERS.getKey(cover.coverDefinition);
            if (id == null || !id.toString().equals(expected.getString(COVER_ID_TAG))
                    || !cover.getSyncDataHolder().serializeNBT(registries).equals(expected.getCompound(COVER_DATA))) {
                return false;
            }
        }
        return true;
    }

    private static void refundFunded(IntegrationContext.Apply context, CompoundTag payload) {
        var registries = context.level().registryAccess();
        if (payload.contains(FUNDED_ITEM, Tag.TAG_COMPOUND)) {
            context.refund(ItemStack.parseOptional(registries, payload.getCompound(FUNDED_ITEM)));
        }
        for (var value : payload.getList(FUNDED_ITEMS, Tag.TAG_COMPOUND)) {
            context.refund(ItemStack.parseOptional(registries, (CompoundTag) value));
        }
    }

    private static void removeStackFromTag(HolderLookup.Provider registries, CompoundTag tag, ItemStack expected) {
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (var key : List.copyOf(tag.getAllKeys())) {
            var value = tag.get(key);
            if (value instanceof CompoundTag compound) {
                if (isStack(ops, compound, expected)) {
                    tag.remove(key);
                } else {
                    removeStackFromTag(registries, compound, expected);
                }
            } else if (value instanceof ListTag list) {
                for (int index = list.size() - 1; index >= 0; index--) {
                    if (!(list.get(index) instanceof CompoundTag compound)) {
                        continue;
                    }
                    if (isStack(ops, compound, expected)) {
                        list.remove(index);
                    } else {
                        removeStackFromTag(registries, compound, expected);
                    }
                }
            }
        }
    }

    private static boolean isStack(DynamicOps<Tag> ops, CompoundTag tag, ItemStack expected) {
        var stack = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        return !stack.isEmpty() && InventoryUtil.isSameMaterial(stack, expected);
    }

    private static HolderLookup.Provider registries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? RegistryAccess.EMPTY : server.registryAccess();
    }

    private static void updateMachine(@Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof MetaMachine machine)) {
            return;
        }
        machine.setChanged();
        machine.markAsChanged();
        machine.notifyBlockUpdate();
        machine.scheduleRenderUpdate();
    }

    private static String directionToString(@Nullable Direction direction) {
        return direction == null ? NONE_DIRECTION : direction.getName();
    }

    private static @Nullable Direction stringToDirection(String value) {
        return NONE_DIRECTION.equalsIgnoreCase(value) ? null : Direction.byName(value);
    }

    private static void transformDirectionString(CompoundTag tag, DirectionTransform transform, String key) {
        if (!tag.contains(key)) {
            return;
        }
        var direction = stringToDirection(tag.getString(key));
        if (direction != null) {
            tag.putString(key, transform.transform(direction).getName());
        }
    }
}
