package lu.kolja.matter_manipulator.api;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** @param unsupportedReason set by {@link #unsupported}, refuses the placement whatever items are available */
public record PlacementRequirements(
        boolean requireDefaultBlockItem,
        List<ItemRequirement> requirements,
        @Nullable Component unsupportedReason
) {

    public PlacementRequirements {
        requirements = List.copyOf(requirements);
    }

    public PlacementRequirements(boolean requireDefaultBlockItem, List<ItemStack> items) {
        this(requireDefaultBlockItem, items.stream().filter(stack -> !stack.isEmpty()).map(ItemRequirement::exact)
                .toList(), null);
    }

    public List<ItemStack> items() {
        return requirements.stream().map(ItemRequirement::exact).toList();
    }

    public static PlacementRequirements defaults() {
        return new PlacementRequirements(true, List.of());
    }

    public static PlacementRequirements replaceWith(ItemStack stack) {
        return new PlacementRequirements(false, List.of(stack));
    }

    public static PlacementRequirements replaceWith(ItemRequirement requirement) {
        return new PlacementRequirements(false, List.of(requirement), null);
    }

    public static PlacementRequirements additional(ItemStack stack) {
        return new PlacementRequirements(true, List.of(stack));
    }

    public static PlacementRequirements additional(ItemRequirement requirement) {
        return new PlacementRequirements(true, List.of(requirement), null);
    }

    /**
     * COPY leaves the block out and other modes refuse the plan, but MOVE is unaffected. The reason follows
     * "cannot be placed: " and wins over other handlers.
     */
    public static PlacementRequirements unsupported(Component reason) {
        return new PlacementRequirements(false, List.of(), Objects.requireNonNull(reason, "reason"));
    }

    public PlacementRequirements merge(PlacementRequirements other) {
        var merged = new ArrayList<ItemRequirement>(requirements.size() + other.requirements.size());
        merged.addAll(requirements);
        merged.addAll(other.requirements);
        return new PlacementRequirements(requireDefaultBlockItem && other.requireDefaultBlockItem, merged,
                unsupportedReason != null ? unsupportedReason : other.unsupportedReason);
    }
}
