package lu.kolja.matter_manipulator.common.blueprint;

import lu.kolja.matter_manipulator.MatterManipulator;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class BlueprintServerTasks {

    private final Executor worker;
    private final Executor serverThread;
    private final Map<UUID, Job> jobs = new HashMap<>();

    public BlueprintServerTasks(Executor worker, Executor serverThread) {
        this.worker = worker;
        this.serverThread = serverThread;
    }

    public interface Work {
        Consumer<ServerPlayer> run(BooleanSupplier dropped) throws IOException;
    }

    interface Task {
        Runnable run(BooleanSupplier dropped) throws IOException;
    }

    private static final class Job {
        private final String kind;
        private volatile boolean dropped;
        @Nullable
        private volatile Runnable result;

        private Job(String kind) {
            this.kind = kind;
        }

        private void drop() {
            dropped = true;
            result = null;
        }
    }

    public boolean busy(UUID player) {
        return jobs.containsKey(player);
    }

    public void submit(ServerPlayer player, String kind, Work work, BiConsumer<ServerPlayer, IOException> failed) {
        submit(player.getUUID(), kind, dropped -> {
            var result = work.run(dropped);
            return () -> {
                var recipient = recipient(player);
                if (recipient != null) {
                    result.accept(recipient);
                }
            };
        }, failure -> {
            var recipient = recipient(player);
            if (recipient != null) {
                failed.accept(recipient, failure);
            }
        });
    }

    @Nullable
    private static ServerPlayer recipient(ServerPlayer player) {
        return player.isRemoved() ? player.server.getPlayerList().getPlayer(player.getUUID()) : player;
    }

    void submit(UUID player, String kind, Task task, Consumer<IOException> failed) {
        if (jobs.containsKey(player)) {
            throw new IllegalStateException("Player " + player + " already has a blueprint job");
        }
        var job = new Job(kind);
        jobs.put(player, job);
        Runnable unfinished = () -> failed.accept(new IOException("the server could not finish this blueprint job"));
        Runnable apply = () -> {
            if (!jobs.remove(player, job)) {
                return;
            }
            var result = job.result;
            job.result = null;
            try {
                result.run();
            } catch (RuntimeException exception) {
                MatterManipulator.LOGGER.error("Applying the blueprint {} of player {} failed", job.kind, player,
                        exception);
            }
        };
        worker.execute(() -> {
            if (job.dropped) {
                return;
            }
            Runnable result = null;
            try {
                result = task.run(() -> job.dropped);
            } catch (IOException exception) {
                result = () -> failed.accept(exception);
            } catch (RuntimeException exception) {
                MatterManipulator.LOGGER.error("The blueprint {} of player {} failed", kind, player, exception);
                result = () -> failed.accept(new IOException(exception.toString(), exception));
            } catch (OutOfMemoryError error) {
                result = unfinished;
                MatterManipulator.LOGGER.error("Ran out of memory in the blueprint {} of player {}", kind, player,
                        error);
                result = () -> failed.accept(new IOException("not enough memory for this blueprint", error));
            } finally {
                job.result = result != null ? result : unfinished;
                if (job.dropped) {
                    job.result = null;
                } else {
                    serverThread.execute(apply);
                }
            }
        });
    }

    public void forget(UUID player) {
        var job = jobs.remove(player);
        if (job != null) {
            job.drop();
        }
    }

    public void clear() {
        for (var job : jobs.values()) {
            job.drop();
        }
        jobs.clear();
    }
}
