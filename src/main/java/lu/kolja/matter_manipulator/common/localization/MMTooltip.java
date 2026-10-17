package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMTooltip implements LocalizationEnum {
    ENERGY("Energy: %s / %s FE"),
    TIER("Tier: %s"),
    BLUEPRINT_SIZE("Size: %s"),
    BLUEPRINT_HASH("Hash: %s"),
    BLUEPRINT_READ("Sneak and use to copy it into your blueprint library."),
    BLUEPRINT_BLANK("Blank. Use it with a Blueprint mode manipulator in your off hand."),
    MODE("Mode: %s"),
    SHAPE("Shape: %s"),
    REMOVE_MODE("Remove: %s"),
    CABLE_ROUTE("Cable Route: %s (%s points)"),
    FLOATING("Floating: %s"),
    TRANSFORM("Transform: %s"),
    AE_LINK("ME Link: %s @ %s, %s, %s"),
    RANGE("Range: %s"),
    RANGE_UNLIMITED("Unlimited"),
    SLOT("%s: %s"),
    BLUEPRINT_WRITTEN("item.matter_manipulator.blueprint.written", "Blueprint: %s");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMTooltip(String englishText) {
        this(null, englishText);
    }

    MMTooltip(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "matter_manipulator.tooltip." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
