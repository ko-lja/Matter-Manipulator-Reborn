package lu.kolja.matter_manipulator.client.blueprint;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class MissingBlueprintReports {

    private final Set<String> reported = new HashSet<>();
    @Nullable
    private String toolBlueprint;

    boolean report(String key) {
        return reported.add(key);
    }

    void toolBlueprint(@Nullable String key) {
        if (!Objects.equals(key, toolBlueprint)) {
            toolBlueprint = key;
            reported.clear();
        }
    }

    void reset() {
        reported.clear();
    }
}
