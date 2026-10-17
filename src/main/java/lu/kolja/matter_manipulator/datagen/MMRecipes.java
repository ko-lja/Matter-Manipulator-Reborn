package lu.kolja.matter_manipulator.datagen;

import lu.kolja.matter_manipulator.common.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;

public class MMRecipes extends RecipeProvider {

    public MMRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
        super(output, lookup);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.PROTOTYPE.get())
                .pattern("IGI")
                .pattern("RTR")
                .pattern("IBI")
                .define('I', Items.IRON_INGOT)
                .define('G', Items.GLASS_PANE)
                .define('R', Items.REDSTONE)
                .define('T', Items.REDSTONE_TORCH)
                .define('B', Items.IRON_BLOCK)
                .unlockedBy("has_redstone", has(Items.REDSTONE))
                .save(output);

        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.MKI.get())
                .pattern("GDG")
                .pattern("RPR")
                .pattern("GDG")
                .define('G', Items.GOLD_INGOT)
                .define('D', Items.DIAMOND)
                .define('R', Items.REDSTONE_BLOCK)
                .define('P', ModItems.PROTOTYPE.get())
                .unlockedBy("has_prototype", has(ModItems.PROTOTYPE.get()))
                .save(output);

        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.MKII.get())
                .pattern("EDE")
                .pattern("DMD")
                .pattern("ENE")
                .define('E', Items.ENDER_EYE)
                .define('D', Items.DIAMOND_BLOCK)
                .define('M', ModItems.MKI.get())
                .define('N', Items.NETHERITE_INGOT)
                .unlockedBy("has_mki", has(ModItems.MKI.get()))
                .save(output);

        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, ModItems.MKIII.get())
                .pattern("NSN")
                .pattern("HMH")
                .pattern("NCN")
                .define('N', Items.NETHERITE_INGOT)
                .define('S', Items.NETHER_STAR)
                .define('H', Items.SHULKER_SHELL)
                .define('M', ModItems.MKII.get())
                .define('C', Items.END_CRYSTAL)
                .unlockedBy("has_mkii", has(ModItems.MKII.get()))
                .save(output);

        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, ModItems.BLUEPRINT.get())
                .requires(Items.PAPER)
                .requires(Items.LAPIS_LAZULI)
                .unlockedBy("has_paper", has(Items.PAPER))
                .save(output);
    }
}
