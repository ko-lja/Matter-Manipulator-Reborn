package lu.kolja.matter_manipulator.common.energy;

import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

public final class StackEnergyStorage implements EnergyHandler {

    public static final String TAG_ENERGY = "Energy";

    private final ItemAccess access;
    private final Item item;
    private final ManipulatorTier tier;

    public StackEnergyStorage(ItemAccess access, ManipulatorTier tier) {
        this.access = access;
        this.item = access.getResource().getItem();
        this.tier = tier;
    }

    @Override
    public long getAmountAsLong() {
        var resource = access.getResource();
        if (!resource.is(item)) {
            return 0L;
        }
        return (long) access.getAmount() * stored(resource.getComponents().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
    }

    @Override
    public long getCapacityAsLong() {
        return access.getResource().is(item) ? (long) access.getAmount() * tier.energyCapacity() : 0L;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return exchange(amount, transaction, true);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        return exchange(amount, transaction, false);
    }

    private int exchange(int amount, TransactionContext transaction, boolean insert) {
        int count = access.getAmount();
        var resource = access.getResource();
        if (amount <= 0 || count == 0 || !resource.is(item)) {
            return 0;
        }
        var data = resource.getComponents().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        int stored = stored(data);
        int room = insert ? tier.energyCapacity() - stored : stored;
        int perItem = Math.min(Math.min(amount / count, tier.transferLimit()), room);
        if (perItem <= 0) {
            return 0;
        }
        var tag = data.copyTag();
        tag.putInt(TAG_ENERGY, insert ? stored + perItem : stored - perItem);
        var updated = resource.with(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return perItem * access.exchange(updated, count, transaction);
    }

    public static int stored(ItemStack stack) {
        return stored(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
    }

    private static int stored(CustomData data) {
        return data.copyTag().getIntOr(TAG_ENERGY, 0);
    }

    public static int receive(ItemStack stack, ManipulatorTier tier, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int stored = stored(stack);
        int accepted = Math.min(Math.min(amount, tier.transferLimit()), tier.energyCapacity() - stored);
        if (accepted > 0) {
            store(stack, tier, stored + accepted);
        }
        return accepted;
    }

    public static boolean consume(ItemStack stack, ManipulatorTier tier, long amount, boolean simulate) {
        if (amount <= 0) {
            return true;
        }
        int stored = stored(stack);
        if (stored < amount) {
            return false;
        }
        if (!simulate) {
            store(stack, tier, (int) (stored - amount));
        }
        return true;
    }

    private static void store(ItemStack stack, ManipulatorTier tier, int energy) {
        int clamped = Math.max(0, Math.min(energy, tier.energyCapacity()));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(TAG_ENERGY, clamped));
    }
}
