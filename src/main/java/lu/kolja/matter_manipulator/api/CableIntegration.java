package lu.kolja.matter_manipulator.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public interface CableIntegration {

    CablePlacementData prepare(IntegrationContext.Cable context);

    /** Whether a route can keep the existing cable at its position. Integrations must opt in; the default is no. */
    default boolean canReuse(IntegrationContext.CableReuse context) {
        return false;
    }

    /**
     * Why the template's cable can never stand at a position, whatever is there now, or null. The text follows
     * "refused by its integration: ".
     */
    default @Nullable Component routeRefusal(IntegrationContext.CableSite context) {
        return null;
    }

    record CablePlacementData(CompoundTag payload, ApplyTiming applyTiming) {
        public CablePlacementData {
            payload = Objects.requireNonNull(payload, "payload").copy();
            Objects.requireNonNull(applyTiming, "applyTiming");
        }

        @Override
        public CompoundTag payload() {
            return payload.copy();
        }

        public static CablePlacementData duringPlacement(CompoundTag payload) {
            return new CablePlacementData(payload, ApplyTiming.DURING_PLACEMENT);
        }

        public static CablePlacementData afterPath(CompoundTag payload) {
            return new CablePlacementData(payload, ApplyTiming.AFTER_PATH);
        }
    }

    enum ApplyTiming {
        DURING_PLACEMENT,
        AFTER_PATH
    }
}
