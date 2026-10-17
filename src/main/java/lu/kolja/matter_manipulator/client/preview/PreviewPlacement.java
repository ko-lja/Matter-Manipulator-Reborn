package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.common.build.PlacementDecision;
import lu.kolja.matter_manipulator.common.build.SkipReason;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class PreviewPlacement {
    static final byte PLACE = 1;
    static final byte REPLACE = 2;
    static final byte NOOP = 3;
    static final byte REMOVE = 4;
    static final byte SKIP = 5;
    public static final byte BLOCKED = 6;
    static final int STATUSES = 7;

    public record Params(ManipulatorMode mode, RemoveMode removeMode, boolean tierCanRemove, BlockPos sourceMin,
            BlockPos sourceMax) {}

    public record Key(PreviewContent content, BlockPos origin, Params params, long worldRevision) {}

    final Key key;
    private final Map<Long, byte[]> statuses = new HashMap<>();
    private final PreviewContent.Section[] order;
    private final int[] counts = new int[STATUSES];
    private int sectionCursor;
    private int indexCursor;

    public PreviewPlacement(Key key) {
        this.key = key;
        order = key.content().sections().toArray(PreviewContent.Section[]::new);
    }

    boolean complete() {
        return sectionCursor >= order.length;
    }

    public byte[] statuses(long section) {
        return complete() ? statuses.get(section) : null;
    }

    public int count(byte status) {
        return counts[status];
    }

    public boolean advance(Level level, Player player, int budget) {
        var origin = key.origin();
        var pos = new BlockPos.MutableBlockPos();
        int evaluated = 0;
        while (sectionCursor < order.length && evaluated < budget) {
            var section = order[sectionCursor];
            var result = statuses.computeIfAbsent(section.key, ignored -> new byte[4096]);
            for (; indexCursor < 4096 && evaluated < budget; indexCursor++) {
                var target = section.states[indexCursor];
                if (target == null) {
                    continue;
                }
                pos.set(origin.getX() + section.originX + PreviewContent.Section.x(indexCursor),
                        origin.getY() + section.originY + PreviewContent.Section.y(indexCursor),
                        origin.getZ() + section.originZ + PreviewContent.Section.z(indexCursor));
                byte status = status(level, player, pos, target, section.visual(indexCursor));
                result[indexCursor] = status;
                counts[status]++;
                evaluated++;
            }
            if (indexCursor >= 4096) {
                indexCursor = 0;
                sectionCursor++;
            }
        }
        return complete();
    }

    private byte status(Level level, Player player, BlockPos.MutableBlockPos pos,
            BlockState target, @Nullable PreviewVisual visual) {
        var params = key.params();
        if (params.mode() == ManipulatorMode.EXCHANGE) {
            return REPLACE;
        }
        if (params.mode() == ManipulatorMode.MOVE && params.sourceMin() != null
                && inside(pos, params.sourceMin(), params.sourceMax())) {
            return PLACE;
        }
        var existing = level.getBlockState(pos).isAir()
                ? PlacementDecision.Existing.at(level, player, pos, target)
                : PlacementDecision.Existing.at(level, player, pos.immutable(), target);
        if (params.mode() == ManipulatorMode.MOVE) {
            boolean canSwap = params.removeMode() == RemoveMode.ALL && !existing.replaceable();
            var decision = PlacementDecision.moveDestination(existing, params.removeMode(), canSwap);
            if (decision.skipped()) {
                return decision.reason() == SkipReason.POLICY || decision.reason() == SkipReason.REMOVAL_REFUSED
                        ? SKIP : BLOCKED;
            }
            return existing.state().isAir() ? PLACE : REPLACE;
        }
        if (params.mode() == ManipulatorMode.CABLE) {
            var template = visual instanceof PreviewVisual.Captured captured ? captured.captured() : null;
            if (params.removeMode() != RemoveMode.ALL
                    && IntegrationDispatcher.canReuseCable(level, pos.immutable(), existing.state(), target, template)) {
                return NOOP;
            }
            if (IntegrationDispatcher.cableRouteRefusal(level, pos, target, template) != null) {
                return SKIP;
            }
        }
        return placementStatus(params, existing, target,
                params.mode() == ManipulatorMode.CABLE && IntegrationDispatcher.isCable(existing.state()));
    }

    static byte placementStatus(Params params, PlacementDecision.Existing existing,
            BlockState target, boolean cable) {
        var decision = target.isAir()
                ? PlacementDecision.remove(existing, params.removeMode(), params.tierCanRemove())
                : params.mode() == ManipulatorMode.CABLE
                        ? PlacementDecision.placeCable(existing, target, cable, params.removeMode(), params.tierCanRemove())
                        : PlacementDecision.place(existing, target, PlacementDecision.Configuration.SAME,
                                params.removeMode(), params.tierCanRemove());
        return switch (decision.action()) {
            case PLACE, RECONFIGURE -> PLACE;
            case REPLACE -> REPLACE;
            case REMOVE -> REMOVE;
            case NOOP -> NOOP;
            case SKIP -> SKIP;
        };
    }

    private static boolean inside(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY()
                && pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
