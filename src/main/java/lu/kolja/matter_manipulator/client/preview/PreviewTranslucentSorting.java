package lu.kolja.matter_manipulator.client.preview;

enum PreviewTranslucentSorting {
    COARSE;

    static final int MAX_RESORTS_PER_FRAME = 2;

    boolean sortAtCompile() {
        return true;
    }

    boolean shouldResort(double movedSqr, double distanceSqr) {
        return movedSqr > Math.max(4.0, distanceSqr * 0.0225);
    }
}
