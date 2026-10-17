package lu.kolja.matter_manipulator.client;

import org.jetbrains.annotations.Nullable;

final class ScrollSteps<E> {

    record Batch<E>(E edit, int steps) {}

    private E edit;
    private int steps;

    @Nullable
    Batch<E> add(E edit, int steps) {
        var displaced = edit.equals(this.edit) ? null : take();
        this.edit = edit;
        this.steps += steps;
        return displaced;
    }

    @Nullable
    Batch<E> take() {
        var batch = edit == null || steps == 0 ? null : new Batch<>(edit, steps);
        edit = null;
        steps = 0;
        return batch;
    }
}
