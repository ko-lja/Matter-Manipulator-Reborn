package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

import java.util.Locale;

public enum MMScreenTooltip implements LocalizationEnum {
    MODE_GEOMETRY("screen.matter_manipulator.tooltip.mode.geometry",
            "Build a generated shape from the configured block slots."),
    MODE_CABLE("screen.matter_manipulator.tooltip.mode.cable",
            "Route the configured cable or pipe between selected points."),
    MODE_EXCHANGE("screen.matter_manipulator.tooltip.mode.exchange",
            "Replace matching blocks inside the selected region."),
    MODE_COPY("screen.matter_manipulator.tooltip.mode.copy", "Copy, transform, and place the selected structure."),
    MODE_MOVE("screen.matter_manipulator.tooltip.mode.move", "Move the selected structure with its contents."),
    MODE_LOCKED("Locked: this manipulator tier cannot use this mode."),
    MODE_BLUEPRINT("screen.matter_manipulator.tooltip.mode.blueprint",
            "Place, transform, and stack a saved blueprint."),
    FLOATING("Allow placement without support from adjacent blocks."),
    CLEAR("Clear all selected coordinates and cable route points."),
    SHAPE("Choose the generated geometry shape."),
    REMOVE("Choose which existing blocks may be removed or replaced."),
    PICK_VOLUME("Use the looked-at block as the active volume template."),
    CABLE_ROUTE("Cycle the cable routing strategy."),
    SEARCH_PROGRESS("Show cable pathfinding progress in the preview."),
    CABLE_PATH_LOG("Show cable pathfinding messages while routing."),
    PICK_CABLE("Use the looked-at block as the cable or pipe template."),
    PICK_TARGET("Use the looked-at block as the exchange target."),
    PICK_REPLACE("Use the looked-at block as the exchange match filter."),
    ROTATE_X("Rotate the copied structure 90 degrees around the X axis."),
    ROTATE_Y("Rotate the copied structure 90 degrees around the Y axis."),
    ROTATE_Z("Rotate the copied structure 90 degrees around the Z axis."),
    MIRROR_X("Mirror the copied structure across the X axis."),
    MIRROR_Y("Mirror the copied structure across the Y axis."),
    MIRROR_Z("Mirror the copied structure across the Z axis."),
    STACK_MINUS("Decrease the number of additional stacked copies."),
    STACK_AXIS("Cycle the axis used for stacked copies."),
    STACK_PLUS("Increase the number of additional stacked copies."),
    SAVE_BLUEPRINT("Save the blocks between A and B as a blueprint file in your library."),
    BLUEPRINT_SAVE("Save the blueprint under this name."),
    BLUEPRINT_CANCEL("Close the prompt without saving."),
    LIBRARY("Choose a blueprint from your library."),
    REFRESH("Read the blueprint folder again."),
    OPEN_FOLDER("Open the blueprint folder."),
    LIBRARY_DONE("Return to the blueprint controls."),
    LIBRARY_COPY("Copy the selected blueprint to the clipboard as text."),
    LIBRARY_COPY_NONE("Select a blueprint to copy it."),
    LIBRARY_LOAD("Load the selected blueprint into this manipulator. Double-click a row or press Enter to do the "
            + "same."),
    LIBRARY_LOAD_NONE("Select a blueprint to load it."),
    LIBRARY_LOAD_CURRENT("This manipulator already uses the selected blueprint (ticked)."),
    LIBRARY_LOAD_BUSY("Wait until the last blueprint you loaded has reached the manipulator."),
    LIBRARY_PASTE("Save a blueprint string from the clipboard to your library. This manipulator keeps its blueprint."),
    UNLOAD_BLUEPRINT("Unload the blueprint from this manipulator. The file stays in your library."),
    CLEAR_ANCHOR("Unpin the paste (C) so it follows the crosshair again."),
    AUTO_CRAFT_MISSING("After the operation finishes, request each missing material as a separate AE2 craft."),
    REQUEST_MISSING("Request the missing materials from AE2 without building anything."),
    PREVIEW("Show a preview of the result. Drag to rotate, scroll to zoom."),
    MATERIALS("Show all required items, with shortages sorted first."),
    RESET_VIEW("Refit the preview and reset its camera."),
    START_OPERATION("Start the configured Matter Manipulator operation."),
    SETTINGS("Change this client's theme colors."),
    COLOR_ACCENT("screen.matter_manipulator.tooltip.color.accent",
            "Color for the start button, switches, and progress bars."),
    COLOR_BUTTONS("screen.matter_manipulator.tooltip.color.buttons", "Color for buttons and dropdown lists."),
    COLOR_INTERFACE("screen.matter_manipulator.tooltip.color.interface",
            "Tint for the panel surfaces and borders."),
    COLOR_RESET("Restore all default theme colors."),
    CUSTOM("Use the color typed in the field next to it."),
    DONE("Save the colors and return to the operation controls."),
    PLACEMENT_SPEED("Blocks placed per second, up to the limit of this tier. Higher speeds may lag the server a "
            + "little."),
    GUI_OPACITY("How solid the panel is. Lower values show the world behind it.");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMScreenTooltip(String englishText) {
        this(null, englishText);
    }

    MMScreenTooltip(String translationKey, String englishText) {
        this.translationKey = translationKey != null
                ? translationKey
                : "screen.matter_manipulator.tooltip." + name().toLowerCase(Locale.ROOT);
        this.englishText = englishText;
    }
}
