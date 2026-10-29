package lu.kolja.matter_manipulator.common.integration.mekanism;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import mekanism.api.SerializationConstants;
import mekanism.api.text.EnumColor;
import mekanism.common.content.network.transmitter.LogisticalTransporter;
import mekanism.common.lib.transmitter.ConnectionType;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;


final class TransmitterIntegration implements BlockIntegration, CableIntegration {

    @Override
    public CompoundTag capture(IntegrationContext.Capture context) {
        if (!(context.blockEntity() instanceof TileEntityTransmitter tile)) {
            return new CompoundTag();
        }
        var transmitter = tile.getTransmitter();
        var tag = fromMekanism(transmitter.write(context.level().registryAccess(), new CompoundTag()));
        int connected = 0;
        for (var side : Direction.values()) {
            if (transmitter.getConnectionType(side) != ConnectionType.NONE) {
                connected |= 1 << side.ordinal();
            }
        }
        tag.putInt(MekanismIntegration.CONNECTED, connected);
        return tag;
    }

    @Override
    public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
        if (!(context.blockEntity() instanceof TileEntityTransmitter tile)) {
            return ApplyResult.REJECTED_UNCHANGED;
        }
        if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
            return ApplyResult.APPLIED;
        }
        var transmitter = tile.getTransmitter();
        if (context.purpose() == PlacementPurpose.RECONFIGURE) {
            for (var side : Direction.values()) {
                var key = SerializationConstants.CONNECTION + side.ordinal();
                if (payload.contains(key)) {
                    transmitter.setConnectionTypeRaw(side, ConnectionType.BY_ID.apply(payload.getInt(key)));
                }
            }
            if (transmitter instanceof LogisticalTransporter transporter && payload.contains(SerializationConstants.COLOR)) {
                transporter.setColor(EnumColor.BY_ID.apply(payload.getInt(SerializationConstants.COLOR)));
            }
        } else {
            var registries = context.level().registryAccess();
            var data = transmitter.write(registries, new CompoundTag());
            var settings = settings(payload);
            for (var key : new String[]{SerializationConstants.REDSTONE, SerializationConstants.COLOR}) {
                if (settings.contains(key)) {
                    data.put(key, settings.get(key).copy());
                }
            }
            join(settings, data, SerializationConstants.CONNECTION);
            join(settings, data, SerializationConstants.MODE);
            transmitter.read(registries, data);
        }
        transmitter.refreshConnections();
        transmitter.notifyTileChange();
        tile.sendUpdatePacket();
        return ApplyResult.APPLIED;
    }

    @Override
    public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
        if (desired.contains(SerializationConstants.REDSTONE) && existing.getBoolean(SerializationConstants.REDSTONE) != desired.getBoolean(SerializationConstants.REDSTONE)) {
            return false;
        }
        for (var side : Direction.values()) {
            var key = SerializationConstants.MODE + side.ordinal();
            if (desired.contains(key) && existing.getInt(key) != desired.getInt(key)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
        var transformed = transformDirections(payload, context);
        if (payload.contains(MekanismIntegration.CONNECTED)) {
            int connected = 0;
            for (var side : Direction.values()) {
                if ((payload.getInt(MekanismIntegration.CONNECTED) & 1 << side.ordinal()) != 0) {
                    connected |= 1 << context.transform().transform(side).ordinal();
                }
            }
            transformed.putInt(MekanismIntegration.CONNECTED, connected);
        }
        return transformed;
    }

    @Override
    public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
        var transformed = MekanismSides.transformArrayByDirection(blockEntity, SerializationConstants.CONNECTION, context.transform());
        return MekanismSides.transformArrayByDirection(transformed, SerializationConstants.MODE, context.transform());
    }

    @Override
    public CablePlacementData prepare(IntegrationContext.Cable context) {
        var payload = settings(context.payload(MekanismIntegration.TRANSMITTER_ID));
        boolean reused = context.blockEntity() instanceof TileEntityTransmitter;
        int connected = 0;
        for (var side : Direction.values()) {
            var key = SerializationConstants.CONNECTION + side.ordinal();
            if (context.lineDirections().contains(side)) {
                payload.putInt(key, ConnectionType.NORMAL.ordinal());
                connected |= 1 << side.ordinal();
            } else if (reused) {
                payload.remove(key);
            } else if (context.level().getBlockEntity(context.pos().relative(side)) instanceof TileEntityTransmitter) {
                payload.putInt(key, ConnectionType.NONE.ordinal());
            } else {
                payload.putInt(key, ConnectionType.NORMAL.ordinal());
            }
        }
        payload.putInt(MekanismIntegration.CONNECTED, connected);
        return CablePlacementData.duringPlacement(payload);
    }

    @Override
    public boolean canReuse(IntegrationContext.CableReuse context) {
        return context.existing().getBlock() == context.target().getBlock();
    }

    private static CompoundTag transformDirections(CompoundTag tag, IntegrationContext.Transform context) {
        var transformed = MekanismSides.transformByDirection(tag, SerializationConstants.CONNECTION, context.transform());
        return MekanismSides.transformByDirection(transformed, SerializationConstants.MODE, context.transform());
    }

    private static CompoundTag fromMekanism(CompoundTag transmitterData) {
        var settings = new CompoundTag();
        for (var key : new String[]{SerializationConstants.REDSTONE, SerializationConstants.COLOR}) {
            if (transmitterData.contains(key)) {
                settings.put(key, transmitterData.get(key).copy());
            }
        }
        for (var key : new String[]{SerializationConstants.CONNECTION, SerializationConstants.MODE}) {
            var values = transmitterData.getIntArray(key);
            for (int index = 0; index < values.length && index < Direction.values().length; index++) {
                settings.putInt(key + index, values[index]);
            }
        }
        return settings;
    }

    private static void join(CompoundTag settings, CompoundTag transmitterData, String key) {
        if (!transmitterData.contains(key, Tag.TAG_INT_ARRAY)) {
            return;
        }
        var values = transmitterData.getIntArray(key).clone();
        for (int index = 0; index < values.length && index < Direction.values().length; index++) {
            if (settings.contains(key + index)) {
                values[index] = settings.getInt(key + index);
            }
        }
        transmitterData.putIntArray(key, values);
    }

    private static CompoundTag settings(CompoundTag transmitterData) {
        var settings = new CompoundTag();
        for (var key : transmitterData.getAllKeys()) {
            if (isSetting(key)) {
                settings.put(key, transmitterData.get(key).copy());
            }
        }
        return settings;
    }

    private static boolean isSetting(String key) {
        if (key.equals(SerializationConstants.REDSTONE) || key.equals(SerializationConstants.COLOR)) {
            return true;
        }
        for (var side : Direction.values()) {
            if (key.equals(SerializationConstants.CONNECTION + side.ordinal()) || key.equals(SerializationConstants.MODE + side.ordinal())) {
                return true;
            }
        }
        return false;
    }
}
