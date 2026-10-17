package lu.kolja.matter_manipulator.common.energy;

import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.energy.IEnergyStorage;

public final class StackEnergyStorage implements IEnergyStorage {

    public static final String TAG_ENERGY = "Energy";

    private final ItemStack stack;
    private final ManipulatorTier tier;

    public StackEnergyStorage(ItemStack stack, ManipulatorTier tier) {
        this.stack = stack;
        this.tier = tier;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        int stored = getEnergyStored();
        int accepted = Math.min(Math.min(maxReceive, tier.transferLimit()), getMaxEnergyStored() - stored);
        if (!simulate && accepted > 0) {
            setEnergy(stored + accepted);
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (maxExtract <= 0) {
            return 0;
        }
        int extracted = Math.min(Math.min(maxExtract, tier.transferLimit()), getEnergyStored());
        if (!simulate && extracted > 0) {
            setEnergy(getEnergyStored() - extracted);
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt(TAG_ENERGY);
    }

    @Override
    public int getMaxEnergyStored() {
        return tier.energyCapacity();
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    public boolean consume(long amount, boolean simulate) {
        if (amount <= 0) {
            return true;
        }
        int stored = getEnergyStored();
        if (stored < amount) {
            return false;
        }
        if (!simulate) {
            setEnergy((int) (stored - amount));
        }
        return true;
    }

    private void setEnergy(int energy) {
        int clamped = Math.max(0, Math.min(energy, getMaxEnergyStored()));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(TAG_ENERGY, clamped));
    }

    public CompoundTag savePreviewTag() {
        var tag = new CompoundTag();
        tag.putInt(TAG_ENERGY, getEnergyStored());
        return tag;
    }
}
