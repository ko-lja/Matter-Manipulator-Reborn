package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum ShapeType implements StringRepresentable, LocalizationEnum {
    CUBOID("cuboid", "Cuboid"),
    LINE("line", "Line"),
    ELLIPSOID("ellipsoid", "Ellipsoid"),
    CYLINDER("cylinder", "Cylinder");

    @Getter(onMethod_ = @Override)
    private final String serializedName;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    ShapeType(String serializedName, String englishText) {
        this.serializedName = serializedName;
        this.englishText = englishText;
    }

    public ShapeType next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String getTranslationKey() {
        return "matter_manipulator.shape." + serializedName;
    }

    public Component displayName() {
        return text();
    }

    public static ShapeType byName(String name) {
        for (var shape : values()) {
            if (shape.serializedName.equals(name)) {
                return shape;
            }
        }
        return CUBOID;
    }
}
