package lu.kolja.matter_manipulator.datagen;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.build.BlockCost;
import lu.kolja.matter_manipulator.common.build.SkipReason;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import lu.kolja.matter_manipulator.common.localization.MMHud;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;
import lu.kolja.matter_manipulator.common.localization.MMKey;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.localization.MMScreen;
import lu.kolja.matter_manipulator.common.localization.MMScreenTooltip;
import lu.kolja.matter_manipulator.common.localization.MMTooltip;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import lu.kolja.matter_manipulator.common.registry.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class MMLanguage extends LanguageProvider {

    public MMLanguage(PackOutput output) {
        super(output, MatterManipulator.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.matter_manipulator", "Matter Manipulator");
        ModItems.ENGLISH_NAMES.forEach((item, name) -> add(item.get(), name));
        addAll(MMMessage.values());
        addAll(MMTooltip.values());
        addAll(MMHud.values());
        addAll(MMIntegrationText.values());
        addAll(MMScreen.values());
        addAll(MMScreenTooltip.values());
        addAll(MMKey.values());
        addAll(ManipulatorMode.values());
        addAll(ShapeType.values());
        addAll(RemoveMode.values());
        addAll(CableRouteMode.values());
        addAll(SkipReason.values());
        addAll(BlockCost.Reason.values());
    }

    private void addAll(LocalizationEnum[] texts) {
        for (var text : texts) {
            add(text.getTranslationKey(), text.getEnglishText());
        }
    }
}
