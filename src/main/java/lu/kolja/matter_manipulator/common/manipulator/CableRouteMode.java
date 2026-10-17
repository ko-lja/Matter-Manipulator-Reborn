package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum CableRouteMode implements StringRepresentable, LocalizationEnum {
    PATHFIND("pathfind", "Pathfind"),
    EDGES("edges", "Edges");

    @Getter(onMethod_ = @Override)
    private final String serializedName;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    CableRouteMode(String serializedName, String englishText) {
        this.serializedName = serializedName;
        this.englishText = englishText;
    }

    public CableRouteMode next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String getTranslationKey() {
        return "matter_manipulator.cable_route." + serializedName;
    }

    public Component displayName() {
        return text();
    }

    public static CableRouteMode byName(String name) {
        for (var mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return PATHFIND;
    }
}
