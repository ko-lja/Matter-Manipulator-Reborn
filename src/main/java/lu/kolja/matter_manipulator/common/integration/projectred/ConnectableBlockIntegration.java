package lu.kolja.matter_manipulator.common.integration.projectred;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import mrtjp.projectred.core.block.ProjectRedBlock;
import mrtjp.projectred.core.tile.IConnectableBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

final class ConnectableBlockIntegration implements BlockIntegration {

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("projectred_core", "connections");

    static boolean isProjectRedBlock(BlockState state) {
        return state.getBlock() instanceof ProjectRedBlock;
    }

    @Override
    public void afterTransfer(IntegrationContext.Transferred context) {
        if (context.blockEntity() instanceof IConnectableBlockEntity connectable) {
            connectable.updateExternals();
        }
    }

    @Override
    public boolean transformsBlockEntity(BlockState state) {
        return false;
    }
}
