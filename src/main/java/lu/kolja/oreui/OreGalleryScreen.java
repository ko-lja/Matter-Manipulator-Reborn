package lu.kolja.oreui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

import static lu.kolja.oreui.OreColors.GRAY_30;
import static lu.kolja.oreui.OreColors.GRAY_40;
import static lu.kolja.oreui.OreColors.GRAY_60;
import static lu.kolja.oreui.OreColors.GRAY_100;
import static lu.kolja.oreui.OreColors.WHITE;

public final class OreGalleryScreen extends Screen {

    public static final int WIDTH = 1032;
    public static final int HEIGHT = 584;
    private static final int[] COLUMNS = {96, 204, 312, 420, 528};
    private static final List<OreState> ENABLED_STATES = List.of(OreState.DEFAULT, OreState.HOVER, OreState.PRESSED);
    private static final int[] CUSTOM_ACCENTS = {0x101010, 0xF2F2F2, 0xFFE600};
    private static final int[] TINTED_COLORS = {0x7345E5, 0xACF2FF, 0x3B4252};
    private static final OreTheme TINTED = OreTheme.of(TINTED_COLORS[0], TINTED_COLORS[1], TINTED_COLORS[2]);

    public OreGalleryScreen() {
        super(Component.literal("Ore UI gallery"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var theme = OreTheme.DEFAULT;
        graphics.fill(0, 0, width, height, OreColors.GRAY_80);
        drawSheet(graphics, theme);
        drawCustomAccents(graphics);
        drawSwatchesAndTabs(graphics, theme);
        drawNavBar(graphics, theme, 16, 384, 608);
        text(graphics, "Well, tags, small destructive button", 656, 236, GRAY_40);
        drawSelection(graphics, theme, 656, 248);
        text(graphics, "List rows, scrollbar scrolled by one row", 656, 130, GRAY_40);
        drawMaterials(graphics, theme, 656, 142, 4);
        drawHexFields(graphics, theme, 904);
        drawTinted(graphics, 416);
        drawDropdowns(graphics, theme);
    }

    private void drawSheet(GuiGraphics graphics, OreTheme theme) {
        var headers = new String[]{"Default", "Hover", "Pressed", "", "Disabled"};
        for (int column = 0; column < COLUMNS.length; column++) {
            text(graphics, headers[column], COLUMNS[column], 8, GRAY_40);
        }
        buttonRow(graphics, theme, 22, "Primary", OreVariant.PRIMARY, false);
        buttonRow(graphics, theme, 50, "Hero", OreVariant.PRIMARY, true);
        buttonRow(graphics, theme, 78, "Secondary", OreVariant.SECONDARY, false);
        buttonRow(graphics, theme, 106, "Neutral", OreVariant.NEUTRAL, false);
        buttonRow(graphics, theme, 134, "Destructive", OreVariant.DESTRUCTIVE, false);

        int y = 168;
        text(graphics, "Switch", 10, y + 4, GRAY_30);
        switchSample(graphics, theme, COLUMNS[0], y, false, OreState.DEFAULT, "Off");
        switchSample(graphics, theme, COLUMNS[1], y, false, OreState.HOVER, "Off, hover");
        switchSample(graphics, theme, COLUMNS[2], y, true, OreState.DEFAULT, "On");
        switchSample(graphics, theme, COLUMNS[3], y, true, OreState.PRESSED, "On, pressed");
        switchSample(graphics, theme, COLUMNS[4], y, false, OreState.DISABLED, "Disabled");

        y = 194;
        text(graphics, "Tab", 10, y + 7, GRAY_30);
        OrePainter.listButton(graphics, font, theme, 96, y, 96, 22, theme.neutral(), OreState.DEFAULT,
                OreIcons.GEOMETRY, Component.literal("Geometry"), Component.empty(), null);
        OrePainter.selectedTab(graphics, font, theme, 204, y, 96, 22, OreIcons.CABLE, Component.literal("Cable"));
        OrePainter.listButton(graphics, font, theme, 312, y, 96, 22, theme.neutral(), OreState.DISABLED,
                OreIcons.LOCK, Component.literal("Move"), Component.literal("Locked"), null);
        text(graphics, "Off, selected, locked", 420, y + 7, GRAY_40);

        y = 226;
        text(graphics, "Other", 10, y + 2, GRAY_30);
        text(graphics, "provisional", 10, y + 13, OreColors.WARN);
        OrePainter.listButton(graphics, font, theme, 96, y, 96, 22, theme.buttons(), OreState.DEFAULT, null,
                Component.literal("Cuboid"), Component.empty(), OreIcons.CHEVRON);
        OrePainter.progressBar(graphics, theme, 204, y + 7, 96, 0.62);
        int x = 312;
        x += OrePainter.tag(graphics, font, x, y + 5, Component.literal("MKI"), OreColors.INFO_TAG) + 4;
        x += OrePainter.tag(graphics, font, x, y + 5, Component.literal("1352 ops, 1 missing"), OreColors.WARN) + 4;
        OrePainter.tag(graphics, font, x, y + 5, Component.literal("Ready"), OreColors.SUCCESS_TAG);
    }

    private void buttonRow(GuiGraphics graphics, OreTheme theme, int y, String name, OreVariant variant,
                           boolean hero) {
        text(graphics, name, 10, y + 7, GRAY_30);
        for (int column = 0; column < ENABLED_STATES.size(); column++) {
            sampleButton(graphics, theme, COLUMNS[column], y, variant, ENABLED_STATES.get(column), hero);
        }
        sampleButton(graphics, theme, COLUMNS[4], y, variant, OreState.DISABLED, hero);
    }

    private void sampleButton(GuiGraphics graphics, OreTheme theme, int x, int y, OreVariant variant, OreState state,
                              boolean hero) {
        if (hero) {
            OrePainter.heroButton(graphics, font, theme, x, y, 96, 22, variant, state, Component.literal("START COPY"));
        } else {
            OrePainter.button(graphics, font, theme, x, y, 96, 22, variant, state, Component.literal("Pick Volume"));
        }
    }

    private void switchSample(GuiGraphics graphics, OreTheme theme, int x, int y, boolean on, OreState state,
                              String label) {
        OrePainter.toggleSwitch(graphics, theme, x, y, on, state);
        text(graphics, label, x + 34, y + 4, GRAY_40);
    }

    private void drawCustomAccents(GuiGraphics graphics) {
        for (int row = 0; row < CUSTOM_ACCENTS.length; row++) {
            int y = 270 + row * 28;
            var theme = OreTheme.of(CUSTOM_ACCENTS[row], OreVariant.SECONDARY.face(), OreColors.GRAY_70);
            text(graphics, String.format("#%06X", CUSTOM_ACCENTS[row]), 10, y + 7, GRAY_30);
            for (int column = 0; column < ENABLED_STATES.size(); column++) {
                OrePainter.button(graphics, font, theme, COLUMNS[column], y, 96, 22, theme.accent(),
                        ENABLED_STATES.get(column), Component.literal("Done"));
            }
            OrePainter.heroButton(graphics, font, theme, COLUMNS[3], y, 96, 22, theme.accent(), OreState.DEFAULT,
                    Component.literal("START COPY"));
            OrePainter.toggleSwitch(graphics, theme, COLUMNS[4], y + 3, true, OreState.DEFAULT);
            OrePainter.progressBar(graphics, theme, COLUMNS[4] + 34, y + 7, 62, 0.62);
        }
    }

    private void drawSwatchesAndTabs(GuiGraphics graphics, OreTheme theme) {
        int y = 354;
        text(graphics, "Swatches", 10, y + 7, GRAY_30);
        var presets = OreVariant.ACCENT_PRESETS;
        for (int index = 0; index < presets.size(); index++) {
            OrePainter.iconButton(graphics, theme, 96 + index * 40, y, 36, 22, presets.get(index), OreState.DEFAULT,
                    index == 0 ? OreIcons.TICK : null);
        }
        OrePainter.iconButton(graphics, theme, 96 + presets.size() * 40, y, 36, 22, OreVariant.custom(0x2E6BE5),
                OreState.HOVER, null);
        OrePainter.toggleTab(graphics, font, theme, 420, y, 100, 22, OreState.DEFAULT, false,
                Component.literal("Preview"));
        OrePainter.toggleTab(graphics, font, theme, 524, y, 100, 22, OreState.DEFAULT, true,
                Component.literal("Materials"));
    }

    private void drawNavBar(GuiGraphics graphics, OreTheme theme, int x, int y, int w) {
        OrePainter.navBar(graphics, font, theme, x, y, w, Component.literal("Matter Manipulator"));
        OrePainter.iconButton(graphics, theme, x + 3, y + 1, 20, 20, theme.buttons(), OreState.DEFAULT,
                OreIcons.CROSS);
        int right = x + w - 8;
        var percent = "87%";
        text(graphics, percent, right - font.width(percent), y + 7, GRAY_100);
        int barX = right - 22 - 64;
        OrePainter.progressBar(graphics, theme, barX, y + 7, 64, 0.87);
        var energy = "Energy";
        int energyX = barX - 5 - font.width(energy);
        text(graphics, energy, energyX, y + 7, GRAY_60);
        var tier = Component.literal("MKII");
        OrePainter.tag(graphics, font, energyX - 8 - OrePainter.tagWidth(font, tier), y + 5, tier, OreColors.INFO_TAG);
    }

    private void drawSelection(GuiGraphics graphics, OreTheme theme, int x, int y) {
        var marks = new String[][]{{"A", "128, 64, -302"}, {"B", "140, 71, -290"}, {"C", "134, 79, -296"}};
        int w = 232;
        int h = marks.length * 15 + 6;
        OrePainter.well(graphics, theme, x, y, w, h);
        for (int row = 0; row < marks.length; row++) {
            int tagWidth = OrePainter.tag(graphics, font, x + 5, y + 5 + row * 15, Component.literal(marks[row][0]), GRAY_30);
            text(graphics, marks[row][1], x + 9 + tagWidth, y + 7 + row * 15, WHITE);
        }
        OrePainter.button(graphics, font, theme, x + w - 49, y + (h - 22) / 2, 44, 22, OreVariant.DESTRUCTIVE,
                OreState.DEFAULT, Component.literal("Clear"));
    }

    private void drawMaterials(GuiGraphics graphics, OreTheme theme, int x, int y, int visibleRows) {
        var stacks = List.of(Items.GOLD_BLOCK, Items.POLISHED_DEEPSLATE, Items.SMOOTH_QUARTZ, Items.GLASS, Items.STONE);
        var counts = new int[][]{{416, 416}, {312, 416}, {1352, 1352}, {98, 98}, {0, 64}};
        int w = 300;
        OrePainter.well(graphics, theme, x, y, w, visibleRows * OrePainter.LIST_ROW_HEIGHT + 2);
        for (int row = 0; row < visibleRows; row++) {
            int index = row + 1;
            int rowY = y + 1 + row * OrePainter.LIST_ROW_HEIGHT;
            OrePainter.listRow(graphics, theme, x + 1, rowY, w - 5, OrePainter.LIST_ROW_HEIGHT, index, index == 2);
            var stack = new ItemStack(stacks.get(index));
            graphics.renderItem(stack, x + 3, rowY + 2);
            var count = counts[index][0] + " / " + counts[index][1];
            int countX = x + w - 9 - font.width(count);
            text(graphics, count, countX, rowY + 7, counts[index][0] >= counts[index][1] ? OreColors.OK : OreColors.MISSING);
            text(graphics, OreText.shorten(font, stack.getHoverName().getString(), countX - 6 - (x + 24)), x + 24,
                    rowY + 7, WHITE);
        }
        OrePainter.scrollbar(graphics, theme, x + w - 3, y + 1, visibleRows * OrePainter.LIST_ROW_HEIGHT,
                stacks.size() * OrePainter.LIST_ROW_HEIGHT, OrePainter.LIST_ROW_HEIGHT);
    }

    private void drawHexFields(GuiGraphics graphics, OreTheme theme, int x) {
        text(graphics, "Custom swatch, hex field", x, 8, GRAY_40);
        var custom = OreVariant.custom(0x2E6BE5);
        OrePainter.iconButton(graphics, theme, x, 22, 36, 22, custom, OreState.DEFAULT, OreIcons.TICK);
        OrePainter.field(graphics, theme, x + 40, 22, 80, 22, false);
        text(graphics, "#2E6BE5", x + 47, 30, WHITE);
        OrePainter.iconButton(graphics, theme, x, 50, 36, 22, custom, OreState.HOVER, null);
        OrePainter.field(graphics, theme, x + 40, 50, 80, 22, true);
        text(graphics, "#2E6B", x + 47, 58, WHITE);
    }

    private void drawTinted(GuiGraphics graphics, int top) {
        var theme = TINTED;
        OrePainter.rect(graphics, 0, top, width, height - top, theme.gray80());
        text(graphics, String.format("Accent #%06X, buttons #%06X, interface #%06X", TINTED_COLORS[0],
                TINTED_COLORS[1], TINTED_COLORS[2]), 10, top + 8, GRAY_40);
        buttonRow(graphics, theme, top + 22, "Neutral", theme.neutral(), false);
        OrePainter.selectedTab(graphics, font, theme, COLUMNS[3], top + 22, 96, 22, OreIcons.CABLE,
                Component.literal("Cable"));
        drawNavBar(graphics, theme, 16, top + 52, 608);
        drawSelection(graphics, theme, 96, top + 84);
        OrePainter.listButton(graphics, font, theme, 336, top + 84, 112, 22, theme.buttons(), OreState.DEFAULT, null,
                Component.literal("Replaceable"), Component.empty(), OreIcons.CHEVRON);
        OrePainter.toggleSwitch(graphics, theme, 460, top + 88, true, OreState.DEFAULT);
        OrePainter.toggleSwitch(graphics, theme, 500, top + 88, false, OreState.HOVER);
        drawMaterials(graphics, theme, 656, top + 22, 3);
    }

    private void drawDropdowns(GuiGraphics graphics, OreTheme theme) {
        text(graphics, "Dropdown lists: hovered row, pressed row", 656, 8, GRAY_40);
        var remove = List.<Component>of(Component.literal("None"), Component.literal("Replaceable"),
                Component.literal("All"));
        OrePainter.listButton(graphics, font, theme, 656, 22, 112, 22, theme.buttons(), OreState.DEFAULT, null,
                Component.literal("Replaceable"), Component.empty(), OreIcons.CHEVRON);
        OrePainter.dropdownList(graphics, font, theme, 656, 43, 112, remove, 1, 0, false);
        var shapes = List.<Component>of(Component.literal("Cuboid"), Component.literal("Line"),
                Component.literal("Ellipsoid"), Component.literal("Cylinder"));
        OrePainter.listButton(graphics, font, theme, 780, 22, 112, 22, theme.buttons(), OreState.DEFAULT, null,
                Component.literal("Cuboid"), Component.empty(), OreIcons.CHEVRON);
        OrePainter.dropdownList(graphics, font, theme, 780, 43, 112, shapes, 0, 2, true);
        var tinted = List.<Component>of(Component.literal("None"), Component.literal("Replaceable"),
                Component.literal("All"));
        OrePainter.dropdownList(graphics, font, TINTED, 336, 521, 112, tinted, 1, 2, false);
    }

    private void text(GuiGraphics graphics, String text, int x, int y, int color) {
        OreText.draw(graphics, font, text, x, y, color);
    }
}
