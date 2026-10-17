package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.build.PlacementCost.Resolved;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.state.BlockStateSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record BlockChange(
        BlockPos pos,
        Kind kind,
        BlockStateSpec target,
        CapturedBlock captured,
        boolean collectDrops,
        PlacementPurpose placementPurpose,
        MoveData move,
        @Nullable BlockState exchanged,
        @Nullable Resolved cost
) {

    public BlockChange {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(placementPurpose, "placementPurpose");
        switch (kind) {
            case PLACE -> require(target != null && captured == null && move == null
                    && placementPurpose == PlacementPurpose.STANDARD, "Invalid PLACE change");
            case PLACE_CAPTURED -> require(target != null && captured != null && move == null
                    && placementPurpose.consumesItems(), "Invalid PLACE_CAPTURED change");
            case CONFIGURE -> require(target != null && captured != null && move == null && !collectDrops
                    && placementPurpose == PlacementPurpose.RECONFIGURE, "Invalid CONFIGURE change");
            case REMOVE -> require(target == null && captured == null && move == null
                    && placementPurpose == PlacementPurpose.STANDARD, "Invalid REMOVE change");
            case MOVE -> require(target == null && captured == null && move != null && !collectDrops
                    && placementPurpose == PlacementPurpose.MOVE_TRANSFER, "Invalid MOVE change");
        }
        require(exchanged == null || (kind == Kind.PLACE || kind == Kind.PLACE_CAPTURED || kind == Kind.REMOVE)
                && placementPurpose == PlacementPurpose.STANDARD, "Only a standard placement or removal exchanges");
        require(cost == null || kind == Kind.PLACE || kind == Kind.PLACE_CAPTURED, "Only a placement has a cost");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static BlockChange place(BlockPos pos, BlockStateSpec target) {
        return new BlockChange(pos, Kind.PLACE, target, null, true, PlacementPurpose.STANDARD, null, null, null);
    }

    public static BlockChange placeCaptured(BlockPos pos, CapturedBlock captured) {
        return placeCaptured(pos, captured, PlacementPurpose.STANDARD);
    }

    public static BlockChange placeCaptured(BlockPos pos, CapturedBlock captured, PlacementPurpose purpose) {
        return new BlockChange(pos, Kind.PLACE_CAPTURED, captured.state(), captured, true, purpose, null, null, null);
    }

    public static BlockChange configure(BlockPos pos, CapturedBlock captured) {
        return new BlockChange(pos, Kind.CONFIGURE, captured.state(), captured, false, PlacementPurpose.RECONFIGURE, null, null,
                null);
    }

    public static BlockChange remove(BlockPos pos) {
        return remove(pos, true);
    }

    public static BlockChange remove(BlockPos pos, boolean collectDrops) {
        return new BlockChange(pos, Kind.REMOVE, null, null, collectDrops, PlacementPurpose.STANDARD, null, null, null);
    }

    public static BlockChange move(
            BlockPos source,
            BlockPos destination,
            BlockState expectedSource,
            BlockState expectedDestination,
            boolean swapDestination,
            ToolTransform transform
    ) {
        return move(source, destination, expectedSource, expectedDestination, swapDestination, transform, 0,
                new BoundingBox(source));
    }

    public static BlockChange move(
            BlockPos source,
            BlockPos destination,
            BlockState expectedSource,
            BlockState expectedDestination,
            boolean swapDestination,
            ToolTransform transform,
            int group
    ) {
        return move(source, destination, expectedSource, expectedDestination, swapDestination, transform, group,
                new BoundingBox(source));
    }

    public static BlockChange move(
            BlockPos source,
            BlockPos destination,
            BlockState expectedSource,
            BlockState expectedDestination,
            boolean swapDestination,
            ToolTransform transform,
            int group,
            BoundingBox selection
    ) {
        var move = new MoveData(
                destination,
                expectedSource,
                expectedDestination,
                swapDestination,
                transform,
                group,
                selection
        );
        return new BlockChange(
                source,
                Kind.MOVE,
                null,
                null,
                false,
                PlacementPurpose.MOVE_TRANSFER,
                move,
                null,
                null
        );
    }

    public BlockChange withExchanged(BlockState matched) {
        return new BlockChange(pos, kind, target, captured, collectDrops, placementPurpose, move, matched, cost);
    }

    BlockChange withCost(Resolved resolved) {
        return new BlockChange(pos, kind, target, captured, collectDrops, placementPurpose, move, exchanged, resolved);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockChange change && pos.equals(change.pos) && kind == change.kind
                && Objects.equals(target, change.target) && Objects.equals(captured, change.captured)
                && collectDrops == change.collectDrops && placementPurpose == change.placementPurpose
                && Objects.equals(move, change.move) && Objects.equals(exchanged, change.exchanged);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, kind, target, captured, collectDrops, placementPurpose, move, exchanged);
    }

    public enum Kind {
        PLACE,
        PLACE_CAPTURED,
        CONFIGURE,
        REMOVE,
        MOVE
    }

    public record MoveData(
            BlockPos destination,
            BlockState expectedSource,
            BlockState expectedDestination,
            boolean swapDestination,
            ToolTransform transform,
            int group,
            BoundingBox selection
    ) {
        public MoveData {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(expectedSource, "expectedSource");
            Objects.requireNonNull(expectedDestination, "expectedDestination");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(selection, "selection");
        }
    }
}
