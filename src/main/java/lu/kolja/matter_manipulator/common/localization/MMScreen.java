package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMScreen implements LocalizationEnum {
    TITLE("Matter Manipulator"),
    OPERATION("OPERATION"),
    ENERGY("Energy"),
    START("Start"),
    SHAPE("Shape"),
    REMOVE("Remove"),
    CLEAR_SHORT("Clear"),
    FLOATING("Floating"),
    PICK_VOLUME("Pick Volume"),
    PICK_CABLE("Pick Cable"),
    PICK_TARGET("Pick Target"),
    PICK_REPLACE("Pick Replace"),
    SEARCH_PROGRESS("Search Debug"),
    SEARCH_PROGRESS_SHORT("Search"),
    CABLE_PATH_LOG("Path Log"),
    CABLE_ROUTE("Routing"),
    CABLE_ROUTE_POINTS("Route points: %s"),
    START_MODE("Start %s"),
    REQUEST_MISSING("Request missing"),
    REQUEST_MISSING_SHORT("Request"),
    PREVIEW("Preview"),
    LIVE_PROJECTION("PREVIEW"),
    MATERIALS("Materials"),
    PREFLIGHT("MATERIALS"),
    RESET_VIEW("Reset view"),
    PREVIEW_EMPTY("Complete the selection to preview the result."),
    PREVIEW_SAMPLED("Preview sampled: the operation contains more blocks."),
    MATERIALS_NONE("This operation requires no items."),
    OPERATIONS_COUNT("%s operations"),
    MOVE_NOTICE("The source blocks are moved."),
    MATERIAL_AVAILABLE("Available: %s / Required: %s"),
    MATERIAL_FALLBACK("Fallback accepted: %s"),
    MATERIAL_ALTERNATIVES("Also accepted: %s"),
    MATERIAL_ALTERNATIVES_MORE("Also accepted: %s and %s more"),
    MATERIAL_VARIANTS("Also accepted: %s with other data"),
    MATERIAL_MISSING("Missing %s"),
    STATUS_LOADING("screen.matter_manipulator.status.loading", "Calculating requirements..."),
    STATUS_INVALID("screen.matter_manipulator.status.invalid", "Operation incomplete"),
    STATUS_SUMMARY("screen.matter_manipulator.status.summary", "%s ops, %s missing"),
    MATERIAL_PROGRESS("%s%% AVAILABLE"),
    STATUS_TOOL_MISSING("screen.matter_manipulator.status.tool_missing",
            "The configured manipulator is no longer held."),
    STATUS_REFRESHING("screen.matter_manipulator.status.refreshing", "Tool state changed; refreshing..."),
    AUTO_CRAFT_MISSING("Auto-craft"),
    SELECTION("Selection"),
    PLACEMENT("Placement"),
    CONFIGURATION("Configuration"),
    TRANSFORM("Transform"),
    ROTATE("Rotate"),
    MIRROR("Mirror"),
    STACK("Stack"),
    SAVE_BLUEPRINT("Save blueprint"),
    SAVE_BLUEPRINT_SHORT("Save"),
    BLUEPRINT_NAME("Name"),
    LIBRARY("Library"),
    LIBRARY_SHORT("Load"),
    LIBRARY_DESCRIPTION("Choose a blueprint for this manipulator from your blueprint folder."),
    LIBRARY_EMPTY("No blueprints yet. Save one in Copy mode, or put .mmbp files into this folder:"),
    REFRESH("Refresh"),
    OPEN_FOLDER("Open folder"),
    LIBRARY_COPY("Copy"),
    LIBRARY_PASTE("Paste"),
    LIBRARY_LOAD("Load"),
    LIBRARY_PREVIEW_NONE("Select a blueprint to preview it."),
    LIBRARY_PREVIEW_READING("Reading %s..."),
    LIBRARY_PREVIEW_TOO_LARGE("Too large to preview here (over %s blocks)."),
    LIBRARY_PREVIEW_EMPTY("This blueprint has no blocks this game can place."),
    BLUEPRINT_NONE("NO BLUEPRINT"),
    BLUEPRINT_UPLOADING("Uploading %s..."),
    BLUEPRINT_NOT_IN_LIBRARY("This blueprint is not in your library, so it cannot be sent to the server again after "
            + "you reconnect."),
    BLUEPRINT_MISSING_MODS("Needs mods that are not installed: %s. Their blocks are skipped."),
    SAVE("Save"),
    CANCEL("Cancel"),
    STACK_COUNT("%s on %s"),
    NOT_SET("NOT SET"),
    SETTINGS("Settings"),
    APPEARANCE_DESCRIPTION("Recolor the accent, the buttons and the interface."),
    COLOR_ACCENT("screen.matter_manipulator.color.accent", "Accent"),
    COLOR_BUTTONS("screen.matter_manipulator.color.buttons", "Buttons"),
    COLOR_INTERFACE("screen.matter_manipulator.color.interface", "Interface"),
    PRESETS("Presets"),
    CUSTOM("Custom"),
    COLOR_HEX("screen.matter_manipulator.color.hex", "Hex color"),
    COLOR_RESET("Reset Default"),
    COLOR_RESET_SHORT("Reset"),
    DONE("Done"),
    PLACEMENT_SPEED("Placement speed"),
    PLACEMENT_SPEED_VALUE("%s blocks/s"),
    GUI_OPACITY("Opacity"),
    GUI_OPACITY_VALUE("%s%%"),
    LOCKED("Locked"),
    ESCAPE_HINT("Esc to close"),
    MODE_DESCRIPTION_GEOMETRY("screen.matter_manipulator.mode_description.geometry",
            "Build a generated shape from configured block slots."),
    MODE_DESCRIPTION_CABLE("screen.matter_manipulator.mode_description.cable",
            "Route a cable or pipe between two endpoints."),
    MODE_DESCRIPTION_EXCHANGE("screen.matter_manipulator.mode_description.exchange",
            "Replace matching blocks inside the selected region."),
    MODE_DESCRIPTION_COPY("screen.matter_manipulator.mode_description.copy",
            "Copy and transform the selected structure."),
    MODE_DESCRIPTION_MOVE("screen.matter_manipulator.mode_description.move", "Move the selected structure with its contents."),
    MODE_DESCRIPTION_BLUEPRINT("screen.matter_manipulator.mode_description.blueprint",
            "Place a blueprint from your library."),
    ON("On"),
    OFF("Off"),
    PREVIEW_CONTROLS("Drag to orbit, scroll to zoom");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMScreen(String englishText) {
        this(null, englishText);
    }

    MMScreen(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "screen.matter_manipulator." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
