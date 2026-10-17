package lu.kolja.matter_manipulator.datagen;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.List;

public class MMItemModels extends ItemModelProvider {

    public MMItemModels(PackOutput output, ExistingFileHelper files) {
        super(output, MatterManipulator.MOD_ID, files);
    }

    @Override
    protected void registerModels() {
        singleTexture(ModItems.BLUEPRINT.getId().getPath(), mcLoc("item/generated"), "layer0", modLoc("item/blueprint"));

        var manipulators = List.of(ModItems.PROTOTYPE, ModItems.MKI, ModItems.MKII, ModItems.MKIII);
        for (int i = 0; i < manipulators.size(); i++) {
            getBuilder(manipulators.get(i).getId().getPath())
                    .parent(new ModelFile.UncheckedModelFile(mcLoc("item/generated")))
                    .texture("layer0", modLoc("item/matter_manipulator_frame"))
                    .texture("layer1", modLoc("item/matter_manipulator_" + i + "_base"))
                    .texture("layer2", modLoc("item/matter_manipulator_" + i + "_overlay"));
        }
    }
}
