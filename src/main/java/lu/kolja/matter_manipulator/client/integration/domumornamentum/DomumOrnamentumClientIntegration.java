package lu.kolja.matter_manipulator.client.integration.domumornamentum;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.common.integration.domumornamentum.DomumOrnamentumIntegration;
import com.ldtteam.domumornamentum.block.decorative.DynamicTimberFrameBlock;
import com.ldtteam.domumornamentum.client.model.properties.ModProperties;
import com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class DomumOrnamentumClientIntegration {

    private DomumOrnamentumClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(DomumOrnamentumIntegration.MATERIALS_ID, new MaterialsPreview(), DomumOrnamentumIntegration::textured);
    }

    private static final class MaterialsPreview implements ClientPreviewIntegration {

        @Override
        public ModelData create(ClientIntegrationContext.CapturedPreview context) {
            var payload = context.payload(DomumOrnamentumIntegration.MATERIALS_ID);
            if (payload.isEmpty()) {
                return ModelData.EMPTY;
            }
            if (!(context.state().getBlock() instanceof DynamicTimberFrameBlock)) {
                return ModelData.builder()
                        .with(ModProperties.MATERIAL_TEXTURE_PROPERTY, DomumOrnamentumIntegration.materials(payload))
                        .build();
            }
            if (context.detail() != PreviewDetail.FULL) {
                return ModelData.EMPTY;
            }
            var frame = DomumOrnamentumIntegration.detached(context.state(), payload);
            return frame == null ? ModelData.EMPTY : frame.getModelData();
        }

        @Override
        public ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
            var blockEntity = context.sourceBlockEntity();
            return blockEntity instanceof IMateriallyTexturedBlockEntity ? blockEntity.getModelData() : ModelData.EMPTY;
        }
    }
}
