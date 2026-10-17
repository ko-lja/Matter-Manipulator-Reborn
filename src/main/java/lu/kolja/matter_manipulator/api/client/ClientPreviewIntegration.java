package lu.kolja.matter_manipulator.api.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.model.data.ModelData;

/** Results are meshed once and cached, so they must describe a static appearance. Positions are preview-local. */
public interface ClientPreviewIntegration {

    default ModelData create(ClientIntegrationContext.CapturedPreview context) {
        return ModelData.EMPTY;
    }

    default ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
        return ModelData.EMPTY;
    }

    /**
     * Called once per layer while the cached mesh is built, not every frame. Write BLOCK vertices at the block
     * origin. Returning true replaces the baked model, and drawing nothing or throwing shows the outline.
     */
    default boolean renderStatic(ClientIntegrationContext.StaticPreview context, PoseStack pose, VertexConsumer out,
            ChunkSectionLayer layer) {
        return false;
    }

    /** The item that stands for a captured block in the UI, or empty for the block's own item. */
    default ItemStack previewIcon(BlockSnapshot captured) {
        return ItemStack.EMPTY;
    }
}
