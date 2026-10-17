package lu.kolja.matter_manipulator.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class GuiPreviewScene {

    private List<PreviewRenderer.PreviewBlock> blocks = List.of();
    private List<PreviewRenderer.PreviewBlock> sourceBlocks = List.of();
    private boolean sourceTruncated;
    private int sourceLimit;
    private int sourceHash;
    private double centerX;
    private double centerY;
    private double centerZ;
    private float yaw = 35.0F;
    private float pitch = 25.0F;
    private float distance = 8.0F;
    private float fittedDistance = 8.0F;
    private boolean truncated;

    public void setSnapshot(PreviewRenderer.GuiPreviewSnapshot snapshot) {
        int maxBlocks = Math.max(MMConfig.MAX_PREVIEW_BLOCKS.get(), MMConfig.MAX_CABLE_PREVIEW_BLOCKS.get());
        if (snapshot.blocks() == sourceBlocks && snapshot.truncated() == sourceTruncated && maxBlocks == sourceLimit) {
            return;
        }
        sourceBlocks = snapshot.blocks();
        sourceTruncated = snapshot.truncated();
        sourceLimit = maxBlocks;
        int hash = Boolean.hashCode(snapshot.truncated());
        for (var block : snapshot.blocks()) {
            hash = 31 * hash + block.pos().hashCode();
            hash = 31 * hash + block.state().hashCode();
        }
        boolean refit = hash != sourceHash;
        sourceHash = hash;
        var positions = new HashSet<BlockPos>();
        for (var block : snapshot.blocks()) {
            positions.add(block.pos());
        }

        var surface = new ArrayList<PreviewRenderer.PreviewBlock>();
        for (var block : snapshot.blocks()) {
            var pos = block.pos();
            boolean enclosed = true;
            for (var direction : Direction.values()) {
                if (!positions.contains(pos.relative(direction))) {
                    enclosed = false;
                    break;
                }
            }
            if (!enclosed) {
                surface.add(block);
                if (surface.size() == maxBlocks) {
                    break;
                }
            }
        }
        blocks = List.copyOf(surface);
        truncated = snapshot.truncated() || surface.size() < positions.size() && surface.size() == maxBlocks;
        if (refit) {
            refit();
        }
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void rotate(double deltaX, double deltaY) {
        yaw += (float) deltaX * 0.55F;
        pitch = Math.max(-89.0F, Math.min(89.0F, pitch + (float) deltaY * 0.55F));
    }

    public void zoom(double delta) {
        distance = Math.max(2.0F, Math.min(512.0F, distance - (float) delta * Math.max(0.75F, distance * 0.08F)));
    }

    public void resetCamera() {
        yaw = 35.0F;
        pitch = 25.0F;
        distance = fittedDistance;
    }

    public void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        if (blocks.isEmpty() || width <= 2 || height <= 2) {
            return;
        }
        var pose = graphics.pose().last().pose();
        var topLeft = pose.transform(new Vector4f(x, y, 0.0F, 1.0F));
        var bottomRight = pose.transform(new Vector4f(x + width, y + height, 0.0F, 1.0F));
        var viewport = viewport(topLeft.x(), topLeft.y(), bottomRight.x() - topLeft.x(), bottomRight.y() - topLeft.y());
        var renderState = GuiRenderState.capture();
        runWithRestoration(
                () -> {
                    setupCamera(viewport, width / (float) Math.max(1, height), renderState);
                    drawBlocks();
                },
                renderState::restore
        );
        drawCompass(graphics, x + width - 25, y + height - 24);
    }

    private void refit() {
        if (blocks.isEmpty()) {
            centerX = centerY = centerZ = 0.0D;
            fittedDistance = distance = 8.0F;
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (var block : blocks) {
            var pos = block.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        centerX = (minX + maxX + 1.0D) * 0.5D;
        centerY = (minY + maxY + 1.0D) * 0.5D;
        centerZ = (minZ + maxZ + 1.0D) * 0.5D;
        int maxDimension = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));
        fittedDistance = Math.max(3.0F, maxDimension * 1.65F + 2.0F);
        distance = Math.min(512.0F, fittedDistance);
    }

    private void setupCamera(Viewport viewport, float guiAspect, GuiRenderState renderState) {
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.viewport(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        RenderSystem.depthMask(true);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        RenderSystem.clearColor(0.035F, 0.055F, 0.075F, 1.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        var horizontal = new Vector3f((float) Math.cos(yawRadians), 0.0F, (float) Math.sin(yawRadians));
        var vertical = new Vector3f(0.0F, (float) Math.tan(pitchRadians), 0.0F);
        var eye = horizontal.add(vertical).normalize().mul(distance);

        renderState.backupProjection();
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setPerspective((float) Math.toRadians(55.0D), Math.max(0.1F, guiAspect), 0.05F, 2048.0F),
                VertexSorting.byDistance(eye)
        );
        var modelView = RenderSystem.getModelViewStack();
        renderState.pushModelView(modelView);
        modelView.identity();
        modelView.mul(new Matrix4f().lookAt(eye, new Vector3f(), new Vector3f(0.0F, 1.0F, 0.0F)));
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableCull();
    }

    private void drawBlocks() {
        var minecraft = Minecraft.getInstance();
        var buffer = minecraft.renderBuffers().bufferSource();
        var dispatcher = minecraft.getBlockRenderer();
        var pose = new PoseStack();
        for (var block : blocks) {
            pose.pushPose();
            pose.translate(
                    block.pos().getX() - centerX,
                    block.pos().getY() - centerY,
                    block.pos().getZ() - centerZ
            );
            try {
                PreviewRenderer.renderPreviewBlock(dispatcher, pose, buffer, block);
            } catch (RuntimeException | LinkageError ignored) {
            } finally {
                pose.popPose();
            }
        }
        buffer.endBatch();
    }

    static void runWithRestoration(Runnable render, Runnable restore) {
        try {
            render.run();
        } finally {
            restore.run();
        }
    }

    private void drawCompass(GuiGraphics graphics, int centerX, int centerY) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        drawAxis(graphics, centerX, centerY, 1.0D, 0.0D, 0.0D, yawRadians, pitchRadians, 0xFFFF6262, "X");
        drawAxis(graphics, centerX, centerY, 0.0D, 1.0D, 0.0D, yawRadians, pitchRadians, 0xFF62E47E, "Y");
        drawAxis(graphics, centerX, centerY, 0.0D, 0.0D, 1.0D, yawRadians, pitchRadians, 0xFF62A8FF, "Z");
    }

    private static void drawAxis(
            GuiGraphics graphics,
            int centerX,
            int centerY,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            int color,
            String label
    ) {
        double horizontal = x * Math.cos(yaw) - z * Math.sin(yaw);
        double depth = x * Math.sin(yaw) + z * Math.cos(yaw);
        double vertical = y * Math.cos(pitch) - depth * Math.sin(pitch);
        int endX = centerX + (int) Math.round(horizontal * 13.0D);
        int endY = centerY - (int) Math.round(vertical * 13.0D);
        line(graphics, centerX, centerY, endX, endY, color);
        graphics.drawString(Minecraft.getInstance().font, label, endX - 2, endY - 4, color, false);
    }

    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                x0 += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static Viewport viewport(float x, float y, float width, float height) {
        var window = Minecraft.getInstance().getWindow();
        double scaleX = window.getWidth() / (double) window.getGuiScaledWidth();
        double scaleY = window.getHeight() / (double) window.getGuiScaledHeight();
        int viewportWidth = Math.max(1, (int) (width * scaleX));
        int viewportHeight = Math.max(1, (int) (height * scaleY));
        return new Viewport(
                (int) (x * scaleX),
                window.getHeight() - (int) (y * scaleY) - viewportHeight,
                viewportWidth,
                viewportHeight
        );
    }

    private static final class GuiRenderState {

        private final int[] viewport;
        private final int[] scissorBox;
        private final float[] clearColor;
        private final boolean depthTest;
        private final boolean blend;
        private final boolean cull;
        private final boolean scissor;
        private final boolean depthMask;
        private boolean projectionBackedUp;
        private boolean modelViewPushed;

        private GuiRenderState(
                int[] viewport,
                int[] scissorBox,
                float[] clearColor,
                boolean depthTest,
                boolean blend,
                boolean cull,
                boolean scissor,
                boolean depthMask
        ) {
            this.viewport = viewport;
            this.scissorBox = scissorBox;
            this.clearColor = clearColor;
            this.depthTest = depthTest;
            this.blend = blend;
            this.cull = cull;
            this.scissor = scissor;
            this.depthMask = depthMask;
        }

        private static GuiRenderState capture() {
            try (var stack = MemoryStack.stackPush()) {
                var viewportBuffer = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBuffer);
                var scissorBuffer = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBuffer);
                var clearColorBuffer = stack.mallocFloat(4);
                GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorBuffer);
                return new GuiRenderState(
                        new int[] {
                                viewportBuffer.get(0),
                                viewportBuffer.get(1),
                                viewportBuffer.get(2),
                                viewportBuffer.get(3)
                        },
                        new int[] {
                                scissorBuffer.get(0),
                                scissorBuffer.get(1),
                                scissorBuffer.get(2),
                                scissorBuffer.get(3)
                        },
                        new float[] {
                                clearColorBuffer.get(0),
                                clearColorBuffer.get(1),
                                clearColorBuffer.get(2),
                                clearColorBuffer.get(3)
                        },
                        GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                        GL11.glIsEnabled(GL11.GL_BLEND),
                        GL11.glIsEnabled(GL11.GL_CULL_FACE),
                        GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                        GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                );
            }
        }

        private void backupProjection() {
            RenderSystem.backupProjectionMatrix();
            projectionBackedUp = true;
        }

        private void pushModelView(Matrix4fStack modelView) {
            modelView.pushMatrix();
            modelViewPushed = true;
        }

        private void restore() {
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            if (projectionBackedUp) {
                RenderSystem.restoreProjectionMatrix();
            }
            if (modelViewPushed) {
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.depthMask(depthMask);
            setEnabled(depthTest, RenderSystem::enableDepthTest, RenderSystem::disableDepthTest);
            setEnabled(blend, RenderSystem::enableBlend, RenderSystem::disableBlend);
            setEnabled(cull, RenderSystem::enableCull, RenderSystem::disableCull);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            setEnabled(scissor, () -> GL11.glEnable(GL11.GL_SCISSOR_TEST), () -> GL11.glDisable(GL11.GL_SCISSOR_TEST));
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        }

        private static void setEnabled(boolean enabled, Runnable enable, Runnable disable) {
            if (enabled) {
                enable.run();
            } else {
                disable.run();
            }
        }
    }

    private record Viewport(int x, int y, int width, int height) {}
}
