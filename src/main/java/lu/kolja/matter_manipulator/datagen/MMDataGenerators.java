package lu.kolja.matter_manipulator.datagen;

import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class MMDataGenerators {

    private MMDataGenerators() {}

    public static void gatherData(GatherDataEvent event) {
        var generator = event.getGenerator();
        var output = generator.getPackOutput();
        var files = event.getExistingFileHelper();
        generator.addProvider(event.includeClient(), new MMItemModels(output, files));
        generator.addProvider(event.includeClient(), new MMLanguage(output));
        generator.addProvider(event.includeServer(), new MMBlockTags(output, event.getLookupProvider(), files));
        generator.addProvider(event.includeServer(), new MMRecipes(output, event.getLookupProvider()));
    }
}
