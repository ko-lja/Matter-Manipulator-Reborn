package lu.kolja.matter_manipulator.api;

public enum PlacementPurpose {
    STANDARD,
    COPY_TEMPLATE,
    MOVE_TRANSFER,
    /**
     * The block was already there and nothing was paid for, so only change settings and never install or remove
     * owned contents. For anything else, return {@code REJECTED_UNCHANGED} before mutating.
     */
    RECONFIGURE;

    public boolean consumesItems() {
        return this == STANDARD || this == COPY_TEMPLATE;
    }

    public boolean preparesIntegrations() {
        return this == STANDARD || this == COPY_TEMPLATE;
    }
}
