package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMHud implements LocalizationEnum {
    ANCHOR_FOLLOWING("Following %s"),
    ANCHOR_PINNED("Pinned %s"),
    ANCHOR_OUT_OF_RANGE("Out of range %s"),
    SELECTION_SIZE("%s x %s x %s (%s blocks)"),
    SCROLL_MOVE_ANCHOR("Scroll: move paste %s"),
    SCROLL_MOVE_SELECTION("Scroll: move selection %s"),
    SCROLL_RESIZE("Scroll: resize %s face"),
    SCROLL_DISTANCE("Scroll: distance %s");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMHud(String englishText) {
        this(null, englishText);
    }

    MMHud(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "matter_manipulator.hud." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
