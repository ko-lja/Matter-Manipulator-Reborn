package lu.kolja.oreui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public enum OreIcons {
    GEOMETRY(0, 0),
    CABLE(16, 0),
    EXCHANGE(32, 0),
    COPY(48, 0),
    MOVE(64, 0),
    BLUEPRINT(80, 0),
    SETTINGS(96, 0),
    CHEVRON(112, 0, 7, 4),
    TICK(0, 16, 7, 6),
    CROSS(16, 16),
    EJECT(48, 16),
    LOCK(32, 16, 5, 7);

    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("oreui", "textures/gui/icons.png");
    private static final int TEX_WIDTH = 128;
    private static final int TEX_HEIGHT = 128;

    private final int u, v, width, height;

    OreIcons(int u, int v) {
        this(u, v, 7, 7);
    }

    OreIcons(int u, int v, int width, int height) {
        this.u = u;
        this.v = v;
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void draw(GuiGraphicsExtractor graphics, int x, int y) {
        draw(graphics, x, y, 0xFFFFFFFF);
    }

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, u, v, width, height, TEX_WIDTH, TEX_HEIGHT, color);
    }

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int drawWidth, int drawHeight, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, u, v, drawWidth, drawHeight, width, height, TEX_WIDTH,
                TEX_HEIGHT, color);
    }
}
