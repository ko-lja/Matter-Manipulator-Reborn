package lu.kolja.matter_manipulator.api;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Throw {@link StorageUnchangedException} only when nothing can have moved. Any other exception or an invalid
 * remainder leaves the outcome unknown, and core never refunds the stack it handed over.
 */
public interface ItemStorageIntegration {

    default long available(IntegrationContext.Storage context, ItemStack stack) {
        return 0;
    }

    /**
     * One stack for each item-and-tags key of {@code like}'s item. Only asked for requirements with variants, and
     * must not mutate anything.
     */
    default List<ItemStack> variants(IntegrationContext.Storage context, ItemStack like) {
        return List.of();
    }

    /** Returns the unfulfilled remainder, which is empty or the same item and tags with no greater count. */
    default ItemStack extract(IntegrationContext.Storage context, ItemStack requested) {
        return requested.copy();
    }

    /** Same remainder rules as extraction. The default declines. */
    default ItemStack insert(IntegrationContext.Storage context, ItemStack stack) {
        return stack.copy();
    }
}
