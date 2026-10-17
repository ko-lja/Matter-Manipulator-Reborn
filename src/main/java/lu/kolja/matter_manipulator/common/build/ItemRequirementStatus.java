package lu.kolja.matter_manipulator.common.build;

import net.minecraft.world.item.ItemStack;

import java.util.List;

public record ItemRequirementStatus(
        ItemStack stack,
        ItemStack fallback,
        String description,
        long available,
        long required,
        List<ItemStack> alternatives,
        boolean variants
) {

    public ItemRequirementStatus {
        stack = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        fallback = fallback == null ? ItemStack.EMPTY : fallback.copyWithCount(1);
        description = description == null || description.isBlank()
                ? stack.getHoverName().getString()
                : description;
        required = Math.max(0L, required);
        available = Math.max(0L, Math.min(available, required));
        alternatives = alternatives.stream().map(alternative -> alternative.copyWithCount(1)).toList();
    }

    public ItemRequirementStatus(ItemStack stack, ItemStack fallback, String description, long available, long required) {
        this(stack, fallback, description, available, required, List.of(), false);
    }

    public long missing() {
        return Math.max(0L, required - available);
    }

    public boolean complete() {
        return missing() == 0L;
    }

    @Override
    public ItemStack stack() {
        return stack.copy();
    }

    @Override
    public ItemStack fallback() {
        return fallback.copy();
    }

    @Override
    public List<ItemStack> alternatives() {
        return alternatives.stream().map(ItemStack::copy).toList();
    }
}
