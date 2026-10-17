package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class PreviewCulling {
    private static final Direction[] DIRECTIONS = Direction.values();

    private PreviewCulling() {}

    static Set<Direction> hiddenSides(BlockState state, BlockPos pos, BlockGetter level,
            Predicate<BlockPos> opaqueUnitCubeAt) {
        var hidden = EnumSet.noneOf(Direction.class);
        for (var direction : DIRECTIONS) {
            var neighbour = pos.relative(direction);
            if (opaqueUnitCubeAt.test(neighbour)
                    && !Block.shouldRenderFace(level, pos, state, level.getBlockState(neighbour), direction)) {
                hidden.add(direction);
            }
        }
        return hidden;
    }

    static boolean opaqueUnitCube(Minecraft minecraft, BlockState state) {
        if (state.hasBlockEntity() || !state.canOcclude() || !state.isSolidRender()) {
            return false;
        }
        var parts = new ArrayList<BlockStateModelPart>();
        minecraft.getModelManager().getBlockStateModelSet().get(state)
                .collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, RandomSource.create(42), parts);
        if (parts.size() != 1 || !(parts.get(0) instanceof SimpleModelWrapper part) || !part.getQuads(null).isEmpty()) {
            return false;
        }
        for (var direction : DIRECTIONS) {
            var quads = part.getQuads(direction);
            if (quads.size() != 1 || quads.get(0).materialInfo().layer() != ChunkSectionLayer.SOLID
                    || !unitFace(quads.get(0), direction)
                    || !opaqueTexture(quads.get(0).materialInfo().sprite().contents().getOriginalImage())) {
                return false;
            }
        }
        return true;
    }

    static boolean opaqueTexture(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixel(x, y) >>> 24) != 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean unitFace(BakedQuad quad, Direction direction) {
        int corners = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            if ((quad.bakedColors().color(vertex) >>> 24) != 255) {
                return false;
            }
            var position = quad.position(vertex);
            int corner = 0;
            for (int axis = 0; axis < 3; axis++) {
                float value = position.get(axis);
                if (value != 0 && value != 1) {
                    return false;
                }
                if (axis == direction.getAxis().ordinal()
                        && value != (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0)) {
                    return false;
                }
                corner |= (int) value << axis;
            }
            corners |= 1 << corner;
        }
        return Integer.bitCount(corners) == 4;
    }

    static final class VisibleFaces extends DelegateBlockStateModel {
        private final Set<Direction> hidden;

        VisibleFaces(BlockStateModel model, Set<Direction> hidden) {
            super(model);
            this.hidden = hidden;
        }

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            int first = parts.size();
            delegate.collectParts(random, parts);
            wrap(parts, first);
        }

        @Override
        public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                List<BlockStateModelPart> parts) {
            int first = parts.size();
            delegate.collectParts(level, pos, state, random, parts);
            wrap(parts, first);
        }

        private void wrap(List<BlockStateModelPart> parts, int first) {
            for (int i = first; i < parts.size(); i++) {
                parts.set(i, new VisiblePart(parts.get(i), hidden));
            }
        }
    }

    private record VisiblePart(BlockStateModelPart delegate, Set<Direction> hidden) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            return hidden.contains(direction) ? List.of() : delegate.getQuads(direction);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return delegate.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return delegate.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return delegate.materialFlags();
        }
    }
}
