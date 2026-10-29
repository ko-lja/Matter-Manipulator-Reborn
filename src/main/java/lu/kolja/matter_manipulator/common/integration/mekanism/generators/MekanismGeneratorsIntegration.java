package lu.kolja.matter_manipulator.common.integration.mekanism.generators;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import mekanism.api.SerializationConstants;
import mekanism.generators.common.registries.GeneratorsBlocks;
import mekanism.generators.common.registries.GeneratorsItems;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorLogicAdapter;
import mekanism.generators.common.tile.fission.TileEntityFissionReactorLogicAdapter.FissionReactorLogic;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorLogicAdapter;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorLogicAdapter.FusionReactorLogic;
import mekanism.generators.common.tile.fusion.TileEntityFusionReactorPort;
import mekanism.generators.common.tile.turbine.TileEntityTurbineRotor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class MekanismGeneratorsIntegration {

    public static final ResourceLocation REACTOR_ID = ResourceLocation.fromNamespaceAndPath("mekanismgenerators", "reactor");
    public static final ResourceLocation ROTOR_ID = ResourceLocation.fromNamespaceAndPath("mekanismgenerators", "turbine_rotor");

    static final String OUTPUT = "output";

    private MekanismGeneratorsIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(REACTOR_ID, new ReactorIntegration(),
                GeneratorsBlocks.FISSION_REACTOR_LOGIC_ADAPTER.get(),
                GeneratorsBlocks.FUSION_REACTOR_LOGIC_ADAPTER.get(),
                GeneratorsBlocks.FUSION_REACTOR_PORT.get());
        registrar.block(ROTOR_ID, new RotorIntegration(), GeneratorsBlocks.TURBINE_ROTOR.get());
    }

    private static final class ReactorIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            var blockEntity = context.blockEntity();
            if (blockEntity instanceof TileEntityFissionReactorLogicAdapter adapter) {
                tag.putInt(SerializationConstants.LOGIC_TYPE, adapter.logicType.ordinal());
            } else if (blockEntity instanceof TileEntityFusionReactorLogicAdapter adapter) {
                tag.putInt(SerializationConstants.LOGIC_TYPE, adapter.logicType.ordinal());
                tag.putBoolean(SerializationConstants.ACTIVE_COOLED, adapter.isActiveCooled());
            } else if (blockEntity instanceof TileEntityFusionReactorPort port && port.getActive()) {
                tag.putBoolean(OUTPUT, true);
            }
            return tag;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            var blockEntity = context.blockEntity();
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return blockEntity == null ? ApplyResult.REJECTED_UNCHANGED : ApplyResult.APPLIED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            int logic = payload.getInt(SerializationConstants.LOGIC_TYPE);
            if (blockEntity instanceof TileEntityFissionReactorLogicAdapter adapter) {
                if (logic < 0 || logic >= FissionReactorLogic.values().length) {
                    return ApplyResult.REJECTED_UNCHANGED;
                }
                adapter.setLogicTypeFromPacket(FissionReactorLogic.values()[logic]);
            } else if (blockEntity instanceof TileEntityFusionReactorLogicAdapter adapter) {
                if (logic < 0 || logic >= FusionReactorLogic.values().length) {
                    return ApplyResult.REJECTED_UNCHANGED;
                }
                adapter.setLogicTypeFromPacket(FusionReactorLogic.values()[logic]);
                if (adapter.isActiveCooled() != payload.getBoolean(SerializationConstants.ACTIVE_COOLED)) {
                    adapter.nextMode();
                }
            } else if (blockEntity instanceof TileEntityFusionReactorPort port) {
                port.setActive(payload.getBoolean(OUTPUT));
            } else {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            return ApplyResult.APPLIED;
        }
    }

    private static final class RotorIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            var tag = new CompoundTag();
            if (context.blockEntity() instanceof TileEntityTurbineRotor rotor && rotor.getHousedBlades() > 0) {
                tag.putInt(SerializationConstants.BLADES, rotor.getHousedBlades());
            }
            return tag;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            int blades = Math.min(payload.getInt(SerializationConstants.BLADES), 2);
            if (blades <= 0) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var output = payload.copy();
            output.remove(SerializationConstants.BLADES);
            var plan = PlacementPreparationPlan.builder(output);
            for (int i = 0; i < blades; i++) {
                plan.component(ItemRequirement.exact(new ItemStack(GeneratorsItems.TURBINE_BLADE.get())), (prepared, acquired) -> {
                    if (!acquired.isEmpty()) {
                        prepared.putInt(SerializationConstants.BLADES, prepared.getInt(SerializationConstants.BLADES) + 1);
                    }
                });
            }
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof TileEntityTurbineRotor rotor)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return ApplyResult.APPLIED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            int blades = payload.getInt(SerializationConstants.BLADES);
            int refunded = 0;
            for (int i = 0; i < blades; i++) {
                if (!rotor.addBlade(false)) {
                    refunded++;
                }
            }
            if (refunded == 0) {
                return ApplyResult.APPLIED;
            }
            context.refund(new ItemStack(GeneratorsItems.TURBINE_BLADE.get(), refunded));
            return ApplyResult.PARTIAL_RESOURCES_SETTLED;
        }
    }
}
