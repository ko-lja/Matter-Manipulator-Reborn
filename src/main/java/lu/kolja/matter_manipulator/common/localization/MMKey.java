package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMKey implements LocalizationEnum {
    OPEN_MENU("Open Manipulator Menu"),
    PICK_BLOCK("Pick Block"),
    COPY("Copy"),
    CUT("Cut"),
    PASTE("Paste"),
    NUDGE_MODIFIER("Nudge modifier"),
    RESIZE_MODIFIER("Resize modifier"),
    CYCLE_MODE("Cycle Operation Mode"),
    MODE_GEOMETRY("Select Geometry Mode"),
    MODE_CABLE("Select Cable Mode"),
    MODE_EXCHANGE("Select Exchange Mode"),
    MODE_COPY("Select Copy Mode"),
    MODE_MOVE("Select Move Mode"),
    MODE_BLUEPRINT("Select Blueprint Mode"),
    CYCLE_SHAPE("Cycle Shape"),
    CYCLE_REMOVE("Cycle Removal Mode"),
    CYCLE_CABLE_ROUTE("Cycle Cable Route Mode"),
    CLEAR_SELECTION("Clear Selection"),
    TOGGLE_FLOATING_PLACEMENT("Toggle Floating Placement"),
    TOGGLE_AUTO_CRAFT_MISSING("Toggle Auto-craft Missing"),
    ROTATE_X("Rotate on X Axis"),
    ROTATE_Y("Rotate on Y Axis"),
    ROTATE_Z("Rotate on Z Axis"),
    MIRROR_X("Mirror on X Axis"),
    MIRROR_Y("Mirror on Y Axis"),
    MIRROR_Z("Mirror on Z Axis"),
    STACK_MINUS("Decrease Stack Count"),
    STACK_AXIS("Cycle Stack Axis"),
    STACK_PLUS("Increase Stack Count"),
    REQUEST_MISSING("Request Missing Materials"),
    START_OPERATION("Start Operation"),
    TOGGLE_CABLE_SEARCH_PROGRESS("Toggle Cable Search Progress"),
    TOGGLE_CABLE_PATH_MESSAGES("Toggle Cable Path Messages"),
    CATEGORY("key.categories.matter_manipulator", "Matter Manipulator");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMKey(String englishText) {
        this(null, englishText);
    }

    MMKey(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "key.matter_manipulator." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
