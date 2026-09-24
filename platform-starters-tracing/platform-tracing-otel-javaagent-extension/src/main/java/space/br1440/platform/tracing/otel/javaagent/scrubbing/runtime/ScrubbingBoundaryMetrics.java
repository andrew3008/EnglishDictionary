package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

public record ScrubbingBoundaryMetrics(
        long inputSpans,
        long outputSpans,
        long spansChanged,
        long spansDroppedFailClosed,
        long eventsScrubbed,
        long linksScrubbed,
        long spanNamesScrubbed,
        long exceptionEventsCanonicalized,
        long collapseFailures,
        long exporterInvocations,
        long hashDowngradedToMask,
        long truncateDowngradedToMask,
        long criticalFailures,
        long nonCriticalFailures,
        long skippedCircuitOpen,
        long surfacesCollapsedFailClosed,
        long batchPreparationFailures,
        long valueAttributesRemoved
) {
}
