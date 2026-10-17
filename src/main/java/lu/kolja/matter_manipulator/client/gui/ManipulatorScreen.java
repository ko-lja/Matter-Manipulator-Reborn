package lu.kolja.matter_manipulator.client.gui;

import lu.kolja.matter_manipulator.client.ClientEvents;
import lu.kolja.matter_manipulator.client.PasteFollow;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.client.blueprint.BlueprintLibrary;
import lu.kolja.matter_manipulator.client.blueprint.LibraryPage;
import lu.kolja.matter_manipulator.common.energy.StackEnergyStorage;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMScreen;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.FloatingPlacement;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintRef;
import lu.kolja.matter_manipulator.common.network.ConfigActionPacket;
import lu.kolja.matter_manipulator.common.network.GuiStatusPacket;
import lu.kolja.matter_manipulator.common.network.GuiStatusRequestPacket;
import lu.kolja.matter_manipulator.common.network.SaveBlueprintPacket;
import lu.kolja.matter_manipulator.common.build.ItemRequirementStatus;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.oreui.OreColors;
import lu.kolja.oreui.OreIcons;
import lu.kolja.oreui.OreInput;
import lu.kolja.oreui.OrePainter;
import lu.kolja.oreui.OreState;
import lu.kolja.oreui.OreText;
import lu.kolja.oreui.OreTheme;
import lu.kolja.oreui.OreVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static lu.kolja.oreui.OreColors.GRAY_100;
import static lu.kolja.oreui.OreColors.GRAY_30;
import static lu.kolja.oreui.OreColors.GRAY_40;
import static lu.kolja.oreui.OreColors.GRAY_50;
import static lu.kolja.oreui.OreColors.GRAY_60;
import static lu.kolja.oreui.OreColors.WHITE;

public final class ManipulatorScreen extends Screen {

    private static final int MATERIAL_ROW_HEIGHT = OrePainter.LIST_ROW_HEIGHT;
    private static final int LIBRARY_ROW_HEIGHT = OrePainter.LIST_ROW_HEIGHT;
    private static final int MIN_MATERIALS_WELL = MATERIAL_ROW_HEIGHT * 2 + 2;
    private static final int SWITCH_GAP = 6;
    private static final String[] MARKERS = {"A", "B", "C"};
    private static final int STATUS_REFRESH_TICKS = 40;
    private static final int COMPILING_REFRESH_TICKS = 10;

    private final InteractionHand hand;
    private final GuiPreviewScene previewScene = new GuiPreviewScene();
    private final OreInput input = new OreInput();
    private GuiStatusPacket status;
    private List<ItemRequirementStatus> materials = List.of();
    private int requestSequence;
    private int pendingRequest = -1;
    private int requestedHash = Integer.MIN_VALUE;
    private int refreshTicks;
    private int materialScroll;
    private boolean compactMaterials;
    private boolean rotatingPreview;
    private boolean settingsOpen;
    private boolean savePromptOpen;
    private boolean libraryOpen;
    private int libraryScroll;
    private long pageClosedByClick = Long.MIN_VALUE / 2;
    @Nullable
    private Path librarySelection;
    private final LibraryPage.Preview<PreviewRenderer.GuiPreviewSnapshot> libraryPreview = new LibraryPage.Preview<>();
    @Nullable
    private Path rowClicked;
    private long rowClickedAt = Long.MIN_VALUE / 2;
    @Nullable
    private String libraryMessage;
    private int libraryMessageColor = OreColors.MISSING;
    private boolean draggingSpeed;
    private int pendingSpeed = -1;
    private boolean draggingOpacity;
    private int opacity = 100;
    private DropdownKind openDropdown;
    private ManipulatorMode lastRenderedMode;
    private boolean colorInitialized;
    private boolean syncingColor;
    private boolean colorDirty;
    private final int[] colors = new int[ThemeColor.values().length];
    private final int[] customColors = new int[ThemeColor.values().length];
    private ThemeColor colorSlot = ThemeColor.ACCENT;
    private OreTheme theme = OreTheme.DEFAULT;
    private double lastMouseX;
    private double lastMouseY;
    private EditBox hexInput;
    private EditBox nameInput;
    private final List<Hint> hints = new ArrayList<>();

    public ManipulatorScreen(InteractionHand hand) {
        super(MMScreen.TITLE.text());
        this.hand = hand;
    }

    @Override
    protected void init() {
        super.init();
        if (!colorInitialized) {
            colors[ThemeColor.ACCENT.ordinal()] = MMConfig.guiAccentColor();
            colors[ThemeColor.BUTTONS.ordinal()] = MMConfig.guiButtonColor();
            colors[ThemeColor.INTERFACE.ordinal()] = MMConfig.guiInterfaceColor();
            for (var color : ThemeColor.values()) {
                int stored = colors[color.ordinal()];
                customColors[color.ordinal()] = color.isPreset(stored) ? color.customExample : stored;
            }
            rebuildTheme();
            opacity = MMConfig.guiOpacity();
            colorInitialized = true;
        }
        initializeHexInput(layout());
        initializeNameInput();
        requestStatus();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(stack().getItem() instanceof ManipulatorItem)) {
            onClose();
            return;
        }
        refreshTicks++;
        int hash = ToolState.get(stack()).previewHash();
        if (pendingRequest >= 0 && refreshTicks > STATUS_REFRESH_TICKS * 2) {
            pendingRequest = -1;
        }
        if (pendingRequest < 0 && (hash != requestedHash || refreshTicks >= STATUS_REFRESH_TICKS)) {
            requestStatus();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, withOpacity(0xC0101010), withOpacity(0xD0101010));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        var layout = layout();
        var stack = stack();
        var state = ToolState.get(stack);
        if (lastRenderedMode != state.mode()) {
            openDropdown = null;
            closeSavePrompt();
            closeLibrary();
            lastRenderedMode = state.mode();
        }
        var buttons = buttons(layout, state, stack);
        var dropdown = dropdownMenu(buttons, layout, state);
        if (openDropdown != null && dropdown == null) {
            openDropdown = null;
        }
        previewScene.setSnapshot(libraryOpen ? libraryPreviewSnapshot()
                : PreviewRenderer.guiPreview(Minecraft.getInstance(), stack));
        input.hover(controlAt(buttons, dropdown, mouseX, mouseY));
        hints.clear();

        drawPanel(graphics, layout, stack);
        if (settingsOpen) {
            drawSettingsPage(graphics, layout, mouseX, mouseY);
        } else if (libraryOpen) {
            drawLibraryPage(graphics, layout, state);
        } else {
            drawControls(graphics, layout, state, buttons);
        }
        drawVisualPane(graphics, layout, state, mouseX, mouseY);
        for (var button : buttons) {
            drawButton(graphics, button, layout.compact());
        }
        if (savePromptOpen) {
            var field = nameField(layout, state);
            nameInput.setX(field.x() + 7);
            nameInput.setY(field.y() + 8);
            nameInput.setWidth(Math.max(8, field.width() - 14));
        }
        if (settingsOpen || savePromptOpen) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }

        boolean overDropdown = dropdown != null && dropdown.rect().contains(mouseX, mouseY);
        if (!overDropdown) {
            for (var button : buttons) {
                if (button.contains(mouseX, mouseY) && !button.tooltip().getString().isEmpty()) {
                    graphics.setTooltipForNextFrame(font, button.tooltip(), mouseX, mouseY);
                    break;
                }
            }
            for (var hint : hints) {
                if (hint.area().contains(mouseX, mouseY)) {
                    graphics.setTooltipForNextFrame(font, font.split(Component.literal(hint.text()), 240), mouseX, mouseY);
                    break;
                }
            }
            drawMaterialTooltip(graphics, layout, mouseX, mouseY);
        }
        if (dropdown != null) {
            drawDropdown(graphics, dropdown);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        if (LibraryPage.ignoresPress(pageClosedByClick, Util.getMillis())) {
            return true;
        }
        var stack = stack();
        var state = ToolState.get(stack);
        var layout = layout();
        var buttons = buttons(layout, state, stack);
        var dropdown = dropdownMenu(buttons, layout, state);
        if (dropdown != null) {
            int option = dropdown.optionAt((int) mouseX, (int) mouseY);
            if (option < 0) {
                openDropdown = null;
            }
            input.press(option < 0 ? null : new OptionId(option));
            return true;
        }
        var control = controlAt(buttons, null, (int) mouseX, (int) mouseY);
        input.press(control);
        if (control != null || super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (settingsOpen && hexField(layout).contains((int) mouseX, (int) mouseY)) {
            setFocused(hexInput);
            return true;
        }
        if (savePromptOpen && nameField(layout, state).contains((int) mouseX, (int) mouseY)) {
            setFocused(nameInput);
            return true;
        }
        if (settingsOpen && speedRect(layout).contains((int) mouseX, (int) mouseY)) {
            setFocused(null);
            draggingSpeed = true;
            pendingSpeed = speedAt(layout, mouseX);
            return true;
        }
        if (settingsOpen && opacityRect(layout).contains((int) mouseX, (int) mouseY)) {
            setFocused(null);
            draggingOpacity = true;
            opacity = opacityAt(layout.innerX(), layout.innerWidth(), mouseX);
            return true;
        }
        if (showsPreview(layout) && previewWell(layout).contains((int) mouseX, (int) mouseY)) {
            rotatingPreview = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && openDropdown != null) {
            openDropdown = null;
            return true;
        }
        if (savePromptOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeSavePrompt();
            return true;
        }
        if (savePromptOpen && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            saveBlueprint();
            return true;
        }
        if (libraryOpen && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            moveLibrarySelection(keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            return true;
        }
        if (libraryOpen && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            loadLibrarySelection();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (draggingSpeed && button == 0) {
            pendingSpeed = speedAt(layout(), mouseX);
            return true;
        }
        if (draggingOpacity && button == 0) {
            var layout = layout();
            opacity = opacityAt(layout.innerX(), layout.innerWidth(), mouseX);
            return true;
        }
        if (rotatingPreview && button == 0) {
            previewScene.rotate(mouseX - lastMouseX, mouseY - lastMouseY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (button != 0) {
            return super.mouseReleased(event);
        }
        rotatingPreview = false;
        if (draggingSpeed) {
            draggingSpeed = false;
            send(ConfigActionPacket.Action.SET_PLACEMENT_SPEED, pendingSpeed, false);
            pendingSpeed = -1;
            return true;
        }
        if (draggingOpacity) {
            draggingOpacity = false;
            MMConfig.setGuiOpacity(opacity);
            return true;
        }
        var stack = stack();
        var state = ToolState.get(stack);
        var layout = layout();
        var buttons = buttons(layout, state, stack);
        var dropdown = dropdownMenu(buttons, layout, state);
        var control = controlAt(buttons, dropdown, (int) mouseX, (int) mouseY);
        if (input.release(control)) {
            activate(control, buttons, dropdown);
        }
        boolean handled = super.mouseReleased(event);
        if (settingsOpen) {
            commitTheme();
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        var layout = layout();
        if (showsMaterials(layout) && materialsWell(layout).contains((int) mouseX, (int) mouseY)) {
            int visibleHeight = Math.max(1, materialContentRect(layout).height());
            int maxScroll = Math.max(0, materials.size() * MATERIAL_ROW_HEIGHT - visibleHeight);
            materialScroll = Mth.clamp(
                    materialScroll - (int) Math.signum(delta) * MATERIAL_ROW_HEIGHT,
                    0,
                    maxScroll
            );
            return true;
        }
        if (libraryOpen && libraryWell(layout).contains((int) mouseX, (int) mouseY)) {
            libraryScroll = LibraryPage.clampScroll(libraryScroll - (int) Math.signum(delta) * LIBRARY_ROW_HEIGHT,
                    BlueprintLibrary.entries().size() * LIBRARY_ROW_HEIGHT, libraryContentRect(layout).height());
            return true;
        }
        if (showsPreview(layout) && previewWell(layout).contains((int) mouseX, (int) mouseY)) {
            previewScene.zoom(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        BlueprintLibrary.cancelChoice();
        commitTheme();
        if (opacity != MMConfig.guiOpacity()) {
            MMConfig.setGuiOpacity(opacity);
        }
        super.removed();
    }

    private Layout layout() {
        int panelWidth = Math.max(300, Math.min(900, width - 12));
        int panelHeight = Math.max(220, Math.min(430, height - 12));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        boolean compact = panelWidth < 600;
        int sidebarWidth = compact ? Math.max(82, Math.min(104, panelWidth / 5)) : 116;
        int controlsWidth = compact ? Mth.clamp((panelWidth - sidebarWidth) * 5 / 9, 132, 232) : 232;
        return new Layout(panelX, panelY, panelWidth, panelHeight, sidebarWidth, controlsWidth, compact);
    }

    private List<UiButton> buttons(Layout layout, ToolState state, ItemStack stack) {
        var entries = new ArrayList<UiButton>();
        entries.add(new UiButton("close", layout.x() + 3, layout.y() + 1, 20, 20, ButtonKind.ICON, theme.buttons(),
                Component.empty(), Component.empty(), translatable("escape_hint"), true, false, OreIcons.CROSS, null,
                this::onClose));
        addModeButtons(entries, layout, state, stack);
        addVisualButtons(entries, layout);
        if (settingsOpen) {
            addSettingsButtons(entries, layout);
            return entries;
        }
        if (libraryOpen) {
            addLibraryButtons(entries, layout);
            return entries;
        }

        var flow = controlFlow(layout, state);
        int x = layout.innerX();
        int width = layout.innerWidth();
        var rows = flow.rowYs();
        int last = rows[rows.length - 1];
        boolean packed = flow.packed();
        int fieldWidth = fieldWidth(width);
        int fieldX = x + width - fieldWidth;

        switch (state.mode()) {
            case GEOMETRY -> {
                var shape = dropdownButton(DropdownKind.SHAPE, "shape", state.shape().displayName());
                var remove = dropdownButton(DropdownKind.REMOVE, "remove", state.removeMode().displayName());
                if (packed) {
                    addPair(entries, x, rows[0], width, shape, remove);
                } else {
                    entries.add(place(shape, fieldX, rows[0], fieldWidth));
                    entries.add(place(remove, fieldX, rows[1], fieldWidth));
                }
                entries.add(place(pickButton("pick_volume", false), x, last, width));
            }
            case CABLE -> {
                var remove = dropdownButton(DropdownKind.REMOVE, "remove", state.removeMode().displayName());
                var route = secondary("cable_route", state.cableRouteMode().displayName(),
                        () -> send(ConfigActionPacket.Action.CYCLE_CABLE_ROUTE, false));
                if (packed) {
                    addPair(entries, x, rows[0], width, remove, route);
                } else {
                    entries.add(place(remove, fieldX, rows[0], fieldWidth));
                    entries.add(place(route, fieldX, rows[1], fieldWidth));
                }
                var search = switchButton("search_progress", PreviewRenderer.renderCableSearchProgress(),
                        PreviewRenderer::toggleCableSearchProgress);
                var pathLog = switchButton("cable_path_log", PreviewRenderer.cableSearchMessages(),
                        PreviewRenderer::toggleCableSearchMessages);
                if (preferredWidth(search) + SWITCH_GAP + preferredWidth(pathLog) > width) {
                    search = withLabel(search, translatable("search_progress_short"));
                }
                addSwitchPair(entries, x, rows[packed ? 1 : 2], width, search, pathLog);
                entries.add(place(pickButton("pick_cable", false), x, last, width));
            }
            case EXCHANGE -> {
                var remove = dropdownButton(DropdownKind.REMOVE, "remove", state.removeMode().displayName());
                var target = pickButton("pick_target", false);
                if (packed) {
                    addPair(entries, x, rows[0], width, remove, target);
                } else {
                    entries.add(place(remove, fieldX, rows[0], fieldWidth));
                    entries.add(place(target, x, rows[1], width));
                }
                entries.add(place(pickButton("pick_replace", true), x, last, width));
            }
            case COPY, MOVE, BLUEPRINT -> {
                int areaWidth = transformAreaWidth(width);
                int areaX = x + width - areaWidth;
                addAxisButtons(entries, areaX, rows[0], areaWidth,
                        ConfigActionPacket.Action.ROTATE_X,
                        ConfigActionPacket.Action.ROTATE_Y,
                        ConfigActionPacket.Action.ROTATE_Z,
                        "rotate");
                addAxisButtons(entries, areaX, rows[1], areaWidth,
                        ConfigActionPacket.Action.MIRROR_X,
                        ConfigActionPacket.Action.MIRROR_Y,
                        ConfigActionPacket.Action.MIRROR_Z,
                        "mirror");
                if (state.mode() != ManipulatorMode.MOVE) {
                    var transform = state.transform();
                    if (!packed || !savePromptOpen) {
                        entries.add(place(secondary("stack_minus", Component.literal("-"),
                                () -> send(ConfigActionPacket.Action.STACK_MINUS, false)), areaX, rows[2], 24));
                        entries.add(place(secondary("stack_axis",
                                translatable("stack_count", transform.stackCount(), transform.stackAxis().displayName()),
                                () -> send(ConfigActionPacket.Action.STACK_AXIS, false)), areaX + 26, rows[2],
                                areaWidth - 52));
                        entries.add(place(secondary("stack_plus", Component.literal("+"),
                                () -> send(ConfigActionPacket.Action.STACK_PLUS, false)), areaX + areaWidth - 24,
                                rows[2], 24));
                    }
                    if (state.mode() == ManipulatorMode.COPY && !savePromptOpen) {
                        boolean hasSelection = state.coordA().isPresent() && state.coordB().isPresent();
                        var save = packed
                                ? place(secondary("save_blueprint", translatable("save_blueprint_short"),
                                        this::openSavePrompt), x, rows[2], width - areaWidth - 4)
                                : place(secondary("save_blueprint", translatable("save_blueprint"),
                                        this::openSavePrompt), x, rows[3], width);
                        entries.add(enabled(save, hasSelection));
                    }
                    if (state.mode() == ManipulatorMode.BLUEPRINT) {
                        var library = secondary("library", translatable("library"), this::openLibrary);
                        if (packed) {
                            int columnWidth = width - areaWidth - 4;
                            if (font.width(library.label()) + 8 > columnWidth) {
                                library = withLabel(library, translatable("library_short"));
                            }
                            entries.add(place(library, x, rows[2], columnWidth));
                        } else {
                            entries.add(place(library, x, rows[3], width));
                        }
                    }
                }
            }
        }

        int wellButtonY = flow.wellY() + (flow.wellHeight() - 22) / 2;
        boolean blueprint = state.mode() == ManipulatorMode.BLUEPRINT;
        entries.add(new UiButton("clear", x + width - 49, wellButtonY, 44, 22,
                ButtonKind.BUTTON, OreVariant.DESTRUCTIVE, translatable("clear_short"), Component.empty(),
                tooltip(blueprint ? "clear_anchor" : "clear"), true, false, null, null,
                () -> send(ConfigActionPacket.Action.CLEAR_COORDS, false)));
        if (blueprint) {
            entries.add(new UiButton("unload_blueprint", x + width - 73, wellButtonY, 20, 22, ButtonKind.ICON,
                    theme.buttons(), Component.empty(), Component.empty(), tooltip("unload_blueprint"),
                    state.blueprintRef().isPresent(), false, OreIcons.EJECT, null,
                    () -> send(ConfigActionPacket.Action.CLEAR_BLUEPRINT, false)));
        }

        var floating = switchButton("floating", state.floatingPlacement(),
                () -> send(ConfigActionPacket.Action.TOGGLE_FLOATING_PLACEMENT, false));
        var autoCraft = switchButton("auto_craft_missing", state.autoCraftMissing(),
                () -> send(ConfigActionPacket.Action.TOGGLE_AUTO_CRAFT_MISSING, false));
        if (flow.pairedSwitches()) {
            addSwitchPair(entries, x, flow.placementY(), width, floating, autoCraft);
        } else {
            entries.add(place(floating, x, flow.placementY(), width));
            entries.add(place(autoCraft, x, flow.placementY() + 19, width));
        }

        if (savePromptOpen) {
            int cancelWidth = Math.min(100, width / 2 - 2);
            entries.add(place(secondary("blueprint_cancel", translatable("cancel"), this::closeSavePrompt), x,
                    layout.actionY(), cancelWidth));
            entries.add(enabled(new UiButton("blueprint_save", x + cancelWidth + 4, layout.actionY(),
                    width - cancelWidth - 4, 22, ButtonKind.BUTTON, theme.accent(), translatable("save"),
                    Component.empty(), tooltip("blueprint_save"), true, false, null, null, this::saveBlueprint),
                    !nameInput.getValue().isBlank()));
            return entries;
        }

        var start = layout.compact() ? translatable("start") : translatable("start_mode", state.mode().displayName());
        var startLabel = Component.literal(start.getString().toUpperCase(Locale.ROOT));
        var startButton = new UiButton("start_operation", 0, 0, 0, 22, ButtonKind.HERO, theme.accent(), startLabel,
                Component.empty(), tooltip("start_operation"), true, false, null, null,
                () -> send(ConfigActionPacket.Action.START, true));
        boolean canRequest = hasCurrentStatus()
                && status.valid()
                && pendingRequest < 0
                && materials.stream().anyMatch(material -> !material.complete());
        var request = enabled(secondary("request_missing", translatable("request_missing"),
                () -> send(ConfigActionPacket.Action.REQUEST_MISSING, false)), canRequest);
        int startWidth = OreText.width(font, startLabel.getString(), true) + 14;
        if (font.width(request.label()) + 8 > width - 4 - startWidth) {
            request = withLabel(request, translatable("request_missing_short"));
        }
        int requestWidth = Mth.clamp(width - 4 - startWidth, 40, Math.min(112, font.width(request.label()) + 14));
        entries.add(place(request, x, layout.actionY(), requestWidth));
        entries.add(place(startButton, x + requestWidth + 4, layout.actionY(), width - requestWidth - 4));
        return entries;
    }

    private void addAxisButtons(
            List<UiButton> entries,
            int x,
            int y,
            int width,
            ConfigActionPacket.Action xAction,
            ConfigActionPacket.Action yAction,
            ConfigActionPacket.Action zAction,
            String tooltipPrefix
    ) {
        int keyWidth = (width - 4) / 3;
        entries.add(place(keyButton("X", tooltipPrefix + "_x", xAction), x, y, keyWidth));
        entries.add(place(keyButton("Y", tooltipPrefix + "_y", yAction), x + keyWidth + 2, y, keyWidth));
        entries.add(place(keyButton("Z", tooltipPrefix + "_z", zAction), x + (keyWidth + 2) * 2, y,
                width - (keyWidth + 2) * 2));
    }

    private void addVisualButtons(List<UiButton> entries, Layout layout) {
        if (layout.compact()) {
            int x = layout.visualX() + 8;
            int width = layout.visualWidth() - 16;
            boolean stacked = stackedTabs(layout);
            int tabWidth = stacked ? width : (width - 4) / 2;
            entries.add(new UiButton("preview", x, layout.bodyY() + 8, tabWidth, 22, ButtonKind.TAB,
                    theme.buttons(), translatable("preview"), Component.empty(), tooltip("preview"), true,
                    !compactMaterials, null, null, () -> compactMaterials = false));
            entries.add(new UiButton("materials", stacked ? x : x + tabWidth + 4,
                    layout.bodyY() + (stacked ? 34 : 8), stacked ? width : width - tabWidth - 4, 22,
                    ButtonKind.TAB, theme.buttons(), translatable("materials"), Component.empty(),
                    tooltip("materials"), true, compactMaterials, null, null, () -> compactMaterials = true));
        }
        if (showsPreview(layout)) {
            var well = previewWell(layout);
            int resetWidth = Math.min(78, well.width() - 8);
            entries.add(new UiButton("reset_view", well.right() - 4 - resetWidth, well.y() + 4, resetWidth, 22,
                    ButtonKind.BUTTON, theme.neutral(), translatable("reset_view"), Component.empty(),
                    tooltip("reset_view"), true, false, null, null, previewScene::resetCamera));
        }
    }

    private void addModeButtons(List<UiButton> entries, Layout layout, ToolState state, ItemStack stack) {
        var item = stack.getItem() instanceof ManipulatorItem manipulator ? manipulator : null;
        var modes = ManipulatorMode.displayOrder();
        int x = layout.x() + 8;
        int width = layout.sidebarWidth() - 16;
        for (int index = 0; index < modes.size(); index++) {
            var mode = modes.get(index);
            boolean enabled = item != null && item.tier().canUse(mode);
            entries.add(new UiButton(
                    "mode." + mode.getSerializedName(),
                    x,
                    layout.modeY(index),
                    width,
                    22,
                    ButtonKind.MODE,
                    theme.neutral(),
                    mode.displayName(),
                    enabled ? Component.empty() : translatable("locked"),
                    tooltip(enabled ? "mode." + mode.getSerializedName() : "mode_locked"),
                    enabled,
                    !settingsOpen && state.mode() == mode,
                    enabled ? modeIcon(mode) : OreIcons.LOCK,
                    null,
                    () -> {
                        openDropdown = null;
                        closeSettings();
                        closeLibrary();
                        send(modeAction(mode), false);
                    }
            ));
        }
        entries.add(new UiButton("settings", x, layout.settingsY(), width, 22, ButtonKind.MODE, theme.neutral(),
                translatable("settings"), Component.empty(), tooltip("settings"), true, settingsOpen,
                OreIcons.SETTINGS, null, this::openSettings));
    }

    private static ConfigActionPacket.Action modeAction(ManipulatorMode mode) {
        return switch (mode) {
            case GEOMETRY -> ConfigActionPacket.Action.MODE_GEOMETRY;
            case CABLE -> ConfigActionPacket.Action.MODE_CABLE;
            case EXCHANGE -> ConfigActionPacket.Action.MODE_EXCHANGE;
            case COPY -> ConfigActionPacket.Action.MODE_COPY;
            case MOVE -> ConfigActionPacket.Action.MODE_MOVE;
            case BLUEPRINT -> ConfigActionPacket.Action.MODE_BLUEPRINT;
        };
    }

    private static OreIcons modeIcon(ManipulatorMode mode) {
        return switch (mode) {
            case GEOMETRY -> OreIcons.GEOMETRY;
            case CABLE -> OreIcons.CABLE;
            case EXCHANGE -> OreIcons.EXCHANGE;
            case COPY -> OreIcons.COPY;
            case MOVE -> OreIcons.MOVE;
            case BLUEPRINT -> OreIcons.BLUEPRINT;
        };
    }

    private void addSettingsButtons(List<UiButton> entries, Layout layout) {
        var flow = settingsFlow(layout);
        int x = layout.innerX();
        int width = layout.innerWidth();
        var tabs = slotTabs(width);
        for (var color : ThemeColor.values()) {
            var tab = tabs[color.ordinal()];
            entries.add(new UiButton("slot." + color.key, x + tab.x(), flow.tabsY() + tab.y(), tab.width(), 22,
                    ButtonKind.SLOT, theme.neutral(), translatable("color." + color.key), Component.empty(),
                    tooltip("color." + color.key), true, colorSlot == color, null, null, () -> selectSlot(color)));
        }

        int swatchWidth = swatchWidth(width);
        int current = colors[colorSlot.ordinal()];
        var presets = colorSlot.presets;
        for (int index = 0; index < presets.length; index++) {
            int rgb = presets[index];
            entries.add(new UiButton("preset." + index, x + index * (swatchWidth + 4), flow.swatchY(), swatchWidth,
                    22, ButtonKind.ICON, OreVariant.of(rgb), Component.empty(), Component.empty(),
                    Component.literal(MMConfig.formatGuiColor(rgb)), true, false, current == rgb ? OreIcons.TICK : null,
                    null, () -> selectColor(rgb)));
        }
        int custom = customColors[colorSlot.ordinal()];
        entries.add(new UiButton("preset.custom", x, flow.customY(), swatchWidth, 22, ButtonKind.ICON,
                OreVariant.of(custom), Component.empty(), Component.empty(), tooltip("custom"), true, false,
                colorSlot.isPreset(current) ? null : OreIcons.TICK, null, () -> selectColor(custom)));

        int resetWidth = Math.min(100, width / 2 - 2);
        var reset = secondary("color_reset", translatable("color_reset"), this::resetColors);
        if (font.width(reset.label()) + 8 > resetWidth) {
            reset = withLabel(reset, translatable("color_reset_short"));
        }
        entries.add(place(reset, x, layout.actionY(), resetWidth));
        entries.add(new UiButton("done", x + resetWidth + 4, layout.actionY(), width - resetWidth - 4, 22,
                ButtonKind.BUTTON, theme.accent(), translatable("done"), Component.empty(), tooltip("done"), true,
                false, null, null, () -> {
                    closeSettings();
                    pageClosedByClick = Util.getMillis();
                }));
    }

    private Rect[] slotTabs(int width) {
        var slots = ThemeColor.values();
        var natural = new int[slots.length];
        for (var slot : slots) {
            natural[slot.ordinal()] = font.width(translatable("color." + slot.key)) + 10;
        }
        var tabs = new Rect[slots.length];
        int first = 0;
        int rowY = 0;
        while (first < slots.length) {
            int end = first + 1;
            int used = natural[first];
            while (end < slots.length && used + 4 + natural[end] <= width) {
                used += 4 + natural[end];
                end++;
            }
            int count = end - first;
            int available = width - 4 * (count - 1);
            int equal = available / count;
            boolean equalFits = true;
            for (int index = first; index < end; index++) {
                equalFits &= natural[index] <= equal;
            }
            int spare = (available - (used - 4 * (count - 1))) / count;
            int tabX = 0;
            for (int index = first; index < end; index++) {
                int tabWidth = equalFits ? equal : natural[index] + spare;
                tabs[index] = new Rect(tabX, rowY, tabWidth, 22);
                tabX += tabWidth + 4;
            }
            first = end;
            rowY += 26;
        }
        return tabs;
    }

    private void initializeHexInput(Layout layout) {
        var field = hexField(layout);
        hexInput = addRenderableWidget(new EditBox(
                font,
                field.x() + 7,
                field.y() + 8,
                Math.max(8, field.width() - 14),
                10,
                MMScreen.COLOR_HEX.text()
        ));
        hexInput.setBordered(false);
        hexInput.setMaxLength(7);
        hexInput.setTextColor(WHITE);
        hexInput.setTextColorUneditable(GRAY_50);
        hexInput.setResponder(this::onHexChanged);
        syncHexInput();
        hexInput.visible = settingsOpen;
    }

    private void initializeNameInput() {
        var name = nameInput == null ? "" : nameInput.getValue();
        nameInput = addRenderableWidget(new EditBox(font, 0, 0, 8, 10,
                MMScreen.BLUEPRINT_NAME.text()));
        nameInput.setBordered(false);
        nameInput.setMaxLength(Blueprint.MAX_NAME_LENGTH);
        nameInput.setTextColor(WHITE);
        nameInput.setTextColorUneditable(GRAY_50);
        nameInput.setValue(name);
        nameInput.visible = savePromptOpen;
        if (savePromptOpen) {
            setFocused(nameInput);
        }
    }

    private void openSavePrompt() {
        openDropdown = null;
        savePromptOpen = true;
        nameInput.setValue("");
        nameInput.visible = true;
        setFocused(nameInput);
    }

    private void closeSavePrompt() {
        savePromptOpen = false;
        if (nameInput != null) {
            nameInput.visible = false;
            if (getFocused() == nameInput) {
                setFocused(null);
            }
        }
    }

    private void saveBlueprint() {
        var name = nameInput.getValue().strip();
        if (name.isEmpty()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new SaveBlueprintPacket(hand, name));
        closeSavePrompt();
        onClose();
    }

    private void openSettings() {
        closeSavePrompt();
        closeLibrary();
        openDropdown = null;
        settingsOpen = true;
        rotatingPreview = false;
        syncHexInput();
        hexInput.visible = true;
    }

    private void closeSettings() {
        commitTheme();
        settingsOpen = false;
        draggingSpeed = false;
        pendingSpeed = -1;
        draggingOpacity = false;
        setFocused(null);
        if (hexInput != null) {
            hexInput.visible = false;
        }
    }

    public void openLibrary() {
        openDropdown = null;
        libraryOpen = true;
        libraryMessage = null;
        rotatingPreview = false;
        var entries = BlueprintLibrary.entries();
        librarySelection = LibraryPage.initialSelection(entries, ToolState.get(stack()).blueprintRef().orElse(null));
        int selected = LibraryPage.indexOf(entries, librarySelection);
        libraryScroll = selected < 0 ? 0 : LibraryPage.scrollToShow(0, selected, LIBRARY_ROW_HEIGHT,
                libraryContentRect(layout()).height());
    }

    private void closeLibrary() {
        BlueprintLibrary.cancelChoice();
        libraryOpen = false;
        libraryMessage = null;
        librarySelection = null;
        libraryPreview.clear();
    }

    private @Nullable BlueprintLibrary.Entry selectedLibraryEntry() {
        var entries = BlueprintLibrary.entries();
        int index = LibraryPage.indexOf(entries, librarySelection);
        return index < 0 ? null : entries.get(index);
    }

    private PreviewRenderer.GuiPreviewSnapshot libraryPreviewSnapshot() {
        var snapshot = libraryPreview.update(selectedLibraryEntry(),
                blueprint -> PreviewRenderer.blueprintPreview(Minecraft.getInstance(), blueprint));
        return snapshot != null ? snapshot : PreviewRenderer.GuiPreviewSnapshot.empty();
    }

    private void clickLibraryRow(Path path) {
        long now = Util.getMillis();
        boolean second = LibraryPage.isDoubleClick(rowClicked, rowClickedAt, path, now);
        rowClicked = second ? null : path;
        rowClickedAt = now;
        librarySelection = path;
        if (second) {
            loadLibrarySelection();
        }
    }

    private void moveLibrarySelection(int delta) {
        var entries = BlueprintLibrary.entries();
        if (entries.isEmpty()) {
            return;
        }
        int index = LibraryPage.moved(LibraryPage.indexOf(entries, librarySelection), delta, entries.size());
        librarySelection = entries.get(index).path();
        libraryScroll = LibraryPage.scrollToShow(libraryScroll, index, LIBRARY_ROW_HEIGHT,
                libraryContentRect(layout()).height());
    }

    private @Nullable String loadRefusal(@Nullable BlueprintLibrary.Entry selected) {
        return LibraryPage.loadRefusal(selected == null ? null : selected.header(),
                ToolState.get(stack()).blueprintRef().orElse(null), BlueprintLibrary.canChoose());
    }

    private void loadLibrarySelection() {
        var selected = selectedLibraryEntry();
        if (selected == null || loadRefusal(selected) != null) {
            return;
        }
        libraryMessage = null;
        libraryMessageColor = OreColors.MISSING;
        BlueprintLibrary.choose(hand, selected, this::closeLibraryByClick, showLibraryMessage(OreColors.MISSING));
    }

    private void closeLibraryByClick() {
        if (libraryOpen) {
            closeLibrary();
            pageClosedByClick = Util.getMillis();
        }
    }

    private void copyBlueprint() {
        var selected = selectedLibraryEntry();
        if (selected == null) {
            return;
        }
        BlueprintLibrary.toClipboard(selected, Minecraft.getInstance().keyboardHandler::setClipboard,
                showLibraryMessage(OreColors.OK), showLibraryMessage(OreColors.MISSING));
    }

    private void pasteBlueprint() {
        BlueprintLibrary.fromClipboard(Minecraft.getInstance().keyboardHandler.getClipboard(),
                showLibraryMessage(OreColors.OK), showLibraryMessage(OreColors.MISSING));
    }

    private Consumer<Component> showLibraryMessage(int color) {
        return message -> {
            libraryMessage = message.getString();
            libraryMessageColor = color;
        };
    }

    private void addLibraryButtons(List<UiButton> entries, Layout layout) {
        int x = layout.innerX();
        int width = layout.innerWidth();
        var selected = selectedLibraryEntry();
        var copy = new UiButton("library_copy", 0, 0, 0, 22, ButtonKind.BUTTON, theme.buttons(),
                translatable("library_copy"), Component.empty(),
                tooltip(selected != null ? "library_copy" : "library_copy_none"), selected != null, false, null, null,
                this::copyBlueprint);
        var paste = secondary("library_paste", translatable("library_paste"), this::pasteBlueprint);
        var refusal = loadRefusal(selected);
        var load = new UiButton("library_load", 0, 0, 0, 22, ButtonKind.BUTTON, theme.accent(),
                translatable("library_load"), Component.empty(), tooltip(refusal == null ? "library_load" : refusal),
                refusal == null, false, null, null, this::loadLibrarySelection);
        addThree(entries, x, clipboardRowY(layout), width, copy, paste, load);
        var refresh = secondary("refresh", translatable("refresh"), BlueprintLibrary::refresh);
        var folder = secondary("open_folder", translatable("open_folder"), BlueprintLibrary::openFolder);
        var done = new UiButton("library_done", 0, 0, 0, 22, ButtonKind.BUTTON, theme.buttons(), translatable("done"),
                Component.empty(), tooltip("library_done"), true, false, null, null, this::closeLibraryByClick);
        addThree(entries, x, layout.actionY(), width, refresh, folder, done);
    }

    private void addThree(List<UiButton> entries, int x, int y, int width, UiButton first, UiButton second,
                          UiButton third) {
        int firstWidth = font.width(first.label()) + 8;
        int secondWidth = font.width(second.label()) + 8;
        int spare = width - 8 - firstWidth - secondWidth - (font.width(third.label()) + 8);
        if (spare >= 0) {
            firstWidth += spare / 3;
            secondWidth += spare / 3;
        } else {
            firstWidth = (width - 8) / 3;
            secondWidth = firstWidth;
        }
        int thirdX = x + firstWidth + secondWidth + 8;
        entries.add(place(first, x, y, firstWidth));
        entries.add(place(second, x + firstWidth + 4, y, secondWidth));
        entries.add(place(third, thirdX, y, x + width - thirdX));
    }

    private void onHexChanged(String value) {
        if (syncingColor) {
            return;
        }
        try {
            int rgb = MMConfig.parseGuiColor(value);
            customColors[colorSlot.ordinal()] = rgb;
            setColor(rgb);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void selectSlot(ThemeColor slot) {
        colorSlot = slot;
        setFocused(null);
        syncHexInput();
    }

    private void selectColor(int rgb) {
        setColor(rgb);
        setFocused(null);
    }

    private void setColor(int rgb) {
        colors[colorSlot.ordinal()] = rgb;
        colorDirty = true;
        rebuildTheme();
    }

    private void resetColors() {
        for (var color : ThemeColor.values()) {
            colors[color.ordinal()] = color.presets[0];
        }
        rebuildTheme();
        colorDirty = false;
        setFocused(null);
        MMConfig.resetGuiThemeColors();
    }

    private void rebuildTheme() {
        theme = OreTheme.of(colors[ThemeColor.ACCENT.ordinal()], colors[ThemeColor.BUTTONS.ordinal()],
                colors[ThemeColor.INTERFACE.ordinal()]);
    }

    private void syncHexInput() {
        if (hexInput == null) {
            return;
        }
        syncingColor = true;
        hexInput.setValue(MMConfig.formatGuiColor(customColors[colorSlot.ordinal()]));
        syncingColor = false;
    }

    private void commitTheme() {
        if (!colorDirty) {
            return;
        }
        MMConfig.setGuiThemeColors(colors[ThemeColor.ACCENT.ordinal()], colors[ThemeColor.BUTTONS.ordinal()],
                colors[ThemeColor.INTERFACE.ordinal()]);
        colorDirty = false;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, Layout layout, ItemStack stack) {
        int x = layout.x();
        int bodyY = layout.bodyY();
        int bodyHeight = layout.bodyHeight();
        OrePainter.outline(graphics, x - 1, layout.y() - 1, layout.width() + 2, layout.height() + 2,
                withOpacity(theme.gray100()));
        OrePainter.navBar(graphics, font, theme, x, layout.y(), layout.width(), Component.empty(), opacity * 255 / 100);
        OrePainter.rect(graphics, x, bodyY, layout.sidebarWidth(), bodyHeight, withOpacity(theme.gray80()));
        OrePainter.rect(graphics, x + layout.sidebarWidth(), bodyY, 1, bodyHeight, withOpacity(theme.gray100()));
        OrePainter.rect(graphics, layout.controlsX(), bodyY, layout.controlsWidth(), bodyHeight, withOpacity(theme.gray70()));
        OrePainter.rect(graphics, layout.controlsX() + layout.controlsWidth(), bodyY, 1, bodyHeight,
                withOpacity(theme.gray100()));
        OrePainter.rect(graphics, layout.visualX(), bodyY, layout.visualWidth(), bodyHeight, withOpacity(theme.gray80()));
        drawNavBarContent(graphics, layout, stack);
        drawSidebar(graphics, layout);
    }

    private void drawNavBarContent(GuiGraphicsExtractor graphics, Layout layout, ItemStack stack) {
        int y = layout.y();
        int titleLeft = layout.x() + 26;
        int titleRight = layout.right() - 8;
        if (stack.getItem() instanceof ManipulatorItem item) {
            double ratio = Mth.clamp(StackEnergyStorage.stored(stack)
                    / (double) Math.max(1, item.tier().energyCapacity()), 0.0D, 1.0D);
            var percent = Math.round(ratio * 100.0D) + "%";
            int right = layout.right() - 8;
            text(graphics, percent, right - font.width(percent), y + 7, GRAY_100);
            int barWidth = layout.compact() ? 40 : 64;
            int barX = right - Math.max(22, font.width(percent) + 4) - barWidth;
            OrePainter.progressBar(graphics, theme, barX, y + 7, barWidth, ratio);
            int left = barX;
            if (!layout.compact()) {
                var energyLabel = translatable("energy").getString();
                left = barX - 5 - font.width(energyLabel);
                text(graphics, energyLabel, left, y + 7, GRAY_60);
            }
            var tier = item.tier().displayName();
            left -= 8 + OrePainter.tagWidth(font, tier);
            OrePainter.tag(graphics, font, left, y + 5, tier, OreColors.INFO_TAG);
            titleRight = left - 8;
        }
        var title = getTitle().getString();
        int titleX = layout.x() + (layout.width() - OreText.width(font, title, true)) / 2;
        if (titleX < titleLeft || titleX + OreText.width(font, title, true) > titleRight) {
            title = OreText.fit(font, title, titleRight - titleLeft, true);
            titleX = titleLeft + (titleRight - titleLeft - OreText.width(font, title, true)) / 2;
        }
        OreText.drawBold(graphics, font, title, titleX, y + 7, GRAY_100, false);
    }

    private void drawSidebar(GuiGraphicsExtractor graphics, Layout layout) {
        int x = layout.x() + 8;
        int width = layout.sidebarWidth() - 16;
        label(graphics, translatable("operation"), x, layout.bodyY() + 8, GRAY_40, width);
        int separatorY = layout.settingsY() - 7;
        OrePainter.rect(graphics, x, separatorY, width, 1, theme.gray100());
        OrePainter.rect(graphics, x, separatorY + 1, width, 1, theme.gray70());
        if (layout.settingsY() + 22 <= layout.bottom() - 18) {
            label(graphics, translatable("escape_hint"), x, layout.bottom() - 14, GRAY_50, width);
        }
    }

    private void drawControls(GuiGraphicsExtractor graphics, Layout layout, ToolState state, List<UiButton> buttons) {
        var flow = controlFlow(layout, state);
        int x = layout.innerX();
        int width = layout.innerWidth();
        var mode = state.mode();
        boolean transformMode = mode == ManipulatorMode.COPY || mode == ManipulatorMode.MOVE
                || mode == ManipulatorMode.BLUEPRINT;
        drawHeader(graphics, mode.displayName(), x, flow.headerY(), width);
        if (flow.description()) {
            drawDescription(graphics, translatable("mode_description." + mode.getSerializedName()), x,
                    flow.headerY() + 14, width);
        }
        if (flow.configLabelY() >= 0 && flow.packed()) {
            for (var button : buttons) {
                if (button.kind() == ButtonKind.DROPDOWN || button.id().equals("cable_route")) {
                    label(graphics, translatable(button.id()), button.x(), flow.configLabelY(), GRAY_40,
                            button.width());
                }
            }
        } else if (flow.configLabelY() >= 0) {
            label(graphics, translatable(transformMode ? "transform" : "configuration"), x, flow.configLabelY(),
                    GRAY_40, width);
        }

        var rows = flow.rowYs();
        if (transformMode) {
            int labelWidth = width - transformAreaWidth(width) - 4;
            fieldLabel(graphics, "rotate", x, rows[0], labelWidth);
            fieldLabel(graphics, "mirror", x, rows[1], labelWidth);
            if (mode != ManipulatorMode.MOVE && !flow.packed()) {
                fieldLabel(graphics, "stack", x, rows[2], labelWidth);
            }
            if (mode == ManipulatorMode.COPY && savePromptOpen) {
                fieldLabel(graphics, "blueprint_name", x, flow.packed() ? rows[2] : rows[3], labelWidth);
                var field = nameField(layout, state);
                OrePainter.field(graphics, theme, field.x(), field.y(), field.width(), field.height(),
                        nameInput.isFocused());
            }
        } else if (!flow.packed()) {
            int labelWidth = width - fieldWidth(width) - 4;
            switch (mode) {
                case GEOMETRY -> {
                    fieldLabel(graphics, "shape", x, rows[0], labelWidth);
                    fieldLabel(graphics, "remove", x, rows[1], labelWidth);
                }
                case CABLE -> {
                    fieldLabel(graphics, "remove", x, rows[0], labelWidth);
                    fieldLabel(graphics, "cable_route", x, rows[1], labelWidth);
                }
                case EXCHANGE -> fieldLabel(graphics, "remove", x, rows[0], labelWidth);
                default -> {
                }
            }
        }

        if (flow.selectionLabelY() >= 0) {
            label(graphics, translatable("selection"), x, flow.selectionLabelY(), GRAY_40, width);
        }
        OrePainter.well(graphics, theme, x, flow.wellY(), width, flow.wellHeight());
        drawSelectionReadouts(graphics, x, flow.wellY(), width, flow.wellHeight(), state, flow.collapsed());
        if (flow.placementLabelY() >= 0) {
            label(graphics, translatable("placement"), x, flow.placementLabelY(), GRAY_40, width);
        }

        int dividerY = layout.actionY() - 7;
        OrePainter.rect(graphics, layout.controlsX(), dividerY, layout.controlsWidth(), 1, theme.gray100());
        OrePainter.rect(graphics, layout.controlsX(), dividerY + 1, layout.controlsWidth(), 1,
                OreColors.mix(theme.gray70(), 0.2));
    }

    private void drawSettingsPage(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        var flow = settingsFlow(layout);
        int x = layout.innerX();
        int width = layout.innerWidth();
        drawHeader(graphics, translatable("settings"), x, flow.headerY(), width);
        if (flow.description()) {
            drawDescription(graphics, translatable("appearance_description"), x, flow.headerY() + 14, width);
        }
        label(graphics, translatable("presets"), x, flow.presetsLabelY(), GRAY_40, width);
        label(graphics, translatable("custom"), x, flow.customLabelY(), GRAY_40, width);
        var field = hexField(layout);
        OrePainter.field(graphics, theme, field.x(), field.y(), field.width(), field.height(),
                hexInput != null && hexInput.isFocused());
        if (flow.speedY() >= 0) {
            drawSpeedSlider(graphics, layout, flow, mouseX, mouseY);
        }
        if (flow.opacityY() >= 0) {
            drawOpacitySlider(graphics, layout, flow, mouseX, mouseY);
        }
    }

    private void drawLibraryPage(GuiGraphicsExtractor graphics, Layout layout, ToolState state) {
        int x = layout.innerX();
        int width = layout.innerWidth();
        int headerY = layout.bodyY() + (layout.dense() ? 8 : 10);
        drawHeader(graphics, translatable("library"), x, headerY, width);
        if (!layout.dense()) {
            drawDescription(graphics, translatable("library_description"), x, headerY + 14, width);
        }
        var well = libraryWell(layout);
        OrePainter.well(graphics, theme, well.x(), well.y(), well.width(), well.height());
        var message = libraryMessageLines(layout);
        for (int line = 0; line < message.size(); line++) {
            text(graphics, message.get(line), x, well.bottom() + 3 + line * 10, libraryMessageColor);
        }
        if (!message.isEmpty() && !libraryMessage.endsWith(message.get(message.size() - 1))) {
            hints.add(new Hint(new Rect(x, well.bottom() + 1, width, message.size() * 10 + 2), libraryMessage));
        }
        var content = libraryContentRect(layout);
        var entries = BlueprintLibrary.entries();
        if (entries.isEmpty()) {
            drawEmptyLibrary(graphics, content);
            return;
        }
        int total = entries.size() * LIBRARY_ROW_HEIGHT;
        libraryScroll = LibraryPage.clampScroll(libraryScroll, total, content.height());
        int rowWidth = libraryRowWidth(content, entries.size());
        var ref = state.blueprintRef().orElse(null);
        graphics.enableScissor(content.x(), content.y(), content.right(), content.bottom());
        int first = libraryScroll / LIBRARY_ROW_HEIGHT;
        int offset = -(libraryScroll % LIBRARY_ROW_HEIGHT);
        int visible = content.height() / LIBRARY_ROW_HEIGHT + 2;
        for (int row = 0; row < visible && first + row < entries.size(); row++) {
            int index = first + row;
            var header = entries.get(index).header();
            int rowY = content.y() + offset + row * LIBRARY_ROW_HEIGHT;
            boolean hovered = input.state(new LibraryRow(entries.get(index).path()), true) != OreState.DEFAULT;
            boolean selected = entries.get(index).path().equals(librarySelection);
            OrePainter.listRow(graphics, theme, content.x(), rowY, rowWidth, LIBRARY_ROW_HEIGHT, index,
                    hovered || selected);
            if (selected) {
                OrePainter.outline(graphics, content.x(), rowY, rowWidth, LIBRARY_ROW_HEIGHT, theme.accent().face());
            }
            var size = Component.literal(dimensions(header.size()));
            int tagX = content.x() + rowWidth - 5 - OrePainter.tagWidth(font, size);
            OrePainter.tag(graphics, font, tagX, rowY + 4, size, GRAY_30);
            int nameRight = tagX - 5;
            if (LibraryPage.isToolBlueprint(header, ref)) {
                int tickX = tagX - 4 - OreIcons.TICK.width();
                OreIcons.TICK.draw(graphics, tickX, rowY + 7, OreColors.OK);
                nameRight = tickX - 4;
            }
            int hintTop = Math.max(rowY, content.y());
            int hintBottom = Math.min(rowY + LIBRARY_ROW_HEIGHT, content.bottom());
            drawBlueprintName(graphics, header.name(), content.x() + 6, rowY + 7, nameRight - content.x() - 6,
                    missingModsWarning(header), new Rect(content.x(), hintTop, rowWidth, hintBottom - hintTop));
        }
        graphics.disableScissor();
        OrePainter.scrollbar(graphics, theme, content.right() - 2, content.y(), content.height(), total,
                libraryScroll);
    }

    private void drawEmptyLibrary(GuiGraphicsExtractor graphics, Rect content) {
        var folder = BlueprintLibrary.folder().toAbsolutePath();
        var gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath();
        var shown = folder.startsWith(gameDirectory) ? gameDirectory.relativize(folder) : folder;
        var message = font.split(translatable("library_empty"), Math.max(20, content.width() - 16));
        var path = pathLines(shown.toString(), Math.max(20, content.width() - 16));
        int y = content.y() + Math.max(4, (content.height() - (message.size() + path.size()) * 10 - 4) / 2);
        for (var line : message) {
            graphics.text(font, line, content.x() + (content.width() - font.width(line)) / 2, y, GRAY_30, false);
            y += 10;
        }
        y += 4;
        hints.add(new Hint(new Rect(content.x(), y - 2, content.width(), path.size() * 10 + 2), folder.toString()));
        for (var line : path) {
            graphics.text(font, line, content.x() + (content.width() - font.width(line)) / 2, y, WHITE, false);
            y += 10;
        }
    }

    private List<FormattedCharSequence> pathLines(String path, int width) {
        var lines = new ArrayList<FormattedCharSequence>();
        var line = "";
        for (var part : path.split("(?<=[/\\\\])")) {
            if (!line.isEmpty() && font.width(line + part) > width) {
                lines.addAll(font.split(Component.literal(line), width));
                line = "";
            }
            line += part;
        }
        lines.addAll(font.split(Component.literal(line), width));
        return lines;
    }

    private void drawBlueprintName(GuiGraphicsExtractor graphics, String name, int x, int y, int maxWidth,
                                   List<String> warnings, Rect hintArea) {
        var shown = OreText.shorten(font, name, maxWidth);
        text(graphics, shown, x, y, warnings.isEmpty() ? WHITE : OreColors.WARN);
        var hint = new ArrayList<String>();
        if (!shown.equals(name)) {
            hint.add(name);
        }
        hint.addAll(warnings);
        if (!hint.isEmpty()) {
            hints.add(new Hint(hintArea, String.join("\n", hint)));
        }
    }

    private List<String> missingModsWarning(Blueprint.Header header) {
        var missing = BlueprintLibrary.missingMods(header);
        return missing.isEmpty()
                ? List.of()
                : List.of(translatable("blueprint_missing_mods", String.join(", ", missing)).getString());
    }

    private static String dimensions(BlockPos size) {
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    private Rect libraryWell(Layout layout) {
        int top = layout.bodyY() + (layout.dense() ? 22 : 48);
        int messageLines = libraryMessageLines(layout).size();
        int bottom = clipboardRowY(layout) - 9 - (messageLines == 0 ? 0 : messageLines * 10 + 2);
        return new Rect(layout.innerX(), top, layout.innerWidth(), bottom - top);
    }

    private static int clipboardRowY(Layout layout) {
        return layout.actionY() - 26;
    }

    private List<String> libraryMessageLines(Layout layout) {
        return libraryMessage == null ? List.of() : OreText.wrap(font, libraryMessage, layout.innerWidth(), 3, false);
    }

    private Rect libraryContentRect(Layout layout) {
        var well = libraryWell(layout);
        return new Rect(well.x() + 1, well.y() + 1, well.width() - 2, well.height() - 2);
    }

    private static int libraryRowWidth(Rect content, int rows) {
        return content.width() - (rows * LIBRARY_ROW_HEIGHT > content.height() ? 3 : 0);
    }

    private @Nullable LibraryRow libraryRowAt(Layout layout, int mouseX, int mouseY) {
        var content = libraryContentRect(layout);
        var entries = BlueprintLibrary.entries();
        if (mouseX < content.x() || mouseX >= content.x() + libraryRowWidth(content, entries.size())) {
            return null;
        }
        int index = LibraryPage.rowAt(mouseY, content.y(), content.height(), libraryScroll, LIBRARY_ROW_HEIGHT,
                entries.size());
        return index < 0 ? null : new LibraryRow(entries.get(index).path());
    }

    private void drawOpacitySlider(GuiGraphicsExtractor graphics, Layout layout, SettingsFlow flow, int mouseX, int mouseY) {
        int x = layout.innerX();
        int width = layout.innerWidth();
        var label = translatable("gui_opacity");
        label(graphics, label, x, flow.opacityLabelY(), GRAY_40, width);
        var value = translatable("gui_opacity_value", opacity).getString();
        int valueWidth = Math.min(font.width(value), width - font.width(label) - 6);
        if (valueWidth > 0) {
            data(graphics, value, x + width - valueWidth, flow.opacityLabelY(), WHITE, valueWidth);
        }
        var slider = opacityRect(layout);
        var state = draggingOpacity ? OreState.PRESSED
                : slider.contains(mouseX, mouseY) ? OreState.HOVER : OreState.DEFAULT;
        OrePainter.slider(graphics, theme, slider.x(), slider.y(), slider.width(), opacity / 100.0, state);
        hints.add(new Hint(slider, tooltip("gui_opacity").getString()));
    }

    static int opacityAt(int sliderX, int sliderWidth, double mouseX) {
        double ratio = (mouseX - sliderX - 4) / Math.max(1, sliderWidth - 9);
        return Mth.clamp((int) Math.round(ratio * 20), 0, 20) * 5;
    }

    private int withOpacity(int argb) {
        return (argb >>> 24) * opacity / 100 << 24 | argb & 0xFFFFFF;
    }

    private void drawSpeedSlider(GuiGraphicsExtractor graphics, Layout layout, SettingsFlow flow, int mouseX, int mouseY) {
        var stack = stack();
        if (!(stack.getItem() instanceof ManipulatorItem item)) {
            return;
        }
        int x = layout.innerX();
        int width = layout.innerWidth();
        var stops = speedStops(item.tier());
        int speed = pendingSpeed >= 0 ? pendingSpeed : ToolState.placementSpeed(stack, item.tier());
        int stop = stopBelow(stops, speed);
        var label = translatable("placement_speed");
        label(graphics, label, x, flow.speedLabelY(), GRAY_40, width);
        var value = translatable("placement_speed_value", stops[stop]).getString();
        int valueWidth = Math.min(font.width(value), width - font.width(label) - 6);
        if (valueWidth > 0) {
            data(graphics, value, x + width - valueWidth, flow.speedLabelY(), WHITE, valueWidth);
        }
        var slider = speedRect(layout);
        var state = draggingSpeed ? OreState.PRESSED
                : slider.contains(mouseX, mouseY) ? OreState.HOVER : OreState.DEFAULT;
        OrePainter.slider(graphics, theme, slider.x(), slider.y(), slider.width(),
                stops.length == 1 ? 1.0 : stop / (double) (stops.length - 1), state);
        hints.add(new Hint(slider, tooltip("placement_speed").getString()));
    }

    static int[] speedStops(ManipulatorTier tier) {
        var stops = new ArrayList<Integer>();
        for (int speed = 1; speed < tier.blocksPerSecond(); speed *= 2) {
            stops.add(speed);
        }
        stops.add(tier.blocksPerSecond());
        return stops.stream().mapToInt(Integer::intValue).toArray();
    }

    static int stopBelow(int[] stops, int speed) {
        int index = 0;
        while (index + 1 < stops.length && stops[index + 1] <= speed) {
            index++;
        }
        return index;
    }

    static int speedAt(int[] stops, int sliderX, int sliderWidth, double mouseX) {
        double ratio = (mouseX - sliderX - 4) / Math.max(1, sliderWidth - 9);
        return stops[Mth.clamp((int) Math.round(ratio * (stops.length - 1)), 0, stops.length - 1)];
    }

    private int speedAt(Layout layout, double mouseX) {
        var stack = stack();
        if (!(stack.getItem() instanceof ManipulatorItem item)) {
            return -1;
        }
        var slider = speedRect(layout);
        return speedAt(speedStops(item.tier()), slider.x(), slider.width(), mouseX);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, Component title, int x, int y, int width) {
        var text = OreText.fit(font, title.getString().toUpperCase(Locale.ROOT), width, true);
        OreText.drawBold(graphics, font, text, x, y, WHITE, true);
    }

    private void drawDescription(GuiGraphicsExtractor graphics, Component description, int x, int y, int width) {
        var lines = OreText.wrap(font, description.getString(), width, 2, true);
        for (int line = 0; line < lines.size(); line++) {
            text(graphics, lines.get(line), x, y + line * 10, GRAY_30);
        }
    }

    private void fieldLabel(GuiGraphicsExtractor graphics, String key, int x, int rowY, int width) {
        label(graphics, translatable(key), x, rowY + 6, WHITE, width);
    }

    private void drawVisualPane(GuiGraphicsExtractor graphics, Layout layout, ToolState state, int mouseX, int mouseY) {
        int x = layout.visualX() + 8;
        int width = layout.visualWidth() - 16;
        if (!layout.compact()) {
            var label = translatable("live_projection").getString();
            text(graphics, label, x, layout.bodyY() + 8, GRAY_40);
            var hint = OreText.fit(font, translatable("preview_controls").getString(), width - font.width(label) - 8,
                    false);
            text(graphics, hint, x + width - font.width(hint), layout.bodyY() + 8, GRAY_50);
        }
        if (showsPreview(layout)) {
            var well = previewWell(layout);
            OrePainter.well(graphics, theme, well.x(), well.y(), well.width(), well.height());
            if (previewScene.isEmpty() && libraryOpen) {
                drawLibraryPreviewState(graphics, well);
            } else if (previewScene.isEmpty()) {
                drawCenteredWrapped(
                        graphics,
                        hasCurrentStatus() && !status.valid()
                                ? status.error()
                                : translatable("preview_empty"),
                        well,
                        GRAY_30
                );
            } else {
                previewScene.draw(graphics, well.x() + 1, well.y() + 1, well.width() - 2, well.height() - 2);
                if (previewScene.isTruncated()) {
                    var lines = OreText.wrap(font, translatable("preview_sampled").getString(), well.width() - 10, 5,
                            true);
                    int top = well.bottom() - 2 - lines.size() * 10;
                    for (int line = 0; line < lines.size(); line++) {
                        text(graphics, lines.get(line), well.x() + 5, top + line * 10, OreColors.WARN);
                    }
                }
            }
        }
        if (showsMaterials(layout)) {
            drawMaterials(graphics, layout, state, mouseX, mouseY);
        }
    }

    private void drawLibraryPreviewState(GuiGraphicsExtractor graphics, Rect well) {
        var selected = selectedLibraryEntry();
        var load = libraryPreview.load();
        if (selected == null || load == null) {
            drawCenteredWrapped(graphics, translatable("library_preview_none"), well, GRAY_30);
        } else if (load.failure() != null) {
            drawCenteredWrapped(graphics, Component.literal(BlueprintLibrary.unreadable(selected, load.failure())
                    .getString()), well, OreColors.MISSING);
        } else if (load.blueprint() == null) {
            drawCenteredWrapped(graphics, translatable("library_preview_reading", selected.header().name()), well,
                    GRAY_30);
        } else if (load.blueprint().volume() > MMConfig.MAX_PREVIEW_INSPECTIONS.get()) {
            drawCenteredWrapped(graphics, translatable("library_preview_too_large",
                    String.format(Locale.ROOT, "%,d", MMConfig.MAX_PREVIEW_INSPECTIONS.get())), well, GRAY_30);
        } else {
            drawCenteredWrapped(graphics, translatable("library_preview_empty"), well, GRAY_30);
        }
    }

    private void drawMaterials(GuiGraphicsExtractor graphics, Layout layout, ToolState state, int mouseX, int mouseY) {
        int x = layout.visualX() + 8;
        int width = layout.visualWidth() - 16;
        int top = materialsTop(layout);
        int summaryLeft = x;
        if (!layout.compact()) {
            var label = translatable("preflight").getString();
            text(graphics, label, x, top + 2, GRAY_40);
            summaryLeft += font.width(label) + 6;
        }
        if (hasCurrentStatus() && !status.compiling() && status.valid()) {
            var summary = OreText.fit(font, statusSummary(), x + width - summaryLeft - 8, false);
            if (!summary.isEmpty()) {
                boolean missing = materials.stream().anyMatch(material -> !material.complete());
                var tag = Component.literal(summary);
                OrePainter.tag(graphics, font, x + width - OrePainter.tagWidth(font, tag), top, tag,
                        missing ? OreColors.WARN : OreColors.SUCCESS_TAG);
            }
        } else {
            var lines = OreText.wrap(font, statusSummary(), x + width - summaryLeft,
                    hasCurrentStatus() && status.valid() ? 1 : 2, true);
            for (int line = 0; line < lines.size(); line++) {
                var summary = lines.get(line);
                text(graphics, summary, x + width - font.width(summary), top + 2 + line * 10, GRAY_30);
            }
        }
        if (hasCurrentStatus() && status.valid()) {
            drawMaterialProgress(graphics, x, top + 17, width);
        }

        var well = materialsWell(layout);
        OrePainter.well(graphics, theme, well.x(), well.y(), well.width(), well.height());
        var notice = noticeLines(layout);
        if (!notice.isEmpty()) {
            for (int line = 0; line < notice.size(); line++) {
                text(graphics, notice.get(line), x, well.bottom() + 3 + line * 10, OreColors.WARN);
            }
            var full = status.notice().getString();
            if (!full.endsWith(notice.get(notice.size() - 1))) {
                hints.add(new Hint(new Rect(x, well.bottom() + 1, width, notice.size() * 10 + 2), full));
            }
        }
        var content = materialContentRect(layout);
        if (content.width() <= 0 || content.height() <= 0) {
            return;
        }
        if (!hasCurrentStatus()) {
            drawCenteredWrapped(graphics, translatable("status.loading"), content, GRAY_30);
            return;
        }
        if (!status.valid()) {
            drawCenteredWrapped(graphics, status.error(), content, OreColors.MISSING);
            return;
        }
        if (materials.isEmpty()) {
            int y = drawStatusRow(graphics, content, content.y(), 0,
                    translatable("operations_count", status.totalOperations()), true);
            y = drawStatusRow(graphics, content, y, 1, translatable("materials_none"), true);
            if (!layout.compact() && state.mode() == ManipulatorMode.MOVE) {
                drawStatusRow(graphics, content, y, 2, translatable("move_notice"), false);
            }
            return;
        }

        int total = materials.size() * MATERIAL_ROW_HEIGHT;
        int maxScroll = Math.max(0, total - content.height());
        materialScroll = Mth.clamp(materialScroll, 0, maxScroll);
        int rowWidth = content.width() - (maxScroll > 0 ? 3 : 0);
        graphics.enableScissor(content.x(), content.y(), content.right(), content.bottom());
        int first = materialScroll / MATERIAL_ROW_HEIGHT;
        int offset = -(materialScroll % MATERIAL_ROW_HEIGHT);
        int visible = content.height() / MATERIAL_ROW_HEIGHT + 2;
        for (int row = 0; row < visible; row++) {
            int index = first + row;
            if (index >= materials.size()) {
                break;
            }
            int rowY = content.y() + offset + row * MATERIAL_ROW_HEIGHT;
            var material = materials.get(index);
            boolean hovered = content.contains(mouseX, mouseY) && mouseX < content.x() + rowWidth
                    && mouseY >= rowY && mouseY < rowY + MATERIAL_ROW_HEIGHT;
            OrePainter.listRow(graphics, theme, content.x(), rowY, rowWidth, MATERIAL_ROW_HEIGHT, index, hovered);
            graphics.item(material.stack(), content.x() + 2, rowY + 2);
            var count = material.available() + " / " + material.required();
            int countX = content.x() + rowWidth - 6 - font.width(count);
            text(graphics, count, countX, rowY + 7, material.complete() ? OreColors.OK : OreColors.MISSING);
            var description = OreText.shorten(font, material.description(), countX - 6 - (content.x() + 23));
            text(graphics, description, content.x() + 23, rowY + 7, WHITE);
        }
        graphics.disableScissor();
        OrePainter.scrollbar(graphics, theme, content.right() - 2, content.y(), content.height(), total,
                materialScroll);
    }

    private int drawStatusRow(GuiGraphicsExtractor graphics, Rect content, int y, int index, Component label, boolean done) {
        var lines = OreText.wrap(font, label.getString(), content.width() - 26, 3, true);
        int height = MATERIAL_ROW_HEIGHT + Math.max(0, lines.size() - 1) * 10;
        if (y + height > content.bottom()) {
            return content.bottom();
        }
        OrePainter.listRow(graphics, theme, content.x(), y, content.width(), height, index, false);
        for (int line = 0; line < lines.size(); line++) {
            text(graphics, lines.get(line), content.x() + 6, y + 7 + line * 10, done ? WHITE : OreColors.WARN);
        }
        if (done) {
            OreIcons.TICK.draw(graphics, content.right() - 13, y + 7, OreColors.OK);
        }
        return y + height;
    }

    private void drawMaterialProgress(GuiGraphicsExtractor graphics, int x, int y, int width) {
        long required = materials.stream().mapToLong(ItemRequirementStatus::required).sum();
        long available = materials.stream()
                .mapToLong(material -> Math.min(material.available(), material.required()))
                .sum();
        double ratio = required == 0 ? 1.0D : Mth.clamp(available / (double) required, 0.0D, 1.0D);
        long percent = Math.round(ratio * 100.0D);
        int color = statusColor(ratio);
        var label = translatable("material_progress", percent).getString();
        if (font.width(label) + 46 > width) {
            OrePainter.progressBar(graphics, theme, x, y, width, ratio);
            var compactLabel = percent + "%";
            int labelX = x + (width - font.width(compactLabel)) / 2;
            OrePainter.rect(graphics, labelX - 2, y, font.width(compactLabel) + 3, 8, theme.gray80());
            text(graphics, compactLabel, labelX, y, color);
            return;
        }
        text(graphics, label, x, y, color);
        int barX = x + font.width(label) + 6;
        OrePainter.progressBar(graphics, theme, barX, y, x + width - barX, ratio);
    }

    private static int statusColor(double ratio) {
        return ratio >= 1.0D ? OreColors.OK : ratio > 0.0D ? OreColors.WARN : OreColors.MISSING;
    }

    private int materialsTop(Layout layout) {
        return layout.compact() ? compactPaneTop(layout) : previewWell(layout).bottom() + 8;
    }

    private boolean stackedTabs(Layout layout) {
        int tabWidth = (layout.visualWidth() - 20) / 2;
        return Math.max(font.width(translatable("preview")), font.width(translatable("materials"))) + 8 > tabWidth;
    }

    private int compactPaneTop(Layout layout) {
        return layout.bodyY() + (stackedTabs(layout) ? 62 : 36);
    }

    private Rect previewWell(Layout layout) {
        int x = layout.visualX() + 8;
        int width = layout.visualWidth() - 16;
        if (layout.compact()) {
            int top = compactPaneTop(layout);
            return new Rect(x, top, width, Math.max(40, layout.bottom() - 8 - top));
        }
        return new Rect(x, layout.bodyY() + 20, width, (layout.bodyHeight() - 66) * 62 / 100);
    }

    private Rect materialsWell(Layout layout) {
        int top = materialsTop(layout) + 30;
        var notice = noticeLines(layout);
        int bottom = layout.bottom() - 8 - (notice.isEmpty() ? 0 : notice.size() * 10 + 2);
        return new Rect(layout.visualX() + 8, top, layout.visualWidth() - 16, Math.max(22, bottom - top));
    }

    private List<String> noticeLines(Layout layout) {
        if (!hasNotice()) {
            return List.of();
        }
        int area = layout.bottom() - 8 - (materialsTop(layout) + 30);
        int maxLines = Math.max(1, (area - MIN_MATERIALS_WELL - 2) / 10);
        return OreText.wrap(font, status.notice().getString(), layout.visualWidth() - 16, maxLines, false);
    }

    private Rect materialContentRect(Layout layout) {
        var well = materialsWell(layout);
        return new Rect(well.x() + 1, well.y() + 1, well.width() - 2, well.height() - 2);
    }

    private boolean hasNotice() {
        return hasCurrentStatus() && status.valid() && !status.notice().getString().isEmpty();
    }

    private void drawCenteredWrapped(GuiGraphicsExtractor graphics, Component component, Rect rect, int color) {
        var lines = font.split(component, Math.max(20, rect.width() - 16));
        int y = rect.y() + Math.max(0, (rect.height() - lines.size() * 10) / 2);
        for (var line : lines) {
            graphics.text(font, line, rect.x() + (rect.width() - font.width(line)) / 2, y, color, false);
            y += 10;
        }
    }

    private String statusSummary() {
        if (!hasCurrentStatus() || status.compiling()) {
            return MMScreen.STATUS_LOADING.text().getString();
        }
        if (!status.valid()) {
            return MMScreen.STATUS_INVALID.text().getString();
        }
        long missing = materials.stream().filter(material -> !material.complete()).count();
        return MMScreen.STATUS_SUMMARY.text(
                status.totalOperations(),
                missing
        ).getString();
    }

    private DropdownMenu dropdownMenu(List<UiButton> buttons, Layout layout, ToolState state) {
        if (openDropdown == null || settingsOpen) {
            return null;
        }
        UiButton trigger = null;
        for (var button : buttons) {
            if (button.dropdownKind() == openDropdown) {
                trigger = button;
                break;
            }
        }
        if (trigger == null) {
            return null;
        }

        var options = dropdownOptions(openDropdown);
        int selected = dropdownSelection(openDropdown, state);
        int menuHeight = options.size() * OrePainter.DROPDOWN_ROW_HEIGHT + 2;
        int menuY = trigger.bottom() - 1;
        if (menuY + menuHeight > layout.actionY() - 7) {
            menuY = trigger.y() - menuHeight + 1;
        }
        menuY = Mth.clamp(menuY, layout.bodyY(), layout.bottom() - menuHeight);
        return new DropdownMenu(
                openDropdown,
                trigger,
                options,
                selected,
                new Rect(trigger.x(), menuY, trigger.width(), menuHeight)
        );
    }

    private static List<Component> dropdownOptions(DropdownKind kind) {
        var options = new ArrayList<Component>();
        switch (kind) {
            case SHAPE -> {
                for (var shape : ShapeType.values()) {
                    options.add(shape.displayName());
                }
            }
            case REMOVE -> {
                for (var mode : RemoveMode.values()) {
                    options.add(mode.displayName());
                }
            }
        }
        return List.copyOf(options);
    }

    private static int dropdownSelection(DropdownKind kind, ToolState state) {
        return switch (kind) {
            case SHAPE -> state.shape().ordinal();
            case REMOVE -> state.removeMode().ordinal();
        };
    }

    private void selectDropdown(DropdownMenu dropdown, int option) {
        openDropdown = null;
        send(dropdown.kind().action, option, false);
    }

    private void drawDropdown(GuiGraphicsExtractor graphics, DropdownMenu dropdown) {
        int hovered = -1;
        boolean pressed = false;
        for (int index = 0; index < dropdown.options().size(); index++) {
            var state = input.state(new OptionId(index), true);
            if (state != OreState.DEFAULT) {
                hovered = index;
                pressed = state == OreState.PRESSED;
            }
        }
        var rect = dropdown.rect();
        OrePainter.dropdownList(graphics, font, theme, rect.x(), rect.y(), rect.width(), dropdown.options(),
                dropdown.selected(), hovered, pressed);
    }

    private @Nullable Object controlAt(List<UiButton> buttons, @Nullable DropdownMenu dropdown, int mouseX,
                                       int mouseY) {
        if (dropdown != null) {
            int option = dropdown.optionAt(mouseX, mouseY);
            return option < 0 ? null : new OptionId(option);
        }
        for (var button : buttons) {
            if (button.enabled() && button.contains(mouseX, mouseY)) {
                return button.id();
            }
        }
        return libraryOpen ? libraryRowAt(layout(), mouseX, mouseY) : null;
    }

    private void activate(@Nullable Object control, List<UiButton> buttons, @Nullable DropdownMenu dropdown) {
        if (control instanceof OptionId option && dropdown != null) {
            selectDropdown(dropdown, option.index());
            return;
        }
        if (control instanceof LibraryRow row) {
            clickLibraryRow(row.path());
            return;
        }
        for (var button : buttons) {
            if (button.id().equals(control)) {
                button.activate().run();
                return;
            }
        }
    }

    private void drawButton(GuiGraphicsExtractor graphics, UiButton button, boolean compact) {
        var state = input.state(button.id(), button.enabled());
        int x = button.x();
        int y = button.y();
        int w = button.width();
        int h = button.height();
        switch (button.kind()) {
            case BUTTON -> OrePainter.button(graphics, font, theme, x, y, w, h, button.variant(), state, button.label());
            case HERO -> OrePainter.heroButton(graphics, font, theme, x, y, w, h, button.variant(), state,
                    button.label());
            case ICON -> OrePainter.iconButton(graphics, theme, x, y, w, h, button.variant(), state, button.icon());
            case TAB -> OrePainter.toggleTab(graphics, font, theme, x, y, w, h, state, button.selected(),
                    button.label());
            case DROPDOWN -> OrePainter.listButton(graphics, font, theme, x, y, w, h, button.variant(), state, null,
                    button.label(), Component.empty(), OreIcons.CHEVRON);
            case SWITCH -> {
                label(graphics, button.label(), x, y + 4, WHITE, w - 32);
                OrePainter.toggleSwitch(graphics, theme, x + w - 28, y, button.selected(), state);
            }
            case SLOT -> {
                if (button.selected()) {
                    OrePainter.selectedTab(graphics, font, theme, x, y, w, h, button.label());
                } else {
                    OrePainter.button(graphics, font, theme, x, y, w, h, button.variant(), state, button.label());
                }
            }
            case MODE -> {
                if (button.selected()) {
                    OrePainter.selectedTab(graphics, font, theme, x, y, w, h, compact ? null : button.icon(),
                            button.label());
                } else if (compact) {
                    OrePainter.listButton(graphics, font, theme, x, y, w, h, button.variant(), state, null,
                            button.label(), Component.empty(), button.enabled() ? null : OreIcons.LOCK);
                } else {
                    OrePainter.listButton(graphics, font, theme, x, y, w, h, button.variant(), state, button.icon(),
                            button.label(), button.value(), null);
                }
            }
        }
    }

    private void drawSelectionReadouts(GuiGraphicsExtractor graphics, int x, int y, int width, int height, ToolState state,
                                       boolean collapsed) {
        var minecraft = Minecraft.getInstance();
        var anchors = minecraft.player == null
                ? new FloatingPlacement.Anchors(state.coordA(), state.coordB(), state.coordC())
                : PasteFollow.anchors(minecraft, stack(), 1.0F);
        if (state.mode() == ManipulatorMode.BLUEPRINT) {
            drawBlueprintReadouts(graphics, x, y, width, height, state, anchors.c().orElse(null), collapsed);
            return;
        }
        int right = x + width - 53;

        if (state.mode() == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES) {
            int valueX = x + 6;
            if (!collapsed) {
                valueX = x + 9 + OrePainter.tag(graphics, font, x + 5, y + (height - 11) / 2, Component.literal("PATH"),
                        GRAY_30);
            }
            label(graphics, translatable("cable_route_points", state.cableWaypoints().size()), valueX,
                    y + (height - 7) / 2, WHITE, right - valueX);
            return;
        }

        var marks = List.of(anchors.a(), anchors.b(), anchors.c()).subList(0, selectionMarkCount(state));
        if (collapsed) {
            int lineY = y + (height - (marks.size() * 10 - 3)) / 2;
            for (int index = 0; index < marks.size(); index++) {
                data(graphics, MARKERS[index] + " " + coord(marks.get(index).orElse(null)), x + 6,
                        lineY + index * 10, WHITE, right - x - 6);
            }
            return;
        }
        int rowsTop = y + (height - (marks.size() * 15 - 4)) / 2;
        for (int index = 0; index < marks.size(); index++) {
            drawMark(graphics, MARKERS[index], marks.get(index).orElse(null), x + 5, rowsTop + index * 15, right);
        }
    }

    private void drawBlueprintReadouts(GuiGraphicsExtractor graphics, int x, int y, int width, int height, ToolState state,
                                       @Nullable BlockPos c, boolean collapsed) {
        int right = x + width - 77;
        int top = y + (height - (collapsed ? 17 : 26)) / 2;
        int nameY = collapsed ? top : top + 2;
        int valueX = x + (collapsed ? 6 : 5);
        var ref = state.blueprintRef().orElse(null);
        var uploading = BlueprintLibrary.uploading();
        if (uploading != null) {
            data(graphics, translatable("blueprint_uploading", uploading).getString(), valueX, nameY, GRAY_30,
                    right - valueX);
        } else if (ref == null) {
            label(graphics, translatable("blueprint_none"), valueX, nameY, OreColors.WARN, right - valueX);
        } else {
            var size = dimensions(ref.size());
            int sizeWidth = collapsed ? font.width(size) : OrePainter.tagWidth(font, Component.literal(size));
            var name = ref.name();
            if (sizeWidth < right - valueX) {
                if (collapsed) {
                    text(graphics, size, valueX, nameY, GRAY_30);
                } else {
                    OrePainter.tag(graphics, font, valueX, top, Component.literal(size), GRAY_30);
                }
                valueX += sizeWidth + 4;
            } else {
                name += " " + size;
            }
            drawBlueprintName(graphics, name, valueX, nameY, right - valueX, blueprintWarnings(ref),
                    new Rect(x, nameY - 2, right - x, 11));
        }
        if (collapsed) {
            data(graphics, "C " + coord(c), x + 6, top + 10, WHITE, right - x - 6);
        } else {
            drawMark(graphics, "C", c, x + 5, top + 15, right);
        }
    }

    private List<String> blueprintWarnings(BlueprintRef ref) {
        var entry = BlueprintLibrary.find(ref.hash(), ref.size());
        return entry.isEmpty()
                ? List.of(translatable("blueprint_not_in_library").getString())
                : missingModsWarning(entry.get().header());
    }

    private void drawMark(GuiGraphicsExtractor graphics, String marker, @Nullable BlockPos pos, int x, int y, int right) {
        int tagWidth = OrePainter.tag(graphics, font, x, y, Component.literal(marker), GRAY_30);
        int valueX = x + tagWidth + 4;
        if (pos == null) {
            label(graphics, translatable("not_set"), valueX, y + 2, OreColors.WARN, right - valueX);
        } else {
            data(graphics, coord(pos), valueX, y + 2, WHITE, right - valueX);
        }
    }

    private static int selectionMarkCount(ToolState state) {
        if (state.mode() == ManipulatorMode.CABLE && state.cableRouteMode() == CableRouteMode.EDGES) {
            return 1;
        }
        return state.mode() == ManipulatorMode.CABLE || state.mode() == ManipulatorMode.EXCHANGE
                || state.mode() == ManipulatorMode.BLUEPRINT ? 2 : 3;
    }

    private static String coord(@Nullable BlockPos pos) {
        return pos == null
                ? MMScreen.NOT_SET.text().getString()
                : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public void acceptStatus(GuiStatusPacket packet) {
        if (packet.hand() != hand || packet.requestId() != pendingRequest) {
            return;
        }
        pendingRequest = -1;
        int currentHash = ToolState.get(stack()).previewHash();
        if (!packet.matches(hand, packet.requestId(), currentHash)) {
            requestedHash = Integer.MIN_VALUE;
            refreshTicks = STATUS_REFRESH_TICKS;
            return;
        }
        status = packet;
        materials = packet.materials().stream()
                .sorted(Comparator
                        .comparing(ItemRequirementStatus::complete)
                        .thenComparing(Comparator.comparingLong(ItemRequirementStatus::missing).reversed())
                        .thenComparing(ItemRequirementStatus::description, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int contentHeight = materialContentRect(layout()).height();
        materialScroll = Mth.clamp(materialScroll, 0,
                Math.max(0, materials.size() * MATERIAL_ROW_HEIGHT - contentHeight));
        refreshTicks = packet.compiling() ? STATUS_REFRESH_TICKS - COMPILING_REFRESH_TICKS : 0;
    }

    private void requestStatus() {
        if (pendingRequest >= 0) {
            return;
        }
        var stack = stack();
        if (!(stack.getItem() instanceof ManipulatorItem)) {
            return;
        }
        requestedHash = ToolState.get(stack).previewHash();
        pendingRequest = ++requestSequence;
        refreshTicks = 0;
        var follow = PasteFollow.target(Minecraft.getInstance(), stack, 1.0F);
        ClientPacketDistributor.sendToServer(new GuiStatusRequestPacket(hand, pendingRequest, requestedHash,
                follow == null ? null : follow.c()));
    }

    private void send(ConfigActionPacket.Action action, boolean close) {
        send(action, ConfigActionPacket.NO_SELECTION, close);
    }

    private void send(ConfigActionPacket.Action action, int selection, boolean close) {
        applyLocal(action, selection);
        if (action != ConfigActionPacket.Action.START || !PasteFollow.commit(hand)) {
            ClientPacketDistributor.sendToServer(new ConfigActionPacket(action, hand, selection));
        }
        if (action == ConfigActionPacket.Action.REQUEST_MISSING) {
            refreshTicks = STATUS_REFRESH_TICKS - 5;
        } else if (action != ConfigActionPacket.Action.START && action != ConfigActionPacket.Action.SET_PLACEMENT_SPEED) {
            requestedHash = Integer.MIN_VALUE;
            refreshTicks = STATUS_REFRESH_TICKS;
        }
        if (close) {
            onClose();
        }
    }

    private void drawMaterialTooltip(GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        if (!showsMaterials(layout) || materials.isEmpty()) {
            return;
        }
        var content = materialContentRect(layout);
        int contentX = content.x();
        int contentY = content.y();
        int contentWidth = content.width();
        int contentHeight = content.height();
        if (mouseX < contentX || mouseX >= contentX + contentWidth
                || mouseY < contentY || mouseY >= contentY + contentHeight) {
            return;
        }
        int index = (mouseY - contentY + materialScroll) / MATERIAL_ROW_HEIGHT;
        if (index < 0 || index >= materials.size()) {
            return;
        }
        var material = materials.get(index);
        var lines = new ArrayList<>(material.stack().getTooltipLines(
                Item.TooltipContext.of(Minecraft.getInstance().level),
                Minecraft.getInstance().player,
                TooltipFlag.Default.NORMAL
        ));
        if (!material.description().equals(material.stack().getHoverName().getString())) {
            lines.add(Component.literal(material.description()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(MMScreen.MATERIAL_AVAILABLE.text(
                material.available(),
                material.required()
        ).withStyle(material.complete() ? ChatFormatting.GREEN : ChatFormatting.RED));
        var alternatives = material.alternatives();
        if (!alternatives.isEmpty()) {
            var names = Component.empty();
            for (int i = 0; i < Math.min(3, alternatives.size()); i++) {
                names.append(i == 0 ? Component.empty() : Component.literal(", "));
                names.append(alternatives.get(i).getHoverName());
            }
            lines.add((alternatives.size() <= 3
                    ? MMScreen.MATERIAL_ALTERNATIVES.text(names)
                    : MMScreen.MATERIAL_ALTERNATIVES_MORE.text(names,
                            alternatives.size() - 3)
            ).withStyle(ChatFormatting.GRAY));
        }
        if (material.variants()) {
            lines.add(MMScreen.MATERIAL_VARIANTS.text(
                    material.stack().getHoverName()
            ).withStyle(ChatFormatting.GRAY));
        }
        if (!material.fallback().isEmpty()) {
            lines.add(MMScreen.MATERIAL_FALLBACK.text(
                    material.fallback().getHoverName()
            ).withStyle(ChatFormatting.GRAY));
        }
        if (!material.complete()) {
            lines.add(MMScreen.MATERIAL_MISSING.text(
                    material.missing()
            ).withStyle(ChatFormatting.RED));
        }
        graphics.setTooltipForNextFrame(font, lines, material.stack().getTooltipImage(), mouseX, mouseY);
    }

    private boolean showsPreview(Layout layout) {
        return !layout.compact() || !compactMaterials;
    }

    private boolean showsMaterials(Layout layout) {
        return !layout.compact() || compactMaterials;
    }

    private boolean hasCurrentStatus() {
        if (status == null || status.hand() != hand) {
            return false;
        }
        var stack = stack();
        return stack.getItem() instanceof ManipulatorItem
                && status.stateHash() == ToolState.get(stack).previewHash();
    }

    private void applyLocal(ConfigActionPacket.Action action, int selection) {
        var state = ToolState.get(stack());
        if (ConfigActionPacket.applySelection(state, action, selection)) {
            return;
        }
        switch (action) {
            case CYCLE_MODE -> state.setMode(state.mode().next());
            case CYCLE_SHAPE -> state.setShape(state.shape().next());
            case CYCLE_REMOVE -> state.setRemoveMode(state.removeMode().next());
            case CYCLE_CABLE_ROUTE -> state.cycleCableRouteMode();
            case TOGGLE_FLOATING_PLACEMENT -> state.toggleFloatingPlacement();
            case TOGGLE_AUTO_CRAFT_MISSING -> state.toggleAutoCraftMissing();
            case CLEAR_COORDS -> state.clearSelection();
            case CLEAR_BLUEPRINT -> {
                state.clearBlueprintRef();
                state.clearCoord("C");
            }
            case MODE_GEOMETRY -> state.setMode(ManipulatorMode.GEOMETRY);
            case MODE_CABLE -> state.setMode(ManipulatorMode.CABLE);
            case MODE_EXCHANGE -> state.setMode(ManipulatorMode.EXCHANGE);
            case MODE_COPY -> state.setMode(ManipulatorMode.COPY);
            case MODE_MOVE -> state.setMode(ManipulatorMode.MOVE);
            case MODE_BLUEPRINT -> state.setMode(ManipulatorMode.BLUEPRINT);
            case ROTATE_X -> state.rotateTransform(Direction.Axis.X);
            case ROTATE_Y -> state.rotateTransform(Direction.Axis.Y);
            case ROTATE_Z -> state.rotateTransform(Direction.Axis.Z);
            case MIRROR_X -> state.toggleMirrorX();
            case MIRROR_Y -> state.toggleMirrorY();
            case MIRROR_Z -> state.toggleMirrorZ();
            case STACK_PLUS -> state.changeStackCount(1);
            case STACK_MINUS -> state.changeStackCount(-1);
            case STACK_AXIS -> state.cycleStackAxis();
            case SET_PLACEMENT_SPEED -> ToolState.setPlacementSpeed(stack(), selection);
            default -> {
            }
        }
    }

    private ItemStack stack() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    private static Component translatable(String key, Object... args) {
        return Component.translatable("screen.matter_manipulator." + key, args);
    }

    private static Component tooltip(String key) {
        return Component.translatable("screen.matter_manipulator.tooltip." + key);
    }

    private UiButton secondary(String id, Component label, Runnable action) {
        return new UiButton(id, 0, 0, 0, 22, ButtonKind.BUTTON, theme.buttons(), label, Component.empty(), tooltip(id),
                true, false, null, null, action);
    }

    private UiButton pickButton(String key, boolean replace) {
        return secondary(key, translatable(key), () -> ClientEvents.pickLookedBlock(replace));
    }

    private UiButton keyButton(String label, String tooltipKey, ConfigActionPacket.Action action) {
        return new UiButton(tooltipKey, 0, 0, 0, 22, ButtonKind.BUTTON, theme.buttons(), Component.literal(label),
                Component.empty(), tooltip(tooltipKey), true, false, null, null, () -> send(action, false));
    }

    private UiButton dropdownButton(DropdownKind kind, String key, Component value) {
        return new UiButton(key, 0, 0, 0, 22, ButtonKind.DROPDOWN, theme.buttons(), value, Component.empty(),
                tooltip(key), true, false, null, kind, () -> openDropdown = openDropdown == kind ? null : kind);
    }

    private static UiButton switchButton(String key, boolean on, Runnable action) {
        return new UiButton(key, 0, 0, 0, 15, ButtonKind.SWITCH, OreVariant.SECONDARY, translatable(key),
                Component.empty(), tooltip(key), true, on, null, null, action);
    }

    private static UiButton withLabel(UiButton button, Component label) {
        return new UiButton(button.id(), button.x(), button.y(), button.width(), button.height(), button.kind(),
                button.variant(), label, button.value(), button.tooltip(), button.enabled(), button.selected(),
                button.icon(), button.dropdownKind(), button.activate());
    }

    private static UiButton enabled(UiButton button, boolean enabled) {
        return new UiButton(button.id(), button.x(), button.y(), button.width(), button.height(), button.kind(),
                button.variant(), button.label(), button.value(), button.tooltip(), enabled, button.selected(),
                button.icon(), button.dropdownKind(), button.activate());
    }

    private static UiButton place(UiButton button, int x, int y, int width) {
        return new UiButton(button.id(), x, y, width, button.height(), button.kind(), button.variant(),
                button.label(), button.value(), button.tooltip(), button.enabled(), button.selected(), button.icon(),
                button.dropdownKind(), button.activate());
    }

    private void addPair(List<UiButton> entries, int x, int y, int width, UiButton first, UiButton second) {
        int gap = 4;
        int firstWidth = firstWidth(width, gap, first, second);
        entries.add(place(first, x, y, firstWidth));
        entries.add(place(second, x + firstWidth + gap, y, width - firstWidth - gap));
    }

    private void addSwitchPair(List<UiButton> entries, int x, int y, int width, UiButton first, UiButton second) {
        int firstWidth = firstWidth(width, SWITCH_GAP, first, second);
        entries.add(place(first, x, y, firstWidth));
        entries.add(place(second, x + firstWidth + SWITCH_GAP, y, width - firstWidth - SWITCH_GAP));
    }

    private int firstWidth(int width, int gap, UiButton first, UiButton second) {
        int usable = Math.max(2, width - gap);
        int firstPreferred = preferredWidth(first);
        int secondPreferred = preferredWidth(second);
        int preferred = Math.max(1, firstPreferred + secondPreferred);
        int split = preferred <= usable
                ? firstPreferred + (usable - preferred) / 2
                : usable * firstPreferred / preferred;
        int minimum = Math.min(42, usable / 2);
        return Mth.clamp(split, minimum, usable - minimum);
    }

    private int preferredWidth(UiButton button) {
        int labelWidth = font.width(button.label());
        return switch (button.kind()) {
            case DROPDOWN -> labelWidth + 26;
            case SWITCH -> labelWidth + 32;
            default -> labelWidth + 14;
        };
    }

    private static int fieldWidth(int width) {
        return Math.min(112, width - 50);
    }

    private static int transformAreaWidth(int width) {
        return Math.min(112, width - 46);
    }

    private static int swatchWidth(int width) {
        return Math.min(32, (width - 20) / 6);
    }

    private ControlFlow controlFlow(Layout layout, ToolState state) {
        var mode = state.mode();
        if (!layout.dense()) {
            return flow(layout, state, true, rowSteps(mode, false), true, false, false, false);
        }
        var steps = rowSteps(mode, true);
        int limit = layout.actionY() - 9;
        var labelled = flow(layout, state, true, steps, true, false, false, false);
        if (labelled.bottom() <= limit) {
            return labelled;
        }
        var unlabelled = flow(layout, state, false, steps, false, false, false, false);
        if (unlabelled.bottom() <= limit) {
            return unlabelled;
        }
        var collapsed = flow(layout, state, false, steps, false, false, false, true);
        if (collapsed.bottom() <= limit) {
            return collapsed;
        }
        var pairedSwitches = flow(layout, state, false, steps, false, false, true, true);
        if (pairedSwitches.bottom() <= limit) {
            return pairedSwitches;
        }
        boolean pairLabels = mode != ManipulatorMode.COPY && mode != ManipulatorMode.MOVE
                && mode != ManipulatorMode.BLUEPRINT;
        return flow(layout, state, pairLabels, packedSteps(mode), false, true, true, true);
    }

    private static ControlFlow flow(Layout layout, ToolState state, boolean configLabel, int[] steps,
                                    boolean sectionLabels, boolean packed, boolean pairedSwitches,
                                    boolean collapsed) {
        boolean dense = layout.dense();
        int bodyY = layout.bodyY();
        int configLabelY = configLabel ? bodyY + (dense ? 22 : 50) : -1;
        var rowYs = new int[steps.length];
        int y = configLabel ? configLabelY + 11 : bodyY + 22;
        for (int row = 0; row < steps.length; row++) {
            rowYs[row] = y;
            y += steps[row];
        }
        int selectionGap = dense ? 6 : 9;
        int placementGap = dense ? 6 : 8;
        int wellHeight = Math.max(26, selectionMarkCount(state) * (collapsed ? 10 : 15) + 6);
        int selectionLabelY = sectionLabels ? y + selectionGap : -1;
        int wellY = sectionLabels ? selectionLabelY + 11 : y + selectionGap;
        int placementLabelY = sectionLabels ? wellY + wellHeight + placementGap : -1;
        int placementY = sectionLabels ? placementLabelY + 11 : wellY + wellHeight + placementGap;
        return new ControlFlow(bodyY + (dense ? 8 : 10), !dense, configLabelY, rowYs, selectionLabelY, wellY,
                wellHeight, placementLabelY, placementY, packed, pairedSwitches, collapsed);
    }

    private static int[] rowSteps(ManipulatorMode mode, boolean dense) {
        int step = dense ? 24 : 25;
        return switch (mode) {
            case GEOMETRY, EXCHANGE -> new int[]{step, step, 22};
            case COPY, BLUEPRINT -> new int[]{step, step, step, 22};
            case CABLE -> new int[]{step, step + 1, 20, 22};
            case MOVE -> new int[]{step, 22};
        };
    }

    private static int[] packedSteps(ManipulatorMode mode) {
        return switch (mode) {
            case GEOMETRY, EXCHANGE, MOVE -> new int[]{24, 22};
            case CABLE -> new int[]{24, 17, 22};
            case COPY, BLUEPRINT -> new int[]{24, 24, 22};
        };
    }

    private SettingsFlow settingsFlow(Layout layout) {
        int bodyY = layout.bodyY();
        var tabs = slotTabs(layout.innerWidth());
        int below = bodyY + tabs[tabs.length - 1].y();
        int customY = layout.dense() ? below + 106 : below + 132;
        int speedY = customY + (layout.dense() ? 36 : 41);
        int opacityY = speedY + (layout.dense() ? 36 : 41);
        if (opacityY + 15 > layout.actionY() - 9) {
            opacityY = -1;
        }
        if (speedY + 15 > layout.actionY() - 9) {
            speedY = -1;
        }
        if (layout.dense()) {
            return new SettingsFlow(bodyY + 8, false, bodyY + 22, below + 53, below + 64, below + 95, customY,
                    customY + 26, speedY, opacityY - 10, opacityY);
        }
        return new SettingsFlow(bodyY + 10, true, bodyY + 48, below + 79, below + 90, below + 121, customY,
                customY + 30, speedY, opacityY - 11, opacityY);
    }

    private Rect speedRect(Layout layout) {
        int speedY = settingsFlow(layout).speedY();
        return speedY < 0 ? new Rect(0, 0, 0, 0) : new Rect(layout.innerX(), speedY, layout.innerWidth(), 15);
    }

    private Rect opacityRect(Layout layout) {
        int opacityY = settingsFlow(layout).opacityY();
        return opacityY < 0 ? new Rect(0, 0, 0, 0) : new Rect(layout.innerX(), opacityY, layout.innerWidth(), 15);
    }

    private Rect nameField(Layout layout, ToolState state) {
        var flow = controlFlow(layout, state);
        int width = layout.innerWidth();
        int areaWidth = transformAreaWidth(width);
        var rows = flow.rowYs();
        return new Rect(layout.innerX() + width - areaWidth, flow.packed() ? rows[2] : rows[3], areaWidth, 22);
    }

    private Rect hexField(Layout layout) {
        int swatchWidth = swatchWidth(layout.innerWidth());
        return new Rect(layout.innerX() + swatchWidth + 4, settingsFlow(layout).customY(),
                layout.innerWidth() - swatchWidth - 4, 22);
    }

    private void text(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        OreText.draw(graphics, font, text, x, y, color);
    }

    private void label(GuiGraphicsExtractor graphics, Component label, int x, int y, int color, int maxWidth) {
        text(graphics, OreText.fit(font, label.getString(), maxWidth, false), x, y, color);
    }

    private void data(GuiGraphicsExtractor graphics, String text, int x, int y, int color, int maxWidth) {
        var shown = OreText.shorten(font, text, maxWidth);
        if (!shown.equals(text)) {
            hints.add(new Hint(new Rect(x, y - 2, maxWidth, 11), text));
        }
        text(graphics, shown, x, y, color);
    }

    private record Layout(
            int x,
            int y,
            int width,
            int height,
            int sidebarWidth,
            int controlsWidth,
            boolean compact
    ) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private int bodyY() {
            return y + 24;
        }

        private int bodyHeight() {
            return height - 24;
        }

        private boolean dense() {
            return height < 330;
        }

        private boolean isShort() {
            return height < 260;
        }

        private int controlsX() {
            return x + sidebarWidth + 1;
        }

        private int innerX() {
            return controlsX() + 10;
        }

        private int innerWidth() {
            return controlsWidth - 20;
        }

        private int visualX() {
            return controlsX() + controlsWidth + 1;
        }

        private int visualWidth() {
            return right() - visualX();
        }

        private int modeY(int index) {
            return bodyY() + 21 + index * (isShort() ? 24 : 26);
        }

        private int settingsY() {
            return modeY(ManipulatorMode.displayOrder().size() - 1) + 22 + (isShort() ? 11 : 15);
        }

        private int actionY() {
            return bottom() - 30;
        }
    }

    private record ControlFlow(
            int headerY,
            boolean description,
            int configLabelY,
            int[] rowYs,
            int selectionLabelY,
            int wellY,
            int wellHeight,
            int placementLabelY,
            int placementY,
            boolean packed,
            boolean pairedSwitches,
            boolean collapsed
    ) {
        private int bottom() {
            return placementY + (pairedSwitches ? 15 : 34);
        }
    }

    private record SettingsFlow(
            int headerY,
            boolean description,
            int tabsY,
            int presetsLabelY,
            int swatchY,
            int customLabelY,
            int customY,
            int speedLabelY,
            int speedY,
            int opacityLabelY,
            int opacityY
    ) {}

    private record Rect(int x, int y, int width, int height) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    private record OptionId(int index) {}

    private record LibraryRow(Path path) {}

    private record Hint(Rect area, String text) {}

    private record DropdownMenu(
            DropdownKind kind,
            UiButton trigger,
            List<Component> options,
            int selected,
            Rect rect
    ) {
        private Rect optionRect(int index) {
            return new Rect(rect.x() + 1, rect.y() + 1 + index * OrePainter.DROPDOWN_ROW_HEIGHT,
                    rect.width() - 2, OrePainter.DROPDOWN_ROW_HEIGHT);
        }

        private int optionAt(int mouseX, int mouseY) {
            if (!rect.contains(mouseX, mouseY)) {
                return -1;
            }
            int index = (mouseY - rect.y() - 1) / OrePainter.DROPDOWN_ROW_HEIGHT;
            return index >= 0 && index < options.size() && optionRect(index).contains(mouseX, mouseY)
                    ? index
                    : -1;
        }
    }

    private record UiButton(
            String id,
            int x,
            int y,
            int width,
            int height,
            ButtonKind kind,
            OreVariant variant,
            Component label,
            Component value,
            Component tooltip,
            boolean enabled,
            boolean selected,
            @Nullable OreIcons icon,
            @Nullable DropdownKind dropdownKind,
            Runnable activate
    ) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }

    private enum ButtonKind {
        BUTTON,
        HERO,
        MODE,
        DROPDOWN,
        SWITCH,
        TAB,
        SLOT,
        ICON
    }

    private enum ThemeColor {
        ACCENT("accent", 0x2E6BE5, MMConfig.DEFAULT_GUI_ACCENT_RGB, 0x7345E5, 0xFFC42B, 0xACF2FF, 0xFFA41F),
        BUTTONS("buttons", 0xF2C9A1, MMConfig.DEFAULT_GUI_BUTTON_RGB, 0x3C8527, 0x7345E5, 0xFFC42B, 0xACF2FF,
                0xFFA41F),
        INTERFACE("interface", 0x2F3A55, MMConfig.DEFAULT_GUI_INTERFACE_RGB, 0x3B4252, 0x4A3F35, 0x35453A, 0x4A3A4F);

        private final String key;
        private final int customExample;
        private final int[] presets;

        ThemeColor(String key, int customExample, int... presets) {
            this.key = key;
            this.customExample = customExample;
            this.presets = presets;
        }

        private boolean isPreset(int rgb) {
            for (int preset : presets) {
                if (preset == rgb) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum DropdownKind {
        SHAPE(ConfigActionPacket.Action.SET_SHAPE),
        REMOVE(ConfigActionPacket.Action.SET_REMOVE);

        private final ConfigActionPacket.Action action;

        DropdownKind(ConfigActionPacket.Action action) {
            this.action = action;
        }
    }
}
