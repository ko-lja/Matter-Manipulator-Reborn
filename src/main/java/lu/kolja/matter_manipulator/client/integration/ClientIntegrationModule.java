package lu.kolja.matter_manipulator.client.integration;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.client.integration.ae2.Ae2ClientIntegration;
import lu.kolja.matter_manipulator.client.integration.domumornamentum.DomumOrnamentumClientIntegration;
import lu.kolja.matter_manipulator.client.integration.fluxnetworks.FluxNetworksClientIntegration;
import lu.kolja.matter_manipulator.client.integration.framedblocks.FramedBlocksClientIntegration;
import lu.kolja.matter_manipulator.client.integration.gtceu.GTClientIntegration;
import lu.kolja.matter_manipulator.client.integration.laserio.LaserIoClientIntegration;
import lu.kolja.matter_manipulator.client.integration.littletiles.LittleTilesClientIntegration;
import lu.kolja.matter_manipulator.client.integration.mekanism.MekanismClientIntegration;
import lu.kolja.matter_manipulator.client.integration.projectred.ProjectRedClientIntegration;
import lu.kolja.matter_manipulator.common.integration.IntegrationModule;
import net.neoforged.fml.ModList;

import java.util.function.Consumer;

public enum ClientIntegrationModule {
    AE2(IntegrationModule.AE2, r -> Ae2ClientIntegration.register(r)),
    DOMUM_ORNAMENTUM(IntegrationModule.DOMUM_ORNAMENTUM, r -> DomumOrnamentumClientIntegration.register(r)),
    FLUX_NETWORKS(IntegrationModule.FLUX_NETWORKS, r -> FluxNetworksClientIntegration.register(r)),
    FRAMED_BLOCKS(IntegrationModule.FRAMED_BLOCKS, r -> FramedBlocksClientIntegration.register(r)),
    GTCEU(IntegrationModule.GTCEU, r -> GTClientIntegration.register(r)),
    LASER_IO(IntegrationModule.LASER_IO, r -> LaserIoClientIntegration.register(r)),
    LITTLE_TILES(IntegrationModule.LITTLE_TILES, r -> LittleTilesClientIntegration.register(r)),
    MEKANISM(IntegrationModule.MEKANISM, r -> MekanismClientIntegration.register(r)),
    PROJECT_RED(IntegrationModule.PROJECT_RED, r -> ProjectRedClientIntegration.register(r));

    private final IntegrationModule integration;
    private final Consumer<ClientIntegrationRegistrar> registration;

    ClientIntegrationModule(IntegrationModule integration, Consumer<ClientIntegrationRegistrar> registration) {
        this.integration = integration;
        this.registration = registration;
    }

    public boolean available(ModList mods) {
        return integration.available(mods);
    }

    public void register(ClientIntegrationRegistrar registrar) {
        registration.accept(registrar);
    }
}
