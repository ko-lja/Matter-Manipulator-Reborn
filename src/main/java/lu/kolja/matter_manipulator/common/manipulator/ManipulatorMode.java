package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

import java.util.List;

public enum ManipulatorMode implements StringRepresentable, LocalizationEnum {
    GEOMETRY("geometry", "Geometry"),
    CABLE("cable", "Cable"),
    EXCHANGE("exchange", "Exchange"),
    COPY("copy", "Copy"),
    MOVE("move", "Move"),
    BLUEPRINT("blueprint", "Blueprint");

    private static final List<ManipulatorMode> DISPLAY_ORDER = List.of(
            COPY,
            BLUEPRINT,
            CABLE,
            MOVE,
            EXCHANGE,
            GEOMETRY
    );

    @Getter(onMethod_ = @Override)
    private final String serializedName;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    ManipulatorMode(String serializedName, String englishText) {
        this.serializedName = serializedName;
        this.englishText = englishText;
    }

    public ManipulatorMode next() {
        int index = DISPLAY_ORDER.indexOf(this);
        return DISPLAY_ORDER.get((index + 1) % DISPLAY_ORDER.size());
    }

    public static List<ManipulatorMode> displayOrder() {
        return DISPLAY_ORDER;
    }

    @Override
    public String getTranslationKey() {
        return "matter_manipulator.mode." + serializedName;
    }

    public Component displayName() {
        return text();
    }

    public static ManipulatorMode byName(String name) {
        for (var mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return GEOMETRY;
    }
}
