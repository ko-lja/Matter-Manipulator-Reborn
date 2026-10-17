package lu.kolja.oreui;

import java.util.List;

import static lu.kolja.oreui.OreColors.GRAY_10;
import static lu.kolja.oreui.OreColors.GRAY_100;
import static lu.kolja.oreui.OreColors.GRAY_30;
import static lu.kolja.oreui.OreColors.GRAY_40;
import static lu.kolja.oreui.OreColors.GRAY_60;
import static lu.kolja.oreui.OreColors.GRAY_70;
import static lu.kolja.oreui.OreColors.GRAY_80;
import static lu.kolja.oreui.OreColors.WHITE;
import static lu.kolja.oreui.OreColors.mix;

public record OreVariant(
        int face,
        int hoverFace,
        int pressedFace,
        int highlight,
        int hoverHighlight,
        int pressedHighlight,
        int lip,
        int text
) {

    public static final OreVariant PRIMARY = mixed(0xFF3C8527, 0xFF52A535, 0xFF2A641C, 0.2, 0.2, 0.1, 0xFF1D4D13, WHITE);
    public static final OreVariant SECONDARY = mixed(GRAY_30, GRAY_10, GRAY_40, 0.4, 0.8, 0.2, GRAY_60, GRAY_100);
    public static final OreVariant NEUTRAL = neutral(GRAY_60, GRAY_70, GRAY_80);
    public static final OreVariant DESTRUCTIVE = mixed(0xFFCA3636, 0xFFDF5050, 0xFFC02D2D, 0.1, 0.1, 0.1, 0xFFAD1D1D, WHITE);
    public static final OreVariant PURPLE = fixed(0xFF7345E5, 0xFF8557F8, 0xFF5D2CC6, 0xFF8557F8, 0xFF4A1CAC, WHITE);
    public static final OreVariant GOLD = fixed(0xFFFFC42B, 0xFFFFE175, 0xFFDB9F21, 0xFFFFD953, 0xFFA47B2A, GRAY_100);
    public static final OreVariant ICE = fixed(0xFFACF2FF, 0xFFBDF5FF, 0xFF70DFFF, 0xFFC4F5FE, 0xFF339BD6, GRAY_100);
    public static final OreVariant ORANGE = fixed(0xFFFFA41F, 0xFFFFB64C, 0xFFFF791A, 0xFFFFD953, 0xFFDE7523, GRAY_100);
    public static final List<OreVariant> ACCENT_PRESETS = List.of(PRIMARY, PURPLE, GOLD, ICE, ORANGE);

    private static final List<OreVariant> FIXED = List.of(PRIMARY, PURPLE, GOLD, ICE, ORANGE, SECONDARY, NEUTRAL);

    public static OreVariant of(int rgb) {
        for (var variant : FIXED) {
            if ((variant.face & 0xFFFFFF) == (rgb & 0xFFFFFF)) {
                return variant;
            }
        }
        return custom(rgb);
    }

    public static OreVariant custom(int rgb) {
        var hsl = OreColors.hsl(rgb);
        double lightness = Math.min(0.7, Math.max(0.2, hsl[2]));
        int face = OreColors.fromHsl(hsl[0], hsl[1], lightness);
        int hover = OreColors.fromHsl(hsl[0], hsl[1], lightness + 0.09);
        int pressed = OreColors.fromHsl(hsl[0], hsl[1], lightness - 0.09);
        int lip = OreColors.fromHsl(hsl[0], hsl[1], lightness - 0.15);
        int luma = 299 * (face >> 16 & 0xFF) + 587 * (face >> 8 & 0xFF) + 114 * (face & 0xFF);
        return new OreVariant(face, hover, pressed, mix(face, 0.2), mix(hover, 0.2), mix(pressed, 0.1), lip,
                luma >= 150_000 ? GRAY_100 : WHITE);
    }

    static OreVariant neutral(int gray60, int gray70, int gray80) {
        return mixed(gray70, gray60, gray80, 0.2, 0.2, 0.2, gray80, WHITE);
    }

    public int face(OreState state) {
        return switch (state) {
            case DEFAULT -> face;
            case HOVER -> hoverFace;
            case PRESSED -> pressedFace;
            case DISABLED -> GRAY_30;
        };
    }

    public int highlight(OreState state) {
        return switch (state) {
            case DEFAULT -> highlight;
            case HOVER -> hoverHighlight;
            case PRESSED -> pressedHighlight;
            case DISABLED -> GRAY_30;
        };
    }

    public int lip(OreState state) {
        return state == OreState.DISABLED ? GRAY_40 : lip;
    }

    public int text(OreState state) {
        return state == OreState.DISABLED ? GRAY_70 : text;
    }

    private static OreVariant mixed(int face, int hover, int pressed, double highlight, double hoverHighlight,
                                    double pressedHighlight, int lip, int text) {
        return new OreVariant(face, hover, pressed, mix(face, highlight), mix(hover, hoverHighlight),
                mix(pressed, pressedHighlight), lip, text);
    }

    private static OreVariant fixed(int face, int hover, int pressed, int highlight, int lip, int text) {
        return new OreVariant(face, hover, pressed, highlight, highlight, highlight, lip, text);
    }
}
