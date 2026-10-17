package lu.kolja.matter_manipulator.client.preview;

import lu.kolja.matter_manipulator.MatterManipulator;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

public final class PreviewDebugStats {
    private static final boolean ENABLED = Boolean.getBoolean("matter_manipulator.previewStats");
    private static final boolean FLOW_MARKERS_ENABLED = Boolean.parseBoolean(
            System.getProperty("matter_manipulator.previewFlowMarkers", "true")
    );
    private static final long REPORT_INTERVAL_FRAMES = 600;
    private static final ThreadMXBean THREADS =
            ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threads
                    && threads.isThreadAllocatedMemorySupported() ? threads : null;

    private static long frames;
    private static long frameNanos;
    private static long frameMaxNanos;
    private static long frameAllocatedBytes;
    private static long frameStart;
    private static long frameAllocationStart;
    private static long inspections;
    private static long inspectionNanos;
    private static long meshCompiles;
    private static long compileNanos;
    private static long uploadNanos;
    private static long meshUploads;
    private static long modelCalls;
    private static long translucentResorts;
    private static long translucentOrderUploads;
    private static long translucentQuadsResorted;
    private static long resortNanos;
    private static long modelDataBuilds;
    private static long ae2ModelDataBuilds;
    private static long gtceuModelDataBuilds;
    private static long fakeBlockEntities;
    private static long statusPasses;
    private static long statusNanos;
    private static long topologyRecomputations;
    private static long flowMarkerFrames;
    private static long flowMarkers;
    private static long flowMarkerNanos;

    private PreviewDebugStats() {}

    public static boolean enabled() {
        return ENABLED;
    }

    static boolean flowMarkersEnabled() {
        return !ENABLED || FLOW_MARKERS_ENABLED;
    }

    static long now() {
        return ENABLED ? System.nanoTime() : 0;
    }

    static void frameStarted() {
        if (!ENABLED) {
            return;
        }
        frameAllocationStart = allocatedBytes();
        frameStart = System.nanoTime();
    }

    static void frameRendered() {
        if (!ENABLED || frameStart == 0) {
            return;
        }
        long elapsed = System.nanoTime() - frameStart;
        frameStart = 0;
        frames++;
        frameNanos += elapsed;
        frameMaxNanos = Math.max(frameMaxNanos, elapsed);
        frameAllocatedBytes += Math.max(0, allocatedBytes() - frameAllocationStart);
        if (frames % REPORT_INTERVAL_FRAMES == 0) {
            logSummary("Preview steady-state");
        }
    }

    private static long allocatedBytes() {
        return THREADS == null ? 0 : THREADS.getCurrentThreadAllocatedBytes();
    }

    static void inspected(long startNanos) {
        if (ENABLED) {
            inspections++;
            inspectionNanos += System.nanoTime() - startNanos;
        }
    }

    static void meshCompiled(int blocks, int surfaceBlocks, int vertices, int buffers, int dynamic,
            long compiled, long uploaded) {
        if (!ENABLED) {
            return;
        }
        meshCompiles++;
        compileNanos += compiled;
        uploadNanos += uploaded;
        MatterManipulator.LOGGER.debug(
                "Preview compile #{}: blocks={}, surface={}, vertices={}, buffers={}, dynamic={}, compile={}ms, "
                        + "upload={}ms, ownedBuffers={}",
                meshCompiles, blocks, surfaceBlocks, vertices, buffers, dynamic, compiled / 1_000_000.0,
                uploaded / 1_000_000.0, PreviewMesh.liveBuffers());
    }

    static void meshUploaded(boolean translucentOrderUpload) {
        if (!ENABLED) {
            return;
        }
        meshUploads++;
        if (translucentOrderUpload) {
            translucentOrderUploads++;
        }
    }

    static void modelRendered() {
        if (ENABLED) {
            modelCalls++;
        }
    }

    static void translucentResorted(int quads, long elapsedNanos) {
        if (!ENABLED) {
            return;
        }
        translucentResorts++;
        translucentOrderUploads++;
        translucentQuadsResorted += quads;
        resortNanos += elapsedNanos;
    }

    public static void modelDataBuilt() {
        if (ENABLED) {
            modelDataBuilds++;
        }
    }

    public static void ae2ModelDataBuilt() {
        if (ENABLED) {
            ae2ModelDataBuilds++;
        }
    }

    public static void gtceuModelDataBuilt() {
        if (ENABLED) {
            gtceuModelDataBuilds++;
        }
    }

    public static void fakeBlockEntityCreated() {
        if (ENABLED) {
            fakeBlockEntities++;
        }
    }

    static void statusEvaluated(long startNanos) {
        if (ENABLED) {
            statusPasses++;
            statusNanos += System.nanoTime() - startNanos;
        }
    }

    static void topologyRecomputed() {
        if (ENABLED) {
            topologyRecomputations++;
        }
    }

    static void cableFlowRendered(int markers, long elapsedNanos) {
        if (!ENABLED) {
            return;
        }
        flowMarkerFrames++;
        flowMarkers += markers;
        flowMarkerNanos += elapsedNanos;
    }

    public static Snapshot snapshot() {
        return new Snapshot(frames, frameNanos, frameMaxNanos, frameAllocatedBytes, inspections, inspectionNanos,
                meshCompiles, compileNanos, uploadNanos, meshUploads, modelCalls, modelDataBuilds, fakeBlockEntities,
                statusPasses, statusNanos, translucentResorts, PreviewMesh.liveBuffers());
    }

    public static void resetFrameMaximum() {
        frameMaxNanos = 0;
    }

    public record Snapshot(long frames, long frameNanos, long frameMaxNanos, long frameAllocatedBytes,
            long inspections, long inspectionNanos, long meshCompiles, long compileNanos, long uploadNanos,
            long meshUploads, long modelCalls, long modelDataBuilds, long fakeBlockEntities, long statusPasses,
            long statusNanos, long translucentResorts, int liveBuffers) {
        public Snapshot minus(Snapshot before) {
            return new Snapshot(frames - before.frames, frameNanos - before.frameNanos, frameMaxNanos,
                    frameAllocatedBytes - before.frameAllocatedBytes, inspections - before.inspections,
                    inspectionNanos - before.inspectionNanos, meshCompiles - before.meshCompiles,
                    compileNanos - before.compileNanos, uploadNanos - before.uploadNanos,
                    meshUploads - before.meshUploads, modelCalls - before.modelCalls,
                    modelDataBuilds - before.modelDataBuilds, fakeBlockEntities - before.fakeBlockEntities,
                    statusPasses - before.statusPasses, statusNanos - before.statusNanos,
                    translucentResorts - before.translucentResorts, liveBuffers);
        }
    }

    static void finishSession() {
        if (!ENABLED || !hasMeasurements()) {
            return;
        }
        logSummary("Preview final");
        MatterManipulator.LOGGER.info("Preview cleared; GPU buffers still owned: {}, staging buffers: {}",
                PreviewMesh.liveBuffers(), PreviewMesh.stagingBuffers());
    }

    private static boolean hasMeasurements() {
        return frames != 0 || meshCompiles != 0 || modelDataBuilds != 0 || topologyRecomputations != 0;
    }

    private static void logSummary(String label) {
        MatterManipulator.LOGGER.info(
                "{}: frames={}, frameAverage={}ms, frameMax={}ms, allocated={}KiB/frame, inspections={} ({}ms), "
                        + "meshCompiles={} ({}ms, upload {}ms), meshUploads={}, modelCalls={}, translucentResorts={}, "
                        + "orderUploads={}, quadsResorted={}, resortTotal={}ms, modelDataBuilds={} (AE2={}, GTCEu={}), "
                        + "fakeBlockEntities={}, statusPasses={} ({}ms), topologyRecomputations={}, "
                        + "flowMarkerFrames={}, flowMarkers={}, flowTotal={}ms",
                label,
                frames,
                averageMillis(frameNanos, frames),
                frameMaxNanos / 1_000_000.0,
                frames == 0 ? 0 : frameAllocatedBytes / 1024 / frames,
                inspections,
                inspectionNanos / 1_000_000.0,
                meshCompiles,
                compileNanos / 1_000_000.0,
                uploadNanos / 1_000_000.0,
                meshUploads,
                modelCalls,
                translucentResorts,
                translucentOrderUploads,
                translucentQuadsResorted,
                resortNanos / 1_000_000.0,
                modelDataBuilds,
                ae2ModelDataBuilds,
                gtceuModelDataBuilds,
                fakeBlockEntities,
                statusPasses,
                statusNanos / 1_000_000.0,
                topologyRecomputations,
                flowMarkerFrames,
                flowMarkers,
                flowMarkerNanos / 1_000_000.0
        );
    }

    private static double averageMillis(long nanos, long count) {
        return count == 0 ? 0.0 : nanos / 1_000_000.0 / count;
    }
}
