package lu.kolja.matter_manipulator.datagen;

import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class MMDataGenerators {

    private MMDataGenerators() {}

    public static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(MMItemModels::new);
        event.createProvider(MMLanguage::new);
        event.createProvider(MMBlockTags::new);
        event.createProvider(MMRecipes.Runner::new);
    }
}
