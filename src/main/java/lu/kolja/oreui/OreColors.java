package lu.kolja.oreui;

public final class OreColors {

    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY_10 = 0xFFF4F6F9;
    public static final int GRAY_30 = 0xFFD0D1D4;
    public static final int GRAY_40 = 0xFFB1B2B5;
    public static final int GRAY_50 = 0xFF8C8D90;
    public static final int GRAY_60 = 0xFF58585A;
    public static final int GRAY_70 = 0xFF48494A;
    public static final int GRAY_80 = 0xFF313233;
    public static final int GRAY_90 = 0xFF242425;
    public static final int GRAY_100 = 0xFF1E1E1F;
    public static final int NAV = 0xFFE7E8EC;
    public static final int NAV_LIP = 0xFFB1B2B6;
    public static final int TEXT_SHADOW = 0x4D000000;
    public static final int OK = 0xFF86D562;
    public static final int MISSING = 0xFFF46D6D;
    public static final int WARN = 0xFFFFE866;
    public static final int SUCCESS_TAG = 0xFFA0E081;
    public static final int INFO_TAG = 0xFF8CB3FF;
    public static final int WELL_SHADE = 0xFF19191A;
    public static final int ROW_ALTERNATE = 0xFF2A2A2B;
    public static final int DROPDOWN_SHADOW = 0x66000000;

    private OreColors() {}

    public static int mix(int argb, double towardWhite) {
        return argb & 0xFF000000
                | mixChannel(argb >> 16, towardWhite) << 16
                | mixChannel(argb >> 8, towardWhite) << 8
                | mixChannel(argb, towardWhite);
    }

    private static int mixChannel(int channel, double towardWhite) {
        return (int) Math.round((channel & 0xFF) * (1 - towardWhite) + 255 * towardWhite);
    }

    static double[] hsl(int rgb) {
        double red = (rgb >> 16 & 0xFF) / 255.0;
        double green = (rgb >> 8 & 0xFF) / 255.0;
        double blue = (rgb & 0xFF) / 255.0;
        double max = Math.max(red, Math.max(green, blue));
        double min = Math.min(red, Math.min(green, blue));
        double delta = max - min;
        double hue = 0.0;
        double saturation = 0.0;
        double lightness = (max + min) / 2;
        if (delta > 0) {
            saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
            hue = (max == red ? (green - blue) / delta + (green < blue ? 6 : 0)
                    : max == green ? (blue - red) / delta + 2
                    : (red - green) / delta + 4) / 6;
        }
        return new double[]{hue, saturation, lightness};
    }

    static int fromHsl(double hue, double saturation, double lightness) {
        lightness = Math.min(1, Math.max(0, lightness));
        double q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation;
        double p = 2 * lightness - q;
        return 0xFF000000
                | hueChannel(p, q, hue + 1.0 / 3) << 16
                | hueChannel(p, q, hue) << 8
                | hueChannel(p, q, hue - 1.0 / 3);
    }

    private static int hueChannel(double p, double q, double t) {
        t = (t + 1) % 1;
        double value = t < 1.0 / 6 ? p + (q - p) * 6 * t
                : t < 0.5 ? q
                : t < 2.0 / 3 ? p + (q - p) * (2.0 / 3 - t) * 6
                : p;
        return (int) Math.round(value * 255);
    }
}
