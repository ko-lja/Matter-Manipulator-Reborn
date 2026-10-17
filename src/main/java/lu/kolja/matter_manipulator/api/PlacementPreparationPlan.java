package lu.kolja.matter_manipulator.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** A payload plus ordered optional components that items pay for. A funding update may only change its payload copy. */
public final class PlacementPreparationPlan {
    private final CompoundTag payload;
    private final List<Step> steps;

    private PlacementPreparationPlan(CompoundTag payload, List<Step> steps) {
        this.payload = Objects.requireNonNull(payload, "payload").copy();
        this.steps = List.copyOf(steps);
    }

    public static PlacementPreparationPlan unchanged(CompoundTag payload) {
        return new PlacementPreparationPlan(payload, List.of());
    }

    public static Builder builder(CompoundTag payload) {
        return new Builder(payload);
    }

    public List<ItemRequirement> requirements() {
        return steps.stream().map(Step::requirement).toList();
    }

    /**
     * The update gets the withdrawn stack, or EMPTY when unfunded. An unfunded component's update must also remove
     * its children's payload.
     */
    public CompoundTag prepare(ItemAcquirer items) {
        Objects.requireNonNull(items, "items");
        var output = payload.copy();
        var funded = new boolean[steps.size()];
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            if (step.parent() >= 0 && !funded[step.parent()]) {
                continue;
            }
            var acquired = Objects.requireNonNull(items.acquire(step.requirement()), "Acquisition returned null");
            if (!acquired.isEmpty() && !step.requirement().accepts(acquired)) {
                throw new IllegalStateException("Acquisition must return a complete stack the requirement accepts");
            }
            funded[index] = !acquired.isEmpty();
            step.update().accept(output, acquired.copy());
        }
        return output;
    }

    public static final class Builder {
        private final CompoundTag payload;
        private final List<Step> steps = new ArrayList<>();
        private boolean built;

        private Builder(CompoundTag payload) {
            this.payload = Objects.requireNonNull(payload, "payload").copy();
        }

        public Component component(ItemRequirement requirement, BiConsumer<CompoundTag, ItemStack> update) {
            return add(-1, requirement, update);
        }

        public PlacementPreparationPlan build() {
            ensureMutable();
            built = true;
            return new PlacementPreparationPlan(payload, steps);
        }

        private Component add(int parent, ItemRequirement requirement, BiConsumer<CompoundTag, ItemStack> update) {
            ensureMutable();
            steps.add(new Step(parent, Objects.requireNonNull(requirement, "requirement"),
                    Objects.requireNonNull(update, "update")));
            return new Component(this, steps.size() - 1);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("Placement preparation plan is already built");
            }
        }
    }

    public static final class Component {
        private final Builder builder;
        private final int index;

        private Component(Builder builder, int index) {
            this.builder = builder;
            this.index = index;
        }

        public Component component(ItemRequirement requirement, BiConsumer<CompoundTag, ItemStack> update) {
            return builder.add(index, requirement, update);
        }
    }

    private record Step(int parent, ItemRequirement requirement, BiConsumer<CompoundTag, ItemStack> update) {}
}
