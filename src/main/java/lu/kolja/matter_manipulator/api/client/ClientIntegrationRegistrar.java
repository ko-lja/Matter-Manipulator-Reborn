package lu.kolja.matter_manipulator.api.client;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Client registration scope. IDs, handlers and selectors follow the same order as common registration. */
public interface ClientIntegrationRegistrar {
    void preview(Identifier id, ClientPreviewIntegration handler, Block... blocks);

    void preview(Identifier id, ClientPreviewIntegration handler, Predicate<BlockState> selector);
}
