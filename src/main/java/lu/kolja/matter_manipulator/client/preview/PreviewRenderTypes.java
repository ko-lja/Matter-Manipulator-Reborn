package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import lu.kolja.matter_manipulator.MatterManipulator;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.List;
import java.util.Optional;

final class PreviewRenderTypes {
    static final PreviewTranslucentSorting TRANSLUCENT_SORTING = PreviewTranslucentSorting.COARSE;
    private static final RenderPipeline GHOST_PIPELINE = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
            .withLocation(MatterManipulator.id("pipeline/preview_ghost"))
            .withShaderDefine("ALPHA_CUTOUT", 0.01F)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build();
    private static final RenderPipeline SIMPLE_DEPTH = prepass("simple_depth", RenderPipelines.BLOCK_SNIPPET, false);
    private static final RenderPipeline SIMPLE_PIPELINE = prepass("simple", RenderPipelines.BLOCK_SNIPPET, true);
    private static final RenderPipeline MASSIVE_DEPTH = prepass("massive_depth", RenderPipelines.DEBUG_FILLED_SNIPPET,
            false);
    private static final RenderPipeline MASSIVE_PIPELINE = prepass("massive", RenderPipelines.DEBUG_FILLED_SNIPPET, true);
    private static final RenderPipeline STATUS_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(MatterManipulator.id("pipeline/preview_status"))
            .build();
    private static final RenderType GHOST = RenderType.create("matter_manipulator_preview_ghost",
            textured(GHOST_PIPELINE));
    private static final RenderType SIMPLE = RenderType.create("matter_manipulator_preview_simple",
            textured(SIMPLE_PIPELINE));
    private static final RenderType MASSIVE = RenderType.create("matter_manipulator_preview_massive",
            RenderSetup.builder(MASSIVE_PIPELINE).createRenderSetup());
    private static final RenderType STATUS = RenderType.create("matter_manipulator_preview_status",
            RenderSetup.builder(STATUS_PIPELINE).createRenderSetup());

    private PreviewRenderTypes() {}

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        for (var pipeline : List.of(GHOST_PIPELINE, SIMPLE_DEPTH, SIMPLE_PIPELINE, MASSIVE_DEPTH, MASSIVE_PIPELINE,
                STATUS_PIPELINE)) {
            event.registerPipeline(pipeline);
        }
    }

    private static RenderPipeline prepass(String name, RenderPipeline.Snippet snippet, boolean color) {
        var builder = RenderPipeline.builder(snippet).withLocation(MatterManipulator.id("pipeline/preview_" + name));
        if (color) {
            builder.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false));
        } else {
            builder.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true));
        }
        return builder.build();
    }

    private static RenderSetup textured(RenderPipeline pipeline) {
        return RenderSetup.builder(pipeline)
                .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS, PreviewRenderTypes::blockSampler)
                .useLightmap()
                .createRenderSetup();
    }

    static GpuSampler blockSampler() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.NEAREST, true);
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

    static RenderPipeline depthPrepass(RenderType type) {
        return type == SIMPLE ? SIMPLE_DEPTH : type == MASSIVE ? MASSIVE_DEPTH : null;
    }

    static boolean compileSorted(RenderType type) {
        return (type == GHOST || type == RenderTypes.translucentMovingBlock()) && TRANSLUCENT_SORTING.sortAtCompile();
    }
}
