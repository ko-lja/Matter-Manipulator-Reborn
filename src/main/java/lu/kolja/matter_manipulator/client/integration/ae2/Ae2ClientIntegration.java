package lu.kolja.matter_manipulator.client.integration.ae2;

import appeng.api.implementations.parts.ICablePart;
import appeng.api.parts.IPartItem;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.block.networking.CableBusBlock;
import appeng.block.networking.CableBusRenderState;
import appeng.block.networking.CableCoreType;
import appeng.blockentity.networking.CableBusBlockEntity;
import lu.kolja.matter_manipulator.client.preview.PreviewDebugStats;
import lu.kolja.matter_manipulator.api.client.PreviewModels;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.common.integration.ae2.Ae2Integration;
import lu.kolja.matter_manipulator.common.integration.ae2.Ae2NbtTransform;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.model.data.ModelData;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class Ae2ClientIntegration {

    private static final String CABLE_ITEM = "cable_item";
    private static final String CONNECTIONS = "connections";
    private static final Map<Identifier, CableAppearance> CABLE_APPEARANCES = new HashMap<>();
    private static final Map<SimpleCableKey, ModelData> SIMPLE_CABLE_DATA = new HashMap<>();

    private Ae2ClientIntegration() {}

    public static void register(ClientIntegrationRegistrar registrar) {
        registrar.preview(
                Ae2Integration.CABLE_ID,
                new CablePreviewIntegration(),
                state -> state.getBlock() instanceof CableBusBlock
        );
    }

    private static final class CablePreviewIntegration implements ClientPreviewIntegration {

        @Override
        public ModelData create(ClientIntegrationContext.CapturedPreview context) {
            if (context.captured() == null || !(context.state().getBlock() instanceof EntityBlock entityBlock)) {
                return ModelData.EMPTY;
            }
            var cableBusTag = cableBusTag(context.captured());
            if (cableBusTag.isEmpty()) {
                return ModelData.EMPTY;
            }
            var appearance = cableAppearance(context.captured());
            if (appearance != null) {
                return simpleRenderData(appearance, context.lineDirections());
            }
            if (context.detail() != PreviewDetail.FULL) {
                return ModelData.EMPTY;
            }
            PreviewDebugStats.fakeBlockEntityCreated();
            var blockEntity = entityBlock.newBlockEntity(context.pos(), context.state());
            if (!(blockEntity instanceof CableBusBlockEntity cableBus)) {
                return ModelData.EMPTY;
            }
            var registries = context.level().registryAccess();
            cableBus.getCableBus().readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING, registries, cableBusTag));
            cableBus.setLevel(context.level());
            var cable = cableBus.getCableBus().getPart(null);
            if (cable != null && !context.lineDirections().isEmpty()) {
                cable.readVisualStateFromNBT(TagValueInput.create(ProblemReporter.DISCARDING, registries,
                        visualConnections(context.lineDirections())));
            }
            var renderState = cableBus.getCableBus().getRenderState();
            renderState.setPos(context.pos());
            var baseData = renderData(renderState);
            return PreviewModels.blockModelData(
                    new PreviewModels.SingleBlockPreviewLevel(
                            (BlockAndTintGetter) context.level(),
                            context.pos(),
                            context.state(),
                            cableBus
                    ),
                    context.pos(),
                    context.state(),
                    baseData
            );
        }

        @Override
        public ModelData fromWorld(ClientIntegrationContext.WorldPreview context) {
            if (!(context.sourceState().getBlock() instanceof CableBusBlock)
                    || !(context.previewState().getBlock() instanceof EntityBlock entityBlock)
                    || !(context.level().getBlockEntity(context.sourcePos()) instanceof CableBusBlockEntity sourceCableBus)) {
                return ModelData.EMPTY;
            }
            if (context.detail() != PreviewDetail.FULL) {
                var renderState = transformed(sourceCableBus.getCableBus().getRenderState(), context.transform());
                renderState.setPos(context.previewPos());
                return renderData(renderState);
            }
            PreviewDebugStats.fakeBlockEntityCreated();
            var blockEntity = entityBlock.newBlockEntity(context.previewPos(), context.previewState());
            if (!(blockEntity instanceof CableBusBlockEntity previewCableBus)) {
                return ModelData.EMPTY;
            }
            var registries = context.level().registryAccess();
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
            sourceCableBus.getCableBus().writeToNBT(output);
            var data = Ae2NbtTransform.transformCableBusDirections(output.buildResult(), context.transform());
            previewCableBus.getCableBus().readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING, registries, data));
            previewCableBus.setLevel(context.level());

            var sourceCable = sourceCableBus.getCableBus().getPart(null);
            var previewCable = previewCableBus.getCableBus().getPart(null);
            if (sourceCable instanceof ICablePart cable && previewCable != null) {
                var directions = new HashSet<Direction>();
                for (var direction : Direction.values()) {
                    if (cable.isConnected(direction)) {
                        directions.add(context.transform().transform(direction));
                    }
                }
                if (!directions.isEmpty()) {
                    previewCable.readVisualStateFromNBT(TagValueInput.create(ProblemReporter.DISCARDING, registries,
                            visualConnections(directions)));
                }
            }

            var renderState = previewCableBus.getCableBus().getRenderState();
            renderState.setPos(context.previewPos());
            var baseData = renderData(renderState);
            return PreviewModels.blockModelData(
                    new PreviewModels.SingleBlockPreviewLevel(
                            (BlockAndTintGetter) context.level(),
                            context.previewPos(),
                            context.previewState(),
                            previewCableBus
                    ),
                    context.previewPos(),
                    context.previewState(),
                    baseData
            );
        }
    }

    private static ModelData renderData(CableBusRenderState state) {
        PreviewDebugStats.modelDataBuilt();
        PreviewDebugStats.ae2ModelDataBuilt();
        var key = new ArrayList<Object>();
        key.add(state.getCableType());
        key.add(state.getCoreType());
        key.add(state.getCableColor());
        key.add(state.getConnectionTypes());
        key.add(state.getCableBusAdjacent());
        key.add(state.getChannelsOnSide());
        key.add(new EnumMap<>(state.getAttachments()));
        key.add(state.getAttachmentConnections());
        var facades = new HashMap<Direction, Object>();
        for (var side : Direction.values()) {
            var facade = state.getFacade(side);
            if (facade != null) {
                facades.put(side, facade);
            }
        }
        key.add(facades);
        key.add(state.getPos());
        key.add(state.getBoundingBoxes());
        return PreviewModels.withPropertyKey(ModelData.builder().with(CableBusRenderState.PROPERTY, state).build(),
                CableBusRenderState.PROPERTY, key);
    }

    static CableBusRenderState transformed(CableBusRenderState source, DirectionTransform transform) {
        var state = new CableBusRenderState();
        state.setCableType(source.getCableType());
        state.setCoreType(source.getCoreType());
        state.setCableColor(source.getCableColor());
        source.getConnectionTypes().forEach((side, type) -> state.getConnectionTypes().put(transform.transform(side), type));
        source.getCableBusAdjacent().forEach(side -> state.getCableBusAdjacent().add(transform.transform(side)));
        source.getChannelsOnSide().forEach((side, channels) ->
                state.getChannelsOnSide().put(transform.transform(side), channels));
        source.getAttachments().forEach((side, model) -> state.getAttachments().put(transform.transform(side), model));
        source.getAttachmentConnections().forEach((side, connection) ->
                state.getAttachmentConnections().put(transform.transform(side), connection));
        for (var side : Direction.values()) {
            var facade = source.getFacade(side);
            if (facade != null) {
                state.setFacade(transform.transform(side), facade);
            }
        }
        for (var box : source.getBoundingBoxes()) {
            state.getBoundingBoxes().add(transformed(box, transform));
        }
        return state;
    }

    private static AABB transformed(AABB box, DirectionTransform transform) {
        var min = transformed(box.minX, box.minY, box.minZ, transform);
        var max = transformed(box.maxX, box.maxY, box.maxZ, transform);
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static double[] transformed(double x, double y, double z, DirectionTransform transform) {
        var source = new double[] {x, y, z};
        var result = new double[3];
        for (var axis : Direction.Axis.values()) {
            var target = transform.transform(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
            double value = source[axis.ordinal()];
            result[target.getAxis().ordinal()] =
                    target.getAxisDirection() == Direction.AxisDirection.POSITIVE ? value : 1.0D - value;
        }
        return result;
    }

    private static ModelData simpleRenderData(CableAppearance appearance, Set<Direction> directions) {
        var key = new SimpleCableKey(appearance.cableType(), appearance.color(), directionMask(directions));
        return SIMPLE_CABLE_DATA.computeIfAbsent(key, ignored -> {
            PreviewDebugStats.modelDataBuilt();
            PreviewDebugStats.ae2ModelDataBuilt();
            var state = simpleRenderState(
                    appearance.cableType(),
                    appearance.color(),
                    BlockPos.ZERO,
                    directions
            );
            return PreviewModels.withPropertyKey(
                    ModelData.builder().with(CableBusRenderState.PROPERTY, state).build(),
                    CableBusRenderState.PROPERTY,
                    key
            );
        });
    }

    private static int directionMask(Set<Direction> directions) {
        int mask = 0;
        for (var direction : directions) {
            mask |= 1 << direction.ordinal();
        }
        return mask;
    }

    static CableBusRenderState simpleRenderState(
            AECableType cableType,
            AEColor color,
            BlockPos pos,
            Set<Direction> directions
    ) {
        var renderState = new CableBusRenderState();
        renderState.setCableType(cableType);
        renderState.setCoreType(CableCoreType.fromCableType(cableType));
        renderState.setCableColor(color);
        renderState.setPos(pos);
        for (var direction : directions) {
            renderState.getConnectionTypes().put(direction, cableType);
            renderState.getCableBusAdjacent().add(direction);
            renderState.getChannelsOnSide().put(direction, 0);
        }
        return renderState;
    }

    private static CableAppearance cableAppearance(BlockSnapshot captured) {
        var payload = captured.payload(Ae2Integration.CABLE_ID);
        var cableId = payload.getString(CABLE_ITEM).map(Identifier::tryParse).orElse(null);
        return cableId == null ? null : CABLE_APPEARANCES.computeIfAbsent(cableId, Ae2ClientIntegration::loadAppearance);
    }

    private static CableAppearance loadAppearance(Identifier cableId) {
        var item = BuiltInRegistries.ITEM.getValue(cableId);
        if (!(item instanceof IPartItem<?> partItem)) {
            return null;
        }
        var part = partItem.createPart();
        if (!(part instanceof ICablePart cable)) {
            return null;
        }
        return new CableAppearance(cable.getCableConnectionType(), cable.getCableColor());
    }

    private static CompoundTag cableBusTag(BlockSnapshot captured) {
        var payload = captured.payload(Ae2Integration.CABLE_ID);
        if (!payload.contains(CABLE_ITEM)) {
            return new CompoundTag();
        }
        var cableBus = new CompoundTag();
        var cable = new CompoundTag();
        cable.putString("id", payload.getStringOr(CABLE_ITEM, ""));
        cableBus.put("cable", cable);
        return cableBus;
    }

    private static CompoundTag visualConnections(Set<Direction> directions) {
        var tag = new CompoundTag();
        var connections = new ListTag();
        for (var direction : directions) {
            connections.add(StringTag.valueOf(direction.getSerializedName()));
        }
        tag.put(CONNECTIONS, connections);
        return tag;
    }

    private record CableAppearance(AECableType cableType, AEColor color) {}
    private record SimpleCableKey(AECableType cableType, AEColor color, int connectionMask) {}

}
