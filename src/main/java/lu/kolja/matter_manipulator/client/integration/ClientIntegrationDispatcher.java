package lu.kolja.matter_manipulator.client.integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.ModList;

import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class ClientIntegrationDispatcher {

    private static volatile ClientIntegrationRegistry registry;

    public enum StaticRender {
        NONE,
        DRAWN,
        FAILED
    }

    private ClientIntegrationDispatcher() {}

    public static synchronized void initialize() {
        if (registry != null) {
            return;
        }
        var registrations = ClientIntegrationRegistration.close();
        var builder = ClientIntegrationRegistry.builder();
        try {
            var mods = ModList.get();
            for (var module : ClientIntegrationModule.values()) {
                if (module.available(mods)) {
                    module.register(builder);
                }
            }
            registrations.forEach(registration -> registration.accept(builder));
            registry = builder.build();
        } finally {
            builder.freeze();
        }
    }

    public static ModelData create(
            Level level,
            BlockPos pos,
            BlockState state,
            CapturedBlock captured,
            Set<Direction> lineDirections,
            PreviewDetail detail
    ) {
        return get().create(level, pos, state, captured, lineDirections, detail);
    }

    public static ModelData fromWorld(
            Level level,
            BlockPos sourcePos,
            BlockState sourceState,
            BlockPos previewPos,
            BlockState previewState,
            ToolTransform transform,
            PreviewDetail detail
    ) {
        return get().fromWorld(level, sourcePos, sourceState, previewPos, previewState, transform, detail);
    }

    public static StaticRender renderStatic(
            Level level,
            BlockAndTintGetter view,
            BlockPos pos,
            BlockState state,
            @Nullable CapturedBlock captured,
            @Nullable BlockPos sourcePos,
            DirectionTransform transform,
            Set<Direction> lineDirections,
            PreviewDetail detail,
            PoseStack pose,
            Function<RenderType, VertexConsumer> buffers
    ) {
        return get().renderStatic(level, view, pos, state, captured, sourcePos, transform, lineDirections, detail, pose,
                buffers);
    }

    public static ItemStack previewIcon(CapturedBlock captured) {
        return get().previewIcon(captured);
    }

    private static ClientIntegrationRegistry get() {
        if (registry == null) {
            throw new IllegalStateException("Client integrations have not been initialized");
        }
        return registry;
    }
}
