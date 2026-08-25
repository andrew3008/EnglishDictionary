package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export;

public record ExportProcessorValues(int queueCapacity,
                                    int queueSize,
                                    long droppedOverflowTotal,
                                    long droppedAfterShutdownTotal,
                                    long failuresTotal,
                                    long timeoutsTotal,
                                    LifecycleState lifecycleState,
                                    long shutdownFailuresTotal,
                                    long droppedAfterFailureTotal,
                                    long discardedQueuedTotal) {

    /**
     * Decoded-представление wire item {@code lifecycleState} (см.
     * ADR-drop-oldest-export-processor-v2 §6). Не является отдельным public top-level типом.
     */
    public enum LifecycleState {
        ACTIVE,
        SHUTTING_DOWN,
        TERMINATED,
        FAILED
    }
}
