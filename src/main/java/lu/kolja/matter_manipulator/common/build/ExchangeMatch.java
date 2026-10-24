package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ExchangeMatch {

    private static final List<ToolTransform> ROTATIONS = rotations();

    private final BlockState match;
    private final BlockState target;
    private final Set<Property<?>> orientation;
    private final Map<BlockState, Optional<BlockState>> targets = new IdentityHashMap<>();

    public ExchangeMatch(BlockState match, BlockState target) {
        this.match = match;
        this.target = target;
        this.orientation = match.getBlock() == target.getBlock() ? Set.of() : orientationProperties(match);
    }

    public Optional<BlockState> target(BlockState existing) {
        if (existing.getBlock() != match.getBlock()) {
            return Optional.empty();
        }
        if (orientation.isEmpty()) {
            return existing == match ? Optional.of(target) : Optional.empty();
        }
        return targets.computeIfAbsent(existing, this::orientedTarget);
    }

    private Optional<BlockState> orientedTarget(BlockState existing) {
        for (var property : match.getProperties()) {
            if (!orientation.contains(property) && !existing.getValue(property).equals(match.getValue(property))) {
                return Optional.empty();
            }
        }
        var result = target;
        for (var property : orientation) {
            result = withValue(result, existing, property);
        }
        return Optional.of(result);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, BlockState source, Property<T> property) {
        var own = state.getBlock().getStateDefinition().getProperty(property.getName());
        return own == null ? state : withNamedValue(state, own, property.getName(source.getValue(property)));
    }

    private static <T extends Comparable<T>> BlockState withNamedValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    private static Set<Property<?>> orientationProperties(BlockState state) {
        var properties = new HashSet<Property<?>>();
        for (var rotation : ROTATIONS) {
            var rotated = IntegrationDispatcher.transformState(state, rotation).orElse(null);
            if (rotated == null || rotated.getBlock() != state.getBlock()) {
                continue;
            }
            for (var property : state.getProperties()) {
                if (!rotated.getValue(property).equals(state.getValue(property))) {
                    properties.add(property);
                }
            }
        }
        return properties;
    }

    private static List<ToolTransform> rotations() {
        var rotations = new ArrayList<ToolTransform>();
        var seen = new HashSet<List<Direction>>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 0; y < 4; y++) {
                    var rotation = new ToolTransform(x, y, z, false, false, false, 1, ToolTransform.IDENTITY.stackAxis());
                    if (seen.add(List.of(rotation.transform(Direction.EAST), rotation.transform(Direction.UP)))) {
                        rotations.add(rotation);
                    }
                }
            }
        }
        return List.copyOf(rotations);
    }
}
