package lu.kolja.oreui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

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

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("oreui", "textures/gui/icons.png");
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

    public void draw(GuiGraphics graphics, int x, int y) {
        draw(graphics, x, y, 0xFFFFFFFF);
    }

    public void draw(GuiGraphics graphics, int x, int y, int color) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(
                (color >> 16 & 0xFF) / 255f,
                (color >> 8 & 0xFF) / 255f,
                (color & 0xFF) / 255f,
                (color >>> 24) / 255f
        );
        graphics.blit(TEXTURE, x, y, u, v, width, height, TEX_WIDTH, TEX_HEIGHT);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    public void draw(GuiGraphics graphics, int x, int y, int drawWidth, int drawHeight, int color) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(
                (color >> 16 & 0xFF) / 255f,
                (color >> 8 & 0xFF) / 255f,
                (color & 0xFF) / 255f,
                (color >>> 24) / 255f
        );
        graphics.blit(TEXTURE, x, y, drawWidth, drawHeight, u, v, width, height, TEX_WIDTH, TEX_HEIGHT);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
