package lu.kolja.oreui;

import static lu.kolja.oreui.OreColors.GRAY_100;
import static lu.kolja.oreui.OreColors.GRAY_60;
import static lu.kolja.oreui.OreColors.GRAY_70;
import static lu.kolja.oreui.OreColors.GRAY_80;
import static lu.kolja.oreui.OreColors.GRAY_90;

public record OreTheme(
        OreVariant accent,
        OreVariant buttons,
        OreVariant neutral,
        int gray60,
        int gray70,
        int gray80,
        int gray90,
        int gray100,
        int nav,
        int navLip,
        int wellShade,
        int rowAlternate
) {

    public static final OreTheme DEFAULT = of(OreVariant.PRIMARY.face(), OreVariant.SECONDARY.face(), GRAY_70);

    public static OreTheme of(int accentRgb, int buttonsRgb, int interfaceRgb) {
        var accent = OreVariant.of(accentRgb);
        var buttons = OreVariant.of(buttonsRgb);
        if ((interfaceRgb & 0xFFFFFF) == (GRAY_70 & 0xFFFFFF)) {
            return new OreTheme(accent, buttons, OreVariant.NEUTRAL, GRAY_60, GRAY_70, GRAY_80, GRAY_90, GRAY_100,
                    OreColors.NAV, OreColors.NAV_LIP, OreColors.WELL_SHADE, OreColors.ROW_ALTERNATE);
        }
        var tint = OreColors.hsl(interfaceRgb);
        int gray60 = tint(tint, GRAY_60);
        int gray70 = tint(tint, GRAY_70);
        int gray80 = tint(tint, GRAY_80);
        return new OreTheme(accent, buttons, OreVariant.neutral(gray60, gray70, gray80), gray60, gray70, gray80,
                tint(tint, GRAY_90), tint(tint, GRAY_100), tint(tint, OreColors.NAV), tint(tint, OreColors.NAV_LIP),
                tint(tint, OreColors.WELL_SHADE), tint(tint, OreColors.ROW_ALTERNATE));
    }

    private static int tint(double[] tint, int gray) {
        return OreColors.fromHsl(tint[0], tint[1], OreColors.hsl(gray)[2]);
    }
}
