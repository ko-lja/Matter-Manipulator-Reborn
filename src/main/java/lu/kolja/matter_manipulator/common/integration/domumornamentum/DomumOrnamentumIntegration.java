package lu.kolja.matter_manipulator.common.integration.domumornamentum;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.build.BlockCost;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.decorative.ShingleSlabBlock;
import com.ldtteam.domumornamentum.block.types.ShingleSlabShapeType;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.entity.block.DynamicTimberFrameBlockEntity;
import com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DomumOrnamentumIntegration {

    public static final ResourceLocation MATERIALS_ID = ResourceLocation.fromNamespaceAndPath("domum_ornamentum", "materials");

    private static final RegistryAccess BUILT_IN = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private DomumOrnamentumIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(MATERIALS_ID, new MaterialsIntegration(), DomumOrnamentumIntegration::textured);
    }

    public static boolean textured(BlockState state) {
        return state.getBlock() instanceof IMateriallyTexturedBlock;
    }

    public static MaterialTextureData materials(CompoundTag payload) {
        return payload.isEmpty() ? MaterialTextureData.EMPTY
                : MaterialTextureData.CODEC.parse(NbtOps.INSTANCE, payload).result().orElse(MaterialTextureData.EMPTY);
    }

    private static CompoundTag payloadOf(MaterialTextureData materials) {
        return materials.isEmpty() ? new CompoundTag()
                : (CompoundTag) MaterialTextureData.CODEC.encodeStart(NbtOps.INSTANCE, materials).getOrThrow();
    }

    @Nullable
    public static BlockEntity detached(BlockState state, CompoundTag payload) {
        if (!(state.getBlock() instanceof EntityBlock block)
                || !(block.newBlockEntity(BlockPos.ZERO, state) instanceof IMateriallyTexturedBlockEntity textured)) {
            return null;
        }
        textured.updateTextureDataWith(materials(payload));
        if (textured instanceof DynamicTimberFrameBlockEntity frame) {
            frame.refreshTextureCache();
        }
        return (BlockEntity) textured;
    }

    private static ItemStack itemOf(BlockState state, CompoundTag payload) {
        var blockEntity = detached(state, payload);
        if (blockEntity == null) {
            return ItemStack.EMPTY;
        }
        return state.getBlock().getCloneItemStack(state, null, new DetachedBlock(state, blockEntity), BlockPos.ZERO, null);
    }

    @Nullable
    private static BlockState transformShingleSlab(BlockState state, DirectionTransform transform, @Nullable BlockState transformed) {
        if (transformed == null || !(state.getBlock() instanceof ShingleSlabBlock)) {
            return transformed;
        }
        var facing = state.getValue(ShingleSlabBlock.FACING);
        var shape = state.getValue(ShingleSlabBlock.SHAPE);
        if (shape == ShingleSlabShapeType.CURVED) {
            var first = transform.transform(facing.getClockWise());
            var second = transform.transform(facing.getOpposite());
            for (var candidate : Direction.Plane.HORIZONTAL) {
                var connections = Set.of(candidate.getClockWise(), candidate.getOpposite());
                if (connections.contains(first) && connections.contains(second)) {
                    return transformed.setValue(ShingleSlabBlock.FACING, candidate);
                }
            }
            return transformed;
        }
        if (shape == ShingleSlabShapeType.TWO_WAY) {
            var turned = transformed.getValue(ShingleSlabBlock.FACING);
            return turned == Direction.SOUTH || turned == Direction.WEST
                    ? transformed.setValue(ShingleSlabBlock.FACING, turned.getOpposite()) : transformed;
        }
        return transformed;
    }

    private static void reconnect(ServerLevel level, BlockPos pos, DynamicTimberFrameBlockEntity frame) {
        frame.resetOffsets();
        var state = level.getBlockState(pos);
        state.updateShape(Direction.UP, level.getBlockState(pos.above()), level, pos, pos.above());
        frame.refreshTextureCache();
    }

    private static final class MaterialsIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return context.blockEntity() instanceof IMateriallyTexturedBlockEntity textured
                    ? payloadOf(textured.getTextureData()) : new CompoundTag();
        }

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            if (payload.isEmpty() || !(BlockCost.of(context.target()) instanceof BlockCost.Items(
                List<ItemRequirement> itemRequirements
            ))) {
                return PlacementRequirements.defaults();
            }
            var item = itemOf(context.target(), payload);
            if (item.isEmpty()) {
                return PlacementRequirements.defaults();
            }
            var requirements = new ArrayList<ItemRequirement>();
            for (var requirement : itemRequirements) {
                var exact = requirement.exact();
                requirements.add(exact.is(item.getItem())
                        ? ItemRequirement.exact(item.copyWithCount(exact.getCount())) : requirement);
            }
            return new PlacementRequirements(false, requirements, null);
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof IMateriallyTexturedBlockEntity textured)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return payloadOf(textured.getTextureData()).equals(payload)
                        ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            textured.updateTextureDataWith(materials(payload));
            if (textured instanceof DynamicTimberFrameBlockEntity frame) {
                reconnect(context.level(), context.pos(), frame);
            }
            var blockEntity = (BlockEntity) textured;
            blockEntity.setChanged();
            var state = blockEntity.getBlockState();
            context.level().sendBlockUpdated(context.pos(), state, state, Block.UPDATE_ALL);
            return ApplyResult.APPLIED;
        }

        @Override
        @Nullable
        public BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
            return transformShingleSlab(context.state(), context.transform(), transformed);
        }

        @Override
        public void afterTransfer(IntegrationContext.Transferred context) {
            if (context.committed() && context.blockEntity() instanceof DynamicTimberFrameBlockEntity frame) {
                reconnect(context.level(), context.pos(), frame);
            }
        }
    }

    private record DetachedBlock(BlockState state, BlockEntity blockEntity) implements LevelReader {

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? blockEntity : null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public RegistryAccess registryAccess() {
            return BUILT_IN;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public int getHeight() {
            return 1;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        @Nullable
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasChunk(int x, int z) {
            return false;
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getSkyDarken() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BiomeManager getBiomeManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getSeaLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DimensionType dimensionType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorldBorder getWorldBorder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB area) {
            return List.of();
        }
    }
}
