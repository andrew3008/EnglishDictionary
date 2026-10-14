package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticCategory;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticKind;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

class RuntimeStatusSectionsTest {

    @Test
    void exportAndDiagnosticKeyNamesAreExact() {
        ExportProcessorValues processor = new ExportProcessorValues(
                10, 2, 3L, 4L, 5L, 6L,
                ExportProcessorValues.LifecycleState.ACTIVE,
                7L, 8L, 9L);
        ExportObservation observation = new ExportObservation(
                "agent",
                new JmxOwnerState.Present<>(processor, 1L),
                new JmxOwnerState.Absent<>(0L));
        Instant seen = Instant.parse("2026-01-01T00:00:00Z");
        JmxDiagnosticSnapshot diagnostic = new JmxDiagnosticSnapshot(
                JmxContractPoint.EXPORT_OBSERVATION,
                JmxDiagnosticCategory.CONTRACT_VIOLATION,
                JmxDiagnosticKind.NULL_VALUE_FOR_REQUIRED_MEMBER,
                1L,
                0L,
                seen,
                seen,
                seen,
                false);

        Map<String, Object> presentExport = exportSection(observation, List.of());
        Map<String, Object> absentExport = exportSection(null, List.of(diagnostic));
        List<Map<String, Object>> diagnostics = RuntimeStatusSections.jmxContractDiagnostics(
                client(null, List.of(diagnostic)));

        assertThat(presentExport).containsOnlyKeys(
                "queueCapacity",
                "queueSize",
                "queueUtilization",
                "lifecycleState",
                "droppedOverflowTotal",
                "droppedAfterShutdownTotal",
                "droppedAfterFailureTotal",
                "discardedQueuedTotal",
                "failuresTotal",
                "shutdownFailuresTotal",
                "timeoutsTotal",
                "safeExporter");
        assertThat(absentExport).containsOnlyKeys(presentExport.keySet().toArray(String[]::new));
        assertThat(absentExport).containsEntry("failuresTotal", null);
        assertThat(diagnostics).singleElement().satisfies(point -> assertThat(point).containsOnlyKeys(
                "point",
                "category",
                "kind",
                "lifetimeCount",
                "suppressedWarnCount",
                "firstSeenAt",
                "lastSeenAt",
                "lastAttemptAt",
                "unrecovered"));
    }

    @Test
    void absentSpanTimeoutKeepsFullKeySet() {
        @SuppressWarnings("unchecked")
        Map<String, Object> spanTimeout = (Map<String, Object>) RuntimeStatusSections.runtime(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                client(null, List.of())).get("spanTimeout");

        assertThat(spanTimeout).containsOnlyKeys(
                "capture",
                "present",
                "forcedSpanEndAttemptsTotal",
                "forcedTraceEndAttemptsTotal",
                "forceEndFailuresTotal",
                "activeSpanCount",
                "activeTraceCount",
                "trackedTraceCount",
                "trackedTraceCapacity",
                "trackingEvictedTotal",
                "trackedSpanCapacity",
                "trackedSpanPerTraceCapacity",
                "spanLedgerInvariantViolationTotal",
                "spansRejectedPerTraceTotal",
                "spansRejectedGlobalTotal",
                "spansRejectedTraceCapacityTotal",
                "scanFailuresTotal",
                "lastSuccessfulBackgroundScanEpochMillis",
                "scannerState");
        assertThat(spanTimeout).containsEntry("present", false).containsEntry("scannerState", null);
    }

    private static Map<String, Object> exportSection(ExportObservation observation,
                                                     List<JmxDiagnosticSnapshot> diagnostics) {
        @SuppressWarnings("unchecked")
        Map<String, Object> export = (Map<String, Object>) RuntimeStatusSections.runtime(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                client(observation, diagnostics)).get("export");
        return export;
    }

    private static PlatformTracingJmxClient client(ExportObservation observation,
                                                   List<JmxDiagnosticSnapshot> diagnostics) {
        PlatformTracingJmxClient jmx = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(jmx.sampling().observation()).thenReturn(Optional.empty());
        when(jmx.validation().observation()).thenReturn(Optional.empty());
        when(jmx.scrubbing().observation()).thenReturn(Optional.empty());
        when(jmx.processorMetrics().observation()).thenReturn(Optional.empty());
        when(jmx.diagnostics().observation()).thenReturn(Optional.empty());
        when(jmx.registrationSnapshot().allRegistered()).thenReturn(false);
        when(jmx.export().observation()).thenReturn(Optional.ofNullable(observation));
        when(jmx.jmxContractDiagnostics()).thenReturn(diagnostics);
        return jmx;
    }
}
