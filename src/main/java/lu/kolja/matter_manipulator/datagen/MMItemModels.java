package lu.kolja.matter_manipulator.datagen;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.registry.ModItems;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.data.PackOutput;

import java.util.List;

public class MMItemModels extends ModelProvider {

    public MMItemModels(PackOutput output) {
        super(output, MatterManipulator.MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        itemModels.generateFlatItem(ModItems.BLUEPRINT.get(), ModelTemplates.FLAT_ITEM);

        var manipulators = List.of(ModItems.PROTOTYPE, ModItems.MKI, ModItems.MKII, ModItems.MKIII);
        for (int i = 0; i < manipulators.size(); i++) {
            var item = manipulators.get(i).get();
            var model = ModelLocationUtils.getModelLocation(item);
            itemModels.generateLayeredItem(model, new Material(MatterManipulator.id("item/matter_manipulator_frame")),
                    new Material(MatterManipulator.id("item/matter_manipulator_" + i + "_base")),
                    new Material(MatterManipulator.id("item/matter_manipulator_" + i + "_overlay")));
            itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(model));
        }
    }
}
