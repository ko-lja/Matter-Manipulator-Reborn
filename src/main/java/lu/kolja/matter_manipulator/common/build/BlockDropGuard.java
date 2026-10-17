package lu.kolja.matter_manipulator.common.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class BlockDropGuard {

    public enum Mode {
        SUPPRESS,
        CAPTURE
    }

    private static final ThreadLocal<List<Scope>> ACTIVE = ThreadLocal.withInitial(ArrayList::new);

    private BlockDropGuard() {}

    public static Scope suppress(ResourceKey<Level> dimension, LongSet positions) {
        return open(Mode.SUPPRESS, dimension, positions);
    }

    public static Scope capture(ResourceKey<Level> dimension, LongSet positions) {
        return open(Mode.CAPTURE, dimension, positions);
    }

    private static Scope open(Mode mode, ResourceKey<Level> dimension, LongSet positions) {
        var scope = new Scope(mode, dimension, positions);
        ACTIVE.get().add(scope);
        return scope;
    }

    public static boolean intercept(ResourceKey<Level> dimension, BlockPos pos, ItemStack stack) {
        long packed = pos.asLong();
        var active = ACTIVE.get();
        for (int index = active.size() - 1; index >= 0; index--) {
            var scope = active.get(index);
            if (scope.dimension.equals(dimension) && scope.positions.contains(packed)) {
                if (scope.mode == Mode.CAPTURE && !stack.isEmpty()) {
                    scope.captured.add(stack.copy());
                }
                return true;
            }
        }
        return false;
    }

    public static final class Scope implements AutoCloseable {

        private final Mode mode;
        private final ResourceKey<Level> dimension;
        private final LongSet positions;
        private final List<ItemStack> captured = new ArrayList<>();

        private Scope(Mode mode, ResourceKey<Level> dimension, LongSet positions) {
            this.mode = mode;
            this.dimension = dimension;
            this.positions = positions;
        }

        public Mode mode() {
            return mode;
        }

        public List<ItemStack> takeCaptured() {
            var taken = List.copyOf(captured);
            captured.clear();
            return taken;
        }

        @Override
        public void close() {
            ACTIVE.get().remove(this);
        }
    }
}
