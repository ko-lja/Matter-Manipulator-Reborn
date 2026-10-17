package lu.kolja.matter_manipulator.common.build;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;

public record BuildPlan(ResourceKey<Level> dimension, List<BlockChange> changes, List<BlockChange> finalizers) {

    public BuildPlan {
        Objects.requireNonNull(dimension, "dimension");
        changes = List.copyOf(changes);
        finalizers = List.copyOf(finalizers);
        if (finalizers.stream().anyMatch(change -> change.kind() != BlockChange.Kind.CONFIGURE)) {
            throw new IllegalArgumentException("Build plan finalizers must be CONFIGURE changes");
        }
    }

    public BuildPlan(ResourceKey<Level> dimension, List<BlockChange> changes) {
        this(dimension, changes, List.of());
    }

    public int totalOperations() {
        return changes.size() + finalizers.size();
    }
}
