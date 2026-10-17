package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

public final class ShapeGenerator {

    private ShapeGenerator() {}

    public static void generate(ShapeType shape, BlockPos a, BlockPos b, BlockPos c, BiConsumer<BlockPos, BlockSlot> out) {
        generate(shape, a, b, c, Integer.MAX_VALUE, out);
    }

    public static boolean generate(
            ShapeType shape,
            BlockPos a,
            BlockPos b,
            BlockPos c,
            int maxPositions,
            BiConsumer<BlockPos, BlockSlot> out
    ) {
        var sink = new LimitedSink(Math.max(0, maxPositions), out);
        switch (shape) {
            case CUBOID -> cuboid(a, b, sink);
            case LINE -> line(a, b, sink);
            case ELLIPSOID -> ellipsoid(a, b, sink);
            case CYLINDER -> cylinder(a, b, c == null ? b : c, sink);
        }
        return !sink.truncated();
    }

    public static Iterable<BlockPos> region(BlockPos a, BlockPos b) {
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> positions = BlockPos.betweenClosed(a, b).iterator();

            @Override
            public boolean hasNext() {
                return positions.hasNext();
            }

            @Override
            public BlockPos next() {
                return positions.next().immutable();
            }
        };
    }

    public static List<BlockPos> line(BlockPos a, BlockPos b) {
        var positions = new ArrayList<BlockPos>();
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps == 0) {
            positions.add(a);
            return positions;
        }
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            positions.add(new BlockPos(
                    a.getX() + (int) Math.round(dx * t),
                    a.getY() + (int) Math.round(dy * t),
                    a.getZ() + (int) Math.round(dz * t)
            ));
        }
        return positions;
    }

    private static void line(BlockPos a, BlockPos b, LimitedSink sink) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps == 0) {
            sink.emit(a, BlockSlot.VOLUME);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            if (!sink.emit(new BlockPos(
                    a.getX() + (int) Math.round(dx * t),
                    a.getY() + (int) Math.round(dy * t),
                    a.getZ() + (int) Math.round(dz * t)
            ), BlockSlot.VOLUME)) {
                return;
            }
        }
    }

    private static void cuboid(BlockPos a, BlockPos b, LimitedSink sink) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int bounds = 0;
                    if (x == minX || x == maxX) bounds++;
                    if (y == minY || y == maxY) bounds++;
                    if (z == minZ || z == maxZ) bounds++;
                    var slot = switch (bounds) {
                        case 0 -> BlockSlot.VOLUME;
                        case 1 -> BlockSlot.FACE;
                        case 2 -> BlockSlot.EDGE;
                        default -> BlockSlot.CORNER;
                    };
                    if (!sink.emit(new BlockPos(x, y, z), slot)) {
                        return;
                    }
                }
            }
        }
    }

    private static void ellipsoid(BlockPos a, BlockPos b, LimitedSink sink) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        double rx = Math.max(0.5, (maxX - minX + 1) / 2.0);
        double ry = Math.max(0.5, (maxY - minY + 1) / 2.0);
        double rz = Math.max(0.5, (maxZ - minZ + 1) / 2.0);
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    double normalized = square((x + 0.5 - cx) / rx)
                            + square((y + 0.5 - cy) / ry)
                            + square((z + 0.5 - cz) / rz);
                    if (normalized <= 1.0) {
                        if (!sink.emit(new BlockPos(x, y, z), normalized > 0.72 ? BlockSlot.FACE : BlockSlot.VOLUME)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void cylinder(BlockPos a, BlockPos radiusPos, BlockPos heightPos, LimitedSink sink) {
        double radius = Math.max(0.5, Math.sqrt(square(radiusPos.getX() - a.getX()) + square(radiusPos.getZ() - a.getZ())));
        int minY = Math.min(a.getY(), heightPos.getY());
        int maxY = Math.max(a.getY(), heightPos.getY());
        int r = (int) Math.ceil(radius);
        for (int y = minY; y <= maxY; y++) {
            for (int z = a.getZ() - r; z <= a.getZ() + r; z++) {
                for (int x = a.getX() - r; x <= a.getX() + r; x++) {
                    double normalized = Math.sqrt(square(x - a.getX()) + square(z - a.getZ())) / radius;
                    if (normalized <= 1.0) {
                        boolean cap = y == minY || y == maxY;
                        if (!sink.emit(new BlockPos(x, y, z), cap || normalized > 0.75 ? BlockSlot.FACE : BlockSlot.VOLUME)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class LimitedSink {
        private final int limit;
        private final BiConsumer<BlockPos, BlockSlot> output;
        private int emitted;
        private boolean truncated;

        private LimitedSink(int limit, BiConsumer<BlockPos, BlockSlot> output) {
            this.limit = limit;
            this.output = output;
        }

        private boolean emit(BlockPos pos, BlockSlot slot) {
            if (emitted >= limit) {
                truncated = true;
                return false;
            }
            output.accept(pos, slot);
            emitted++;
            return true;
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
