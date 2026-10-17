package lu.kolja.matter_manipulator.api;

import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface ItemAcquirer {
    /** Returns the whole withdrawn stack, which {@link ItemRequirement#accepts}, or EMPTY if nothing was taken. */
    ItemStack acquire(ItemRequirement requirement);
}
