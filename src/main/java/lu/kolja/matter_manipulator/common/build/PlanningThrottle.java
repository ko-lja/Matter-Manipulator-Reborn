package lu.kolja.matter_manipulator.common.build;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class PlanningThrottle<R> {

    private static final int REPEAT_TICKS = 6;

    private final int intervalTicks;
    private final Map<UUID, Planned<R>> planned = new HashMap<>();
    private final Map<UUID, Consumer<ServerPlayer>> deferredStatus = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();

    PlanningThrottle(int intervalTicks) {
        this.intervalTicks = intervalTicks;
    }

    public boolean repeats(UUID player, long now) {
        var previous = lastAction.get(player);
        return previous != null && now >= previous && now - previous <= REPEAT_TICKS;
    }

    public boolean admit(UUID player, long now) {
        var previous = lastAction.put(player, now);
        if (previous != null && withinWindow(previous, now)) {
            return false;
        }
        planned.put(player, new Planned<>(now, null, null));
        return true;
    }

    public boolean admitStatus(UUID player, long now, Consumer<ServerPlayer> retry) {
        if (!mayPlan(player, now)) {
            deferredStatus.put(player, retry);
            return false;
        }
        planned.put(player, new Planned<>(now, null, null));
        deferredStatus.remove(player);
        return true;
    }

    public void remember(UUID player, long now, Object key, R result) {
        var last = planned.get(player);
        if (last != null && last.plannedAt() == now) {
            planned.put(player, new Planned<>(now, key, result));
        }
    }

    public R recall(UUID player, Object key, long now) {
        var last = planned.get(player);
        return last != null && last.key() != null && last.key().equals(key) && withinWindow(last.plannedAt(), now)
                ? last.result()
                : null;
    }

    List<Map.Entry<UUID, Consumer<ServerPlayer>>> drainReady(long now) {
        var ready = new ArrayList<Map.Entry<UUID, Consumer<ServerPlayer>>>();
        var iterator = deferredStatus.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (mayPlan(entry.getKey(), now)) {
                ready.add(Map.entry(entry.getKey(), entry.getValue()));
                iterator.remove();
            }
        }
        planned.values().removeIf(last -> !withinWindow(last.plannedAt(), now));
        lastAction.values().removeIf(requestedAt -> !withinWindow(requestedAt, now));
        return ready;
    }

    public void forget(UUID player) {
        planned.remove(player);
        deferredStatus.remove(player);
        lastAction.remove(player);
    }

    void clear() {
        planned.clear();
        deferredStatus.clear();
        lastAction.clear();
    }

    int trackedPlayers() {
        var players = new HashSet<>(planned.keySet());
        players.addAll(deferredStatus.keySet());
        players.addAll(lastAction.keySet());
        return players.size();
    }

    private boolean mayPlan(UUID player, long now) {
        var last = planned.get(player);
        return last == null || !withinWindow(last.plannedAt(), now);
    }

    private boolean withinWindow(long since, long now) {
        return now - since < intervalTicks && now >= since;
    }

    private record Planned<R>(long plannedAt, Object key, R result) {}
}
