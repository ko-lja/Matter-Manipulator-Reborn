package lu.kolja.matter_manipulator.api;

import lu.kolja.matter_manipulator.common.util.InventoryUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Paid with the first one available: {@code exact}, {@code alternatives}, a stack {@code variants} accepts, then
 * {@code fallback}. {@code variants} must not modify stacks and must refuse any whose data is a resource.
 */
public record ItemRequirement(
        ItemStack exact,
        ItemStack fallback,
        String description,
        List<ItemStack> alternatives,
        @Nullable Predicate<ItemStack> variants
) {

    public ItemRequirement {
        exact = Objects.requireNonNull(exact, "exact").copy();
        fallback = Objects.requireNonNull(fallback, "fallback").copy();
        if (exact.isEmpty() || (!fallback.isEmpty() && fallback.getCount() != exact.getCount())) {
            throw new IllegalArgumentException("Requirement needs a nonempty exact stack and equal-count fallback");
        }
        var distinct = new ArrayList<ItemStack>(alternatives.size());
        for (var alternative : alternatives) {
            if (alternative.getCount() != exact.getCount()) {
                throw new IllegalArgumentException("Alternatives need the exact stack's count: " + alternative);
            }
            if (!InventoryUtil.isSameMaterial(alternative, exact)
                    && distinct.stream().noneMatch(seen -> InventoryUtil.isSameMaterial(seen, alternative))) {
                distinct.add(alternative.copy());
            }
        }
        alternatives = List.copyOf(distinct);
        description = description == null || description.isBlank()
                ? exact.getHoverName().getString()
                : description;
    }

    public ItemRequirement(ItemStack exact, ItemStack fallback, String description) {
        this(exact, fallback, description, List.of(), null);
    }

    public static ItemRequirement exact(ItemStack stack) {
        return new ItemRequirement(stack, ItemStack.EMPTY, stack.getHoverName().getString());
    }

    public static ItemRequirement withFallback(ItemStack exact, ItemStack fallback) {
        return new ItemRequirement(exact, fallback, exact.getHoverName().getString());
    }

    public static ItemRequirement anyOf(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("anyOf needs at least one stack");
        }
        var first = stacks.get(0);
        return new ItemRequirement(first, ItemStack.EMPTY, first.getHoverName().getString(),
                stacks.subList(1, stacks.size()), null);
    }

    /** Reads the tag immediately, so call it on the server and not during registration. Throws if the tag is empty. */
    public static ItemRequirement tag(TagKey<Item> tag, int count) {
        var stacks = new ArrayList<ItemStack>();
        for (var item : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            stacks.add(new ItemStack(item.value(), count));
        }
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("Item tag " + tag.location() + " has no items");
        }
        return anyOf(stacks);
    }

    /** Only for items whose data is never a resource. */
    public static ItemRequirement ignoringNbt(ItemStack stack) {
        return exact(stack).withVariants(variant -> true);
    }

    public ItemRequirement withVariants(Predicate<ItemStack> accepts) {
        return new ItemRequirement(exact, fallback, description, alternatives, Objects.requireNonNull(accepts, "accepts"));
    }

    public boolean accepts(ItemStack stack) {
        if (matches(exact, stack) || matches(fallback, stack)) {
            return true;
        }
        for (var alternative : alternatives) {
            if (matches(alternative, stack)) {
                return true;
            }
        }
        return stack.getCount() == exact.getCount() && acceptsVariant(stack);
    }

    public boolean acceptsVariant(ItemStack stack) {
        return variants != null && !stack.isEmpty() && stack.is(exact.getItem()) && variants.test(stack);
    }

    private static boolean matches(ItemStack option, ItemStack stack) {
        return !option.isEmpty() && !stack.isEmpty() && option.getCount() == stack.getCount()
                && InventoryUtil.isSameMaterial(option, stack);
    }

    @Override
    public ItemStack exact() {
        return exact.copy();
    }

    @Override
    public ItemStack fallback() {
        return fallback.copy();
    }

    @Override
    public List<ItemStack> alternatives() {
        return alternatives.stream().map(ItemStack::copy).toList();
    }
}
