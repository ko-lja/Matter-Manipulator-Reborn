package lu.kolja.oreui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static lu.kolja.oreui.OreColors.GRAY_10;
import static lu.kolja.oreui.OreColors.GRAY_100;
import static lu.kolja.oreui.OreColors.GRAY_30;
import static lu.kolja.oreui.OreColors.GRAY_40;
import static lu.kolja.oreui.OreColors.GRAY_50;
import static lu.kolja.oreui.OreColors.GRAY_60;
import static lu.kolja.oreui.OreColors.WHITE;
import static lu.kolja.oreui.OreColors.mix;

public final class OrePainter {

    public static final int DROPDOWN_ROW_HEIGHT = 18;
    public static final int LIST_ROW_HEIGHT = 20;
    private static final int LABEL_CAP = 7;

    private OrePainter() {}

    public static int buttonFrame(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h, OreVariant variant,
                                  OreState state) {
        int offset = sink(state);
        rect(graphics, x, y + offset, w, h - offset, state == OreState.DISABLED ? GRAY_50 : theme.gray100());
        rect(graphics, x + 1, y + h - 3 + offset, w - 2, 2 - offset, variant.lip(state));
        rect(graphics, x + 1, y + 1 + offset, w - 2, h - 4, variant.highlight(state));
        rect(graphics, x + 2, y + 2 + offset, w - 4, h - 6, variant.face(state));
        return y + 1 + offset;
    }

    public static void button(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                              OreVariant variant, OreState state, Component label) {
        int top = buttonFrame(graphics, theme, x, y, w, h, variant, state);
        var text = OreText.fit(font, label.getString(), w - 8, false);
        OreText.draw(graphics, font, text, x + (w - font.width(text)) / 2, labelY(top, h), variant.text(state));
    }

    public static void heroButton(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                                  OreVariant variant, OreState state, Component label) {
        int top = buttonFrame(graphics, theme, x, y, w, h, variant, state);
        var text = OreText.fit(font, label.getString(), w - 8, true);
        int color = variant.text(state);
        OreText.drawBold(graphics, font, text, x + (w - OreText.width(font, text, true)) / 2, labelY(top, h), color,
                color == WHITE);
    }

    public static void iconButton(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h, OreVariant variant,
                                  OreState state, @Nullable OreIcons icon) {
        int top = buttonFrame(graphics, theme, x, y, w, h, variant, state);
        if (icon != null) {
            icon.draw(graphics, x + (w - icon.width()) / 2, top + (h - 4 - icon.height()) / 2, variant.text(state));
        }
    }

    public static void listButton(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                                  OreVariant variant, OreState state, @Nullable OreIcons icon, Component label,
                                  Component trailing, @Nullable OreIcons trailingIcon) {
        int top = buttonFrame(graphics, theme, x, y, w, h, variant, state);
        int color = variant.text(state);
        int textY = labelY(top, h);
        int left = x + 7;
        if (icon != null) {
            icon.draw(graphics, left, top + (h - 4 - icon.height()) / 2, color);
            left += 12;
        }
        int right = x + w - 7;
        if (trailingIcon != null) {
            right -= trailingIcon.width();
            trailingIcon.draw(graphics, right, top + (h - 4 - trailingIcon.height()) / 2, color);
            right -= 5;
        }
        var labelText = label.getString();
        var trailingText = trailing.getString();
        if (!trailingText.isEmpty()
                && !OreText.fit(font, trailingText, right - left - font.width(labelText) - 5, false).isEmpty()) {
            right -= font.width(trailingText);
            OreText.draw(graphics, font, trailingText, right, textY, color);
            right -= 5;
        }
        OreText.draw(graphics, font, OreText.fit(font, labelText, right - left, false), left, textY, color);
    }

    public static void selectedTab(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                                   @Nullable OreIcons icon, Component label) {
        tabFrame(graphics, theme, x, y, w, h, mix(theme.gray70(), 0.2), theme.gray80(), WHITE);
        int left = x + 7;
        if (icon != null) {
            icon.draw(graphics, left, y + 4 + (h - 6 - icon.height()) / 2, WHITE);
            left += 12;
        }
        var text = OreText.fit(font, label.getString(), x + w - 7 - left, false);
        OreText.draw(graphics, font, text, left, y + 4 + (h - 6 - LABEL_CAP) / 2, WHITE);
    }

    public static void selectedTab(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                                   Component label) {
        tabFrame(graphics, theme, x, y, w, h, mix(theme.gray70(), 0.2), theme.gray80(), WHITE);
        var text = OreText.fit(font, label.getString(), w - 8, false);
        OreText.draw(graphics, font, text, x + (w - font.width(text)) / 2, y + 4 + (h - 6 - LABEL_CAP) / 2, WHITE);
    }

    public static void toggleTab(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, int h,
                                 OreState state, boolean selected, Component label) {
        if (!selected) {
            button(graphics, font, theme, x, y, w, h, theme.buttons(), state, label);
            return;
        }
        var accent = theme.accent();
        tabFrame(graphics, theme, x, y, w, h, mix(accent.face(), 0.1), accent.face(), accent.text());
        var text = OreText.fit(font, label.getString(), w - 8, false);
        OreText.draw(graphics, font, text, x + (w - font.width(text)) / 2, y + 4 + (h - 6 - LABEL_CAP) / 2,
                accent.text());
    }

    public static void toggleSwitch(GuiGraphics graphics, OreTheme theme, int x, int y, boolean on, OreState state) {
        var accent = theme.accent();
        boolean disabled = state == OreState.DISABLED;
        int border = disabled ? GRAY_50 : theme.gray100();
        int trackFace = disabled ? GRAY_30 : on ? accent.face() : GRAY_50;
        int trackHighlight = disabled ? GRAY_30 : on ? accent.highlight() : mix(GRAY_50, 0.2);
        int trackX = on ? x : x + 15;
        rect(graphics, trackX, y + 2, 13, 13, border);
        rect(graphics, on ? x + 1 : trackX, y + 3, 12, 11, trackHighlight);
        rect(graphics, on ? x + 2 : trackX, y + 4, 11, 9, trackFace);

        int thumbX = on ? x + 13 : x;
        int thumbFace = switch (state) {
            case DEFAULT, DISABLED -> GRAY_30;
            case HOVER -> GRAY_10;
            case PRESSED -> GRAY_40;
        };
        int offset = sink(state);
        rect(graphics, thumbX, y + offset, 15, 15 - offset, border);
        rect(graphics, thumbX + 1, y + 1 + offset, 13, 11,
                disabled ? GRAY_30 : mix(thumbFace, state == OreState.HOVER ? 0.6 : 0.4));
        rect(graphics, thumbX + 1, y + 12 + offset, 13, 2 - offset, disabled ? GRAY_40 : GRAY_60);
        rect(graphics, thumbX + 2, y + 2 + offset, 11, 9, thumbFace);
    }

    public static void well(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h) {
        rect(graphics, x, y, w, h, theme.gray100());
        rect(graphics, x + 1, y + 1, w - 2, h - 2, theme.gray90());
        rect(graphics, x + 1, y + 1, w - 2, 1, theme.wellShade());
    }

    public static void field(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h, boolean focused) {
        well(graphics, theme, x, y, w, h);
        if (focused) {
            outline(graphics, x, y, w, h, WHITE);
        }
    }

    public static int tagWidth(Font font, Component label) {
        return font.width(label) + 8;
    }

    public static int tag(GuiGraphics graphics, Font font, int x, int y, Component label, int fill) {
        int w = tagWidth(font, label);
        rect(graphics, x, y, w, 11, GRAY_100);
        rect(graphics, x + 1, y + 1, w - 2, 9, fill);
        OreText.draw(graphics, font, label.getString(), x + 4, y + 2, GRAY_100);
        return w;
    }

    public static void progressBar(GuiGraphics graphics, OreTheme theme, int x, int y, int w, double ratio) {
        int fill = theme.accent().face();
        rect(graphics, x, y, w, 8, theme.gray100());
        rect(graphics, x + 1, y + 1, w - 2, 6, theme.gray80());
        int filled = (int) Math.round((w - 2) * Math.min(1, Math.max(0, ratio)));
        rect(graphics, x + 1, y + 1, filled, 6, fill);
        rect(graphics, x + 1, y + 1, filled, 1, mix(fill, 0.25));
    }

    public static void slider(GuiGraphics graphics, OreTheme theme, int x, int y, int w, double ratio, OreState state) {
        progressBar(graphics, theme, x, y + 4, w, ratio);
        boolean disabled = state == OreState.DISABLED;
        int thumbX = x + (int) Math.round((w - 9) * Math.min(1, Math.max(0, ratio)));
        int thumbFace = switch (state) {
            case DEFAULT, DISABLED -> GRAY_30;
            case HOVER -> GRAY_10;
            case PRESSED -> GRAY_40;
        };
        int offset = sink(state);
        rect(graphics, thumbX, y + offset, 9, 15 - offset, disabled ? GRAY_50 : theme.gray100());
        rect(graphics, thumbX + 1, y + 1 + offset, 7, 11,
                disabled ? GRAY_30 : mix(thumbFace, state == OreState.HOVER ? 0.6 : 0.4));
        rect(graphics, thumbX + 1, y + 12 + offset, 7, 2 - offset, disabled ? GRAY_40 : GRAY_60);
        rect(graphics, thumbX + 2, y + 2 + offset, 5, 9, thumbFace);
    }

    public static void navBar(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w, Component title) {
        rect(graphics, x, y, w, 21, theme.nav());
        rect(graphics, x, y, w, 1, WHITE);
        rect(graphics, x, y + 21, w, 2, theme.navLip());
        rect(graphics, x, y + 23, w, 1, theme.gray100());
        var text = title.getString();
        OreText.drawBold(graphics, font, text, x + (w - OreText.width(font, text, true)) / 2, y + 7, GRAY_100, false);
    }

    public static void dropdownList(GuiGraphics graphics, Font font, OreTheme theme, int x, int y, int w,
                                    List<Component> options, int selected, int hovered, boolean pressed) {
        var rows = theme.buttons();
        int h = options.size() * DROPDOWN_ROW_HEIGHT + 2;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        rect(graphics, x + 2, y + 3, w, h - 1, OreColors.DROPDOWN_SHADOW);
        rect(graphics, x, y, w, h, theme.gray100());
        for (int index = 0; index < options.size(); index++) {
            int rowY = y + 1 + index * DROPDOWN_ROW_HEIGHT;
            rect(graphics, x + 1, rowY, w - 2, DROPDOWN_ROW_HEIGHT,
                    index != hovered ? rows.face() : pressed ? rows.pressedFace() : rows.hoverFace());
            if (index > 0) {
                rect(graphics, x + 1, rowY, w - 2, 1, rows.pressedFace());
            }
            var text = OreText.fit(font, options.get(index).getString(), w - 24, false);
            OreText.draw(graphics, font, text, x + 8, rowY + 6, rows.text());
            if (index == selected) {
                OreIcons.TICK.draw(graphics, x + w - 14, rowY + 6, rows.text());
            }
        }
        graphics.pose().popPose();
    }

    public static void listRow(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h, int index,
                               boolean hovered) {
        rect(graphics, x, y, w, h,
                hovered ? theme.gray70() : index % 2 == 1 ? theme.gray90() : theme.rowAlternate());
    }

    public static void scrollbar(GuiGraphics graphics, OreTheme theme, int x, int y, int height, int total,
                                 int scroll) {
        int maxScroll = total - height;
        if (maxScroll <= 0) {
            return;
        }
        int thumb = Math.max(8, height * height / total);
        int thumbY = y + (height - thumb) * Math.min(maxScroll, Math.max(0, scroll)) / maxScroll;
        rect(graphics, x, y, 2, height, theme.gray100());
        rect(graphics, x, thumbY, 2, thumb, GRAY_30);
    }

    public static void outline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        rect(graphics, x, y, w, 1, color);
        rect(graphics, x, y + h - 1, w, 1, color);
        rect(graphics, x, y + 1, 1, h - 2, color);
        rect(graphics, x + w - 1, y + 1, 1, h - 2, color);
    }

    public static void rect(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) {
            graphics.fill(x, y, x + w, y + h, color);
        }
    }

    private static void tabFrame(GuiGraphics graphics, OreTheme theme, int x, int y, int w, int h, int highlight,
                                 int face, int indicator) {
        rect(graphics, x, y + 2, w, h - 2, theme.gray100());
        rect(graphics, x + 1, y + 3, w - 2, h - 4, highlight);
        rect(graphics, x + 2, y + 4, w - 4, h - 6, face);
        rect(graphics, x + (w - 22) / 2, y + h - 2, 22, 1, indicator);
    }

    private static int sink(OreState state) {
        return state == OreState.PRESSED ? 2 : state == OreState.HOVER ? 1 : 0;
    }

    private static int labelY(int top, int h) {
        return top + (h - 4 - LABEL_CAP) / 2;
    }
}
