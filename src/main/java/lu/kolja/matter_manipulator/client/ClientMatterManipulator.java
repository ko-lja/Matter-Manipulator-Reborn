package lu.kolja.matter_manipulator.client;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.gui.GuiPreviewScene;
import lu.kolja.matter_manipulator.client.gui.ManipulatorScreen;
import lu.kolja.matter_manipulator.client.integration.ClientIntegrationDispatcher;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.datagen.MMDataGenerators;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
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
        modBus.addListener(PreviewRenderer::registerPipelines);
        modBus.addListener(ClientMatterManipulator::registerPictureInPictureRenderers);
        modBus.addListener(MMDataGenerators::gatherData);
        NeoForge.EVENT_BUS.register(ClientEvents.class);
    }

    private static void registerPictureInPictureRenderers(RegisterPictureInPictureRenderersEvent event) {
        event.register(GuiPreviewScene.State.class, GuiPreviewScene.Renderer::new);
    }

    private static void registerReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(MatterManipulator.id("world_preview"), (ResourceManagerReloadListener) manager -> PreviewRenderer.clearWorldPreview());
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientIntegrationDispatcher::initialize);
    }

    public static void openScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new ManipulatorScreen(hand));
    }
}
