package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum RemoveMode implements StringRepresentable, LocalizationEnum {
    NONE("none", "None"),
    REPLACEABLE("replaceable", "Replaceable"),
    ALL("all", "All");

    @Getter(onMethod_ = @Override)
    private final String serializedName;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    RemoveMode(String serializedName, String englishText) {
        this.serializedName = serializedName;
        this.englishText = englishText;
    }

    public RemoveMode next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String getTranslationKey() {
        return "matter_manipulator.remove." + serializedName;
    }

    public Component displayName() {
        return text();
    }

    public static RemoveMode byName(String name) {
        for (var mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return NONE;
    }
}
