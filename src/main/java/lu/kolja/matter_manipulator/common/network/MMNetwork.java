package lu.kolja.matter_manipulator.common.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class MMNetwork {

    private static final String PROTOCOL = "6";

    private MMNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToServer(ConfigActionPacket.TYPE, ConfigActionPacket.STREAM_CODEC, ConfigActionPacket::handle);
        registrar.playToServer(PickBlockPacket.TYPE, PickBlockPacket.STREAM_CODEC, PickBlockPacket::handle);
        registrar.playToClient(ToolStateSyncPacket.TYPE, ToolStateSyncPacket.STREAM_CODEC, ToolStateSyncPacket::handle);
        registrar.playToServer(GuiStatusRequestPacket.TYPE, GuiStatusRequestPacket.STREAM_CODEC,
                GuiStatusRequestPacket::handle);
        registrar.playToClient(GuiStatusPacket.TYPE, GuiStatusPacket.STREAM_CODEC, GuiStatusPacket::handle);
        registrar.playToServer(PasteAnchorPacket.TYPE, PasteAnchorPacket.STREAM_CODEC, PasteAnchorPacket::handle);
        registrar.playToServer(NudgeAnchorPacket.TYPE, NudgeAnchorPacket.STREAM_CODEC, NudgeAnchorPacket::handle);
        registrar.playToServer(EditSelectionPacket.TYPE, EditSelectionPacket.STREAM_CODEC, EditSelectionPacket::handle);
        registrar.playToServer(SaveBlueprintPacket.TYPE, SaveBlueprintPacket.STREAM_CODEC, SaveBlueprintPacket::handle);
        registrar.playToClient(BlueprintDownloadPacket.TYPE, BlueprintDownloadPacket.STREAM_CODEC,
                BlueprintDownloadPacket::handle);
        registrar.playToServer(BlueprintUploadPacket.TYPE, BlueprintUploadPacket.STREAM_CODEC,
                BlueprintUploadPacket::handle);
        registrar.playToClient(BlueprintRequestPacket.TYPE, BlueprintRequestPacket.STREAM_CODEC,
                BlueprintRequestPacket::handle);
    }
}
