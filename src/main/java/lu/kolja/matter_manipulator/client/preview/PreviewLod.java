package lu.kolja.matter_manipulator.client.preview;

enum PreviewLod {
    FULL,
    STATIC,
    SIMPLE,
    MASSIVE;

    static final int FULL_LIMIT = 2_000;
    static final int STATIC_LIMIT = 10_000;
    static final int SIMPLE_LIMIT = 50_000;

    static PreviewLod forBlocks(long blocks) {
        if (blocks <= FULL_LIMIT) {
            return FULL;
        }
        if (blocks <= STATIC_LIMIT) {
            return STATIC;
        }
        return blocks <= SIMPLE_LIMIT ? SIMPLE : MASSIVE;
    }

    boolean usesModels() {
        return this == FULL || this == STATIC;
    }
}
