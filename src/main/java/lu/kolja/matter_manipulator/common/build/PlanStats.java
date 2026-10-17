package lu.kolja.matter_manipulator.common.build;

import java.util.concurrent.atomic.AtomicLongArray;

public final class PlanStats {

    private static final boolean ENABLED = Boolean.getBoolean("matter_manipulator.planStats");

    public enum Counter {
        SOURCE_CAPTURES,
        INTEGRATION_CAPTURES,
        BLOCK_TRANSFORMS,
        STATE_TRANSFORMS,
        HANDLER_MATCHES,
        HANDLER_MATCH_MISSES,
        PLACEMENT_COSTS,
        BLOCK_COSTS,
        INTEGRATION_REQUIREMENTS,
        PREPARATION_REQUIREMENTS,
        DECISIONS,
        EXISTING_READS,
        SPEC_RESOLVES,
        TOUCH_CHECKS,
        PLAN_COMPILES,
        PLAN_CACHE_HITS,
        MOVE_MEMBERS_PREPARED,
        COMPILE_SLICES,
        SOURCE_VALIDATIONS,
        SOURCE_CHECKS,
        SOURCE_REREADS
    }

    public enum Section {
        PLAN,
        PLAN_SOURCE,
        PLAN_VALIDATE,
        PLAN_TRANSFORM,
        PLAN_PLACEMENT_CHECK,
        STATUS_DESTINATION,
        STATUS_REQUIREMENTS,
        STATUS_SUPPLY,
        EXECUTION_PRECHECK,
        EXECUTION_APPLY,
        CAPTURE_BLOCK_ENTITY,
        CAPTURE_INTEGRATION,
        TRANSFORM_STATE,
        TRANSFORM_COMPAT,
        TRANSFORM_BLOCK_ENTITY,
        MOVE_CHECK,
        MOVE_CAPTURE,
        MOVE_DESTINATION_CAPTURE,
        MOVE_ISOLATE,
        MOVE_PROTECTION,
        MOVE_CLEAR,
        MOVE_PLACE,
        MOVE_VERIFY,
        MOVE_SURVIVAL,
        MOVE_ROLLBACK,
        MOVE_NEIGHBOURS,
        MOVE_PREPARE
    }

    private static final AtomicLongArray COUNTS = new AtomicLongArray(Counter.values().length);
    private static final AtomicLongArray NANOS = new AtomicLongArray(Section.values().length);

    private PlanStats() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void count(Counter counter) {
        if (ENABLED) {
            COUNTS.incrementAndGet(counter.ordinal());
        }
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(Section section, long start) {
        if (ENABLED) {
            NANOS.addAndGet(section.ordinal(), System.nanoTime() - start);
        }
    }

    public static void add(Section section, long nanos) {
        if (ENABLED) {
            NANOS.addAndGet(section.ordinal(), nanos);
        }
    }

    public static Snapshot snapshot() {
        var counts = new long[COUNTS.length()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = COUNTS.get(i);
        }
        var nanos = new long[NANOS.length()];
        for (int i = 0; i < nanos.length; i++) {
            nanos[i] = NANOS.get(i);
        }
        return new Snapshot(counts, nanos);
    }

    public record Snapshot(long[] counts, long[] nanos) {

        public Snapshot minus(Snapshot earlier) {
            var counts = this.counts.clone();
            for (int i = 0; i < counts.length; i++) {
                counts[i] -= earlier.counts[i];
            }
            var nanos = this.nanos.clone();
            for (int i = 0; i < nanos.length; i++) {
                nanos[i] -= earlier.nanos[i];
            }
            return new Snapshot(counts, nanos);
        }

        public long count(Counter counter) {
            return counts[counter.ordinal()];
        }

        public double millis(Section section) {
            return nanos[section.ordinal()] / 1e6;
        }
    }
}
