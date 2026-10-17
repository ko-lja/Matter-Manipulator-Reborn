package lu.kolja.matter_manipulator.common.integration.fluxnetworks;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementRequirements;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import sonar.fluxnetworks.api.FluxConstants;
import sonar.fluxnetworks.api.FluxDataComponents;
import sonar.fluxnetworks.common.block.FluxConnectorBlock;
import sonar.fluxnetworks.common.connection.FluxNetwork;
import sonar.fluxnetworks.common.connection.FluxNetworkData;
import sonar.fluxnetworks.common.device.TileFluxDevice;
import sonar.fluxnetworks.register.RegistryBlocks;

import java.util.Objects;

public final class FluxNetworksIntegration {

    public static final Identifier DEVICE_ID = Identifier.fromNamespaceAndPath("fluxnetworks", "device");

    public static final String NETWORK_ID = FluxConstants.NETWORK_ID;
    public static final String CUSTOM_NAME = FluxConstants.CUSTOM_NAME;
    public static final String PRIORITY = FluxConstants.PRIORITY;
    public static final String SURGE_MODE = FluxConstants.SURGE_MODE;
    public static final String LIMIT = FluxConstants.LIMIT;
    public static final String DISABLE_LIMIT = FluxConstants.DISABLE_LIMIT;
    public static final String FORCED_LOADING = FluxConstants.FORCED_LOADING;
    public static final String ENERGY = FluxConstants.ENERGY;
    public static final byte NBT_TILE_SETTINGS = FluxConstants.NBT_TILE_SETTINGS;
    private static final String[] SETTINGS = {CUSTOM_NAME, PRIORITY, SURGE_MODE, LIMIT, DISABLE_LIMIT};

    private FluxNetworksIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(DEVICE_ID, new DeviceIntegration(),
                RegistryBlocks.FLUX_PLUG.get(),
                RegistryBlocks.FLUX_POINT.get(),
                RegistryBlocks.FLUX_CONTROLLER.get(),
                RegistryBlocks.BASIC_FLUX_STORAGE.get(),
                RegistryBlocks.HERCULEAN_FLUX_STORAGE.get(),
                RegistryBlocks.GARGANTUAN_FLUX_STORAGE.get());
    }

    private static final class DeviceIntegration implements BlockIntegration {

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (!(context.blockEntity() instanceof TileFluxDevice device)) {
                return tag;
            }
            tag.putString(CUSTOM_NAME, device.getCustomName());
            tag.putInt(PRIORITY, device.getRawPriority());
            tag.putBoolean(SURGE_MODE, device.getSurgeMode());
            tag.putLong(LIMIT, device.getRawLimit());
            tag.putBoolean(DISABLE_LIMIT, device.getDisableLimit());
            if (FluxNetworkData.getNetwork(device.getNetworkID()).isValid()) {
                tag.putInt(NETWORK_ID, device.getNetworkID());
            }
            if (device.isForcedLoading()) {
                tag.putBoolean(FORCED_LOADING, true);
            }
            return tag;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof TileFluxDevice device)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var player = context.player();
            switch (context.purpose()) {
                case MOVE_TRANSFER -> {
                    var restored = capture(new IntegrationContext.Capture(context.level(), context.pos(), context.state()));
                    if (!sameSettings(restored, payload) || !sameNetwork(restored, payload)) {
                        return ApplyResult.REJECTED_UNCHANGED;
                    }
                    boolean forcedLoading = payload.getBoolean(FORCED_LOADING);
                    context.afterTick(() -> afterMove(device, forcedLoading));
                    return ApplyResult.APPLIED;
                }
                case RECONFIGURE -> {
                    if (!device.canPlayerAccess(player)) {
                        return ApplyResult.REJECTED_UNCHANGED;
                    }
                    device.readCustomTag(settings(payload), NBT_TILE_SETTINGS);
                    return ApplyResult.APPLIED;
                }
                default -> {
                    device.setOwnerUUID(player.getUUID());
                    device.readCustomTag(settings(payload), NBT_TILE_SETTINGS);
                    if (!device.getDeviceType().isController() && payload.contains(NETWORK_ID)) {
                        connect(device, FluxNetworkData.getNetwork(payload.getInt(NETWORK_ID)), player);
                    }
                    clearSideConnections(context.level(), context.pos());
                    return ApplyResult.APPLIED;
                }
            }
        }

        @Override
        public CompoundTag portable(IntegrationContext.Placement context, CompoundTag payload) {
            payload.remove(NETWORK_ID);
            payload.remove(FORCED_LOADING);
            return payload;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return sameNetwork(existing, desired);
        }

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            var item = context.target().getBlock().asItem();
            if (item == Items.AIR) {
                return PlacementRequirements.defaults();
            }
            return PlacementRequirements.replaceWith(ItemRequirement.exact(new ItemStack(item))
                    .withVariants(stack -> !storesEnergy(stack)));
        }

        @Override
        public boolean dropCarriesContents(IntegrationContext.Removal context, ItemStack drop) {
        return storesEnergy(drop);
        }
    }

    public static boolean storesEnergy(ItemStack stack) {
        Long energy = stack.get(FluxDataComponents.STORED_ENERGY);
        return energy != null && energy > 0;
    }

    private static void connect(TileFluxDevice device, FluxNetwork network, ServerPlayer player) {
        if (!network.isValid()) {
            player.displayClientMessage(Component.translatable("matter_manipulator.message.flux_network_gone"), true);
            return;
        }
        if (!network.canPlayerAccess(player)) {
            player.displayClientMessage(Component.translatable("matter_manipulator.message.flux_network_denied",
                    network.getNetworkName()), true);
            return;
        }
        device.connect(network);
    }

    private static void clearSideConnections(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FluxConnectorBlock)) {
            return;
        }
        var cleared = state;
        for (var side : FluxConnectorBlock.SIDES_CONNECTED) {
            cleared = cleared.setValue(side, false);
        }
        if (cleared != state) {
            level.setBlock(pos, cleared, Block.UPDATE_ALL);
        }
    }

    private static void afterMove(TileFluxDevice device, boolean forcedLoading) {
        if (device.isRemoved()) {
            return;
        }
        if (forcedLoading) {
            var tag = new CompoundTag();
            tag.putBoolean(FORCED_LOADING, true);
            device.readCustomTag(tag, NBT_TILE_SETTINGS);
        }
        if (!device.getNetwork().isValid()) {
            device.connect(FluxNetworkData.getNetwork(device.getNetworkID()));
        }
    }

    private static CompoundTag settings(CompoundTag payload) {
        var tag = new CompoundTag();
        for (var key : SETTINGS) {
            var value = payload.get(key);
            if (value != null) {
                tag.put(key, value.copy());
            }
        }
        return tag;
    }

    private static boolean sameSettings(CompoundTag a, CompoundTag b) {
        for (var key : SETTINGS) {
            if (!Objects.equals(a.get(key), b.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNetwork(CompoundTag a, CompoundTag b) {
        return Objects.equals(a.get(NETWORK_ID), b.get(NETWORK_ID));
    }
}
