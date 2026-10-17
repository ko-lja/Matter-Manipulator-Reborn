package lu.kolja.matter_manipulator.common.localization;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public interface LocalizationEnum {

    String getEnglishText();

    String getTranslationKey();

    default MutableComponent text() {
        return Component.translatable(getTranslationKey());
    }

    default MutableComponent text(Object... args) {
        return Component.translatable(getTranslationKey(), args);
    }
}
