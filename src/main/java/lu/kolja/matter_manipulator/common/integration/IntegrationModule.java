package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.common.integration.ae2.Ae2Integration;
import lu.kolja.matter_manipulator.common.integration.domumornamentum.DomumOrnamentumIntegration;
import lu.kolja.matter_manipulator.common.integration.fluxnetworks.FluxNetworksIntegration;
import lu.kolja.matter_manipulator.common.integration.framedblocks.FramedBlocksIntegration;
import lu.kolja.matter_manipulator.common.integration.gtceu.GTIntegration;
import lu.kolja.matter_manipulator.common.integration.laserio.LaserIoIntegration;
import lu.kolja.matter_manipulator.common.integration.littletiles.LittleTilesIntegration;
import lu.kolja.matter_manipulator.common.integration.mekanism.MekanismIntegration;
import lu.kolja.matter_manipulator.common.integration.mekanism.generators.MekanismGeneratorsIntegration;
import lu.kolja.matter_manipulator.common.integration.projectred.ProjectRedIntegration;
import lu.kolja.matter_manipulator.common.integration.rechiseled.RechiseledIntegration;
import net.neoforged.fml.ModList;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public enum IntegrationModule {
    AE2(r -> Ae2Integration.register(r), "ae2"),
    DOMUM_ORNAMENTUM(r -> DomumOrnamentumIntegration.register(r), "domum_ornamentum"),
    FLUX_NETWORKS(r -> FluxNetworksIntegration.register(r), "fluxnetworks"),
    FRAMED_BLOCKS(r -> FramedBlocksIntegration.register(r), "framedblocks"),
    GTCEU(r -> GTIntegration.register(r), "gtceu"),
    LASER_IO(r -> LaserIoIntegration.register(r), "laserio"),
    LITTLE_TILES(r -> LittleTilesIntegration.register(r), "littletiles"),
    MEKANISM(r -> MekanismIntegration.register(r), "mekanism"),
    MEKANISM_GENERATORS(r -> MekanismGeneratorsIntegration.register(r), "mekanism", "mekanismgenerators"),
    PROJECT_RED(r -> ProjectRedIntegration.register(r), "cb_multipart", "projectred_core"),
    RECHISELED(r -> RechiseledIntegration.register(r), "rechiseled");

    private final Consumer<IntegrationRegistrar> registration;
    private final String[] requiredMods;

    IntegrationModule(Consumer<IntegrationRegistrar> registration, String... requiredMods) {
        this.registration = registration;
        this.requiredMods = requiredMods;
    }

    public static List<IntegrationModule> loaded(ModList mods) {
        return Arrays.stream(values()).filter(module -> module.available(mods)).toList();
    }

    public boolean available(ModList mods) {
        return Arrays.stream(requiredMods).allMatch(mods::isLoaded);
    }

    public void register(IntegrationRegistrar registrar) {
        registration.accept(registrar);
    }
}
