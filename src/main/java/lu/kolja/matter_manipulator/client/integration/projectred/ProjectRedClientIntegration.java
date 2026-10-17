package lu.kolja.matter_manipulator.client.integration.projectred;

import codechicken.multipart.init.CBMultipartModContent;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.common.integration.projectred.MultipartPreviewParts;

public final class ProjectRedClientIntegration {

    private ProjectRedClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(MultipartPreviewParts.PAYLOAD, new MultipartClientIntegration(),
                CBMultipartModContent.MULTIPART_BLOCK.get());
    }
}
