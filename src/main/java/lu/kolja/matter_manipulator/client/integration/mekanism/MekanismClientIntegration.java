package lu.kolja.matter_manipulator.client.integration.mekanism;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.common.integration.mekanism.MekanismIntegration;
import mekanism.api.NBTConstants;
import mekanism.client.model.data.TransmitterModelData;
import mekanism.common.block.transmitter.BlockDiversionTransporter;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.Set;

public final class MekanismClientIntegration {

    private MekanismClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(MekanismIntegration.TRANSMITTER_ID, new TransmitterPreview(), MekanismIntegration::isTransmitter);
    }

    static void connect(TransmitterModelData data, CompoundTag payload, Set<Direction> lineDirections) {
        int connected = payload.getInt(MekanismIntegration.CONNECTED);
        for (var side : Direction.values()) {
            var key = NBTConstants.CONNECTION + side.ordinal();
            ConnectionType type;
            if (!lineDirections.isEmpty()) {
                type = lineDirections.contains(side) ? ConnectionType.NORMAL : ConnectionType.NONE;
            } else if ((connected & 1 << side.ordinal()) == 0) {
                type = ConnectionType.NONE;
            } else {
                type = payload.contains(key) ? ConnectionType.byIndexStatic(payload.getInt(key)) : ConnectionType.NORMAL;
            }
            data.setConnectionData(side, type);
        }
        data.setHasColor(payload.contains(NBTConstants.COLOR) && payload.getInt(NBTConstants.COLOR) >= 0);
    }

    private static final class TransmitterPreview implements ClientPreviewIntegration {

        @Override
        public ModelData create(ClientIntegrationContext.CapturedPreview context) {
            var data = modelData(context.state());
            connect(data, context.payload(MekanismIntegration.TRANSMITTER_ID), context.lineDirections());
            return ModelData.builder().with(TileEntityTransmitter.TRANSMITTER_PROPERTY, data).build();
        }

        @Override
        public ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
            if (!(context.sourceBlockEntity() instanceof TileEntityTransmitter tile)) {
                return ModelData.EMPTY;
            }
            var source = tile.getModelData().get(TileEntityTransmitter.TRANSMITTER_PROPERTY);
            if (source == null) {
                return ModelData.EMPTY;
            }
            var data = modelData(context.sourceState());
            for (var side : Direction.values()) {
                data.setConnectionData(context.transform().transform(side), source.getConnectionType(side));
            }
            data.setHasColor(source.getHasColor());
            return ModelData.builder().with(TileEntityTransmitter.TRANSMITTER_PROPERTY, data).build();
        }

        private static TransmitterModelData modelData(BlockState state) {
            return state.getBlock() instanceof BlockDiversionTransporter
                    ? new TransmitterModelData.Diversion()
                    : new TransmitterModelData();
        }
    }
}
