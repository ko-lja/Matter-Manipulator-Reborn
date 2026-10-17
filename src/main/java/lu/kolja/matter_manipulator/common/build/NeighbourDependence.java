package lu.kolja.matter_manipulator.common.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class NeighbourDependence {

    private static final Map<BlockState, Boolean> DEPENDS = new ConcurrentHashMap<>();

    private NeighbourDependence() {}

    static boolean dependsOnNeighbours(BlockState state) {
        var cached = DEPENDS.get(state);
        if (cached != null) {
            return cached;
        }
        boolean computed = !independent(state);
        DEPENDS.putIfAbsent(state, computed);
        return computed;
    }

    private static boolean independent(BlockState state) {
        if (state.getBlock() instanceof FallingBlock
                || !state.getFluidState().isEmpty()
                || !"minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) {
            return false;
        }
        var alone = new AloneLevel(state);
        try {
            if (!state.canSurvive(alone, AloneLevel.POS)) {
                return false;
            }
            for (var direction : Direction.values()) {
                var neighbour = AloneLevel.POS.relative(direction);
                if (state.updateShape(direction, Blocks.AIR.defaultBlockState(), alone, AloneLevel.POS, neighbour) != state) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError unknown) {
            return false;
        }
    }

    private record AloneLevel(BlockState state) implements LevelAccessor {

        static final BlockPos POS = new BlockPos(0, 64, 0);

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return POS.equals(pos) ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
            return predicate.test(getBlockState(pos));
        }

        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
            return predicate.test(getFluidState(pos));
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {}

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay) {}

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {}

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {}

        @Override
        public long nextSubTickCount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LevelData getLevelData() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        @Nullable
        public MinecraftServer getServer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChunkSource getChunkSource() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomSource getRandom() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                              float pitch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addParticle(ParticleOptions particle, double x, double y, double z, double xSpeed, double ySpeed,
                                double zSpeed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB area, Predicate<? super T> predicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<? extends Player> players() {
            throw new UnsupportedOperationException();
        }

        @Override
        @Nullable
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
            throw new UnsupportedOperationException();
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
        public RegistryAccess registryAccess() {
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
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean isMoving) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
            throw new UnsupportedOperationException();
        }
    }
}
