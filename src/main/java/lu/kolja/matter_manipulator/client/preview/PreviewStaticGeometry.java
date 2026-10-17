package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.client.integration.ClientIntegrationDispatcher;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class PreviewStaticGeometry {
    private static final int STRIDE = 8;

    private final Map<RenderType, Recorder> layers;

    private PreviewStaticGeometry(Map<RenderType, Recorder> layers) {
        this.layers = layers;
    }

    static @Nullable PreviewStaticGeometry record(Level level, PreviewContentLevel view, PreviewVisual visual,
            BlockPos pos, BlockState state, PreviewDetail detail) {
        var recorders = new HashMap<RenderType, Recorder>();
        var drawn = PreviewModelData.renderStatic(level, view, visual, pos, state, detail, new PoseStack(),
                layer -> recorders.computeIfAbsent(layer, ignored -> new Recorder()));
        if (drawn != ClientIntegrationDispatcher.StaticRender.DRAWN) {
            return null;
        }
        recorders.values().removeIf(recorder -> recorder.vertices == 0);
        return recorders.isEmpty() ? null : new PreviewStaticGeometry(Map.copyOf(recorders));
    }

    void render(PoseStack pose, MultiBufferSource buffers) {
        var last = pose.last();
        layers.forEach((layer, recorded) -> {
            var consumer = buffers.getBuffer(RenderTypeHelper.getEntityRenderType(layer, false));
            var data = recorded.data;
            for (int vertex = 0; vertex < recorded.vertices; vertex++) {
                int i = vertex * STRIDE;
                int color = recorded.colors[vertex];
                consumer.addVertex(last.pose(), data[i], data[i + 1], data[i + 2])
                        .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24)
                        .setUv(data[i + 3], data[i + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(last, data[i + 5], data[i + 6], data[i + 7]);
            }
        });
    }

    private static final class Recorder implements VertexConsumer {
        private float[] data = new float[STRIDE * 64];
        private int[] colors = new int[64];
        private int vertices;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (vertices == colors.length) {
                data = Arrays.copyOf(data, data.length * 2);
                colors = Arrays.copyOf(colors, colors.length * 2);
            }
            int i = vertices * STRIDE;
            Arrays.fill(data, i, i + STRIDE, 0.0F);
            data[i] = x;
            data[i + 1] = y;
            data[i + 2] = z;
            colors[vertices++] = -1;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            colors[vertices - 1] = alpha << 24 | red << 16 | green << 8 | blue;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            int i = (vertices - 1) * STRIDE;
            data[i + 3] = u;
            data[i + 4] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            int i = (vertices - 1) * STRIDE;
            data[i + 5] = x;
            data[i + 6] = y;
            data[i + 7] = z;
            return this;
        }
    }
}
