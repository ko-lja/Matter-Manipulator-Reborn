package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

final class PreviewRenderTypes extends RenderType {
    static final PreviewTranslucentSorting TRANSLUCENT_SORTING = PreviewTranslucentSorting.COARSE;
    private static final RenderType GHOST = RenderType.create(
            "matter_manipulator_preview_ghost",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            RenderType.BIG_BUFFER_SIZE,
            true,
            false,
            CompositeState.builder()
                    .setLightmapState(LIGHTMAP)
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(TRANSLUCENT_TARGET)
                    .createCompositeState(true)
    );
    private static final RenderType SIMPLE_DEPTH = prepass("simple_depth", DefaultVertexFormat.BLOCK, true, false);
    private static final RenderType SIMPLE = prepass("simple", DefaultVertexFormat.BLOCK, true, true);
    private static final RenderType MASSIVE_DEPTH = prepass("massive_depth", DefaultVertexFormat.POSITION_COLOR, false,
            false);
    private static final RenderType MASSIVE = prepass("massive", DefaultVertexFormat.POSITION_COLOR, false, true);
    private static final RenderType STATUS = RenderType.create(
            "matter_manipulator_preview_status",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            RenderType.SMALL_BUFFER_SIZE,
            false,
            false,
            CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setOutputState(TRANSLUCENT_TARGET)
                    .createCompositeState(false)
    );

    private PreviewRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
            boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    private static RenderType prepass(String name, VertexFormat format, boolean textured, boolean color) {
        var state = CompositeState.builder()
                .setShaderState(textured ? RENDERTYPE_TRANSLUCENT_SHADER : POSITION_COLOR_SHADER)
                .setOutputState(TRANSLUCENT_TARGET);
        if (textured) {
            state.setTextureState(BLOCK_SHEET_MIPPED).setLightmapState(LIGHTMAP);
        }
        if (color) {
            state.setTransparencyState(TRANSLUCENT_TRANSPARENCY).setDepthTestState(EQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE);
        } else {
            state.setWriteMaskState(DEPTH_WRITE);
        }
        return RenderType.create("matter_manipulator_preview_" + name, format, VertexFormat.Mode.QUADS,
                RenderType.SMALL_BUFFER_SIZE, false, false, state.createCompositeState(false));
    }

    static RenderType ghost() {
        return GHOST;
    }

    static RenderType simple() {
        return SIMPLE;
    }

    static RenderType massive() {
        return MASSIVE;
    }

    static RenderType status() {
        return STATUS;
    }

    static RenderType depthPrepass(RenderType type) {
        return type == SIMPLE ? SIMPLE_DEPTH : type == MASSIVE ? MASSIVE_DEPTH : null;
    }

    static boolean compileSorted(RenderType type) {
        return (type == GHOST || type == RenderType.translucent()) && TRANSLUCENT_SORTING.sortAtCompile();
    }
}
