package lu.kolja.matter_manipulator.common.integration.projectred;

import lu.kolja.matter_manipulator.api.IntegrationRegistrar;

import codechicken.multipart.init.CBMultipartModContent;
import net.neoforged.fml.ModList;

public final class ProjectRedIntegration {

    private ProjectRedIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(MultipartIntegration.ID, new MultipartIntegration(), CBMultipartModContent.MULTIPART_BLOCK.get());
        registrar.block(OrientedBlockIntegration.ID, new OrientedBlockIntegration(), OrientedBlockIntegration::isOriented);
        registrar.block(ConnectableBlockIntegration.ID, new ConnectableBlockIntegration(),
                ConnectableBlockIntegration::isProjectRedBlock);
        if (ModList.get().isLoaded("projectred_expansion")) {
            registrar.block(PneumaticTubeIntegration.ID, new PneumaticTubeIntegration(),
                    CBMultipartModContent.MULTIPART_BLOCK.get());
        }
        if (ModList.get().isLoaded("projectred_transmission")) {
            registrar.cable(MultipartIntegration.ID, new WireCableIntegration(),
                    CBMultipartModContent.MULTIPART_BLOCK.get());
        }
    }
}
