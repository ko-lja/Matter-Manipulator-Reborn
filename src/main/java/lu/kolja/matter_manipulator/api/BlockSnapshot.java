package lu.kolja.matter_manipulator.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/** Immutable. The NBT accessors return owned copies. */
public final class BlockSnapshot {
    private final BlockState state;
    private final CompoundTag blockEntity;
    private final CompoundTag payloads;

    public BlockSnapshot(BlockState state, CompoundTag blockEntity, CompoundTag payloads) {
        this.state = Objects.requireNonNull(state, "state");
        this.blockEntity = Objects.requireNonNull(blockEntity, "blockEntity").copy();
        this.payloads = Objects.requireNonNull(payloads, "payloads").copy();
    }

    public BlockState state() {
        return state;
    }

    public CompoundTag blockEntity() {
        return blockEntity.copy();
    }

    /** Empty when this integration has no saved data. Other integrations' data is read-only. */
    public CompoundTag payload(ResourceLocation id) {
        return payloads.getCompound(Objects.requireNonNull(id, "id").toString()).copy();
    }
}
