package lu.kolja.matter_manipulator.common.integration.rechiseled;

import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.build.BlockCost;

import com.supermartijn642.rechiseled.api.chiseling.ChiselingBlockShape;
import com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipe;
import com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipeManager;
import com.supermartijn642.rechiseled.api.chiseling.ItemWithWorth;
import com.supermartijn642.rechiseled.blocks.RechiseledBlock;
import com.supermartijn642.rechiseled.blocks.RechiseledSlabBlock;
import com.supermartijn642.rechiseled.blocks.RechiseledStairBlock;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class RechiseledIntegration {

    public static final Identifier VARIANTS_ID = Identifier.fromNamespaceAndPath("rechiseled", "variants");

    private RechiseledIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.placementCost(VARIANTS_ID, RechiseledIntegration::placementRequirements, state ->
                state.getBlock() instanceof RechiseledBlock
                        || state.getBlock() instanceof RechiseledSlabBlock
                        || state.getBlock() instanceof RechiseledStairBlock);
    }

    private static PlacementRequirements placementRequirements(IntegrationContext.Placement context) {
        if (!(BlockCost.of(context.target()) instanceof BlockCost.Items cost)) {
            return PlacementRequirements.defaults();
        }
        var recipes = ChiselingRecipeManager.get(false);
        var requirements = new ArrayList<ItemRequirement>();
        boolean widened = false;
        for (var requirement : cost.requirements()) {
            var exact = requirement.exact();
            var recipe = recipes.getRecipeForItem(exact.getItem());
            var others = recipe == null ? List.<Item>of() : interchangeable(recipe, exact.getItem());
            if (others.isEmpty()) {
                requirements.add(requirement);
                continue;
            }
            var stacks = new ArrayList<ItemStack>();
            stacks.add(exact);
            others.forEach(item -> stacks.add(new ItemStack(item, exact.getCount())));
            requirements.add(ItemRequirement.anyOf(stacks));
            widened = true;
        }
        return widened ? new PlacementRequirements(false, requirements, null) : PlacementRequirements.defaults();
    }

    private static List<Item> interchangeable(ChiselingRecipe recipe, Item item) {
        for (var entry : recipe.entries()) {
            for (var shape : ChiselingBlockShape.values()) {
                for (var own : new ItemWithWorth[] {entry.getRegularItem(shape), entry.getConnectingItem(shape)}) {
                    if (own != null && own.item() == item) {
                        return interchangeable(recipe, shape, own);
                    }
                }
            }
        }
        return List.of();
    }

    private static List<Item> interchangeable(ChiselingRecipe recipe, ChiselingBlockShape shape, ItemWithWorth own) {
        var items = new ArrayList<Item>();
        for (var entry : recipe.entries()) {
            for (var other : new ItemWithWorth[] {entry.getRegularItem(shape), entry.getConnectingItem(shape)}) {
                if (other != null && other.item() != own.item() && other.worth() == own.worth()
                        && !items.contains(other.item())) {
                    items.add(other.item());
                }
            }
        }
        return items;
    }
}
