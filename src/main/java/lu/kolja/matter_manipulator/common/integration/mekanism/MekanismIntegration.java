package lu.kolja.matter_manipulator.common.integration.mekanism;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;
import mekanism.api.SerializationConstants;
import mekanism.api.Upgrade;
import mekanism.api.security.SecurityMode;
import mekanism.common.block.BlockBounding;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeHasBounding;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.block.transmitter.BlockTransmitter;
import mekanism.common.tile.TileEntityBoundingBlock;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.multiblock.TileEntityInductionPort;
import mekanism.common.tile.multiblock.TileEntitySPSPort;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class MekanismIntegration {

    public static final ResourceLocation MACHINE_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "machine");
    public static final ResourceLocation BOUNDING_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "bounding");
    public static final ResourceLocation TRANSMITTER_ID = ResourceLocation.fromNamespaceAndPath("mekanism", "transmitter");

    static final String CONFIG = "config";
    static final String UPGRADES = "upgrades";
    static final String OUTPUT = "output";
    static final String SECURITY = "security";
    public static final String CONNECTED = "connected";

    private MekanismIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(MACHINE_ID, new MachineIntegration(), state -> state.getBlock() instanceof BlockTile<?, ?>);
        registrar.block(BOUNDING_ID, new BoundingIntegration(), state -> state.getBlock() instanceof BlockBounding);
        var transmitter = new TransmitterIntegration();
        registrar.block(TRANSMITTER_ID, transmitter, MekanismIntegration::isTransmitter);
        registrar.cable(TRANSMITTER_ID, transmitter, MekanismIntegration::isTransmitter);
    }

    public static boolean isTransmitter(BlockState state) {
        return state.getBlock() instanceof BlockTransmitter;
    }

    static Direction facing(BlockState state) {
        var facing = Attribute.getFacing(state);
        return facing == null ? Direction.NORTH : facing;
    }

    static Direction transformedFacing(BlockState state, IntegrationContext.Transform context) {
        var facing = Attribute.getFacing(state);
        return facing == null ? Direction.NORTH : context.transform().transform(facing);
    }

    private static Set<BlockPos> boundingPositions(Level level, BlockPos pos, BlockState state) {
        var bounding = Attribute.get(state, AttributeHasBounding.class);
        if (bounding == null) {
            return Set.of();
        }
        var positions = new HashSet<BlockPos>();
        bounding.handle(level, pos, state, positions, (ignored, position, collected) -> {
            collected.add(position.immutable());
            return true;
        });
        return positions;
    }

    private static final class MachineIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (!(context.blockEntity() instanceof TileEntityMekanism tile)) {
                return tag;
            }
            var config = tile.getConfigurationData(context.level().registryAccess(), FakePlayerFactory.getMinecraft(context.level()));
            if (!config.isEmpty()) {
                tag.put(CONFIG, config);
            }
            if (tile.supportsUpgrades()) {
                var upgrades = new HashMap<Upgrade, Integer>();
                for (var upgrade : tile.getComponent().getInstalledTypes()) {
                    upgrades.put(upgrade, tile.getComponent().getUpgrades(upgrade));
                }
                if (!upgrades.isEmpty()) {
                    var upgradeTag = new CompoundTag();
                    Upgrade.saveMap(upgrades, upgradeTag);
                    tag.put(UPGRADES, upgradeTag);
                }
            }
            if (isPort(tile) && tile.getActive()) {
                tag.putBoolean(OUTPUT, true);
            }
            if (tile.hasSecurity() && tile.getSecurity().getMode() != SecurityMode.PUBLIC) {
                tag.putInt(SECURITY, tile.getSecurity().getMode().ordinal());
            }
            return tag;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            var upgrades = Upgrade.buildMap(payload.getCompound(UPGRADES));
            if (upgrades.isEmpty()) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var output = payload.copy();
            output.remove(UPGRADES);
            var plan = PlacementPreparationPlan.builder(output);
            upgrades.forEach((upgrade, count) -> {
                for (int i = 0; i < count; i++) {
                    plan.component(ItemRequirement.exact(UpgradeUtils.getStack(upgrade)), (prepared, acquired) -> {
                        if (acquired.isEmpty()) {
                            return;
                        }
                        var funded = new HashMap<>(Upgrade.buildMap(prepared.getCompound(UPGRADES)));
                        funded.merge(upgrade, 1, Integer::sum);
                        var upgradeTag = new CompoundTag();
                        Upgrade.saveMap(funded, upgradeTag);
                        prepared.put(UPGRADES, upgradeTag);
                    });
                }
            });
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof TileEntityMekanism tile)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return ApplyResult.APPLIED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var level = context.level();
            var bounding = boundingPositions(level, context.pos(), context.state());
            for (var position : bounding) {
                if (!level.getBlockState(position).canBeReplaced()) {
                    context.reject(MMIntegrationText.SKIP_MEKANISM_BOUNDING_OBSTRUCTED.text());
                    return ApplyResult.REJECTED_UNCHANGED;
                }
            }
            var player = context.player();
            if (tile.hasSecurity()) {
                tile.setOwnerUUID(player.getUUID());
                if (payload.contains(SECURITY)) {
                    tile.getSecurity().setMode(SecurityMode.BY_ID.apply(payload.getInt(SECURITY)));
                }
            }
            if (payload.contains(CONFIG, CompoundTag.TAG_COMPOUND)) {
                tile.setConfigurationData(level.registryAccess(), player, payload.getCompound(CONFIG));
                tile.configurationDataSet();
            }
            boolean refunded = false;
            if (payload.contains(UPGRADES, CompoundTag.TAG_COMPOUND)) {
                for (var entry : Upgrade.buildMap(payload.getCompound(UPGRADES)).entrySet()) {
                    var upgrade = entry.getKey();
                    int count = entry.getValue();
                    int installed = tile.supportsUpgrades() && tile.getComponent().supports(upgrade)
                            ? tile.getComponent().addUpgrades(upgrade, count)
                            : 0;
                    if (installed < count) {
                        context.refund(UpgradeUtils.getStack(upgrade, count - installed));
                        refunded = true;
                    }
                }
            }
            if (payload.getBoolean(OUTPUT) && isPort(tile)) {
                tile.setActive(true);
            }
            if (tile.supportsRedstone()) {
                tile.updatePower();
            }
            if (!bounding.isEmpty()) {
                Attribute.get(context.state(), AttributeHasBounding.class).placeBoundingBlocks(level, context.pos(), context.state());
            }
            return refunded ? ApplyResult.PARTIAL_RESOURCES_SETTLED : ApplyResult.APPLIED;
        }

        @Override
        public Set<BlockPos> additionalChangedPositions(IntegrationContext.SideEffects context, CompoundTag payload) {
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return Set.of();
            }
            return boundingPositions(context.level(), context.pos(), context.state());
        }

        @Override
        public Set<BlockPos> moveGroupPeers(IntegrationContext.Capture context) {
            return boundingPositions(context.level(), context.pos(), context.state());
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            if (!payload.contains(CONFIG, CompoundTag.TAG_COMPOUND)) {
                return payload;
            }
            var state = context.state();
            payload.put(CONFIG, MekanismSides.transformMachine(payload.getCompound(CONFIG), facing(state),
                    transformedFacing(state, context), context.transform()));
            return payload;
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            var state = context.state();
            return MekanismSides.transformMachine(blockEntity, facing(state), transformedFacing(state, context), context.transform());
        }

        @Override
        public CompoundTag portable(IntegrationContext.Placement context, CompoundTag payload) {
            payload.getCompound(CONFIG).remove(SerializationConstants.COMPONENT_FREQUENCY);
            return payload;
        }

        private static boolean isPort(TileEntityMekanism tile) {
            return tile instanceof TileEntityInductionPort || tile instanceof TileEntitySPSPort;
        }
    }

    private static final class BoundingIntegration implements BlockIntegration {

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            return PlacementRequirements.unsupported(MMIntegrationText.BLOCK_COST_MEKANISM_BOUNDING.text());
        }

        @Override
        public Component removalRefusal(BlockState state) {
            return MMIntegrationText.REFUSAL_MEKANISM_BOUNDING.text();
        }

        @Override
        public Set<BlockPos> moveGroupPeers(IntegrationContext.Capture context) {
            if (context.level().getBlockEntity(context.pos()) instanceof TileEntityBoundingBlock bounding && bounding.canRedirectFrom(context.pos())) {
                return Set.of(bounding.getMainPos());
            }
            return Set.of();
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return false;
        }

        @Override
        public boolean relocatesBlockEntity(BlockState state) {
            return true;
        }

        @Override
        public CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
            var main = NbtUtils.readBlockPos(blockEntity, SerializationConstants.MAIN).map(context::destinationOf).orElse(null);
            if (main != null) {
                blockEntity.put(SerializationConstants.MAIN, NbtUtils.writeBlockPos(main));
            }
            return blockEntity;
        }
    }
}
