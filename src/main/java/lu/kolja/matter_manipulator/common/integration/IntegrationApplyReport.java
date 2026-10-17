package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.api.PlacementPurpose;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public record IntegrationApplyReport(
        Set<ResourceLocation> applied,
        Set<ResourceLocation> rejectedUnchanged,
        Set<ResourceLocation> partial,
        Set<ResourceLocation> uncertain,
        @Nullable Component rejectionReason,
        List<String> warnings
) {
    public static final IntegrationApplyReport NONE =
            new IntegrationApplyReport(Set.of(), Set.of(), Set.of(), Set.of(), null, List.of());

    public IntegrationApplyReport {
        applied = Set.copyOf(applied);
        rejectedUnchanged = Set.copyOf(rejectedUnchanged);
        partial = Set.copyOf(partial);
        uncertain = Set.copyOf(uncertain);
        warnings = List.copyOf(warnings);
    }

    public boolean successfulFor(PlacementPurpose purpose) {
        return rejectedUnchanged.isEmpty() && uncertain.isEmpty() && (purpose.consumesItems() || partial.isEmpty());
    }

    public boolean rejectedEntirely() {
        return !rejectedUnchanged.isEmpty() && applied.isEmpty() && partial.isEmpty() && uncertain.isEmpty();
    }
}
