package lu.kolja.matter_manipulator.common.util;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.api.ItemAcquirer;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.build.ItemRequirementResolver;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.integration.StorageIntegrationException;
import lu.kolja.matter_manipulator.common.localization.MMMessage;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

public final class InventoryUtil {

    private InventoryUtil() {}

    private static final Set<DataComponentType<?>> COSMETIC_COMPONENTS = Set.of(DataComponents.CUSTOM_NAME,
            DataComponents.LORE, DataComponents.DYED_COLOR, DataComponents.MAP_COLOR, DataComponents.REPAIR_COST,
            DataComponents.TOOLTIP_DISPLAY, DataComponents.CUSTOM_MODEL_DATA);

    public static boolean consumeForPlacement(ServerPlayer player, ItemStack tool, ItemStack required) {
        if (required.isEmpty() || player.getAbilities().instabuild) {
            return true;
        }
        return consumeAcrossSources(
                required,
                stack -> IntegrationDispatcher.extractItem(player, tool, stack),
                stack -> consumeFromInventory(player.getInventory(), stack),
                stack -> giveOrDrop(player, tool, stack),
                (stack, failure) -> settleAfterStorageFailure(player, tool, stack, failure)
        );
    }

    public static @Nullable List<ItemStack> consumeForPlacement(
            ServerPlayer player,
            ItemStack tool,
            List<ItemRequirement> requirements
    ) {
        if (player.getAbilities().instabuild) {
            return List.of();
        }
        return consumeAll(
                requirements,
                requirement -> acquireRequirement(
                        requirement,
                        stack -> consumeForPlacement(player, tool, stack),
                        like -> variants(player, tool, like)
                ),
                stack -> giveOrDrop(player, tool, stack),
                (stack, failure) -> settleAfterStorageFailure(player, tool, stack, failure)
        );
    }

    static @Nullable List<ItemStack> consumeAll(
            List<ItemRequirement> requirements,
            ItemAcquirer acquirer,
            Consumer<ItemStack> refund,
            BiConsumer<ItemStack, StorageIntegrationException> failureRefund
    ) {
        var consumed = new ArrayList<ItemStack>();
        for (var requirement : requirements) {
            ItemStack acquired;
            try {
                acquired = acquirer.acquire(requirement);
            } catch (StorageIntegrationException exception) {
                consumed.forEach(stack -> failureRefund.accept(stack.copy(), exception));
                throw exception;
            }
            if (acquired.isEmpty()) {
                consumed.forEach(stack -> refund.accept(stack.copy()));
                return null;
            }
            consumed.add(acquired.copy());
        }
        return consumed;
    }

    static List<ItemStack> variants(ServerPlayer player, ItemStack tool, ItemStack like) {
        return variants(IntegrationDispatcher.storedVariants(player, tool, like), player.getInventory().getNonEquipmentItems(), like);
    }

    private static List<ItemStack> variants(List<ItemStack> stored, List<ItemStack> inventory, ItemStack like) {
        var found = new ArrayList<ItemStack>();
        for (var source : List.of(stored, inventory)) {
            for (var stack : source) {
                if (stack.is(like.getItem()) && found.stream().noneMatch(seen -> InventoryUtil.isSameMaterial(seen, stack))) {
                    found.add(stack.copyWithCount(1));
                }
            }
        }
        return found;
    }

    public static void refundPlacement(ServerPlayer player, ItemStack tool, List<ItemStack> requiredItems) {
        for (var required : requiredItems) {
            giveOrDrop(player, tool, required.copy());
        }
    }

    public static void refundAfterStorageFailure(
            ServerPlayer player,
            ItemStack tool,
            List<ItemStack> requiredItems,
            StorageIntegrationException failure
    ) {
        for (var required : requiredItems) {
            settleAfterStorageFailure(player, tool, required.copy(), failure);
        }
    }

    public static void settleAfterStorageFailure(
            ServerPlayer player,
            ItemStack tool,
            ItemStack stack,
            StorageIntegrationException failure
    ) {
        giveOrDrop(player, tool, stack, failure);
    }

    public static void giveOrDrop(ServerPlayer player, ItemStack tool, ItemStack stack) {
        giveOrDrop(player, tool, stack, null);
    }

    private static void giveOrDrop(
            ServerPlayer player,
            ItemStack tool,
            ItemStack stack,
            @Nullable StorageIntegrationException cause
    ) {
        if (player.getAbilities().instabuild) {
            return;
        }
        returnOwned(player, tool, stack, cause);
    }

    public static void returnContents(ServerPlayer player, ItemStack tool, ItemStack stack) {
        returnOwned(player, tool, stack, null);
    }

    private static void returnOwned(
            ServerPlayer player,
            ItemStack tool,
            ItemStack stack,
            @Nullable StorageIntegrationException cause
    ) {
        if (stack.isEmpty()) {
            return;
        }
        settle(
                stack,
                cause,
                (remaining, excluded) -> IntegrationDispatcher.insertItem(player, tool, remaining, excluded),
                remaining -> giveToPlayer(player, remaining),
                failure -> reportUncertain(player, failure)
        );
    }

    public static void settle(
            ItemStack stack,
            @Nullable StorageIntegrationException cause,
            BiFunction<ItemStack, Set<Identifier>, ItemStack> inserter,
            Consumer<ItemStack> toPlayer,
            Consumer<StorageIntegrationException> report
    ) {
        var failures = FAILURES.get();
        var excluded = new HashSet<Identifier>();
        if (failures != null) {
            if (cause != null) {
                failures.record(cause);
            }
            excluded.addAll(failures.failed);
        }
        if (cause != null) {
            excluded.add(cause.integrationId());
        }
        var settlement = insertOrSettle(stack, remaining -> inserter.apply(remaining, Set.copyOf(excluded)));
        if (!settlement.toPlayer().isEmpty()) {
            toPlayer.accept(settlement.toPlayer());
        }
        if (settlement.failure() != null) {
            if (failures != null) {
                failures.record(settlement.failure());
            }
            report.accept(settlement.failure());
        }
    }

    private static final ThreadLocal<StorageFailures> FAILURES = new ThreadLocal<>();

    public static StorageFailures trackStorageFailures() {
        var failures = new StorageFailures();
        FAILURES.set(failures);
        return failures;
    }

    public static final class StorageFailures implements AutoCloseable {

        private final Set<Identifier> failed = new LinkedHashSet<>();
        private StorageIntegrationException first;

        private StorageFailures() {}

        private void record(StorageIntegrationException failure) {
            failed.add(failure.integrationId());
            if (first == null) {
                first = failure;
            }
        }

        public @Nullable StorageIntegrationException first() {
            return first;
        }

        @Override
        public void close() {
            if (FAILURES.get() == this) {
                FAILURES.remove();
            }
        }
    }

    public record Settlement(ItemStack toPlayer, StorageIntegrationException failure) {}

    public static Settlement insertOrSettle(ItemStack stack, Function<ItemStack, ItemStack> externalInserter) {
        try {
            return new Settlement(externalInserter.apply(stack.copy()), null);
        } catch (StorageIntegrationException exception) {
            return new Settlement(exception.callerOwned(), exception);
        }
    }

    public static void reportUncertain(ServerPlayer player, StorageIntegrationException failure) {
        var uncertain = failure.uncertain();
        if (uncertain.isEmpty()) {
            return;
        }
        MatterManipulator.LOGGER.warn(
                "Storage integration {} failed mid-transfer for {}: up to {}x {} may have moved and was not refunded",
                failure.integrationId(),
                player.getGameProfile().name(),
                uncertain.getCount(),
                uncertain.getItem(),
                failure
        );
        player.sendSystemMessage(MMMessage.STORAGE_UNCERTAIN.text(
                uncertain.getCount(),
                uncertain.getHoverName(),
                failure.integrationId().toString()
        ), false);
    }

    private static void giveToPlayer(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        player.getInventory().placeItemBackInInventory(stack, false);
    }

    public static ItemRequirementResolver.ItemSupply placementSupply(ServerPlayer player, ItemStack tool) {
        if (player.getAbilities().instabuild) {
            return ItemRequirementResolver.ItemSupply.perKey(stack -> Long.MAX_VALUE);
        }
        var storedVariants = new HashMap<Item, List<ItemStack>>();
        return placementSupply(
                stack -> IntegrationDispatcher.availableItem(player, tool, stack),
                like -> storedVariants.computeIfAbsent(like.getItem(),
                        item -> IntegrationDispatcher.storedVariants(player, tool, like)),
                player.getInventory().getNonEquipmentItems()
        );
    }

    static ItemRequirementResolver.ItemSupply placementSupply(ToLongFunction<ItemStack> storage, List<ItemStack> inventory) {
        return placementSupply(storage, like -> List.of(), inventory);
    }

    static ItemRequirementResolver.ItemSupply placementSupply(
            ToLongFunction<ItemStack> storage,
            Function<ItemStack, List<ItemStack>> storedVariants,
            List<ItemStack> inventory
    ) {
        var external = ItemRequirementResolver.ItemSupply.perKey(storage);
        var slots = inventory.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        return new ItemRequirementResolver.ItemSupply() {
            @Override
            public long reserve(ItemStack required, long amount) {
                long reserved = external.reserve(required, amount);
                for (boolean exactPass : new boolean[] {true, false}) {
                    for (var slot : slots) {
                        if (reserved >= amount) {
                            return reserved;
                        }
                        boolean eligible = exactPass ? matchesExact(slot, required) : canConsume(slot, required);
                        if (!eligible) {
                            continue;
                        }
                        int taken = (int) Math.min(amount - reserved, slot.getCount());
                        slot.shrink(taken);
                        reserved += taken;
                    }
                }
                return reserved;
            }

            @Override
            public List<ItemStack> variants(ItemStack like) {
                return InventoryUtil.variants(storedVariants.apply(like), slots, like);
            }
        };
    }

    static long saturatingAdd(long available, long additional) {
        long nonNegativeAvailable = Math.max(0L, available);
        long nonNegativeAdditional = Math.max(0L, additional);
        if (nonNegativeAvailable >= Long.MAX_VALUE - nonNegativeAdditional) {
            return Long.MAX_VALUE;
        }
        return nonNegativeAvailable + nonNegativeAdditional;
    }

    public static boolean canConsume(ItemStack candidate, ItemStack required) {
        if (matchesExact(candidate, required)) {
            return true;
        }
        return !candidate.isEmpty()
                && !required.isEmpty()
                && required.isComponentsPatchEmpty()
                && candidate.is(required.getItem())
                && hasOnlyCosmeticData(candidate);
    }

    static boolean hasOnlyCosmeticData(ItemStack stack) {
        for (var component : stack.getComponentsPatch().entrySet()) {
            if (!COSMETIC_COMPONENTS.contains(component.getKey())) {
                return false;
            }
        }
        return true;
    }

    public static long countConsumable(Inventory inventory, ItemStack required) {
        long count = 0L;
        for (var stack : inventory.getNonEquipmentItems()) {
            if (canConsume(stack, required)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static boolean consumeFromInventory(Inventory inventory, ItemStack required) {
        if (countConsumable(inventory, required) < required.getCount()) {
            return false;
        }
        int remaining = required.getCount();
        for (boolean exactPass : new boolean[] {true, false}) {
            for (var stack : inventory.getNonEquipmentItems()) {
                if (remaining <= 0) {
                    break;
                }
                boolean eligible = exactPass ? matchesExact(stack, required) : canConsume(stack, required);
                if (!eligible) {
                    continue;
                }
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        inventory.setChanged();
        return true;
    }

    public static boolean isSameMaterial(ItemStack first, ItemStack second) {
        return ItemStack.isSameItemSameComponents(first, second);
    }

    static boolean matchesExact(ItemStack available, ItemStack required) {
        return !available.isEmpty()
                && !required.isEmpty()
                && InventoryUtil.isSameMaterial(available, required);
    }

    static boolean consumeExactWithPriority(
            ItemStack required, Predicate<ItemStack> networkExtractor, Predicate<ItemStack> inventoryExtractor) {
        return networkExtractor.test(required.copy()) || inventoryExtractor.test(required.copy());
    }

    static boolean consumeAcrossSources(
            ItemStack required,
            Function<ItemStack, ItemStack> externalExtractor,
            Predicate<ItemStack> inventoryExtractor,
            Consumer<ItemStack> externalRefund,
            BiConsumer<ItemStack, StorageIntegrationException> failureRefund
    ) {
        ItemStack remainder;
        try {
            remainder = externalExtractor.apply(required.copy());
        } catch (StorageIntegrationException exception) {
            var withdrawn = exception.callerOwned();
            if (!withdrawn.isEmpty()) {
                failureRefund.accept(withdrawn, exception);
            }
            throw exception;
        }
        Objects.requireNonNull(remainder, "External item source returned null extraction remainder");
        if (remainder.isEmpty()) {
            return true;
        }
        if (!matchesExact(remainder, required) || remainder.getCount() > required.getCount()) {
            throw new IllegalStateException("External item source returned an invalid extraction remainder");
        }
        if (inventoryExtractor.test(remainder.copy())) {
            return true;
        }
        int extractedCount = required.getCount() - remainder.getCount();
        if (extractedCount > 0) {
            var refund = required.copy();
            refund.setCount(extractedCount);
            externalRefund.accept(refund);
        }
        return false;
    }

    static ItemStack acquireRequirement(
            ItemRequirement requirement,
            Predicate<ItemStack> extractor,
            Function<ItemStack, List<ItemStack>> variants
    ) {
        var exact = requirement.exact();
        if (extractor.test(exact.copy())) {
            return exact;
        }
        for (var alternative : requirement.alternatives()) {
            if (extractor.test(alternative.copy())) {
                return alternative;
            }
        }
        if (requirement.variants() != null) {
            for (var candidate : variants.apply(exact)) {
                var variant = candidate.copyWithCount(exact.getCount());
                if (!InventoryUtil.isSameMaterial(variant, exact) && requirement.acceptsVariant(variant)
                        && extractor.test(variant.copy())) {
                    return variant;
                }
            }
        }
        var fallback = requirement.fallback();
        if (!fallback.isEmpty() && extractor.test(fallback.copy())) {
            return fallback;
        }
        return ItemStack.EMPTY;
    }

    public static final class Acquisition implements ItemAcquirer {

        private final boolean creative;
        private final Predicate<ItemStack> extractor;
        private final Function<ItemStack, List<ItemStack>> variants;
        private final Consumer<ItemStack> refund;
        private final BiConsumer<ItemStack, StorageIntegrationException> failureRefund;
        private final List<ItemStack> consumed = new ArrayList<>();
        private final List<ItemRequirement> missing = new ArrayList<>();

        public Acquisition(ServerPlayer player, ItemStack tool) {
            this(player != null && player.getAbilities().instabuild,
                    stack -> consumeForPlacement(player, tool, stack),
                    like -> variants(player, tool, like),
                    stack -> giveOrDrop(player, tool, stack),
                    (stack, failure) -> settleAfterStorageFailure(player, tool, stack, failure));
        }

        public Acquisition(Predicate<ItemStack> extractor, Consumer<ItemStack> refund) {
            this(false, extractor, like -> List.of(), refund, (stack, failure) -> refund.accept(stack));
        }

        public Acquisition(
                Predicate<ItemStack> extractor,
                Consumer<ItemStack> refund,
                BiConsumer<ItemStack, StorageIntegrationException> failureRefund
        ) {
            this(false, extractor, like -> List.of(), refund, failureRefund);
        }

        private Acquisition(
                boolean creative,
                Predicate<ItemStack> extractor,
                Function<ItemStack, List<ItemStack>> variants,
                Consumer<ItemStack> refund,
                BiConsumer<ItemStack, StorageIntegrationException> failureRefund
        ) {
            this.creative = creative;
            this.extractor = Objects.requireNonNull(extractor, "extractor");
            this.variants = variants;
            this.refund = Objects.requireNonNull(refund, "refund");
            this.failureRefund = Objects.requireNonNull(failureRefund, "failureRefund");
        }

        @Override
        public ItemStack acquire(ItemRequirement requirement) {
            if (creative) {
                return requirement.exact();
            }
            var acquired = acquireRequirement(requirement, extractor, variants);
            if (!acquired.isEmpty()) {
                consumed.add(acquired.copy());
                return acquired;
            }
            missing.add(requirement);
            return ItemStack.EMPTY;
        }

        public List<ItemStack> consumed() {
            return consumed.stream().map(ItemStack::copy).toList();
        }

        public List<ItemRequirement> missing() {
            return List.copyOf(missing);
        }

        public void refund() {
            consumed.forEach(stack -> refund.accept(stack.copy()));
            consumed.clear();
        }

        public void refundAfterStorageFailure(StorageIntegrationException failure) {
            consumed.forEach(stack -> failureRefund.accept(stack.copy(), failure));
            consumed.clear();
        }
    }
}
