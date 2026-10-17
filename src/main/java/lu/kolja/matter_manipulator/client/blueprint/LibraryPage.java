package lu.kolja.matter_manipulator.client.blueprint;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintRef;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class LibraryPage {

    static final int UPLOAD_GAP_TICKS = 20;
    static final int UPLOAD_TIMEOUT_TICKS = 200;
    static final long DOUBLE_CLICK_MILLIS = 500;

    @Nullable
    private String loading;
    @Nullable
    private String pending;
    @Nullable
    private String pendingName;
    private long sent;
    private long settled = Long.MIN_VALUE / 2;

    boolean canUpload(long now) {
        return loading == null && pending == null && now - settled >= UPLOAD_GAP_TICKS;
    }

    void loading(@Nullable String name) {
        loading = name;
    }

    void uploaded(String key, String name, long now) {
        pending = key;
        pendingName = name;
        sent = now;
        settled = now;
    }

    @Nullable
    String uploading() {
        return loading != null ? loading : pendingName;
    }

    void toolBlueprint(@Nullable String key, long now) {
        if (pending != null && (pending.equals(key) || now - sent >= UPLOAD_TIMEOUT_TICKS)) {
            pending = null;
            pendingName = null;
            settled = now;
        }
    }

    public static boolean ignoresPress(long closedAt, long now) {
        return now - closedAt < DOUBLE_CLICK_MILLIS;
    }

    public static boolean isToolBlueprint(Blueprint.Header header, @Nullable BlueprintRef ref) {
        return ref != null && header.matches(ref.hash(), ref.size()) && ref.name().equals(header.name());
    }

    @Nullable
    public static Path initialSelection(List<BlueprintLibrary.Entry> entries, @Nullable BlueprintRef ref) {
        for (var entry : entries) {
            if (isToolBlueprint(entry.header(), ref)) {
                return entry.path();
            }
        }
        return null;
    }

    public static int indexOf(List<BlueprintLibrary.Entry> entries, @Nullable Path selected) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).path().equals(selected)) {
                return index;
            }
        }
        return -1;
    }

    public static int moved(int index, int delta, int rows) {
        if (index < 0) {
            return delta > 0 ? 0 : rows - 1;
        }
        return Math.max(0, Math.min(rows - 1, index + delta));
    }

    public static int scrollToShow(int scroll, int index, int rowHeight, int visible) {
        int top = index * rowHeight;
        if (top < scroll) {
            return top;
        }
        return Math.max(scroll, top + rowHeight - visible);
    }

    @Nullable
    public static String loadRefusal(@Nullable Blueprint.Header selected, @Nullable BlueprintRef ref,
                                     boolean canUpload) {
        if (selected == null) {
            return "library_load_none";
        }
        if (isToolBlueprint(selected, ref)) {
            return "library_load_current";
        }
        return canUpload ? null : "library_load_busy";
    }

    public static boolean isDoubleClick(@Nullable Path first, long firstAt, Path second, long now) {
        return second.equals(first) && now - firstAt < DOUBLE_CLICK_MILLIS;
    }

    public static int clampScroll(int scroll, int total, int visible) {
        return Math.max(0, Math.min(scroll, total - visible));
    }

    public static int rowAt(int y, int top, int height, int scroll, int rowHeight, int rows) {
        if (y < top || y >= top + height) {
            return -1;
        }
        int index = (y - top + scroll) / rowHeight;
        return index < rows ? index : -1;
    }

    static List<String> missingMods(List<String> mods, Predicate<String> loaded) {
        return mods.stream().filter(mod -> !loaded.test(mod)).toList();
    }

    public static final class Preview<T> {
        @Nullable
        private BlueprintTasks.Key key;
        @Nullable
        private BlueprintTasks.Load load;
        @Nullable
        private T built;

        @Nullable
        public T update(@Nullable BlueprintLibrary.Entry selected, Function<Blueprint, T> build) {
            if (selected == null) {
                clear();
                return null;
            }
            var header = selected.header();
            var selectedKey = new BlueprintTasks.Key(selected.path(), header.hash(), header.size());
            if (!selectedKey.equals(key)) {
                clear();
                key = selectedKey;
                load = BlueprintLibrary.loadAsync(selected);
            }
            var blueprint = load.blueprint();
            if (built == null && blueprint != null) {
                built = build.apply(blueprint);
            }
            return built;
        }

        @Nullable
        public BlueprintTasks.Load load() {
            return load;
        }

        public void clear() {
            if (load != null) {
                BlueprintLibrary.tasks.abandon(load);
            }
            key = null;
            load = null;
            built = null;
        }
    }
}
