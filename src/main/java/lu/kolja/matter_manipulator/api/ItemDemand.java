package lu.kolja.matter_manipulator.api;

import net.minecraft.world.item.ItemStack;

/** An item still missing after resolving requirements. {@code stack} has count 1; {@code amount} is the quantity. */
public record ItemDemand(ItemStack stack, long amount) {

    public ItemDemand {
        stack = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (stack.isEmpty() || amount <= 0) {
            throw new IllegalArgumentException("Item demands require a non-empty stack and positive amount");
        }
    }

    @Override
    public ItemStack stack() {
        return stack.copy();
    }
}
