package lu.kolja.matter_manipulator.client.gui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class GuiPreviewScene {
    private static final int WELL_BACKGROUND = 0xFF090E13;

    private List<PreviewRenderer.PreviewBlock> blocks = List.of();
    private BlockAndTintGetter view = BlockAndTintGetter.EMPTY;
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
        view = snapshot.view();
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

    public void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (blocks.isEmpty() || width <= 2 || height <= 2) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, WELL_BACKGROUND);
        graphics.submitPictureInPictureRenderState(new State(blocks, view, yaw, pitch, distance, centerX, centerY, centerZ,
                x, y, x + width, y + height, graphics.peekScissorStack()));
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

    public record State(List<PreviewRenderer.PreviewBlock> blocks, BlockAndTintGetter view, float yaw, float pitch,
            float distance, double centerX, double centerY, double centerZ, int x0, int y0, int x1, int y1,
            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds)
            implements PictureInPictureRenderState {
        State(List<PreviewRenderer.PreviewBlock> blocks, BlockAndTintGetter view, float yaw, float pitch,
                float distance, double centerX, double centerY, double centerZ, int x0, int y0, int x1, int y1,
                @Nullable ScreenRectangle scissorArea) {
            this(blocks, view, yaw, pitch, distance, centerX, centerY, centerZ, x0, y0, x1, y1, scissorArea,
                    PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
        }

        @Override
        public float scale() {
            return 1.0F;
        }
    }

    public static final class Renderer extends PictureInPictureRenderer<State> {
        private final Projection projection = new Projection();
        private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer(
                "Matter Manipulator GUI preview");

        public Renderer(MultiBufferSource.BufferSource bufferSource) {
            super(bufferSource);
        }

        @Override
        public Class<State> getRenderStateClass() {
            return State.class;
        }

        @Override
        protected void renderToTexture(State state, PoseStack guiPose) {
            double yawRadians = Math.toRadians(state.yaw());
            double pitchRadians = Math.toRadians(state.pitch());
            var eye = new Vector3f((float) Math.cos(yawRadians), (float) Math.tan(pitchRadians),
                    (float) Math.sin(yawRadians)).normalize().mul(state.distance());
            projection.setupPerspective(0.05F, 2048.0F, 55.0F, state.x1() - state.x0(), state.y1() - state.y0());
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
            var modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.identity().lookAt(eye, new Vector3f(), new Vector3f(0.0F, 1.0F, 0.0F));
            try {
                var renderer = new ModelBlockRenderer(false, false, Minecraft.getInstance().getBlockColors());
                var pose = new PoseStack();
                for (var block : state.blocks()) {
                    pose.pushPose();
                    pose.translate(block.pos().getX() - state.centerX(), block.pos().getY() - state.centerY(),
                            block.pos().getZ() - state.centerZ());
                    try {
                        PreviewRenderer.renderPreviewBlock(renderer, pose, bufferSource, state.view(), block);
                        PreviewRenderer.renderItemRenderedBlock(pose, bufferSource, block);
                    } catch (RuntimeException | LinkageError ignored) {
                    } finally {
                        pose.popPose();
                    }
                }
                bufferSource.endBatch();
            } finally {
                modelView.popMatrix();
                RenderSystem.restoreProjectionMatrix();
            }
        }

        @Override
        protected String getTextureLabel() {
            return "matter_manipulator_preview";
        }

        @Override
        public void close() {
            super.close();
            projectionBuffer.close();
        }
    }

    private void drawCompass(GuiGraphicsExtractor graphics, int centerX, int centerY) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        drawAxis(graphics, centerX, centerY, 1.0D, 0.0D, 0.0D, yawRadians, pitchRadians, 0xFFFF6262, "X");
        drawAxis(graphics, centerX, centerY, 0.0D, 1.0D, 0.0D, yawRadians, pitchRadians, 0xFF62E47E, "Y");
        drawAxis(graphics, centerX, centerY, 0.0D, 0.0D, 1.0D, yawRadians, pitchRadians, 0xFF62A8FF, "Z");
    }

    private static void drawAxis(
            GuiGraphicsExtractor graphics,
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
        graphics.text(Minecraft.getInstance().font, label, endX - 2, endY - 4, color, false);
    }

    private static void line(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
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
}
