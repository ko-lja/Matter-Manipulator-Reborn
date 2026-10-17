package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.config.MMConfig;

public record PreparationBudget(int membersPerTask, long nanosPerTask, long nanosPerTick) {

    public static final PreparationBudget DEFAULT = new PreparationBudget(1024, 3_000_000L, 4_000_000L);
    public static final PreparationBudget UNLIMITED = new PreparationBudget(Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    static final int COMMIT_WEIGHT_PER_TICK = 2048;
    static final long COMMIT_NANOS_PER_TICK = 25_000_000L;

    public PreparationBudget {
        if (membersPerTask < 1 || nanosPerTask < 1 || nanosPerTick < 1) {
            throw new IllegalArgumentException("A preparation budget must allow some work");
        }
    }

    public static PreparationBudget members(int membersPerTask) {
        return new PreparationBudget(membersPerTask, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public Slice slice(int preparingTasks) {
        return slice(preparingTasks, Commits.forTick());
    }

    public Slice slice(int preparingTasks, Commits commits) {
        return new Slice(membersPerTask, Math.min(nanosPerTask, nanosPerTick / Math.max(1, preparingTasks)), commits);
    }

    public static final class Commits {
        private final int members;
        private final long weight;
        private final long nanos;
        private int groups;
        private int committedMembers;
        private long committedWeight;
        private long spent;

        public Commits(int members, long weight, long nanos) {
            this.members = members;
            this.weight = weight;
            this.nanos = nanos;
        }

        static Commits forTick() {
            return new Commits(MMConfig.maxAtomicMoveGroup(), COMMIT_WEIGHT_PER_TICK, COMMIT_NANOS_PER_TICK);
        }

        boolean allow(int size, int groupWeight) {
            return groups == 0
                    || committedMembers + size <= members && committedWeight + groupWeight <= weight && spent < nanos;
        }

        void add(int size, int groupWeight, long groupNanos) {
            groups++;
            committedMembers += size;
            committedWeight += groupWeight;
            spent += groupNanos;
        }
    }

    public static final class Slice {
        private final long nanos;
        private final Commits commits;
        private int members;
        private int taken;
        private long spent;
        private long openedAt;

        Slice(int members, long nanos, Commits commits) {
            this.members = members;
            this.nanos = nanos;
            this.commits = commits;
        }

        void open() {
            openedAt = System.nanoTime();
        }

        long close() {
            long elapsed = System.nanoTime() - openedAt;
            spent += elapsed;
            return elapsed;
        }

        boolean take() {
            if (taken > 0 && (members <= 0 || spent + System.nanoTime() - openedAt >= nanos)) {
                return false;
            }
            members--;
            taken++;
            return true;
        }

        long nanos() {
            return nanos;
        }

        Commits commits() {
            return commits;
        }

        int taken() {
            return taken;
        }

        public long spent() {
            return spent;
        }
    }
}
