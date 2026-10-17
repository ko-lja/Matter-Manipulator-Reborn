package lu.kolja.matter_manipulator.common.build;

import lombok.Getter;
import lu.kolja.matter_manipulator.common.localization.LocalizationEnum;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

public enum SkipReason implements LocalizationEnum {
    REMOVE_MODE_NONE("occupied, and remove mode None keeps existing blocks"),
    NOT_REPLACEABLE("occupied by a block that is not replaceable (remove mode Replaceable)"),
    CONFIGURATION_DIFFERS("the block is already there but configured differently, and reconfiguring it means "
            + "replacing it (remove mode All)"),
    CONFIGURATION_REFUSED("the block is already there, but its configuration could not be changed in place without "
            + "adding, removing or replacing what it holds"),
    TIER_CANNOT_REMOVE("this tier cannot remove blocks"),
    POLICY("unbreakable or protected block"),
    REMOVAL_REFUSED("its integration refuses to have it removed"),
    OBSTRUCTED("an obstructing block that cannot be swapped out of the way"),
    WORLD_CHANGED("the block changed since the operation was planned"),
    UNKNOWN_BLOCK("the planned block is not registered"),
    ORIENTATION("the block has no state for the rotated or mirrored orientation"),
    UNSUPPORTED_COST("the block cannot be paid for"),
    INTEGRATION_REJECTED("its integration could not install it, so it was taken away again and refunded"),
    COMPANION_MISSING("the other half that carries its cost is missing"),
    MISSING_ITEMS("missing items"),
    NOT_PERMITTED("you may not build there"),
    PROTECTED("a protection or claim refused the change"),
    UNLOADED("the chunk is not loaded"),
    OUT_OF_RANGE("out of range"),
    WORLD_REFUSED("the world refused the change");

    @Getter(onMethod_ = @Override)
    private final String englishText;

    SkipReason(String englishText) {
        this.englishText = englishText;
    }

    @Override
    public String getTranslationKey() {
        return "matter_manipulator.skip." + name().toLowerCase(Locale.ROOT);
    }

    public static Component describe(Map<SkipReason, Integer> counts) {
        return describe(counts, Map.of());
    }

    public static Component explained(Component detail) {
        return MMIntegrationText.SKIP_INTEGRATION_REJECTED_BECAUSE.text(detail);
    }

    public static Component describe(Map<SkipReason, Integer> counts, Map<Component, Integer> explained) {
        var details = Component.empty();
        for (var reason : values()) {
            var count = counts.get(reason);
            if (count == null) {
                continue;
            }
            if (!details.getSiblings().isEmpty()) {
                details.append("; ");
            }
            details.append(MMMessage.COPY_SKIPPED_ENTRY.text(count,
                    reason.text()));
        }
        explained.forEach((description, count) -> {
            if (!details.getSiblings().isEmpty()) {
                details.append("; ");
            }
            details.append(MMMessage.COPY_SKIPPED_ENTRY.text(count, description));
        });
        return details;
    }
}
