package lu.kolja.matter_manipulator.client.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PreviewContent {
    public static final PreviewContent EMPTY = new PreviewContent(Map.of(), 0, 0, false);

    private final Map<Long, Section> sections;
    final int blocks;
    final int positions;
    final boolean truncated;

    private PreviewContent(Map<Long, Section> sections, int blocks, int positions, boolean truncated) {
        this.sections = sections;
        this.blocks = blocks;
        this.positions = positions;
        this.truncated = truncated;
    }

    public Collection<Section> sections() {
        return sections.values();
    }

    public Section section(long key) {
        return sections.get(key);
    }

    boolean isEmpty() {
        return positions == 0;
    }

    BlockState state(int x, int y, int z) {
        var section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return section == null ? null : section.states[Section.index(x & 15, y & 15, z & 15)];
    }

    PreviewVisual visual(int x, int y, int z) {
        var section = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return section == null || section.visuals == null ? null : section.visuals[Section.index(x & 15, y & 15, z & 15)];
    }

    public static final class Section {
        public final long key;
        public final int originX;
        public final int originY;
        public final int originZ;
        public final BlockState[] states;
        final PreviewVisual[] visuals;
        final int positions;
        final int blocks;

        private Section(long key, BlockState[] states, PreviewVisual[] visuals) {
            this.key = key;
            originX = SectionPos.sectionToBlockCoord(SectionPos.x(key));
            originY = SectionPos.sectionToBlockCoord(SectionPos.y(key));
            originZ = SectionPos.sectionToBlockCoord(SectionPos.z(key));
            this.states = states;
            this.visuals = visuals;
            int positions = 0;
            int blocks = 0;
            for (var state : states) {
                if (state != null) {
                    positions++;
                    if (!state.isAir()) {
                        blocks++;
                    }
                }
            }
            this.positions = positions;
            this.blocks = blocks;
        }

        public static int index(int x, int y, int z) {
            return x | z << 4 | y << 8;
        }

        public static int x(int index) {
            return index & 15;
        }

        public static int y(int index) {
            return index >> 8;
        }

        public static int z(int index) {
            return index >> 4 & 15;
        }

        PreviewVisual visual(int index) {
            return visuals == null ? null : visuals[index];
        }

        private boolean sameContents(Section other) {
            return Arrays.equals(states, other.states) && Arrays.equals(visuals, other.visuals);
        }
    }

    static final class Builder {
        private final Map<Long, BlockState[]> states = new LinkedHashMap<>();
        private final Map<Long, PreviewVisual[]> visuals = new LinkedHashMap<>();
        private final int limit;
        private int positions;
        private boolean truncated;
        private long lastKey = Long.MIN_VALUE;
        private BlockState[] lastStates;

        Builder(int limit) {
            this.limit = limit;
        }

        boolean add(int x, int y, int z, BlockState state, PreviewVisual visual) {
            long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            var sectionStates = key == lastKey ? lastStates : states.get(key);
            if (sectionStates == null) {
                if (positions >= limit) {
                    truncated = true;
                    return false;
                }
                sectionStates = new BlockState[4096];
                states.put(key, sectionStates);
            }
            lastKey = key;
            lastStates = sectionStates;
            int index = Section.index(x & 15, y & 15, z & 15);
            if (sectionStates[index] == null) {
                if (positions >= limit) {
                    truncated = true;
                    return false;
                }
                positions++;
            }
            sectionStates[index] = Objects.requireNonNull(state);
            var sectionVisuals = visual == null && visuals.isEmpty() ? null : visuals.get(key);
            if (visual != null && sectionVisuals == null) {
                sectionVisuals = new PreviewVisual[4096];
                visuals.put(key, sectionVisuals);
            }
            if (sectionVisuals != null) {
                sectionVisuals[index] = visual;
            }
            return true;
        }

        boolean add(BlockPos local, BlockState state, PreviewVisual visual) {
            return add(local.getX(), local.getY(), local.getZ(), state, visual);
        }

        void markTruncated() {
            truncated = true;
        }

        PreviewContent build(PreviewContent previous) {
            if (positions == 0) {
                return new PreviewContent(Map.of(), 0, 0, truncated);
            }
            var sections = new LinkedHashMap<Long, Section>();
            int blocks = 0;
            for (var entry : states.entrySet()) {
                var section = new Section(entry.getKey(), entry.getValue(), visuals.get(entry.getKey()));
                var old = previous.section(entry.getKey());
                sections.put(entry.getKey(), old != null && old.sameContents(section) ? old : section);
                blocks += section.blocks;
            }
            return new PreviewContent(Collections.unmodifiableMap(sections), blocks, positions, truncated);
        }
    }
}
