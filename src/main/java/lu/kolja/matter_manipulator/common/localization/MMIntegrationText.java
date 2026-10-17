package lu.kolja.matter_manipulator.common.localization;

import lombok.Getter;

public enum MMIntegrationText implements LocalizationEnum {
    SKIP_INTEGRATION_REJECTED_BECAUSE("matter_manipulator.skip.integration_rejected_because",
            "refused by its integration: %s"),
    BLOCK_COST_FRAMEDBLOCKS_FLUID_CAMO("matter_manipulator.block_cost.framedblocks.fluid_camo",
            "its fluid camo cannot be paid for with items"),
    BLOCK_COST_LITTLETILES_COPY("matter_manipulator.block_cost.littletiles.copy",
            "LittleTiles blocks can only be moved, not copied"),
    REFUSAL_INTEGRATION_FAILED("matter_manipulator.refusal.integration_failed",
            "its integration failed to decide (see the server log)"),
    REFUSAL_LITTLETILES_REMOVAL("matter_manipulator.refusal.littletiles.removal",
            "LittleTiles blocks can only be moved; removing them would destroy their materials"),
    REFUSAL_LITTLETILES_PARTIAL_STRUCTURE("matter_manipulator.refusal.littletiles.partial_structure",
            "select the whole LittleTiles structure"),
    BLOCK_COST_CB_MULTIPART_FIRST_PART("matter_manipulator.block_cost.cb_multipart.first_part",
            "its first part has no item to pay for it"),
    SKIP_CB_MULTIPART_FIRST_PART_UNREADABLE("matter_manipulator.skip.cb_multipart.first_part_unreadable",
            "its first part could not be loaded"),
    SKIP_CB_MULTIPART_FIRST_PART_OBSTRUCTED("matter_manipulator.skip.cb_multipart.first_part_obstructed",
            "an entity is in the way of its first part"),
    WARNING_CB_MULTIPART_UNMIRRORED("matter_manipulator.warning.cb_multipart.unmirrored",
            "%s gate(s) could not be mirrored exactly and were placed unmirrored, at: %s"),
    BLOCK_COST_PROJECTRED_NOT_A_WIRE("matter_manipulator.block_cost.projectred.not_a_wire",
            "This Project Red part can't be used in cable mode"),
    SKIP_PROJECTRED_NO_WIRE_SURFACE("matter_manipulator.skip.projectred.no_wire_surface", "no surface for the wire"),
    TRANSFORM_CB_MULTIPART_CANNOT_FACE("matter_manipulator.transform.cb_multipart.cannot_face",
            "its %s cannot face that way");

    @Getter(onMethod_ = @Override)
    private final String translationKey;
    @Getter(onMethod_ = @Override)
    private final String englishText;

    MMIntegrationText(String translationKey, String englishText) {
        this.translationKey = translationKey;
        this.englishText = englishText;
    }
}
