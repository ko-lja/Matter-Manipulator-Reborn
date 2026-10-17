package lu.kolja.matter_manipulator.client.preview;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

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
            if (opaqueUnitCubeAt.test(neighbour) && !Block.shouldRenderFace(state, level, pos, direction, neighbour)) {
                hidden.add(direction);
            }
        }
        return hidden;
    }

    static boolean opaqueUnitCube(Minecraft minecraft, BlockState state) {
        if (state.hasBlockEntity() || !state.canOcclude()
                || !state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        var model = minecraft.getBlockRenderer().getBlockModel(state);
        if (!(model instanceof SimpleBakedModel)
                || !model.getRenderTypes(state, RandomSource.create(42), ModelData.EMPTY)
                        .asList().equals(List.of(RenderType.solid()))) {
            return false;
        }
        if (!model.getQuads(state, null, RandomSource.create(42), ModelData.EMPTY, RenderType.solid()).isEmpty()) {
            return false;
        }
        for (var direction : DIRECTIONS) {
            var quads = model.getQuads(state, direction, RandomSource.create(42), ModelData.EMPTY, RenderType.solid());
            if (quads.size() != 1 || !unitFace(quads.get(0).getVertices(), direction)
                    || !opaqueTexture(quads.get(0).getSprite().contents().getOriginalImage())) {
                return false;
            }
        }
        return true;
    }

    static boolean opaqueTexture(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean unitFace(int[] vertices, Direction direction) {
        int corners = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            if ((vertices[vertex * 8 + 3] >>> 24) != 255) {
                return false;
            }
            int corner = 0;
            for (int axis = 0; axis < 3; axis++) {
                float value = Float.intBitsToFloat(vertices[vertex * 8 + axis]);
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

    static final class VisibleFaces extends BakedModelWrapper<BakedModel> {
        private final Set<Direction> hidden;

        VisibleFaces(BakedModel model, Set<Direction> hidden) {
            super(model);
            this.hidden = hidden;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                ModelData data, RenderType type) {
            return hidden.contains(side) ? List.of() : originalModel.getQuads(state, side, random, data, type);
        }
    }
}
