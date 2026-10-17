package lu.kolja.oreui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class OreText {

    public static final String ELLIPSIS = "...";
    private static @Nullable ShortenListener listener;

    private OreText() {}

    public interface ShortenListener {
        void shortened(boolean label, String text, int maxWidth, String shown);
    }

    public static void onShortened(@Nullable ShortenListener listener) {
        OreText.listener = listener;
    }

    public static int width(Font font, String text, boolean bold) {
        return bold ? font.width(Component.literal(text).withStyle(ChatFormatting.BOLD)) : font.width(text);
    }

    public static String fit(Font font, String text, int maxWidth, boolean bold) {
        if (width(font, text, bold) <= maxWidth) {
            return text;
        }
        report(true, text, maxWidth, "");
        return "";
    }

    public static String shorten(Font font, String text, int maxWidth) {
        var lines = wrap(font, text, maxWidth, 1, false);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    public static List<String> wrap(Font font, String text, int maxWidth, int maxLines, boolean label) {
        var lines = new ArrayList<String>();
        var line = "";
        for (var word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            var joined = line.isEmpty() ? word : line + " " + word;
            if (font.width(joined) <= maxWidth) {
                line = joined;
                continue;
            }
            if (line.isEmpty() || lines.size() + 1 >= maxLines || font.width(word) > maxWidth) {
                return overflow(font, text, maxWidth, lines, line, label);
            }
            lines.add(line);
            line = word;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    public static void draw(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    public static void drawBold(GuiGraphics graphics, Font font, String text, int x, int y, int color, boolean shadow) {
        var bold = Component.literal(text).withStyle(ChatFormatting.BOLD);
        if (shadow) {
            graphics.drawString(font, bold, x + 1, y + 1, OreColors.TEXT_SHADOW, false);
        }
        graphics.drawString(font, bold, x, y, color, false);
    }

    private static List<String> overflow(Font font, String text, int maxWidth, List<String> lines, String line,
                                         boolean label) {
        if (label) {
            report(true, text, maxWidth, "");
            return List.of();
        }
        var last = line;
        while (!last.isEmpty() && font.width(withEllipsis(last)) > maxWidth) {
            last = last.substring(0, Math.max(0, last.lastIndexOf(' ')));
        }
        if (!last.isEmpty()) {
            lines.add(withEllipsis(last));
        }
        report(false, text, maxWidth, String.join(" | ", lines));
        return lines;
    }

    private static String withEllipsis(String text) {
        return text.replaceAll("[,;:]+$", "") + ELLIPSIS;
    }

    private static void report(boolean label, String text, int maxWidth, String shown) {
        if (listener != null) {
            listener.shortened(label, text, maxWidth, shown);
        }
    }
}
