package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.client.gui.ManipulatorScreen;
import lu.kolja.matter_manipulator.client.integration.ClientIntegrationDispatcher;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public final class ClientMatterManipulator {

    private ClientMatterManipulator() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientMatterManipulator::clientSetup);
        modBus.addListener(ClientEvents::registerKeyMappings);
        modBus.addListener(PasteFollow::registerOverlay);
        modBus.addListener(ScrollEdit::registerOverlay);
        modBus.addListener(PendingSelection::registerOverlay);
        modBus.addListener(ClientMatterManipulator::registerReloadListeners);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
    }

    private static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> PreviewRenderer.clearWorldPreview());
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientIntegrationDispatcher::initialize);
    }

    public static void openScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new ManipulatorScreen(hand));
    }
}
