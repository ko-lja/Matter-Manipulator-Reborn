package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.MatterManipulator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class IntegrationFailures {

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private IntegrationFailures() {}

    static void report(ResourceLocation id, String operation, BlockState state, Throwable exception) {
        var key = id + ":" + operation;
        if (!REPORTED.add(key)) {
            return;
        }
        var blockId = state == null ? "<none>" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        MatterManipulator.LOGGER.error("Integration {} failed during {} for {}; using fallback behavior", id, operation, blockId, exception);
    }

    static void clear() {
        REPORTED.clear();
    }

    static int reportedCount() {
        return REPORTED.size();
    }
}
