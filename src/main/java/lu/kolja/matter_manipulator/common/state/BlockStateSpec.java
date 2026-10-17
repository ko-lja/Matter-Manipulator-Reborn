package lu.kolja.matter_manipulator.common.state;

import lu.kolja.matter_manipulator.common.build.PlanStats;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Dynamic;

public final class BlockStateSpec {

    private static final String TAG_BLOCK = "block";
    private static final String TAG_PROPERTIES = "properties";
    private static final Map<BlockState, BlockStateSpec> BY_STATE = new ConcurrentHashMap<>();

    private final ResourceLocation blockId;
    private final CompoundTag properties;
    private Optional<BlockState> resolved;

    public BlockStateSpec(ResourceLocation blockId, CompoundTag properties) {
        this.blockId = blockId;
        this.properties = properties.copy();
    }

    public static BlockStateSpec fromState(BlockState state) {
        var spec = BY_STATE.get(state);
        if (spec == null) {
            spec = create(state);
            var previous = BY_STATE.putIfAbsent(state, spec);
            spec = previous != null ? previous : spec;
        }
        return spec;
    }

    private static BlockStateSpec create(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var props = new CompoundTag();
        for (var entry : state.getValues().entrySet()) {
            props.putString(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue()));
        }
        return new BlockStateSpec(id, props);
    }

    public static Optional<BlockStateSpec> fromTag(CompoundTag tag) {
        if (!tag.contains(TAG_BLOCK)) {
            return Optional.empty();
        }
        var id = ResourceLocation.tryParse(tag.getString(TAG_BLOCK));
        if (id == null) {
            return Optional.empty();
        }
        var properties = tag.getCompound(TAG_PROPERTIES);
        return Optional.of(new BlockStateSpec(id, properties));
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString(TAG_BLOCK, blockId.toString());
        tag.put(TAG_PROPERTIES, properties.copy());
        return tag;
    }

    public BlockStateSpec upgraded(int dataVersion) {
        var tag = new CompoundTag();
        tag.putString("Name", blockId.toString());
        tag.put("Properties", properties.copy());
        var fixed = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag),
                dataVersion, SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
        var id = ResourceLocation.tryParse(fixed.getString("Name"));
        return id == null ? this : new BlockStateSpec(id, fixed.getCompound("Properties"));
    }

    public boolean isKnown() {
        return BuiltInRegistries.BLOCK.containsKey(blockId);
    }

    public boolean isAir() {
        return tryResolve().map(BlockState::isAir).orElse(false);
    }

    public Optional<BlockState> tryResolve() {
        var result = resolved;
        if (result == null) {
            result = parse();
            resolved = result;
        }
        return result;
    }

    private Optional<BlockState> parse() {
        PlanStats.count(PlanStats.Counter.SPEC_RESOLVES);
        if (!isKnown()) {
            return Optional.empty();
        }
        var state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
        for (var property : state.getProperties()) {
            if (properties.contains(property.getName())) {
                state = setProperty(state, property, properties.getString(property.getName()));
            }
        }
        return Optional.of(state);
    }

    public BlockState resolve() {
        return tryResolve().orElseThrow(() -> new IllegalStateException("Unknown block id " + blockId));
    }

    public Block block() {
        return resolve().getBlock();
    }

    public ResourceLocation blockId() {
        return blockId;
    }

    public String displayName() {
        return blockId.toString();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        var parsed = property.getValue(value);
        return parsed.map(propertyValue -> state.setValue(property, propertyValue)).orElse(state);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String propertyValueName(Property property, Comparable value) {
        return property.getName(value);
    }
}
