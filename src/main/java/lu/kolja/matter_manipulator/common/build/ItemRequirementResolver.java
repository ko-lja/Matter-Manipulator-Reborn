package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.api.ItemDemand;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.util.InventoryUtil;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.ToLongFunction;

public final class ItemRequirementResolver {

    private ItemRequirementResolver() {}

    public static List<ItemDemand> missing(
            List<ItemRequirement> requirements,
            ToLongFunction<ItemStack> availability
    ) {
        return missing(requirements, ItemSupply.perKey(availability));
    }

    public static List<ItemDemand> missing(List<ItemRequirement> requirements, ItemSupply supply) {
        var missing = new ArrayList<StackAmount>();
        var copies = new IdentityHashMap<ItemRequirement, Options>();
        for (var requirement : requirements) {
            var options = copies.computeIfAbsent(requirement, Options::new);
            long remaining = options.exact.getCount() - options.reserve(requirement, supply);
            if (remaining > 0) {
                add(missing, options.fallback.isEmpty() ? options.exact : options.fallback, remaining);
            }
        }
        return missing.stream().map(entry -> new ItemDemand(entry.stack(), entry.amount())).toList();
    }

    public static List<ItemRequirementStatus> statuses(
            List<ItemRequirement> requirements,
            ToLongFunction<ItemStack> availability
    ) {
        return statuses(requirements, ItemSupply.perKey(availability));
    }

    public static List<ItemRequirementStatus> statuses(List<ItemRequirement> requirements, ItemSupply supply) {
        var statuses = new ArrayList<MutableStatus>();
        var copies = new IdentityHashMap<ItemRequirement, Options>();
        for (var requirement : requirements) {
            var options = copies.computeIfAbsent(requirement, Options::new);
            var exact = options.exact;
            var fallback = options.fallback;
            long required = (long) exact.getCount();
            long available = options.reserve(requirement, supply);

            var status = find(statuses, options, requirement);
            if (status == null) {
                status = new MutableStatus(
                        exact.copyWithCount(1),
                        fallback.copyWithCount(fallback.isEmpty() ? 0 : 1),
                        requirement.description(),
                        options.alternatives,
                        requirement.variants() != null
                );
                statuses.add(status);
            }
            status.required += required;
            status.available += available;
        }
        return statuses.stream().map(MutableStatus::freeze).toList();
    }

    private record Options(ItemStack exact, List<ItemStack> alternatives, ItemStack fallback) {

        private Options(ItemRequirement requirement) {
            this(requirement.exact(), requirement.alternatives(), requirement.fallback());
        }

        private long reserve(ItemRequirement requirement, ItemSupply supply) {
            long required = exact.getCount();
            long reserved = supply.reserve(exact, required);
            for (var alternative : alternatives) {
                if (reserved >= required) {
                    return reserved;
                }
                reserved += supply.reserve(alternative, required - reserved);
            }
            if (reserved < required && requirement.variants() != null) {
                for (var candidate : supply.variants(exact)) {
                    if (reserved >= required) {
                        return reserved;
                    }
                    if (!InventoryUtil.isSameMaterial(candidate, exact) && requirement.acceptsVariant(candidate)) {
                        reserved += supply.reserve(candidate, required - reserved);
                    }
                }
            }
            if (reserved < required && !fallback.isEmpty()) {
                reserved += supply.reserve(fallback, required - reserved);
            }
            return reserved;
        }
    }

    public interface ItemSupply {

        long reserve(ItemStack key, long amount);

        default List<ItemStack> variants(ItemStack like) {
            return List.of();
        }

        static ItemSupply perKey(ToLongFunction<ItemStack> availability) {
            var pools = new ArrayList<StackAmount>();
            return (key, amount) -> {
                var entry = find(pools, key);
                if (entry == null) {
                    entry = new StackAmount(key.copyWithCount(1), Math.max(0, availability.applyAsLong(key.copyWithCount(1))));
                    pools.add(entry);
                }
                long taken = Math.min(Math.max(0L, amount), entry.amount());
                entry.setAmount(entry.amount() - taken);
                return taken;
            };
        }
    }

    private static void add(List<StackAmount> amounts, ItemStack stack, long amount) {
        var entry = find(amounts, stack);
        if (entry == null) {
            amounts.add(new StackAmount(stack.copyWithCount(1), amount));
        } else {
            entry.setAmount(entry.amount() + amount);
        }
    }

    private static StackAmount find(List<StackAmount> amounts, ItemStack stack) {
        for (var entry : amounts) {
            if (InventoryUtil.isSameMaterial(entry.stack(), stack)) {
                return entry;
            }
        }
        return null;
    }

    private static MutableStatus find(List<MutableStatus> statuses, Options options, ItemRequirement requirement) {
        for (var status : statuses) {
            if (status.description.equals(requirement.description())
                    && InventoryUtil.isSameMaterial(status.stack, options.exact)
                    && stacksMatch(status.fallback, options.fallback)
                    && status.variants == (requirement.variants() != null)
                    && sameStacks(status.alternatives, options.alternatives)) {
                return status;
            }
        }
        return null;
    }

    private static boolean sameStacks(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!InventoryUtil.isSameMaterial(first.get(i), second.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean stacksMatch(ItemStack first, ItemStack second) {
        return first.isEmpty() && second.isEmpty() || InventoryUtil.isSameMaterial(first, second);
    }

    private static final class StackAmount {

        private final ItemStack stack;
        private long amount;

        private StackAmount(ItemStack stack, long amount) {
            this.stack = stack;
            this.amount = amount;
        }

        private ItemStack stack() {
            return stack;
        }

        private long amount() {
            return amount;
        }

        private void setAmount(long amount) {
            this.amount = amount;
        }
    }

    private static final class MutableStatus {

        private final ItemStack stack;
        private final ItemStack fallback;
        private final String description;
        private final List<ItemStack> alternatives;
        private final boolean variants;
        private long available;
        private long required;

        private MutableStatus(
                ItemStack stack,
                ItemStack fallback,
                String description,
                List<ItemStack> alternatives,
                boolean variants
        ) {
            this.stack = stack;
            this.fallback = fallback;
            this.description = description;
            this.alternatives = alternatives;
            this.variants = variants;
        }

        private ItemRequirementStatus freeze() {
            return new ItemRequirementStatus(stack, fallback, description, available, required, alternatives, variants);
        }
    }
}
