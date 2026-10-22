package lu.kolja.matter_manipulator.common.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.features.GridLinkables;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.implementations.parts.ICablePart;
import appeng.api.implementations.items.IFacadeItem;
import appeng.api.inventories.ISegmentedInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.RelativeSide;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.util.IConfigurableObject;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.networking.CableBusBlock;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.util.SettingsFrom;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.BlockIntegration.ApplyResult;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.CraftingIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.ItemDemand;
import lu.kolja.matter_manipulator.api.ItemStorageIntegration;
import lu.kolja.matter_manipulator.api.StorageUnchangedException;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.registry.ModItems;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.BiConsumer;

public final class Ae2Integration {

    public static final ResourceLocation SETTINGS_ID = new ResourceLocation("ae2", "block_entity");
    public static final ResourceLocation CABLE_ID = new ResourceLocation("ae2", "cable_bus");
    public static final ResourceLocation ORIENTATION_ID = new ResourceLocation("ae2", "orientation");
    public static final ResourceLocation STORAGE_ID = new ResourceLocation("ae2", "storage");
    public static final ResourceLocation CRAFTING_ID = new ResourceLocation("ae2", "crafting");

    private static final String MEMORY_CARD = "memory_card";
    private static final String CONFIG = "config";
    private static final String CABLE_ITEM = "cable_item";
    private static final String FORMAT_VERSION = "format_version";
    private static final String PARTS = "parts";
    private static final String FACADES = "facades";
    private static final String ITEM = "item";
    private static final String SETTINGS = "settings";
    private static final String INSTALLABLES = "installables";
    private static final String PATTERNS = "patterns";
    private static final String CENTER = "center";
    private static final String UPGRADES = "upgrades";
    private static final String CELLS = "cells";
    private static final String FUNDED_ITEM = "_funded_item";
    private static final String LINK_DIMENSION = "dimension";
    private static final String LINK_X = "x";
    private static final String LINK_Y = "y";
    private static final String LINK_Z = "z";
    public static final Map<UUID, CraftingQueue> CRAFTING_QUEUES = new LinkedHashMap<>();

    private Ae2Integration() {}

    public static void register(IntegrationRegistrar registrar) {
        var settings = new SettingsIntegration();
        var cable = new CableBusIntegration();
        registrar.block(SETTINGS_ID, settings, state -> isAe2Block(state) && state.hasBlockEntity());
        registrar.block(CABLE_ID, cable, state -> state.getBlock() instanceof CableBusBlock);
        registrar.block(ORIENTATION_ID, new OrientationIntegration(), Ae2Integration::hasAe2Orientation);
        registrar.cable(CABLE_ID, cable, state -> state.getBlock() instanceof CableBusBlock);
        registrar.storage(STORAGE_ID, new StorageIntegration());
        registrar.crafting(CRAFTING_ID, new CraftingIntegration() {
            @Override
            public boolean request(IntegrationContext.Crafting context) {
                return requestCrafting(context.player(), context.tool(), context.demands());
            }

            @Override
            public void tick() {
                tickCrafting();
            }

            @Override
            public void serverStopping() {
                cancelCrafting();
            }

            @Override
            public void playerLoggedOut(UUID player) {
                cancelCrafting(player);
            }
        });
    }

    public static boolean requestCrafting(ServerPlayer player, ItemStack tool, List<ItemDemand> demands) {
        if (demands.isEmpty()) {
            return false;
        }
        if (CRAFTING_QUEUES.containsKey(player.getUUID())) {
            player.displayClientMessage(MMMessage.CRAFT_REQUEST_ACTIVE.text(), false);
            return false;
        }
        var context = new IntegrationContext.Storage(player, tool);
        var accessPoint = linkedAccessPoint(context);
        var grid = accessPoint == null ? null : accessPoint.getGrid();
        if (grid == null) {
            player.displayClientMessage(MMMessage.CRAFT_NO_NETWORK.text(), false);
            return false;
        }

        var combined = new LinkedHashMap<AEItemKey, ItemDemand>();
        for (var demand : demands) {
            var key = AEItemKey.of(demand.stack());
            if (key == null) {
                continue;
            }
            combined.compute(key, (k, previous) -> previous == null
                    ? demand
                    : new ItemDemand(demand.stack(), Math.addExact(previous.amount(), demand.amount())));
        }
        if (combined.isEmpty()) {
            return false;
        }

        var source = IActionSource.ofPlayer(player, accessPoint);
        var crafting = grid.getCraftingService();
        var entries = new ArrayList<CraftingEntry>();
        for (var entry : combined.entrySet()) {
            var demand = entry.getValue();
            var future = crafting.beginCraftingCalculation(
                    player.level(),
                    () -> source,
                    entry.getKey(),
                    demand.amount(),
                    CalculationStrategy.REPORT_MISSING_ITEMS
            );
            entries.add(new CraftingEntry(entry.getKey(), demand, future));
        }
        CRAFTING_QUEUES.put(player.getUUID(), new CraftingQueue(player, tool, grid, source, entries));
        player.displayClientMessage(
                MMMessage.CRAFT_REQUEST_STARTED.text(entries.size()),
                false
        );
        return true;
    }

    public static void tickCrafting() {
        CRAFTING_QUEUES.values().removeIf(CraftingQueue::tick);
    }

    static void cancelCrafting() {
        var queues = List.copyOf(CRAFTING_QUEUES.values());
        CRAFTING_QUEUES.clear();
        queues.forEach(CraftingQueue::cancelPending);
    }

    static void cancelCrafting(UUID player) {
        var queue = CRAFTING_QUEUES.remove(player);
        if (queue != null) {
            queue.cancelPending();
        }
    }

    public static void commonSetup() {
        var handler = new IGridLinkableHandler() {
            @Override
            public boolean canLink(ItemStack stack) {
                return stack.getItem() instanceof ManipulatorItem;
            }

            @Override
            public void link(ItemStack itemStack, GlobalPos pos) {
                writeLink(itemStack, pos);
            }

            @Override
            public void unlink(ItemStack itemStack) {
                ToolState.get(itemStack).setAeLinkTag(new CompoundTag());
            }
        };

        for (var item : List.of(ModItems.PROTOTYPE.get(), ModItems.MKI.get(), ModItems.MKII.get(), ModItems.MKIII.get())) {
            if (GridLinkables.get(item) == null) {
                GridLinkables.register(item, handler);
            }
        }
        MatterManipulator.LOGGER.info("Registered Matter Manipulators as AE2 wireless linkable items");
    }

    private static boolean isAe2Block(BlockState state) {
        return ForgeRegistries.BLOCKS.getKey(state.getBlock()).getNamespace().equals("ae2");
    }

    private static boolean hasAe2Orientation(BlockState state) {
        return state.hasProperty(PatternProviderBlock.PUSH_DIRECTION)
                || IOrientationStrategy.get(state).getProperties().contains(IOrientationStrategy.SPIN);
    }

    private static final class OrientationIntegration implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public @Nullable BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
            if (transformed == null) {
                return null;
            }
            var state = context.state();
            var result = transformed;
            var strategy = IOrientationStrategy.get(state);
            if (strategy.getProperties().contains(IOrientationStrategy.SPIN)) {
                var orientation = Ae2OrientationTransform.transform(BlockOrientation.get(strategy, state), context.transform());
                result = strategy.setOrientation(result, orientation.getSide(RelativeSide.FRONT), orientation.getSpin());
            }
            if (state.hasProperty(PatternProviderBlock.PUSH_DIRECTION)) {
                result = result.setValue(PatternProviderBlock.PUSH_DIRECTION,
                        Ae2OrientationTransform.transform(state.getValue(PatternProviderBlock.PUSH_DIRECTION),
                                context.transform()));
            }
            return result;
        }
    }

    private static final class SettingsIntegration implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            var blockEntity = context.blockEntity();
            if (blockEntity instanceof AEBaseBlockEntity aeBase) {
                var memoryCard = new CompoundTag();
                aeBase.exportSettings(SettingsFrom.MEMORY_CARD, memoryCard, null);
                if (!memoryCard.isEmpty()) {
                    tag.put(MEMORY_CARD, memoryCard);
                }
            }
            if (blockEntity instanceof IConfigurableObject configurable) {
                var config = new CompoundTag();
                configurable.getConfigManager().writeToNBT(config);
                if (!config.isEmpty()) {
                    tag.put(CONFIG, config);
                }
            }
            var installables = captureInstallables(blockEntity);
            if (!installables.isEmpty()) {
                tag.put(INSTALLABLES, installables);
            }
            tag.putInt(FORMAT_VERSION, 1);
            return tag;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            if (payload.getInt(FORMAT_VERSION) < 1) {
                payload.remove(INSTALLABLES);
                payload.getCompound(MEMORY_CARD).remove(PATTERNS);
                return PlacementPreparationPlan.unchanged(payload);
            }
            var output = payload.copy();
            clearFundedContents(output, MEMORY_CARD);
            var plan = PlacementPreparationPlan.builder(output);
            planPatterns(plan, null, payload.getCompound(MEMORY_CARD), tag -> tag.getCompound(MEMORY_CARD));
            planInstallables(plan, null, payload.getCompound(INSTALLABLES), tag -> tag.getCompound(INSTALLABLES));
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            var blockEntity = context.blockEntity();
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                if (blockEntity == null || !sameOwnedResources(
                        capture(new IntegrationContext.Capture(context.level(), context.pos(), context.state())),
                        payload,
                        MEMORY_CARD)) {
                    return ApplyResult.REJECTED_UNCHANGED;
                }
                markChanged(blockEntity);
                return ApplyResult.APPLIED;
            }
            boolean inPlace = context.purpose() == PlacementPurpose.RECONFIGURE;
            if (inPlace && (blockEntity == null || !sameOwnedResources(
                    capture(new IntegrationContext.Capture(context.level(), context.pos(), context.state())),
                    payload,
                    MEMORY_CARD))) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (payload.contains(MEMORY_CARD, Tag.TAG_COMPOUND) && blockEntity instanceof AEBaseBlockEntity aeBase) {
                var memoryCard = payload.getCompound(MEMORY_CARD).copy();
                var patterns = memoryCard.getList(PATTERNS, Tag.TAG_COMPOUND).copy();
                memoryCard.remove(PATTERNS);
                aeBase.importSettings(SettingsFrom.MEMORY_CARD, memoryCard, null);
                if (!inPlace) {
                    applyPatterns(blockEntity, patterns);
                }
            }
            if (payload.contains(CONFIG, Tag.TAG_COMPOUND) && blockEntity instanceof IConfigurableObject configurable) {
                configurable.getConfigManager().readFromNBT(payload.getCompound(CONFIG));
            }
            if (!inPlace) {
                applyInstallables(blockEntity, payload.getCompound(INSTALLABLES));
            }
            markChanged(blockEntity);
            return blockEntity == null ? ApplyResult.REJECTED_UNCHANGED : ApplyResult.APPLIED;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return existing.getInt(FORMAT_VERSION) >= 1
                    && desired.getInt(FORMAT_VERSION) >= 1
                    && sameOwnedResources(existing, desired, MEMORY_CARD);
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            var copy = payload.copy();
            if (copy.contains(MEMORY_CARD, Tag.TAG_COMPOUND)) {
                copy.put(MEMORY_CARD, Ae2OrientationTransform.transformPushDirection(
                        transformAeTag(copy.getCompound(MEMORY_CARD), context.transform()), context.transform()));
            }
            if (copy.contains(CONFIG, Tag.TAG_COMPOUND)) {
                copy.put(CONFIG, transformAeTag(copy.getCompound(CONFIG), context.transform()));
            }
            return copy;
        }

        @Override
        public void beforeRemove(IntegrationContext.Removal context) {
            if (!(context.blockEntity() instanceof AEBaseBlockEntity aeBase)) {
                return;
            }
            if (context.collectDrops()) {
                var drops = new ArrayList<ItemStack>();
                aeBase.addAdditionalDrops(context.level(), context.pos(), drops);
                drops.forEach(context.dropSink());
            }
            aeBase.clearContent();
        }
    }

    private static final class CableBusIntegration implements BlockIntegration, CableIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return captureCable(context.blockEntity());
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            if (payload.getInt(FORMAT_VERSION) < 1) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var output = payload.copy();
            var parts = payload.getCompound(PARTS);
            var initialParts = new CompoundTag();
            if (parts.contains(CENTER, Tag.TAG_COMPOUND)) {
                var center = parts.getCompound(CENTER).copy();
                clearFundedContents(center, SETTINGS);
                initialParts.put(CENTER, center);
            }
            output.put(PARTS, initialParts);
            output.put(FACADES, new CompoundTag());
            var plan = PlacementPreparationPlan.builder(output);
            for (var sideName : parts.getAllKeys()) {
                var part = parts.getCompound(sideName).copy();
                PlacementPreparationPlan.Component parent = null;
                if (!CENTER.equals(sideName)) {
                    var stack = ItemStack.of(part.getCompound(ITEM));
                    parent = plan.component(ItemRequirement.exact(stack), (prepared, acquired) -> {
                        if (!acquired.isEmpty()) {
                            var funded = part.copy();
                            clearFundedContents(funded, SETTINGS);
                            funded.put(FUNDED_ITEM, acquired.save(new CompoundTag()));
                            prepared.getCompound(PARTS).put(sideName, funded);
                        }
                    });
                }
                planPatterns(plan, parent, part.getCompound(SETTINGS),
                        tag -> tag.getCompound(PARTS).getCompound(sideName).getCompound(SETTINGS));
                planInstallables(plan, parent, part.getCompound(INSTALLABLES),
                        tag -> tag.getCompound(PARTS).getCompound(sideName).getCompound(INSTALLABLES));
            }
            var facades = payload.getCompound(FACADES);
            for (var sideName : facades.getAllKeys()) {
                var facade = facades.getCompound(sideName).copy();
                plan.component(ItemRequirement.exact(ItemStack.of(facade)), (prepared, acquired) -> {
                    if (!acquired.isEmpty()) {
                        var funded = facade.copy();
                        funded.put(FUNDED_ITEM, acquired.save(new CompoundTag()));
                        prepared.getCompound(FACADES).put(sideName, funded);
                    }
                });
            }
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof CableBusBlockEntity cableBus)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return reconfigureCable(cableBus, payload);
            }
            if (payload.getInt(FORMAT_VERSION) < 1) {
                return applyLegacyCable(cableBus, context, payload)
                        ? ApplyResult.APPLIED
                        : ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                if (!matchesTransferred(cableBus, payload)) {
                    return ApplyResult.REJECTED_UNCHANGED;
                }
                markChanged(cableBus);
                return ApplyResult.APPLIED;
            }

            boolean partiallyApplied = false;
            var parts = payload.getCompound(PARTS);
            for (var sideName : orderedPartSides()) {
                if (!parts.contains(sideName, Tag.TAG_COMPOUND)) {
                    continue;
                }
                var side = sideFromKey(sideName);
                var partData = parts.getCompound(sideName);
                var stack = ItemStack.of(partData.getCompound(ITEM));
                if (!(stack.getItem() instanceof IPartItem<?> partItem)) {
                    refundFunded(context, partData);
                    partiallyApplied = true;
                    continue;
                }
                var part = cableBus.getCableBus().getPart(side);
                if (part == null) {
                    part = cableBus.getCableBus().addPart(partItem, side, context.player());
                }
                if (part != null) {
                    applyPartContents(part, partData);
                } else {
                    refundFunded(context, partData);
                    partiallyApplied = true;
                }
            }

            var facades = payload.getCompound(FACADES);
            for (var side : Direction.values()) {
                if (!facades.contains(side.getName(), Tag.TAG_COMPOUND)) {
                    continue;
                }
                var stack = ItemStack.of(facades.getCompound(side.getName()));
                var existing = cableBus.getFacadeContainer().getFacade(side);
                if (existing != null && ItemStack.isSameItemSameTags(existing.getItemStack(), stack)) {
                    continue;
                }
                if (stack.getItem() instanceof IFacadeItem facadeItem) {
                    var facade = facadeItem.createPartFromItemStack(stack, side);
                    if (facade != null && cableBus.getFacadeContainer().addFacade(facade)) {
                        continue;
                    }
                }
                refundFunded(context, facades.getCompound(side.getName()));
                partiallyApplied = true;
            }
            markChanged(cableBus);
            return partiallyApplied ? ApplyResult.PARTIAL_RESOURCES_SETTLED : ApplyResult.APPLIED;
        }

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            if (payload.getInt(FORMAT_VERSION) >= 1) {
                var stack = ItemStack.of(payload.getCompound(PARTS).getCompound(CENTER).getCompound(ITEM));
                return stack.getItem() instanceof IPartItem<?>
                        ? PlacementRequirements.replaceWith(stack)
                        : PlacementRequirements.defaults();
            }
            var itemId = cableItemId(payload);
            if (itemId == null) {
                return PlacementRequirements.defaults();
            }
            var item = BuiltInRegistries.ITEM.get(itemId);
            return item instanceof IPartItem<?> ? PlacementRequirements.replaceWith(new ItemStack(item)) : PlacementRequirements.defaults();
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            if (existing.getInt(FORMAT_VERSION) < 1 || desired.getInt(FORMAT_VERSION) < 1
                    || !existing.getCompound(FACADES).equals(desired.getCompound(FACADES))) {
                return false;
            }
            var existingParts = existing.getCompound(PARTS);
            var desiredParts = desired.getCompound(PARTS);
            if (!existingParts.getAllKeys().equals(desiredParts.getAllKeys())) {
                return false;
            }
            for (var sideName : desiredParts.getAllKeys()) {
                var installed = existingParts.getCompound(sideName);
                var planned = desiredParts.getCompound(sideName);
                if (!installed.getCompound(ITEM).equals(planned.getCompound(ITEM))
                        || !sameOwnedResources(installed, planned, SETTINGS)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public CablePlacementData prepare(IntegrationContext.Cable context) {
            var payload = captureCable(context.blockEntity());
            if (!payload.getCompound(PARTS).contains(CENTER, Tag.TAG_COMPOUND)) {
                var template = context.payload(CABLE_ID);
                if (!template.isEmpty()) {
                    payload = template;
                }
            }
            return CablePlacementData.duringPlacement(centerCableOnly(payload));
        }

        @Override
        public boolean canReuse(IntegrationContext.CableReuse context) {
            return context.existing().getBlock() == context.target().getBlock();
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            if (payload.getInt(FORMAT_VERSION) < 1) {
                return payload.copy();
            }
            var transformed = payload.copy();
            var outputParts = new CompoundTag();
            var parts = payload.getCompound(PARTS);
            for (var sideName : parts.getAllKeys()) {
                var side = sideFromKey(sideName);
                var newName = side == null ? CENTER : context.transform().transform(side).getName();
                var part = parts.getCompound(sideName).copy();
                transformPartContents(part, context.transform());
                outputParts.put(newName, part);
            }
            transformed.put(PARTS, outputParts);

            var outputFacades = new CompoundTag();
            var facades = payload.getCompound(FACADES);
            for (var sideName : facades.getAllKeys()) {
                var side = Direction.byName(sideName);
                if (side != null) {
                    outputFacades.put(context.transform().transform(side).getName(), facades.getCompound(sideName).copy());
                }
            }
            transformed.put(FACADES, outputFacades);
            return transformed;
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            return Ae2NbtTransform.transformCableBusDirections(blockEntity, context.transform());
        }
    }

    private static final class StorageIntegration implements ItemStorageIntegration {

        @Override
        public long available(IntegrationContext.Storage context, ItemStack stack) {
            var storage = linkedStorage(context);
            var key = AEItemKey.of(stack);
            return storage == null || key == null
                    ? 0
                    : storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.ofPlayer(context.player()));
        }

        @Override
        public List<ItemStack> variants(IntegrationContext.Storage context, ItemStack like) {
            var storage = linkedStorage(context);
            var key = AEItemKey.of(like);
            if (storage == null || key == null) {
                return List.of();
            }
            var found = new ArrayList<ItemStack>();
            for (var entry : storage.getAvailableStacks().findFuzzy(key, FuzzyMode.IGNORE_ALL)) {
                if (entry.getKey() instanceof AEItemKey stored && stored.getItem() == like.getItem()
                        && entry.getLongValue() > 0) {
                    found.add(stored.toStack());
                }
            }
            return found;
        }

        @Override
        public ItemStack extract(IntegrationContext.Storage context, ItemStack required) {
            if (required.isEmpty()) {
                return ItemStack.EMPTY;
            }
            var target = resolve(context, required);
            if (target == null) {
                return required;
            }
            long extracted = target.storage().extract(target.key(), required.getCount(), Actionable.MODULATE, target.source());
            var remainder = required.copy();
            remainder.shrink((int) Math.min(extracted, required.getCount()));
            return remainder;
        }

        @Override
        public ItemStack insert(IntegrationContext.Storage context, ItemStack stack) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            var target = resolve(context, stack);
            if (target == null) {
                return stack;
            }
            long inserted = target.storage().insert(target.key(), stack.getCount(), Actionable.MODULATE, target.source());
            if (inserted <= 0) {
                return stack;
            }
            var remainder = stack.copy();
            remainder.shrink((int) Math.min(inserted, Integer.MAX_VALUE));
            return remainder;
        }

        private static StorageTarget resolve(IntegrationContext.Storage context, ItemStack stack) {
            try {
                var storage = linkedStorage(context);
                var key = storage == null ? null : AEItemKey.of(stack);
                return key == null ? null : new StorageTarget(storage, key, IActionSource.ofPlayer(context.player()));
            } catch (RuntimeException | LinkageError exception) {
                throw new StorageUnchangedException("Could not resolve the linked ME network", exception);
            }
        }
    }

    private record StorageTarget(MEStorage storage, AEItemKey key, IActionSource source) {}

    private static CompoundTag captureCable(BlockEntity blockEntity) {
        var tag = new CompoundTag();
        if (!(blockEntity instanceof CableBusBlockEntity cableBus)) {
            return tag;
        }
        tag.putInt(FORMAT_VERSION, 1);
        var parts = new CompoundTag();
        for (var sideName : orderedPartSides()) {
            var side = sideFromKey(sideName);
            var part = cableBus.getCableBus().getPart(side);
            if (part == null) {
                continue;
            }
            parts.put(sideName, capturePartContents(part));
            if (side == null && part instanceof ICablePart) {
                tag.putString(CABLE_ITEM, IPartItem.getId(part.getPartItem()).toString());
            }
        }
        tag.put(PARTS, parts);

        var facades = new CompoundTag();
        for (var side : Direction.values()) {
            var facade = cableBus.getFacadeContainer().getFacade(side);
            if (facade != null) {
                facades.put(side.getName(), facade.getItemStack().save(new CompoundTag()));
            }
        }
        tag.put(FACADES, facades);
        return tag;
    }

    private static CompoundTag capturePartContents(IPart part) {
        var tag = new CompoundTag();
        tag.put(ITEM, new ItemStack(part.getPartItem()).save(new CompoundTag()));
        var settings = new CompoundTag();
        part.exportSettings(SettingsFrom.MEMORY_CARD, settings);
        if (!settings.isEmpty()) {
            tag.put(SETTINGS, settings);
        }
        if (part instanceof IConfigurableObject configurable) {
            var config = new CompoundTag();
            configurable.getConfigManager().writeToNBT(config);
            if (!config.isEmpty()) {
                tag.put(CONFIG, config);
            }
        }
        var installables = captureInstallables(part);
        if (!installables.isEmpty()) {
            tag.put(INSTALLABLES, installables);
        }
        return tag;
    }

    private static boolean matchesTransferred(CableBusBlockEntity cableBus, CompoundTag payload) {
        var parts = payload.getCompound(PARTS);
        for (var sideName : orderedPartSides()) {
            var existing = cableBus.getCableBus().getPart(sideFromKey(sideName));
            if (!parts.contains(sideName, Tag.TAG_COMPOUND)) {
                if (existing != null) {
                    return false;
                }
                continue;
            }
            var desired = ItemStack.of(parts.getCompound(sideName).getCompound(ITEM));
            if (existing == null || desired.isEmpty() || !desired.is(existing.getPartItem().asItem())) {
                return false;
            }
        }
        var facades = payload.getCompound(FACADES);
        var container = cableBus.getFacadeContainer();
        for (var side : Direction.values()) {
            var existing = container.getFacade(side);
            if (!facades.contains(side.getName(), Tag.TAG_COMPOUND)) {
                if (existing != null) {
                    return false;
                }
                continue;
            }
            var desired = ItemStack.of(facades.getCompound(side.getName()));
            if (existing == null || !ItemStack.isSameItemSameTags(existing.getItemStack(), desired)) {
                return false;
            }
        }
        return true;
    }

    private static ApplyResult reconfigureCable(CableBusBlockEntity cableBus, CompoundTag payload) {
        var bus = cableBus.getCableBus();
        if (payload.getInt(FORMAT_VERSION) < 1) {
            return bus.getPart(null) != null ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
        }
        var parts = payload.getCompound(PARTS);
        for (var sideName : parts.getAllKeys()) {
            if (!orderedPartSides().contains(sideName)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var part = bus.getPart(sideFromKey(sideName));
            var planned = parts.getCompound(sideName);
            if (part == null
                    || !ItemStack.of(planned.getCompound(ITEM)).is(part.getPartItem().asItem())
                    || !sameOwnedResources(capturePartContents(part), planned, SETTINGS)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
        }
        var facades = payload.getCompound(FACADES);
        for (var sideName : facades.getAllKeys()) {
            var side = Direction.byName(sideName);
            var installed = side == null ? null : cableBus.getFacadeContainer().getFacade(side);
            if (installed == null
                    || !ItemStack.isSameItemSameTags(installed.getItemStack(), ItemStack.of(facades.getCompound(sideName)))) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
        }
        for (var sideName : parts.getAllKeys()) {
            applyPartSettings(bus.getPart(sideFromKey(sideName)), parts.getCompound(sideName));
        }
        markChanged(cableBus);
        return ApplyResult.APPLIED;
    }

    private static boolean sameOwnedResources(CompoundTag existing, CompoundTag desired, String settingsKey) {
        return existing.getCompound(INSTALLABLES).equals(desired.getCompound(INSTALLABLES))
                && existing.getCompound(settingsKey).getList(PATTERNS, Tag.TAG_COMPOUND)
                        .equals(desired.getCompound(settingsKey).getList(PATTERNS, Tag.TAG_COMPOUND));
    }

    private static CompoundTag captureInstallables(Object holder) {
        var output = new CompoundTag();
        if (!(holder instanceof ISegmentedInventory segmented)) {
            return output;
        }
        captureInventory(segmented.getSubInventory(ISegmentedInventory.UPGRADES), output, UPGRADES);
        captureInventory(segmented.getSubInventory(ISegmentedInventory.CELLS), output, CELLS);
        return output;
    }

    private static void captureInventory(InternalInventory inventory, CompoundTag output, String key) {
        if (inventory == null || inventory.isEmpty()) {
            return;
        }
        var items = new ListTag();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var item = stack.save(new CompoundTag());
            item.putInt("Slot", slot);
            items.add(item);
        }
        if (!items.isEmpty()) {
            output.put(key, items);
        }
    }

    private static void clearFundedContents(CompoundTag payload, String settingsKey) {
        var settings = payload.getCompound(settingsKey);
        if (settings.contains(PATTERNS, Tag.TAG_LIST)) {
            settings.put(PATTERNS, new ListTag());
        }
        if (payload.contains(INSTALLABLES, Tag.TAG_COMPOUND)) {
            payload.put(INSTALLABLES, new CompoundTag());
        }
    }

    private static PlacementPreparationPlan.Component component(
            PlacementPreparationPlan.Builder plan,
            PlacementPreparationPlan.Component parent,
            ItemRequirement requirement,
            BiConsumer<CompoundTag, ItemStack> update
    ) {
        return parent == null ? plan.component(requirement, update) : parent.component(requirement, update);
    }

    private static void planInstallables(
            PlacementPreparationPlan.Builder plan,
            PlacementPreparationPlan.Component parent,
            CompoundTag installables,
            Function<CompoundTag, CompoundTag> target
    ) {
        for (var key : List.of(UPGRADES, CELLS)) {
            for (var value : installables.getList(key, Tag.TAG_COMPOUND)) {
                var itemTag = ((CompoundTag) value).copy();
                var stack = ItemStack.of(itemTag);
                if (CELLS.equals(key) && stack.getItem() instanceof ICellWorkbenchItem cell) {
                    planCell(plan, parent, itemTag, stack, cell, target);
                } else {
                    component(plan, parent, ItemRequirement.exact(stack), (prepared, acquired) -> {
                        if (!acquired.isEmpty()) {
                            appendFunded(target.apply(prepared), key, itemTag, acquired);
                        }
                    });
                }
            }
        }
    }

    private static void planCell(
            PlacementPreparationPlan.Builder plan,
            PlacementPreparationPlan.Component parent,
            CompoundTag itemTag,
            ItemStack template,
            ICellWorkbenchItem cell,
            Function<CompoundTag, CompoundTag> target
    ) {
        var blank = new ItemStack(template.getItem(), template.getCount());
        var fundedCell = component(plan, parent, ItemRequirement.exact(blank), (prepared, acquired) -> {
            if (acquired.isEmpty()) {
                return;
            }
            var sourceConfig = cell.getConfigInventory(template);
            var targetConfig = cell.getConfigInventory(acquired);
            targetConfig.clear();
            for (int slot = 0; slot < Math.min(sourceConfig.size(), targetConfig.size()); slot++) {
                targetConfig.setStack(slot, sourceConfig.getStack(slot));
            }
            cell.setFuzzyMode(acquired, cell.getFuzzyMode(template));
            cell.getUpgrades(acquired).clear();
            appendFunded(target.apply(prepared), CELLS, itemTag, acquired);
        });
        var upgrades = cell.getUpgrades(template);
        int capacity = cell.getUpgrades(blank).size();
        for (int slot = 0; slot < Math.min(upgrades.size(), capacity); slot++) {
            var upgrade = upgrades.getStackInSlot(slot);
            if (upgrade.isEmpty()) {
                continue;
            }
            int targetSlot = slot;
            fundedCell.component(ItemRequirement.exact(upgrade), (prepared, acquired) -> {
                if (!acquired.isEmpty()) {
                    var fundedTag = findSlot(target.apply(prepared).getList(CELLS, Tag.TAG_COMPOUND), itemTag.getInt("Slot"));
                    var funded = ItemStack.of(fundedTag.getCompound(FUNDED_ITEM));
                    cell.getUpgrades(funded).setItemDirect(targetSlot, acquired);
                    fundedTag.put(FUNDED_ITEM, funded.save(new CompoundTag()));
                }
            });
        }
    }

    private static CompoundTag findSlot(ListTag items, int slot) {
        for (var value : items) {
            var item = (CompoundTag) value;
            if (item.getInt("Slot") == slot) {
                return item;
            }
        }
        throw new IllegalStateException("Funded cell is missing its declared slot " + slot);
    }

    private static void appendFunded(CompoundTag target, String key, CompoundTag template, ItemStack acquired) {
        var funded = template.copy();
        funded.put(FUNDED_ITEM, acquired.save(new CompoundTag()));
        var items = target.getList(key, Tag.TAG_COMPOUND);
        items.add(funded);
        target.put(key, items);
    }

    private static void applyInstallables(Object holder, CompoundTag installables) {
        if (!(holder instanceof ISegmentedInventory segmented) || installables == null) {
            return;
        }
        applyInventory(segmented.getSubInventory(ISegmentedInventory.UPGRADES), installables.getList(UPGRADES, Tag.TAG_COMPOUND));
        applyInventory(segmented.getSubInventory(ISegmentedInventory.CELLS), installables.getList(CELLS, Tag.TAG_COMPOUND));
    }

    private static void applyInventory(InternalInventory inventory, ListTag items) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        for (var value : items) {
            var itemTag = (CompoundTag) value;
            int slot = itemTag.getInt("Slot");
            if (slot >= 0 && slot < inventory.size()) {
                var stack = itemTag.contains(FUNDED_ITEM, Tag.TAG_COMPOUND)
                        ? ItemStack.of(itemTag.getCompound(FUNDED_ITEM))
                        : ItemStack.of(itemTag);
                inventory.setItemDirect(slot, stack);
            }
        }
    }

    private static void planPatterns(
            PlacementPreparationPlan.Builder plan,
            PlacementPreparationPlan.Component parent,
            CompoundTag settings,
            Function<CompoundTag, CompoundTag> target
    ) {
        for (var value : settings.getList(PATTERNS, Tag.TAG_COMPOUND)) {
            var patternTag = ((CompoundTag) value).copy();
            var pattern = ItemStack.of(patternTag);
            var blank = AEItems.BLANK_PATTERN.stack(pattern.getCount());
            component(plan, parent, ItemRequirement.withFallback(pattern, blank), (prepared, acquired) -> {
                if (!acquired.isEmpty()) {
                    appendFunded(target.apply(prepared), PATTERNS, patternTag, acquired);
                }
            });
        }
    }

    private static void applyPatterns(Object holder, ListTag patterns) {
        if (!(holder instanceof PatternContainer container)) {
            return;
        }
        var inventory = container.getTerminalPatternInventory();
        inventory.clear();
        for (var value : patterns) {
            var patternTag = (CompoundTag) value;
            int slot = patternTag.getInt("Slot");
            if (slot >= 0 && slot < inventory.size()) {
                inventory.setItemDirect(slot, ItemStack.of(patternTag));
            }
        }
    }

    private static void applyPartContents(IPart part, CompoundTag data) {
        applyPartSettings(part, data);
        if (data.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            applyPatterns(part, data.getCompound(SETTINGS).getList(PATTERNS, Tag.TAG_COMPOUND));
        }
        applyInstallables(part, data.getCompound(INSTALLABLES));
    }

    private static void applyPartSettings(IPart part, CompoundTag data) {
        if (data.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            var settings = data.getCompound(SETTINGS).copy();
            settings.remove(PATTERNS);
            part.importSettings(SettingsFrom.MEMORY_CARD, settings, null);
        }
        if (data.contains(CONFIG, Tag.TAG_COMPOUND) && part instanceof IConfigurableObject configurable) {
            configurable.getConfigManager().readFromNBT(data.getCompound(CONFIG));
        }
    }

    private static void transformPartContents(CompoundTag part, DirectionTransform transform) {
        if (part.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            part.put(SETTINGS, transformAeTag(part.getCompound(SETTINGS), transform));
        }
        if (part.contains(CONFIG, Tag.TAG_COMPOUND)) {
            part.put(CONFIG, transformAeTag(part.getCompound(CONFIG), transform));
        }
    }

    private static void refundFunded(IntegrationContext.Apply context, Tag value) {
        if (value instanceof CompoundTag compound) {
            if (compound.contains(FUNDED_ITEM, Tag.TAG_COMPOUND)) {
                context.refund(ItemStack.of(compound.getCompound(FUNDED_ITEM)));
            }
            for (var key : compound.getAllKeys()) {
                if (!FUNDED_ITEM.equals(key)) {
                    var child = compound.get(key);
                    if (child != null) {
                        refundFunded(context, child);
                    }
                }
            }
        } else if (value instanceof ListTag list) {
            for (var child : list) {
                refundFunded(context, child);
            }
        }
    }

    private static boolean applyLegacyCable(
            CableBusBlockEntity cableBus,
            IntegrationContext.Apply context,
            CompoundTag payload
    ) {
        if (cableBus.getCableBus().getPart(null) != null) {
            return true;
        }
        var itemId = cableItemId(payload);
        var partItem = itemId == null ? null : IPartItem.byId(itemId);
        if (partItem == null) {
            return false;
        }
        var added = cableBus.getCableBus().addPart(partItem, null, context.player());
        if (added != null) {
            markChanged(cableBus);
        }
        return added != null;
    }

    private static List<String> orderedPartSides() {
        var sides = new ArrayList<String>();
        sides.add(CENTER);
        for (var side : Direction.values()) {
            sides.add(side.getName());
        }
        return List.copyOf(sides);
    }

    private static Direction sideFromKey(String sideName) {
        return CENTER.equals(sideName) ? null : Direction.byName(sideName);
    }

    private static ResourceLocation cableItemId(CompoundTag tag) {
        return tag.contains(CABLE_ITEM, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(CABLE_ITEM)) : null;
    }

    private static CompoundTag centerCableOnly(CompoundTag payload) {
        if (payload.getInt(FORMAT_VERSION) < 1) {
            return payload.copy();
        }
        var output = new CompoundTag();
        output.putInt(FORMAT_VERSION, 1);
        if (payload.contains(CABLE_ITEM, Tag.TAG_STRING)) {
            output.putString(CABLE_ITEM, payload.getString(CABLE_ITEM));
        }
        var center = payload.getCompound(PARTS).getCompound(CENTER);
        if (!center.isEmpty()) {
            var parts = new CompoundTag();
            var bareCenter = new CompoundTag();
            bareCenter.put(ITEM, center.getCompound(ITEM).copy());
            parts.put(CENTER, bareCenter);
            output.put(PARTS, parts);
        }
        return output;
    }

    private static void writeLink(ItemStack stack, GlobalPos pos) {
        var tag = new CompoundTag();
        tag.putString(LINK_DIMENSION, pos.dimension().location().toString());
        tag.putInt(LINK_X, pos.pos().getX());
        tag.putInt(LINK_Y, pos.pos().getY());
        tag.putInt(LINK_Z, pos.pos().getZ());
        ToolState.get(stack).setAeLinkTag(tag);
    }

    private static IWirelessAccessPoint linkedAccessPoint(IntegrationContext.Storage context) {
        if (!(context.tool().getItem() instanceof ManipulatorItem)) {
            return null;
        }
        var tag = ToolState.get(context.tool()).aeLinkTag();
        if (!tag.contains(LINK_DIMENSION) || !tag.contains(LINK_X) || !tag.contains(LINK_Y) || !tag.contains(LINK_Z)) {
            return null;
        }
        var dimensionId = ResourceLocation.tryParse(tag.getString(LINK_DIMENSION));
        if (dimensionId == null || context.player().getServer() == null) {
            return null;
        }
        var dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        var level = context.player().getServer().getLevel(dimension);
        if (level == null || level != context.player().level()) {
            return null;
        }
        var pos = new BlockPos(tag.getInt(LINK_X), tag.getInt(LINK_Y), tag.getInt(LINK_Z));
        if (!(level.getBlockEntity(pos) instanceof IWirelessAccessPoint accessPoint) || !accessPoint.isActive()) {
            return null;
        }
        double range = accessPoint.getRange();
        if (context.player().blockPosition().distSqr(pos) > range * range) {
            return null;
        }
        return accessPoint.getGrid() == null ? null : accessPoint;
    }

    private static IGrid linkedGrid(IntegrationContext.Storage context) {
        var accessPoint = linkedAccessPoint(context);
        return accessPoint == null ? null : accessPoint.getGrid();
    }

    private static MEStorage linkedStorage(IntegrationContext.Storage context) {
        var grid = linkedGrid(context);
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    public record CraftingEntry(
            AEItemKey key,
            ItemDemand demand,
            Future<ICraftingPlan> future
    ) {}

    public static final class CraftingQueue {

        private final ServerPlayer player;
        private final ItemStack tool;
        private final IGrid grid;
        private final IActionSource source;
        private final List<CraftingEntry> entries;
        private int index;
        private int submitted;
        private int failed;

        public CraftingQueue(
                ServerPlayer player,
                ItemStack tool,
                IGrid grid,
                IActionSource source,
                List<CraftingEntry> entries
        ) {
            this.player = player;
            this.tool = tool;
            this.grid = grid;
            this.source = source;
            this.entries = List.copyOf(entries);
        }

        private boolean tick() {
            if (player.isRemoved()
                    || player.getServer() == null
                    || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
                cancelPending();
                return true;
            }
            var currentGrid = linkedGrid(new IntegrationContext.Storage(player, tool));
            if (currentGrid == null || currentGrid != grid) {
                cancelPending();
                player.displayClientMessage(MMMessage.CRAFT_NO_NETWORK.text(), false);
                return true;
            }

            while (index < entries.size()) {
                var entry = entries.get(index);
                if (!entry.future().isDone()) {
                    return false;
                }
                ICraftingPlan plan;
                try {
                    plan = entry.future().get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancelPending();
                    return true;
                } catch (ExecutionException | RuntimeException exception) {
                    MatterManipulator.LOGGER.warn("AE2 crafting calculation failed for {}", entry.demand(), exception);
                    reportFailure(entry, MMMessage.CRAFT_FAILURE_CALCULATION.text());
                    index++;
                    failed++;
                    continue;
                }
                if (plan.simulation()) {
                    reportFailure(entry, MMMessage.CRAFT_FAILURE_INCOMPLETE_PLAN.text());
                    index++;
                    failed++;
                    continue;
                }

                var result = grid.getCraftingService().submitJob(plan, null, null, true, source);
                if (result.successful()) {
                    player.displayClientMessage(MMMessage.CRAFT_JOB_SUBMITTED.text(
                            entry.demand().amount(),
                            entry.demand().stack().getHoverName()
                    ), false);
                    index++;
                    submitted++;
                    continue;
                }
                if (isBusy(result.errorCode(), result.errorDetail())) {
                    return false;
                }
                reportFailure(entry, failureReason(result.errorCode()));
                index++;
                failed++;
            }

            player.displayClientMessage(MMMessage.CRAFT_REQUEST_COMPLETE.text(
                    submitted,
                    failed
            ), false);
            return true;
        }

        private static boolean isBusy(CraftingSubmitErrorCode code, Object detail) {
            if (code == CraftingSubmitErrorCode.CPU_BUSY) {
                return true;
            }
            return code == CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND
                    && detail instanceof UnsuitableCpus cpus
                    && cpus.busy() > 0
                    && cpus.offline() == 0
                    && cpus.tooSmall() == 0
                    && cpus.excluded() == 0;
        }

        private static Component failureReason(CraftingSubmitErrorCode code) {
            return code == null ? MMMessage.CRAFT_FAILURE_UNKNOWN.text() : switch (code) {
                case INCOMPLETE_PLAN -> MMMessage.CRAFT_FAILURE_INCOMPLETE_PLAN.text();
                case NO_CPU_FOUND -> MMMessage.CRAFT_FAILURE_NO_CPU.text();
                case NO_SUITABLE_CPU_FOUND -> MMMessage.CRAFT_FAILURE_NO_SUITABLE_CPU.text();
                case CPU_BUSY -> MMMessage.CRAFT_FAILURE_CPU_BUSY.text();
                case CPU_OFFLINE -> MMMessage.CRAFT_FAILURE_CPU_OFFLINE.text();
                case CPU_TOO_SMALL -> MMMessage.CRAFT_FAILURE_CPU_TOO_SMALL.text();
                case MISSING_INGREDIENT -> MMMessage.CRAFT_FAILURE_MISSING_INGREDIENT.text();
            };
        }

        private void reportFailure(CraftingEntry entry, Component reason) {
            player.displayClientMessage(MMMessage.CRAFT_JOB_FAILED.text(
                    entry.demand().amount(),
                    entry.demand().stack().getHoverName(),
                    reason
            ), false);
        }

        private void cancelPending() {
            for (int i = index; i < entries.size(); i++) {
                try {
                    entries.get(i).future().cancel(true);
                } catch (RuntimeException exception) {
                    MatterManipulator.LOGGER.warn("Could not cancel AE2 crafting calculation for {}", entries.get(i).demand(), exception);
                }
            }
        }
    }

    private static void markChanged(BlockEntity blockEntity) {
        if (blockEntity instanceof AEBaseBlockEntity aeBase) {
            aeBase.markForUpdate();
            aeBase.saveChanges();
        } else if (blockEntity != null) {
            blockEntity.setChanged();
        }
    }

    private static CompoundTag transformAeTag(CompoundTag input, DirectionTransform transform) {
        var output = input.copy();
        transformDirectionString(output, transform, "forward");
        transformDirectionString(output, transform, "up");
        return Ae2NbtTransform.transformCableBusDirections(output, transform);
    }

    private static void transformDirectionString(CompoundTag tag, DirectionTransform transform, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return;
        }
        var direction = Direction.byName(tag.getString(key).toLowerCase(Locale.ROOT));
        if (direction != null) {
            tag.putString(key, transform.transform(direction).getName());
        }
    }

}
