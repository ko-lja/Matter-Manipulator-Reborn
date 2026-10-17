package lu.kolja.matter_manipulator.common.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MMConfig {

    public static final int DEFAULT_GUI_ACCENT_RGB = 0x3C8527;
    public static final int DEFAULT_GUI_BUTTON_RGB = 0xD0D1D4;
    public static final int DEFAULT_GUI_INTERFACE_RGB = 0x48494A;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.ConfigValue<String> GUI_ACCENT_COLOR;
    public static final ModConfigSpec.ConfigValue<String> GUI_BUTTON_COLOR;
    public static final ModConfigSpec.ConfigValue<String> GUI_INTERFACE_COLOR;
    public static final ModConfigSpec.IntValue GUI_OPACITY;
    public static final ModConfigSpec.BooleanValue SHOW_CABLE_SEARCH_PROGRESS;
    public static final ModConfigSpec.BooleanValue CABLE_SEARCH_MESSAGES;
    public static final ModConfigSpec.IntValue MAX_PREVIEW_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_CABLE_PREVIEW_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_PREVIEW_INSPECTIONS;
    public static final ModConfigSpec.IntValue CABLE_PATH_STEPS_PER_TICK;
    public static final ModConfigSpec.IntValue WORLD_PREVIEW_REFRESH_TICKS;
    public static final ModConfigSpec.IntValue MAX_SEARCH_PROGRESS_BOXES;
    public static final ModConfigSpec.IntValue MAX_SEARCH_FRONTIER_BOXES;
    public static final ModConfigSpec.IntValue MAX_CABLE_FLOW_MARKERS;
    public static final ModConfigSpec.IntValue PREVIEW_RULER_LENGTH;
    public static final ModConfigSpec.IntValue GHOST_ALPHA;
    public static final ModConfigSpec SERVER_SPEC;
    public static final int SYNCHRONOUS_PLAN_CEILING = 262_144;
    public static final int REGION_VOLUME_CEILING = 16_777_216;
    public static final int BLUEPRINT_BYTES_CEILING = 64 << 20;
    public static final ModConfigSpec.IntValue MAX_QUEUED_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_REGION_VOLUME;
    public static final ModConfigSpec.IntValue MAX_ATOMIC_MOVE_GROUP;
    public static final ModConfigSpec.IntValue MAX_BLUEPRINT_BYTES;
    public static final ModConfigSpec.IntValue PROTOTYPE_FE_PER_BLOCK;
    public static final ModConfigSpec.IntValue MKI_FE_PER_BLOCK;
    public static final ModConfigSpec.IntValue MKII_FE_PER_BLOCK;
    public static final ModConfigSpec.IntValue MKIII_FE_PER_BLOCK;

    static {
        var clientBuilder = new ModConfigSpec.Builder();

        clientBuilder.push("interface");
        GUI_ACCENT_COLOR = clientBuilder
                .comment("Matter Manipulator GUI accent in #RRGGBB format.")
                .define("accentColor", formatGuiColor(DEFAULT_GUI_ACCENT_RGB), MMConfig::isGuiColor);
        GUI_BUTTON_COLOR = clientBuilder
                .comment("Matter Manipulator button color in #RRGGBB format.")
                .define("buttonColor", formatGuiColor(DEFAULT_GUI_BUTTON_RGB), MMConfig::isGuiColor);
        GUI_INTERFACE_COLOR = clientBuilder
                .comment("Matter Manipulator panel and border tint in #RRGGBB format.")
                .define("interfaceColor", formatGuiColor(DEFAULT_GUI_INTERFACE_RGB), MMConfig::isGuiColor);
        GUI_OPACITY = clientBuilder
                .comment("Matter Manipulator panel opacity in percent; 0 shows the world through the panel.")
                .defineInRange("opacity", 100, 0, 100);
        clientBuilder.pop();

        clientBuilder.push("preview");
        SHOW_CABLE_SEARCH_PROGRESS = clientBuilder
                .comment("Render cable pathfinder visited/frontier diagnostics in the world.")
                .define("showCableSearchProgress", false);
        CABLE_SEARCH_MESSAGES = clientBuilder
                .comment("Show cable path search completion and failure messages in chat.")
                .define("cableSearchMessages", true);
        MAX_PREVIEW_BLOCKS = clientBuilder
                .comment("Maximum non-cable ghost blocks prepared for world and GUI previews. Larger previews render"
                        + " with less detail (simplified blocks above 10000, occupancy bricks above 50000).")
                .defineInRange("maxPreviewBlocks", 131_072, 256, 262_144);
        MAX_CABLE_PREVIEW_BLOCKS = clientBuilder
                .comment("Maximum cable or pipe ghost blocks prepared for world and GUI previews.")
                .defineInRange("maxCablePreviewBlocks", 8_192, 256, 131_072);
        MAX_PREVIEW_INSPECTIONS = clientBuilder
                .comment("Largest exchange, copy or move source box (in blocks) the preview inspects. It is read over"
                        + " several frames; a larger box previews its bounds only.")
                .defineInRange("maxPreviewInspections", 262_144, 256, 16_777_216);
        CABLE_PATH_STEPS_PER_TICK = clientBuilder
                .comment("Cable pathfinder work performed per client tick while preparing a preview.")
                .defineInRange("cablePathStepsPerTick", 512, 16, 65_536);
        WORLD_PREVIEW_REFRESH_TICKS = clientBuilder
                .comment("Ticks between refreshes for world-sensitive exchange, copy, and move previews.")
                .defineInRange("worldPreviewRefreshTicks", 10, 1, 200);
        MAX_SEARCH_PROGRESS_BOXES = clientBuilder
                .comment("Maximum visited-node boxes drawn by cable search diagnostics.")
                .defineInRange("maxSearchProgressBoxes", 4_096, 1, 65_536);
        MAX_SEARCH_FRONTIER_BOXES = clientBuilder
                .comment("Maximum frontier boxes drawn by cable search diagnostics.")
                .defineInRange("maxSearchFrontierBoxes", 512, 1, 16_384);
        MAX_CABLE_FLOW_MARKERS = clientBuilder
                .comment("Maximum animated direction markers drawn along cable previews.")
                .defineInRange("maxCableFlowMarkers", 512, 1, 8_192);
        PREVIEW_RULER_LENGTH = clientBuilder
                .comment("Length in blocks of the world-preview axis rulers.")
                .defineInRange("rulerLength", 16, 1, 128);
        GHOST_ALPHA = clientBuilder
                .comment("Maximum alpha of rendered ghost block models.")
                .defineInRange("ghostAlpha", 128, 16, 255);
        clientBuilder.pop();

        CLIENT_SPEC = clientBuilder.build();

        var builder = new ModConfigSpec.Builder();

        builder.push("building");
        MAX_QUEUED_BLOCKS = builder
                .comment("Hard cap for a single queued manipulator operation. Planning is synchronous on the server"
                        + " thread, so values above " + SYNCHRONOUS_PLAN_CEILING + " are clamped to it at runtime.")
                .defineInRange("maxQueuedBlocks", 131_072, 1, 5_000_000);
        MAX_REGION_VOLUME = builder
                .comment("Largest bounding-box volume that exchange, copy, and move may scan while planning."
                        + " Checked before any block is inspected.")
                .defineInRange("maxRegionVolume", 262_144, 1, REGION_VOLUME_CEILING);
        MAX_ATOMIC_MOVE_GROUP = builder
                .comment("Largest group of connected blocks (doors, rails and their supports, redstone, fluids, ...) that"
                        + " a move transfers at once, within one server tick. Larger groups are left in place and"
                        + " reported when the move is planned.")
                .defineInRange("maxAtomicMoveGroup", 512, 1, 8_192);
        MAX_BLUEPRINT_BYTES = builder
                .comment("Largest blueprint, in uncompressed bytes, that a player may upload or the server saves or"
                        + " reads. A blueprint also holds at most maxRegionVolume blocks.")
                .defineInRange("maxBlueprintBytes", 8 << 20, 64 << 10, BLUEPRINT_BYTES_CEILING);
        PROTOTYPE_FE_PER_BLOCK = builder.defineInRange("prototypeFePerBlock", 100, 0, 1_000_000);
        MKI_FE_PER_BLOCK = builder.defineInRange("mkiFePerBlock", 250, 0, 1_000_000);
        MKII_FE_PER_BLOCK = builder.defineInRange("mkiiFePerBlock", 500, 0, 1_000_000);
        MKIII_FE_PER_BLOCK = builder.defineInRange("mkiiiFePerBlock", 1_000, 0, 1_000_000);
        builder.pop();

        SERVER_SPEC = builder.build();
    }

    private MMConfig() {}

    public static int maxQueuedBlocks() {
        var configured = SERVER_SPEC.isLoaded() ? MAX_QUEUED_BLOCKS.get() : MAX_QUEUED_BLOCKS.getDefault();
        return effectiveQueueLimit(configured);
    }

    public static int maxAtomicMoveGroup() {
        return SERVER_SPEC.isLoaded() ? MAX_ATOMIC_MOVE_GROUP.get() : MAX_ATOMIC_MOVE_GROUP.getDefault();
    }

    public static int maxBlueprintBytes() {
        return SERVER_SPEC.isLoaded() ? MAX_BLUEPRINT_BYTES.get() : MAX_BLUEPRINT_BYTES.getDefault();
    }

    static int effectiveQueueLimit(int configured) {
        return Math.max(1, Math.min(configured, SYNCHRONOUS_PLAN_CEILING));
    }

    public static int guiAccentColor() {
        return guiColor(GUI_ACCENT_COLOR, DEFAULT_GUI_ACCENT_RGB);
    }

    public static int guiButtonColor() {
        return guiColor(GUI_BUTTON_COLOR, DEFAULT_GUI_BUTTON_RGB);
    }

    public static int guiInterfaceColor() {
        return guiColor(GUI_INTERFACE_COLOR, DEFAULT_GUI_INTERFACE_RGB);
    }

    public static void setGuiThemeColors(int accentRgb, int buttonRgb, int interfaceRgb) {
        GUI_ACCENT_COLOR.set(formatGuiColor(accentRgb));
        GUI_BUTTON_COLOR.set(formatGuiColor(buttonRgb));
        GUI_INTERFACE_COLOR.set(formatGuiColor(interfaceRgb));
        GUI_INTERFACE_COLOR.save();
    }

    public static int guiOpacity() {
        return GUI_OPACITY.get();
    }

    public static void setGuiOpacity(int percent) {
        GUI_OPACITY.set(percent);
        GUI_OPACITY.save();
    }

    public static void resetGuiThemeColors() {
        setGuiThemeColors(DEFAULT_GUI_ACCENT_RGB, DEFAULT_GUI_BUTTON_RGB, DEFAULT_GUI_INTERFACE_RGB);
    }

    public static int parseGuiColor(String value) {
        if (value == null || value.length() != 7 || value.charAt(0) != '#') {
            throw new IllegalArgumentException("Expected #RRGGBB");
        }
        return Integer.parseInt(value.substring(1), 16) & 0xFFFFFF;
    }

    public static String formatGuiColor(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static int guiColor(ModConfigSpec.ConfigValue<String> value, int fallback) {
        try {
            return parseGuiColor(value.get());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static boolean isGuiColor(Object value) {
        try {
            parseGuiColor(value instanceof String string ? string : null);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }
}
