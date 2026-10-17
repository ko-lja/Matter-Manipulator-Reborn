package lu.kolja.matter_manipulator.common.build;

import lombok.Getter;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

public sealed interface BlockCost permits BlockCost.Items, BlockCost.Companion, BlockCost.Unsupported {

    static BlockCost of(BlockState state) {
        PlanStats.count(PlanStats.Counter.BLOCK_COSTS);
        return BlockCostRules.resolve(state);
    }

    static BlockCost of(BlockStateSpec spec) {
        return spec.tryResolve().map(BlockCost::of).orElseGet(() -> unsupported(Reason.UNKNOWN_BLOCK));
    }

    static BlockCost items(List<ItemStack> items) {
        Objects.requireNonNull(items, "items");
        if (items.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
            throw new IllegalArgumentException("A placement cost needs nonempty stacks: " + items);
        }
        return new Items(items.stream().map(ItemRequirement::exact).toList());
    }

    static BlockCost unsupported(Reason reason) {
        return new Unsupported(reason);
    }

    boolean placeable();

    List<ItemRequirement> requirements();

    default List<ItemStack> items() {
        return requirements().stream().map(ItemRequirement::exact).toList();
    }

    record Items(List<ItemRequirement> requirements) implements BlockCost {

        public Items {
            Objects.requireNonNull(requirements, "requirements");
            if (requirements.isEmpty()) {
                throw new IllegalArgumentException("A placement cost needs at least one requirement");
            }
            requirements = List.copyOf(requirements);
        }

        @Override
        public boolean placeable() {
            return true;
        }
    }

    record Companion(Direction towardsPrimary, Predicate<BlockState> primary, List<ItemRequirement> requirements)
            implements BlockCost {

        public Companion {
            Objects.requireNonNull(towardsPrimary, "towardsPrimary");
            Objects.requireNonNull(primary, "primary");
            requirements = List.copyOf(requirements);
        }

        public Companion(Direction towardsPrimary, Predicate<BlockState> primary) {
            this(towardsPrimary, primary, List.of());
        }

        @Override
        public boolean placeable() {
            return true;
        }

        public BlockPos primaryPos(BlockPos pos) {
            return pos.relative(towardsPrimary);
        }

        public boolean isPrimary(BlockState state) {
            return state != null && primary.test(state);
        }

        public boolean hasPrimary(BlockGetter level, BlockPos pos) {
            return isPrimary(level.getBlockState(primaryPos(pos)));
        }

        Companion withRequirements(List<ItemRequirement> replacement) {
            return new Companion(towardsPrimary, primary, replacement);
        }
    }

    record Unsupported(Reason reason, @Nullable Component detail) implements BlockCost {

        public Unsupported {
            Objects.requireNonNull(reason, "reason");
        }

        public Unsupported(Reason reason) {
            this(reason, null);
        }

        public Component describe() {
            return detail != null ? detail : reason.text();
        }

        @Override
        public boolean placeable() {
            return false;
        }

        @Override
        public List<ItemRequirement> requirements() {
            throw new IllegalStateException("Unsupported placement (" + reason + ") has no item cost");
        }
    }

    enum Reason implements LocalizationEnum {
        AIR(false, "air is a removal, not a placement"),
        UNKNOWN_BLOCK(false, "the block is not registered"),
        NO_ITEM(true, "it has no item to pay with"),
        FLUID(false, "fluids cannot be created from nothing"),
        FIRE(false, "fire cannot be created from nothing"),
        PORTAL(false, "portals and gateways cannot be placed"),
        MOVING_PISTON(false, "moving piston blocks cannot be placed"),
        NO_FAITHFUL_COST(false, "its item cost cannot be derived from the block"),
        GROWTH_STAGE(false, "its growth stage cannot be paid for with items"),
        BUCKET_ITEM(false, "its item is a filled bucket"),
        INVALID_STATE(false, "the block state is not valid on its own"),
        EMPTY_REPLACEMENT(false, "an integration supplied no items for it");

        private final boolean integrationMayReplace;
        @Getter(onMethod_ = @Override)
        private final String englishText;

        Reason(boolean integrationMayReplace, String englishText) {
            this.integrationMayReplace = integrationMayReplace;
            this.englishText = englishText;
        }

        public boolean integrationMayReplace() {
            return integrationMayReplace;
        }

        @Override
        public String getTranslationKey() {
            return "matter_manipulator.block_cost." + name().toLowerCase(Locale.ROOT);
        }
    }
}
