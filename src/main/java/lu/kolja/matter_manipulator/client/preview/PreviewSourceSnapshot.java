package lu.kolja.matter_manipulator.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.function.ToIntFunction;

public final class PreviewSourceSnapshot {
    public record Key(Object level, BlockPos min, BlockPos max) {}

    final Key key;
    private final long[] sectionKeys;
    private final Section[] sections;
    private final Section[] pending;
    private int cursor;
    private boolean complete;
    private boolean changed;
    private int version;
    private long lastPassTick;

    public PreviewSourceSnapshot(Key key) {
        this.key = key;
        var minSection = SectionPos.of(key.min());
        var maxSection = SectionPos.of(key.max());
        int count = (maxSection.x() - minSection.x() + 1) * (maxSection.y() - minSection.y() + 1)
                * (maxSection.z() - minSection.z() + 1);
        sectionKeys = new long[count];
        int index = 0;
        for (int y = minSection.y(); y <= maxSection.y(); y++) {
            for (int z = minSection.z(); z <= maxSection.z(); z++) {
                for (int x = minSection.x(); x <= maxSection.x(); x++) {
                    sectionKeys[index++] = SectionPos.asLong(x, y, z);
                }
            }
        }
        sections = new Section[count];
        pending = new Section[count];
    }

    static long volume(BlockPos min, BlockPos max) {
        return (max.getX() - (long) min.getX() + 1) * (max.getY() - (long) min.getY() + 1)
                * (max.getZ() - (long) min.getZ() + 1);
    }

    boolean complete() {
        return complete;
    }

    int version() {
        return version;
    }

    public boolean advance(BlockGetter level, ToIntFunction<BlockPos> stamp, long gameTime, int refreshTicks, int budget) {
        if (complete && cursor == 0 && gameTime - lastPassTick < refreshTicks) {
            return false;
        }
        if (cursor == 0) {
            lastPassTick = gameTime;
        }
        int read = 0;
        while (cursor < sectionKeys.length && read < budget) {
            var section = read(level, stamp, sectionKeys[cursor]);
            read += section.inspected;
            if (sections[cursor] == null || !sections[cursor].sameContents(section)) {
                pending[cursor] = section;
                changed = true;
            }
            cursor++;
        }
        if (cursor < sectionKeys.length) {
            return false;
        }
        cursor = 0;
        if (!changed && complete) {
            return false;
        }
        for (int i = 0; i < pending.length; i++) {
            if (pending[i] != null) {
                sections[i] = pending[i];
                pending[i] = null;
            }
        }
        changed = false;
        complete = true;
        version++;
        return true;
    }

    private Section read(BlockGetter level, ToIntFunction<BlockPos> stamp, long sectionKey) {
        int originX = SectionPos.sectionToBlockCoord(SectionPos.x(sectionKey));
        int originY = SectionPos.sectionToBlockCoord(SectionPos.y(sectionKey));
        int originZ = SectionPos.sectionToBlockCoord(SectionPos.z(sectionKey));
        var min = key.min();
        var max = key.max();
        var states = new BlockState[4096];
        int[] stamps = null;
        int inspected = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(originY, min.getY()); y <= Math.min(originY + 15, max.getY()); y++) {
            for (int z = Math.max(originZ, min.getZ()); z <= Math.min(originZ + 15, max.getZ()); z++) {
                for (int x = Math.max(originX, min.getX()); x <= Math.min(originX + 15, max.getX()); x++) {
                    pos.set(x, y, z);
                    var state = level.getBlockState(pos);
                    int index = PreviewContent.Section.index(x & 15, y & 15, z & 15);
                    states[index] = state;
                    if (state.hasBlockEntity()) {
                        if (stamps == null) {
                            stamps = new int[4096];
                        }
                        stamps[index] = stamp.applyAsInt(pos);
                    }
                    inspected++;
                }
            }
        }
        return new Section(originX, originY, originZ, states, stamps, inspected);
    }

    void forEach(Visitor visitor) {
        var pos = new BlockPos.MutableBlockPos();
        for (var section : sections) {
            if (section == null) {
                continue;
            }
            for (int index = 0; index < 4096; index++) {
                var state = section.states[index];
                if (state == null || state.isAir()) {
                    continue;
                }
                pos.set(section.originX + PreviewContent.Section.x(index),
                        section.originY + PreviewContent.Section.y(index),
                        section.originZ + PreviewContent.Section.z(index));
                visitor.visit(pos, state, section.stamps == null ? 0 : section.stamps[index]);
            }
        }
    }

    interface Visitor {
        void visit(BlockPos pos, BlockState state, int stamp);
    }

    private record Section(int originX, int originY, int originZ, BlockState[] states, int[] stamps, int inspected) {
        boolean sameContents(Section other) {
            return Arrays.equals(states, other.states) && Arrays.equals(stamps, other.stamps);
        }
    }
}
