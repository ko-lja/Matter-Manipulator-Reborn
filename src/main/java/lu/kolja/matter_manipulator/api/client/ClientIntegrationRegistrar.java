package lu.kolja.matter_manipulator.api.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Client registration scope. IDs, handlers and selectors follow the same order as common registration. */
public interface ClientIntegrationRegistrar {
    void preview(ResourceLocation id, ClientPreviewIntegration handler, Block... blocks);

    void preview(ResourceLocation id, ClientPreviewIntegration handler, Predicate<BlockState> selector);
}
