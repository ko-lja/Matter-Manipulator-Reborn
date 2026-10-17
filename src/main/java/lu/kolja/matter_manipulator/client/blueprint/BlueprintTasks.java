package lu.kolja.matter_manipulator.client.blueprint;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class BlueprintTasks {

    private final Executor worker;
    private final Executor mainThread;
    private final Map<Key, Load> loading = new HashMap<>();
    @Nullable
    private Load last;
    private int generation;

    BlueprintTasks(Executor worker, Executor mainThread) {
        this.worker = worker;
        this.mainThread = mainThread;
    }

    interface Work {
        Runnable run() throws IOException;
    }

    interface Read {
        Blueprint read() throws IOException;
    }

    record Key(Path path, String hash, BlockPos size) {}

    public static final class Load {
        private final Key key;
        @Nullable
        private Blueprint blueprint;
        @Nullable
        private IOException failure;
        private final List<Runnable> waiting = new ArrayList<>();
        private volatile boolean abandoned;

        private Load(Key key) {
            this.key = key;
        }

        @Nullable
        public Blueprint blueprint() {
            return blueprint;
        }

        @Nullable
        public IOException failure() {
            return failure;
        }

        public boolean done() {
            return blueprint != null || failure != null;
        }

        public boolean matches(String hash, BlockPos size) {
            return key.hash().equals(hash) && key.size().equals(size);
        }

        private void finish() {
            for (var then : waiting) {
                then.run();
            }
            waiting.clear();
        }
    }

    void run(Work work, Consumer<IOException> failed) {
        run(work, failed, true);
    }

    void runPastLogout(Work work, Consumer<IOException> failed) {
        run(work, failed, false);
    }

    private void run(Work work, Consumer<IOException> failed, boolean droppedByClear) {
        int started = generation;
        Runnable unfinished = () -> failed.accept(new IOException("the blueprint task could not finish"));
        worker.execute(() -> {
            Runnable result = null;
            try {
                result = work.run();
            } catch (IOException exception) {
                result = () -> failed.accept(exception);
            } catch (RuntimeException exception) {
                result = () -> failed.accept(new IOException(exception.toString(), exception));
            } catch (OutOfMemoryError error) {
                result = unfinished;
                MatterManipulator.LOGGER.error("Ran out of memory reading a blueprint", error);
                result = () -> failed.accept(new IOException("not enough memory to decode it", error));
            } finally {
                var apply = result != null ? result : unfinished;
                mainThread.execute(() -> {
                    if (!droppedByClear || started == generation) {
                        apply.run();
                    }
                });
            }
        });
    }

    Load load(Key key, Read read) {
        var finished = last;
        if (finished != null && finished.key.equals(key)) {
            return finished;
        }
        var current = loading.get(key);
        if (current != null) {
            return current;
        }
        var load = new Load(key);
        loading.put(key, load);
        run(() -> {
            if (load.abandoned) {
                return () -> {};
            }
            var blueprint = read.read();
            return () -> {
                loading.remove(key, load);
                load.blueprint = blueprint;
                last = load;
                load.finish();
            };
        }, failure -> {
            loading.remove(key, load);
            load.failure = failure;
            load.finish();
        });
        return load;
    }

    void abandon(Load load) {
        if (load.done() || !load.waiting.isEmpty() || loading.get(load.key) != load) {
            return;
        }
        load.abandoned = true;
        loading.remove(load.key);
    }

    void whenDone(Load load, Runnable then) {
        if (load.done()) {
            then.run();
        } else {
            load.waiting.add(then);
        }
    }

    void clear() {
        generation++;
        loading.clear();
        last = null;
    }
}
