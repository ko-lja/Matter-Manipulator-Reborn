package lu.kolja.matter_manipulator.api.client;

/** How much an integration may spend on one preview block. */
public enum PreviewDetail {
    /** Used for small previews. May build a detached block entity to get the exact appearance. */
    FULL,
    /** Used for medium previews. Answer from the state, payload or source block only, without a block entity. */
    STATIC
}
