package lu.kolja.matter_manipulator.common.integration.projectred;

import codechicken.multipart.block.TileMultipart;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import mrtjp.projectred.expansion.pneumatics.PneumaticTransportContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

final class PneumaticTubeIntegration implements BlockIntegration {

    static final Identifier ID = Identifier.fromNamespaceAndPath("projectred_expansion", "tube_contents");

    @Override
    public void beforeRemove(IntegrationContext.Removal context) {
        if (!context.collectDrops() || !(context.blockEntity() instanceof TileMultipart tile)) {
            return;
        }
        for (var part : tile.getPartList()) {
            if (part instanceof PneumaticTransportContainer tube) {
                var transport = tube.getPneumaticTransport();
                transport.getPayloads().forEach(payload -> context.dropSink().accept(payload.getItemStack().copy()));
                transport.removePayloadsConditionally(payload -> true);
            }
        }
    }

    @Override
    public boolean transformsBlockEntity(BlockState state) {
        return false;
    }
}
