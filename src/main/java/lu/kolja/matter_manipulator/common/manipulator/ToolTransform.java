package lu.kolja.matter_manipulator.common.manipulator;

import lu.kolja.matter_manipulator.api.DirectionTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

public record ToolTransform(
        int rotationX,
        int rotationY,
        int rotationZ,
        boolean mirrorX,
        boolean mirrorY,
        boolean mirrorZ,
        int stackCount,
        StackAxis stackAxis
) implements DirectionTransform {

    public static final ToolTransform IDENTITY = new ToolTransform(0, 0, 0, false, false, false, 1, StackAxis.X);
    private static final ToolTransform MIRROR_X = IDENTITY.toggleMirrorX();
    private static final ToolTransform VERTICAL_FLIP = IDENTITY.toggleMirrorY();
    private static final int STAIR_CACHE_LIMIT = 1 << 16;
    private static final ConcurrentHashMap<StairKey, Optional<BlockState>> STAIRS = new ConcurrentHashMap<>();

    public ToolTransform {
        rotationX = Math.floorMod(rotationX, 4);
        rotationY = Math.floorMod(rotationY, 4);
        rotationZ = Math.floorMod(rotationZ, 4);
        stackCount = Math.max(1, Math.min(64, stackCount));
        if (stackAxis == null) {
            stackAxis = StackAxis.X;
        }
    }

    public boolean isIdentity() {
        return transform(Direction.EAST) == Direction.EAST
                && transform(Direction.UP) == Direction.UP
                && transform(Direction.SOUTH) == Direction.SOUTH;
    }

    public ToolTransform rotate(Direction.Axis axis) {
        return switch (axis) {
            case X -> new ToolTransform(rotationX + 1, rotationY, rotationZ, mirrorX, mirrorY, mirrorZ, stackCount, stackAxis);
            case Y -> new ToolTransform(rotationX, rotationY + 1, rotationZ, mirrorX, mirrorY, mirrorZ, stackCount, stackAxis);
            case Z -> new ToolTransform(rotationX, rotationY, rotationZ + 1, mirrorX, mirrorY, mirrorZ, stackCount, stackAxis);
        };
    }

    public ToolTransform toggleMirrorX() {
        return new ToolTransform(rotationX, rotationY, rotationZ, !mirrorX, mirrorY, mirrorZ, stackCount, stackAxis);
    }

    public ToolTransform toggleMirrorY() {
        return new ToolTransform(rotationX, rotationY, rotationZ, mirrorX, !mirrorY, mirrorZ, stackCount, stackAxis);
    }

    public ToolTransform toggleMirrorZ() {
        return new ToolTransform(rotationX, rotationY, rotationZ, mirrorX, mirrorY, !mirrorZ, stackCount, stackAxis);
    }

    public ToolTransform withStackCount(int count) {
        return new ToolTransform(rotationX, rotationY, rotationZ, mirrorX, mirrorY, mirrorZ, count, stackAxis);
    }

    public ToolTransform cycleStackAxis() {
        return new ToolTransform(rotationX, rotationY, rotationZ, mirrorX, mirrorY, mirrorZ, stackCount, stackAxis.next());
    }

    public BlockPos transformRelative(BlockPos relative, BlockPos sourceSize) {
        var bounds = transformedBounds(sourceSize);
        return applyVector(relative).subtract(bounds.min());
    }

    public BlockPos transformedSize(BlockPos sourceSize) {
        var bounds = transformedBounds(sourceSize);
        return bounds.max().subtract(bounds.min());
    }

    public BlockPos stackOffset(BlockPos transformedSize, int stackIndex) {
        if (stackIndex <= 0) {
            return BlockPos.ZERO;
        }
        int span = switch (stackAxis) {
            case X -> transformedSize.getX() + 1;
            case Y -> transformedSize.getY() + 1;
            case Z -> transformedSize.getZ() + 1;
        };
        return stackAxis.offset(span * stackIndex);
    }

    public Direction transform(Direction direction) {
        var vector = applyVector(directionVector(direction));
        return Direction.getNearest(vector.getX(), vector.getY(), vector.getZ());
    }

    public Direction.Axis transform(Direction.Axis axis) {
        return transform(positive(axis)).getAxis();
    }

    public Optional<BlockState> transform(BlockState state) {
        return transform(state, false);
    }

    public Optional<BlockState> transformApproximately(BlockState state) {
        return transform(state, true);
    }

    private Optional<BlockState> transform(BlockState state, boolean approximate) {
        if (isIdentity()) {
            return Optional.of(state);
        }
        var up = transform(Direction.UP);
        if (isStairs(state)) {
            return transformProperties(state, approximate);
        }
        if (up == Direction.UP) {
            return Optional.of(transformHorizontal(state));
        }
        if (up == Direction.DOWN) {
            return VERTICAL_FLIP.transformProperties(state, approximate).map(this::transformHorizontal);
        }
        return transformProperties(state, approximate);
    }

    private BlockState transformHorizontal(BlockState state) {
        var east = transform(Direction.EAST);
        var south = transform(Direction.SOUTH);
        for (var mirror : new Mirror[] { Mirror.NONE, Mirror.FRONT_BACK }) {
            for (var rotation : Rotation.values()) {
                if (rotation.rotate(mirror.mirror(Direction.EAST)) != east
                        || rotation.rotate(mirror.mirror(Direction.SOUTH)) != south) {
                    continue;
                }
                var mirrored = mirror == Mirror.NONE ? state : ownOrGeneric(state, state.mirror(mirror), MIRROR_X);
                return rotation == Rotation.NONE
                        ? mirrored
                        : ownOrGeneric(mirrored, mirrored.rotate(rotation), IDENTITY.withRotationY(rotation.ordinal()));
            }
        }
        throw new IllegalStateException("No horizontal rotation matches " + summary());
    }

    private static boolean isStairs(BlockState state) {
        return state.hasProperty(StairBlock.FACING) && state.hasProperty(StairBlock.HALF) && state.hasProperty(StairBlock.SHAPE);
    }

    private static BlockState ownOrGeneric(BlockState before, BlockState own, ToolTransform generic) {
        if (own != before) {
            return own;
        }
        return generic.transformProperties(before, false).orElse(before);
    }

    private ToolTransform withRotationY(int rotation) {
        return new ToolTransform(rotationX, rotation, rotationZ, mirrorX, mirrorY, mirrorZ, stackCount, stackAxis);
    }

    private Optional<BlockState> transformProperties(BlockState state, boolean approximate) {
        var up = transform(Direction.UP);
        boolean sideways = up.getAxis() != Direction.Axis.Y;
        if (sideways) {
            var variant = WallVariants.transform(this, state);
            if (variant != null) {
                return approximate ? Optional.of(variant) : Optional.empty();
            }
        }
        var result = state;
        var handled = new HashSet<Property<?>>();
        if (isStairs(state)) {
            var stairs = transformStairs(state, approximate);
            if (stairs.isEmpty()) {
                return stairs;
            }
            result = stairs.get();
            handled.addAll(List.of(StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE));
        } else if (sideways && state.hasProperty(ATTACH_FACE) && state.hasProperty(HORIZONTAL_FACING)) {
            result = transformAttached(state);
            handled.addAll(List.of(ATTACH_FACE, HORIZONTAL_FACING));
        }

        boolean sidesLost = false;
        var sidesSet = new HashSet<Property<?>>();
        for (var side : Direction.values()) {
            var source = sideProperty(state, side);
            if (source == null) {
                continue;
            }
            handled.add(source);
            var target = sideProperty(state, transform(side));
            if (target == null) {
                if (!sideways && side.getAxis() == Direction.Axis.Y) {
                    sidesSet.add(source);
                    continue;
                }
                if (!approximate) {
                    return Optional.empty();
                }
                sidesLost = true;
                continue;
            }
            var value = state.getValue(source);
            if (!target.getPossibleValues().contains(value)) {
                if (!approximate) {
                    return Optional.empty();
                }
                sidesLost = true;
                continue;
            }
            result = setValue(result, target, value);
            sidesSet.add(target);
        }
        if (sidesLost) {
            for (var side : Direction.values()) {
                var property = sideProperty(state, side);
                if (property != null && !sidesSet.contains(property)) {
                    result = setValue(result, property, state.getBlock().defaultBlockState().getValue(property));
                }
            }
        }

        boolean chestLost = false;
        for (var property : state.getProperties()) {
            if (handled.contains(property) || property.getPossibleValues().size() < 2) {
                continue;
            }
            var original = state.getValue(property);
            var value = transformValue(property, original, up);
            if (value != null && property.getPossibleValues().contains(value)) {
                result = setValue(result, property, value);
                continue;
            }
            if (!approximate || original instanceof DoubleBlockHalf || state.hasProperty(BlockStateProperties.BED_PART)) {
                return Optional.empty();
            }
            chestLost |= original instanceof ChestType;
            if (original instanceof RailShape rail && rail.isAscending()) {
                result = setValue(result, property, switch (rail) {
                    case ASCENDING_EAST, ASCENDING_WEST -> RailShape.EAST_WEST;
                    default -> RailShape.NORTH_SOUTH;
                });
            }
        }
        if (chestLost) {
            result = result.setValue(BlockStateProperties.CHEST_TYPE, chestType(state, result));
        }
        return Optional.of(result);
    }

    private ChestType chestType(BlockState state, BlockState result) {
        if (!state.hasProperty(HORIZONTAL_FACING)) {
            return ChestType.SINGLE;
        }
        var partner = transform(ChestBlock.getConnectedDirection(state));
        var facing = result.getValue(HORIZONTAL_FACING);
        if (partner == facing.getClockWise()) {
            return ChestType.LEFT;
        }
        return partner == facing.getCounterClockWise() ? ChestType.RIGHT : ChestType.SINGLE;
    }

    @Nullable
    private Comparable<?> transformValue(Property<?> property, Comparable<?> value, Direction up) {
        if (value instanceof Direction direction) {
            return transform(direction);
        }
        if (value instanceof Direction.Axis axis) {
            return transform(axis);
        }
        if (up == Direction.UP) {
            return value;
        }
        boolean flip = up == Direction.DOWN;
        if (value instanceof Half half) {
            return flip ? (half == Half.TOP ? Half.BOTTOM : Half.TOP) : null;
        }
        if (value instanceof SlabType slab) {
            if (slab == SlabType.DOUBLE) {
                return slab;
            }
            return flip ? (slab == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP) : null;
        }
        if (value instanceof DoubleBlockHalf half) {
            return flip ? (half == DoubleBlockHalf.UPPER ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER) : null;
        }
        if (value instanceof AttachFace face) {
            return flip ? switch (face) {
                case FLOOR -> AttachFace.CEILING;
                case CEILING -> AttachFace.FLOOR;
                case WALL -> AttachFace.WALL;
            } : null;
        }
        if (value instanceof BellAttachType bell) {
            return flip ? switch (bell) {
                case FLOOR -> BellAttachType.CEILING;
                case CEILING -> BellAttachType.FLOOR;
                default -> bell;
            } : null;
        }
        if (value instanceof RailShape rail) {
            return flip && !rail.isAscending() ? rail : null;
        }
        if (value instanceof ChestType chest) {
            return flip || chest == ChestType.SINGLE ? chest : null;
        }
        if (property == BlockStateProperties.HANGING) {
            return flip ? !(Boolean) value : null;
        }
        if (property == BlockStateProperties.ROTATION_16) {
            return flip ? value : null;
        }
        return value;
    }

    private Optional<BlockState> transformStairs(BlockState state, boolean approximate) {
        var key = new StairKey(state, transform(Direction.EAST), transform(Direction.UP), transform(Direction.SOUTH),
                approximate);
        var cached = STAIRS.get(key);
        if (cached != null) {
            return cached;
        }
        var result = approximate
                ? transformStairs(state, false).or(() -> Optional.of(nearestStairs(state)))
                : matchStairs(state);
        if (STAIRS.size() < STAIR_CACHE_LIMIT) {
            STAIRS.putIfAbsent(key, result);
        }
        return result;
    }

    private Optional<BlockState> matchStairs(BlockState state) {
        int target = transformOctants(octants(state));
        var preferredFacing = transform(state.getValue(StairBlock.FACING));
        BlockState match = null;
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (var half : Half.values()) {
                for (var shape : StairsShape.values()) {
                    var candidate = state.setValue(StairBlock.FACING, facing)
                            .setValue(StairBlock.HALF, half)
                            .setValue(StairBlock.SHAPE, shape);
                    if (octants(candidate) == target && (match == null || facing == preferredFacing)) {
                        match = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(match);
    }

    private BlockState nearestStairs(BlockState state) {
        int target = transformOctants(octants(state));
        var preferredFacing = transform(state.getValue(StairBlock.FACING));
        var nearest = state;
        int nearestDistance = Integer.MAX_VALUE;
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (var half : Half.values()) {
                for (var shape : StairsShape.values()) {
                    var candidate = state.setValue(StairBlock.FACING, facing)
                            .setValue(StairBlock.HALF, half)
                            .setValue(StairBlock.SHAPE, shape);
                    int distance = Integer.bitCount(octants(candidate) ^ target);
                    if (distance < nearestDistance || distance == nearestDistance && facing == preferredFacing
                            && nearest.getValue(StairBlock.FACING) != preferredFacing) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private static int octants(BlockState state) {
        var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        int mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            double x = (octant & 1) == 0 ? 0.25 : 0.75;
            double y = (octant & 2) == 0 ? 0.25 : 0.75;
            double z = (octant & 4) == 0 ? 0.25 : 0.75;
            for (var box : boxes) {
                if (box.contains(x, y, z)) {
                    mask |= 1 << octant;
                    break;
                }
            }
        }
        return mask;
    }

    private int transformOctants(int mask) {
        int result = 0;
        for (int octant = 0; octant < 8; octant++) {
            if ((mask & 1 << octant) == 0) {
                continue;
            }
            var corner = applyVector(new BlockPos(
                    (octant & 1) == 0 ? -1 : 1,
                    (octant & 2) == 0 ? -1 : 1,
                    (octant & 4) == 0 ? -1 : 1
            ));
            result |= 1 << ((corner.getX() > 0 ? 1 : 0) | (corner.getY() > 0 ? 2 : 0) | (corner.getZ() > 0 ? 4 : 0));
        }
        return result;
    }

    private BlockState transformAttached(BlockState state) {
        var face = state.getValue(ATTACH_FACE);
        var facing = state.getValue(HORIZONTAL_FACING);
        var pointing = transform(switch (face) {
            case FLOOR -> Direction.UP;
            case CEILING -> Direction.DOWN;
            case WALL -> facing;
        });
        if (pointing.getAxis() != Direction.Axis.Y) {
            return state.setValue(ATTACH_FACE, AttachFace.WALL).setValue(HORIZONTAL_FACING, pointing);
        }
        var localUp = transform(face == AttachFace.WALL ? Direction.UP : facing);
        return state.setValue(ATTACH_FACE, pointing == Direction.UP ? AttachFace.FLOOR : AttachFace.CEILING)
                .setValue(HORIZONTAL_FACING, localUp);
    }

    public String summary() {
        return "R[X=" + (rotationX * 90)
                + " Y=" + (rotationY * 90)
                + " Z=" + (rotationZ * 90)
                + "] F[X=" + mirrorX
                + " Y=" + mirrorY
                + " Z=" + mirrorZ
                + "]"
                + " Stack=" + stackCount
                + " " + stackAxis.name();
    }

    private Bounds transformedBounds(BlockPos sourceSize) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int x : new int[] { 0, sourceSize.getX() }) {
            for (int y : new int[] { 0, sourceSize.getY() }) {
                for (int z : new int[] { 0, sourceSize.getZ() }) {
                    var transformed = applyVector(new BlockPos(x, y, z));
                    minX = Math.min(minX, transformed.getX());
                    minY = Math.min(minY, transformed.getY());
                    minZ = Math.min(minZ, transformed.getZ());
                    maxX = Math.max(maxX, transformed.getX());
                    maxY = Math.max(maxY, transformed.getY());
                    maxZ = Math.max(maxZ, transformed.getZ());
                }
            }
        }

        return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    private BlockPos applyVector(BlockPos vector) {
        int x = mirrorX ? -vector.getX() : vector.getX();
        int y = mirrorY ? -vector.getY() : vector.getY();
        int z = mirrorZ ? -vector.getZ() : vector.getZ();

        for (int i = 0; i < rotationX; i++) {
            int nextY = -z;
            int nextZ = y;
            y = nextY;
            z = nextZ;
        }
        for (int i = 0; i < rotationY; i++) {
            int nextX = -z;
            int nextZ = x;
            x = nextX;
            z = nextZ;
        }
        for (int i = 0; i < rotationZ; i++) {
            int nextX = -y;
            int nextY = x;
            x = nextX;
            y = nextY;
        }

        return new BlockPos(x, y, z);
    }

    private static BlockPos directionVector(Direction direction) {
        return new BlockPos(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static Direction positive(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.EAST;
            case Y -> Direction.UP;
            case Z -> Direction.SOUTH;
        };
    }

    @Nullable
    private static Property<?> sideProperty(BlockState state, Direction side) {
        var name = side.getName();
        for (var property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static BlockState setValue(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }

    private record Bounds(BlockPos min, BlockPos max) {}

    private record StairKey(BlockState state, Direction east, Direction up, Direction south, boolean approximate) {}

    private static final class WallVariants {
        private static final Map<Block, Block> WALL_BY_STANDING = new HashMap<>();
        private static final Map<Block, Block> STANDING_BY_WALL = new HashMap<>();

        static {
            for (var block : BuiltInRegistries.BLOCK) {
                if (block.asItem() instanceof StandingAndWallBlockItem item && !(item instanceof HangingSignItem)
                        && item.getBlock() != block && block.defaultBlockState().hasProperty(HORIZONTAL_FACING)
                        && !item.getBlock().defaultBlockState().hasProperty(HORIZONTAL_FACING)) {
                    WALL_BY_STANDING.put(item.getBlock(), block);
                    STANDING_BY_WALL.put(block, item.getBlock());
                }
            }
        }

        @Nullable
        static BlockState transform(ToolTransform transform, BlockState state) {
            var wall = WALL_BY_STANDING.get(state.getBlock());
            if (wall != null) {
                return shared(state, wall.defaultBlockState()).setValue(HORIZONTAL_FACING, transform.transform(Direction.UP));
            }
            var standing = STANDING_BY_WALL.get(state.getBlock());
            if (standing == null) {
                return null;
            }
            var facing = state.getValue(HORIZONTAL_FACING);
            if (transform.transform(facing.getOpposite()) != Direction.DOWN) {
                return null;
            }
            var result = shared(state, standing.defaultBlockState());
            return result.hasProperty(BlockStateProperties.ROTATION_16)
                    ? result.setValue(BlockStateProperties.ROTATION_16, RotationSegment.convertToSegment(facing))
                    : result;
        }

        private static BlockState shared(BlockState source, BlockState target) {
            var result = target;
            for (var property : target.getProperties()) {
                if (property == HORIZONTAL_FACING || property == BlockStateProperties.ROTATION_16) {
                    continue;
                }
                for (var sourceProperty : source.getProperties()) {
                    var value = source.getValue(sourceProperty);
                    if (sourceProperty.getName().equals(property.getName()) && property.getPossibleValues().contains(value)) {
                        result = setValue(result, property, value);
                    }
                }
            }
            return result;
        }
    }
}
