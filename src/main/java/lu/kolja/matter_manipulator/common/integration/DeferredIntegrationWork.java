package lu.kolja.matter_manipulator.common.integration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class DeferredIntegrationWork {

    private static final Queue<Task> NEXT_TICK = new ConcurrentLinkedDeque<>();

    private DeferredIntegrationWork() {}

    public static void enqueue(
            ResourceLocation owner,
            String operation,
            BlockState state,
            Runnable runnable
    ) {
        NEXT_TICK.add(new Task(owner, operation, state, runnable));
    }

    public static void runPending() {
        Task task;
        while ((task = NEXT_TICK.poll()) != null) {
            try {
                task.runnable().run();
            } catch (RuntimeException | LinkageError exception) {
                IntegrationFailures.report(task.owner(), task.operation(), task.state(), exception);
            }
        }
    }

    public static void clear() {
        NEXT_TICK.clear();
    }

    private record Task(ResourceLocation owner, String operation, BlockState state, Runnable runnable) {}
}
