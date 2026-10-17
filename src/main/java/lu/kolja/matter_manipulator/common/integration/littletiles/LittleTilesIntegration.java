package lu.kolja.matter_manipulator.common.integration.littletiles;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.PlacementRequirements;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import team.creative.creativecore.common.util.math.base.Axis;
import team.creative.creativecore.common.util.math.base.Facing;
import team.creative.creativecore.common.util.math.transformation.Rotation;
import team.creative.littletiles.LittleTilesRegistry;
import team.creative.littletiles.common.block.entity.BETiles;
import team.creative.littletiles.common.block.little.tile.LittleTile;
import team.creative.littletiles.common.block.little.tile.collection.LittleCollection;
import team.creative.littletiles.common.block.little.tile.group.LittleGroup;
import team.creative.littletiles.common.block.little.tile.parent.StructureParentCollection;
import team.creative.littletiles.common.grid.LittleGrid;
import team.creative.littletiles.common.structure.exception.CorruptedConnectionException;
import team.creative.littletiles.common.structure.exception.NotYetConnectedException;
import team.creative.littletiles.common.structure.registry.LittleStructureRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LittleTilesIntegration {

    public static final Identifier TILES_ID = Identifier.fromNamespaceAndPath("littletiles", "tiles");
    public static final String COPY_REFUSED = "matter_manipulator.block_cost.littletiles.copy";
    public static final String REMOVAL_REFUSED = "matter_manipulator.refusal.littletiles.removal";
    public static final String PARTIAL_STRUCTURE = "matter_manipulator.refusal.littletiles.partial_structure";

    private static final String CONTENT = "content";
    private static final String TILES = "tiles";
    private static final String CHILDREN = "children";
    private static final String STRUCTURE = "structure";
    private static final String COORD = "coord";
    private static final String BLOCKS = "b";
    private static final String AIMED_STATE = "aS";
    private static final String TIMELINE = "timeline";
    private static final List<String> SIDE_CACHE = List.of("east", "west", "up", "down", "south", "north", "y_axis");

    private LittleTilesIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(TILES_ID, new TilesIntegration(), LittleTilesRegistry.BLOCK_TILES.value(),
                LittleTilesRegistry.BLOCK_TILES_TICKING.value(), LittleTilesRegistry.BLOCK_TILES_RENDERED.value(),
                LittleTilesRegistry.BLOCK_TILES_TICKING_RENDERED.value());
    }

    private static final class TilesIntegration implements BlockIntegration {

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            return PlacementRequirements.unsupported(Component.translatable(COPY_REFUSED));
        }

        @Override
        public Component removalRefusal(BlockState state) {
            return Component.translatable(REMOVAL_REFUSED);
        }

        @Override
        @Nullable
        public Component moveRefusal(IntegrationContext.Capture context) {
            for (var block : structureBlocks(context)) {
                if (!context.inSelection(block)) {
                    return Component.translatable(PARTIAL_STRUCTURE);
                }
            }
            return null;
        }

        @Override
        public Set<BlockPos> moveGroupPeers(IntegrationContext.Capture context) {
            return structureBlocks(context);
        }

        @Override
        public boolean relocatesBlockEntity(BlockState state) {
            return true;
        }

        @Override
        public CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
            var source = context.source();
            for (var child : structureCollections(blockEntity)) {
                if (!child.contains(STRUCTURE, Tag.TAG_COMPOUND)) {
                    requireMoved(context, source.offset(coord(child)), "its structure's main block");
                    continue;
                }
                var structure = child.getCompound(STRUCTURE);
                if (structure.contains(AIMED_STATE) && structure.getInt(AIMED_STATE) != -1 || structure.contains(TIMELINE)) {
                    throw new IllegalStateException("The LittleTiles structure at " + source.toShortString()
                            + " is animating and cannot be moved");
                }
                var blocks = structure.getIntArray(BLOCKS);
                for (int i = 0; i + 2 < blocks.length; i += 3) {
                    requireMoved(context, source.offset(blocks[i], blocks[i + 1], blocks[i + 2]), "a block of its structure");
                }
                for (var link : links(structure)) {
                    if (link.contains("entity") || link.contains("subWorld")) {
                        throw new IllegalStateException("The LittleTiles structure at " + source.toShortString()
                                + " is linked to an animation and cannot be moved");
                    }
                    requireMoved(context, source.offset(coord(link)), "a linked structure");
                }
            }
            return blockEntity;
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            var transform = context.transform();
            var turn = Turn.of(transform);
            var grid = LittleGrid.getOrThrow(blockEntity);
            var content = blockEntity.getCompound(CONTENT);
            content.put(TILES, turnTiles(content.getCompound(TILES), grid, turn));
            for (var child : structureCollections(blockEntity)) {
                child.put(TILES, turnTiles(child.getCompound(TILES), grid, turn));
                if (!child.contains(STRUCTURE, Tag.TAG_COMPOUND)) {
                    putCoord(child, transform.transformOffset(coord(child)));
                    continue;
                }
                var structure = child.getCompound(STRUCTURE);
                if (structure.contains(BLOCKS)) {
                    var blocks = structure.getIntArray(BLOCKS);
                    for (int i = 0; i + 2 < blocks.length; i += 3) {
                        var turned = transform.transformOffset(new BlockPos(blocks[i], blocks[i + 1], blocks[i + 2]));
                        blocks[i] = turned.getX();
                        blocks[i + 1] = turned.getY();
                        blocks[i + 2] = turned.getZ();
                    }
                    structure.putIntArray(BLOCKS, blocks);
                }
                for (var link : links(structure)) {
                    putCoord(link, transform.transformOffset(coord(link)));
                }
                var type = LittleStructureRegistry.REGISTRY.get(structure.getString("id"));
                if (type == null) {
                    continue;
                }
                for (var field : type.directional) {
                    if (!structure.contains(field.saveKey)) {
                        continue;
                    }
                    var value = field.createTemporary(structure);
                    if (turn.mirrorX()) {
                        value = field.transform(value, grid, Axis.X.getMatrix(), grid.rotationCenter);
                    }
                    for (var rotation : turn.rotations()) {
                        value = field.transform(value, grid, rotation.getMatrix(), grid.rotationCenter);
                    }
                    field.save(structure, value);
                }
            }
            SIDE_CACHE.forEach(blockEntity::remove);
            return blockEntity;
        }
    }

    private static Set<BlockPos> structureBlocks(IntegrationContext.Capture context) {
        var blocks = new HashSet<BlockPos>();
        if (!(context.blockEntity() instanceof BETiles tiles)) {
            return blocks;
        }
        for (var collection : tiles.structures()) {
            if (!(collection instanceof StructureParentCollection structure)) {
                continue;
            }
            if (!structure.isMain()) {
                blocks.add(structure.getStructurePosition());
                continue;
            }
            try {
                structure.getStructure().positions().forEach(blocks::add);
            } catch (CorruptedConnectionException | NotYetConnectedException exception) {
                throw new IllegalStateException("The LittleTiles structure at " + context.pos().toShortString()
                        + " is not loaded", exception);
            }
        }
        return blocks;
    }

    private static void requireMoved(IntegrationContext.Relocation context, BlockPos linked, String what) {
        if (context.destinationOf(linked) == null) {
            throw new IllegalStateException("The LittleTiles block at " + context.source().toShortString() + " needs "
                    + what + " at " + linked.toShortString() + ", which is outside the moved region");
        }
    }

    private static List<CompoundTag> structureCollections(CompoundTag blockEntity) {
        var children = blockEntity.getCompound(CONTENT).getList(CHILDREN, Tag.TAG_COMPOUND);
        var collections = new ArrayList<CompoundTag>(children.size());
        for (int i = 0; i < children.size(); i++) {
            collections.add(children.getCompound(i));
        }
        return collections;
    }

    private static List<CompoundTag> links(CompoundTag structure) {
        var links = new ArrayList<CompoundTag>();
        if (structure.contains(LittleGroup.PARENT_KEY, Tag.TAG_COMPOUND)) {
            links.add(structure.getCompound(LittleGroup.PARENT_KEY));
        }
        for (var key : List.of(LittleGroup.CHILDREN_KEY, LittleGroup.EXTENSION_KEY)) {
            var list = structure.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                links.add(list.getCompound(i));
            }
        }
        return links;
    }

    private static BlockPos coord(CompoundTag holder) {
        var coord = holder.getIntArray(COORD);
        if (coord.length != 3) {
            throw new IllegalStateException("Invalid LittleTiles structure coord " + holder);
        }
        return new BlockPos(coord[0], coord[1], coord[2]);
    }

    private static void putCoord(CompoundTag holder, BlockPos coord) {
        holder.putIntArray(COORD, new int[] {coord.getX(), coord.getY(), coord.getZ()});
    }

    private static CompoundTag turnTiles(CompoundTag tiles, LittleGrid grid, Turn turn) {
        var collection = new LittleCollection();
        LittleCollection.loadExtended(collection, tiles);
        var turned = new CompoundTag();
        for (var tile : collection) {
            turn.apply(tile, grid);
            if (!turned.contains(tile.getBlockName())) {
                turned.put(tile.getBlockName(), new ListTag());
            }
            var boxes = turned.getList(tile.getBlockName(), Tag.TAG_INT_ARRAY);
            boxes.add(new IntArrayTag(new int[] {tile.color}));
            for (var box : tile) {
                var array = box.getArray();
                var extended = new int[array.length + 1];
                System.arraycopy(array, 0, extended, 1, array.length);
                boxes.add(new IntArrayTag(extended));
            }
        }
        return turned;
    }

    private record Turn(boolean mirrorX, List<Rotation> rotations) {

        private static final Map<List<Facing>, List<Rotation>> QUARTER_TURNS = quarterTurns();

        static Turn of(DirectionTransform transform) {
            var east = transform.transform(Direction.EAST);
            var up = transform.transform(Direction.UP);
            boolean mirrored = !east.getNormal().cross(up.getNormal()).equals(transform.transform(Direction.SOUTH).getNormal());
            var rotatedEast = mirrored ? east.getOpposite() : east;
            return new Turn(mirrored, QUARTER_TURNS.get(List.of(Facing.get(rotatedEast), Facing.get(up))));
        }

        void apply(LittleTile tile, LittleGrid grid) {
            if (mirrorX) {
                tile.transform(Axis.X.getMatrix(), grid.rotationCenter);
            }
            for (var rotation : rotations) {
                tile.transform(rotation.getMatrix(), grid.rotationCenter);
            }
        }

        private static Map<List<Facing>, List<Rotation>> quarterTurns() {
            var turns = new HashMap<List<Facing>, List<Rotation>>();
            var queue = new ArrayDeque<List<Rotation>>();
            queue.add(List.of());
            while (!queue.isEmpty()) {
                var sequence = queue.poll();
                var east = Facing.EAST;
                var up = Facing.UP;
                for (var rotation : sequence) {
                    east = rotation.rotate(east);
                    up = rotation.rotate(up);
                }
                if (turns.putIfAbsent(List.of(east, up), sequence) != null) {
                    continue;
                }
                for (var rotation : List.of(Rotation.X_CLOCKWISE, Rotation.Y_CLOCKWISE, Rotation.Z_CLOCKWISE)) {
                    var next = new ArrayList<>(sequence);
                    next.add(rotation);
                    queue.add(List.copyOf(next));
                }
            }
            return Map.copyOf(turns);
        }
    }
}
