package space.br1440.platform.tracing.autoconfigure.status;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorSpanTimeoutValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

import lombok.experimental.UtilityClass;

@UtilityClass
final class RuntimeStatusSections {

    static Map<String, Object> runtime(TraceOperations traceOperations,
                                       TracingRuntime tracingRuntime,
                                       PlatformTracingJmxClient jmx) {
        TracingState state = tracingRuntime.state();
        ActiveTraceContextView traceContext = traceOperations.traceContext();
        ProcessorMetricsObservation processorMetrics = jmx.processorMetrics().observation().orElse(null);
        ProcessorCompositeValues processorErrors = ownerValues(processorMetrics, ProcessorMetricsObservation::composite);

        return StatusSection.create()
                .value("implementation", traceOperations.getClass().getName())
                .value("mode", state.mode().name())
                .value("reason", state.reason().orElse(null))
                .value("currentTraceId", traceContext.traceId().orElse(null))
                .value("currentSpanId", traceContext.spanId().orElse(null))
                .value("domainMBeansAvailable", jmx.registrationSnapshot().allRegistered())
                .value("sampling", sampling(jmx))
                .value("validation", validation(jmx))
                .value("scrubbing", scrubbing(jmx))
                .value("export", export(jmx))
                .value("spanTimeout", spanTimeout(processorMetrics))
                .value("processorErrorsTotal", processorErrors == null ? null : processorErrors.errorsTotal())
                .value("processorErrorsByName", processorErrors == null ? Map.of() : processorErrors.errorsByName())
                .build();
    }

    static List<Map<String, Object>> jmxContractDiagnostics(PlatformTracingJmxClient jmx) {
        return jmx.jmxContractDiagnostics().stream()
                .map(RuntimeStatusSections::diagnosticPoint)
                .toList();
    }

    private static Map<String, Object> sampling(PlatformTracingJmxClient jmx) {
        SamplingObservation sampling = jmx.sampling().observation().orElse(null);
        Map<String, Long> decisions = ownerValues(sampling, SamplingObservation::decisionCounts);
        DiagnosticsObservation diagnostics = jmx.diagnostics().observation().orElse(null);

        return StatusSection.from(sampling)
                .field("enabled", SamplingObservation::samplerEnabled)
                .field("ratio", SamplingObservation::samplingRatio)
                .field("routeRatioCount", SamplingObservation::routeRatioCount)
                .field("configSource", SamplingObservation::configLastUpdatedSource)
                .value("invalidConfigCount", diagnostics == null ? null : diagnostics.invalidConfigCount())
                .value("decisions", decisions == null ? Map.of() : decisions)
                .build();
    }

    private static Map<String, Object> validation(PlatformTracingJmxClient jmx) {
        ValidationValues validation = ownerValues(
                jmx.validation().observation().orElse(null),
                ValidationObservation::owner
        );

        return StatusSection.from(validation)
                .field("level", ValidationValues::validationLevel)
                .field("violationsTotal", ValidationValues::violationsTotal)
                .field("configVersion", ValidationValues::configVersion)
                .field("configSource", ValidationValues::configLastUpdatedSource)
                .build();
    }

    private static Map<String, Object> scrubbing(PlatformTracingJmxClient jmx) {
        ScrubbingValues scrubbing = ownerValues(
                jmx.scrubbing().observation().orElse(null),
                ScrubbingObservation::owner
        );

        return StatusSection.from(scrubbing)
                .field("enabled", ScrubbingValues::enabled)
                .field("policyVersion", ScrubbingValues::policyVersion)
                .field("ruleCount", ScrubbingValues::rulesLoaded)
                .build();
    }

    private static Map<String, Object> export(PlatformTracingJmxClient jmx) {
        ExportObservation export = jmx.export().observation().orElse(null);
        ExportProcessorValues processor = ownerValues(export, ExportObservation::processor);
        SafeExporterValues exporter = ownerValues(export, ExportObservation::exporter);

        return StatusSection.from(processor)
                .field("queueCapacity", ExportProcessorValues::queueCapacity)
                .field("queueSize", ExportProcessorValues::queueSize)
                .field("queueUtilization", RuntimeStatusSections::queueUtilization)
                .field("lifecycleState", value -> value.lifecycleState().name())
                .field("droppedOverflowTotal", ExportProcessorValues::droppedOverflowTotal)
                .field("droppedAfterShutdownTotal", ExportProcessorValues::droppedAfterShutdownTotal)
                .field("droppedAfterFailureTotal", ExportProcessorValues::droppedAfterFailureTotal)
                .field("discardedQueuedTotal", ExportProcessorValues::discardedQueuedTotal)
                .field("failuresTotal", ExportProcessorValues::failuresTotal)
                .field("shutdownFailuresTotal", ExportProcessorValues::shutdownFailuresTotal)
                .field("timeoutsTotal", ExportProcessorValues::timeoutsTotal)
                .value("safeExporter", exporter == null ? Map.of() : exporter.metrics())
                .build();
    }

    private static double queueUtilization(ExportProcessorValues processor) {
        return (processor.queueCapacity() > 0) ? (double) processor.queueSize() / processor.queueCapacity() : 0.0;
    }

    private static Map<String, Object> spanTimeout(ProcessorMetricsObservation processorMetrics) {
        ProcessorSpanTimeoutValues spanTimeout = ownerValues(processorMetrics, ProcessorMetricsObservation::spanTimeout);
        ObservationCapture capture = (processorMetrics == null) ? null : processorMetrics.capture();

        return StatusSection.from(spanTimeout)
                .value("capture", capture(capture))
                .value("present", spanTimeout != null)

                .field("forcedSpanEndAttemptsTotal", value -> value.forceEnd().forcedSpanEndAttemptsTotal())
                .field("forcedTraceEndAttemptsTotal", value -> value.forceEnd().forcedTraceEndAttemptsTotal())
                .field("forceEndFailuresTotal", value -> value.forceEnd().forceEndFailuresTotal())

                .field("activeSpanCount", value -> value.tracking().activeSpanCount())
                .field("activeTraceCount", value -> value.tracking().activeTraceCount())
                .field("trackedTraceCount", value -> value.tracking().trackedTraceCount())
                .field("trackedTraceCapacity", value -> value.tracking().trackedTraceCapacity())
                .field("trackingEvictedTotal", value -> value.tracking().trackingEvictedTotal())
                .field("trackedSpanCapacity", value -> value.tracking().trackedSpanCapacity())
                .field("trackedSpanPerTraceCapacity", value -> value.tracking().trackedSpanPerTraceCapacity())
                .field("spanLedgerInvariantViolationTotal", value -> value.tracking().spanLedgerInvariantViolationTotal())

                .field("spansRejectedPerTraceTotal", value -> value.rejections().spansRejectedPerTraceTotal())
                .field("spansRejectedGlobalTotal", value -> value.rejections().spansRejectedGlobalTotal())
                .field("spansRejectedTraceCapacityTotal", value -> value.rejections().spansRejectedTraceCapacityTotal())

                .field("scanFailuresTotal", value -> value.scanner().scanFailuresTotal())
                .field("lastSuccessfulBackgroundScanEpochMillis", value -> value.scanner().lastSuccessfulBackgroundScanEpochMillis())
                .field("scannerState", value -> value.scanner().scannerState())
                .build();
    }

    private static Map<String, Object> capture(ObservationCapture capture) {
        return StatusSection.from(capture)
                .field("agentInstanceId", ObservationCapture::agentInstanceId)
                .field("sequence", ObservationCapture::sequence)
                .field("startedAtEpochMillis", ObservationCapture::startedAtEpochMilli)
                .field("completedAtEpochMillis", ObservationCapture::completedAtEpochMilli)
                .build();
    }

    private static Map<String, Object> diagnosticPoint(JmxDiagnosticSnapshot snapshot) {
        return StatusSection.create()
                .value("point", snapshot.point().name())
                .value("category", snapshot.category().name())
                .value("kind", snapshot.kind().name())
                .value("lifetimeCount", snapshot.lifetimeCount())
                .value("suppressedWarnCount", snapshot.suppressedWarnCount())
                .value("firstSeenAt", snapshot.firstSeenAt())
                .value("lastSeenAt", snapshot.lastSeenAt())
                .value("lastAttemptAt", snapshot.lastAttemptAt())
                .value("unrecovered", snapshot.unrecovered())
                .build();
    }

    private static <O, T> T ownerValues(O observation, Function<O, JmxOwnerState<T>> owner) {
        return (observation != null) && owner.apply(observation) instanceof JmxOwnerState.Present<T> present
                ? present.values()
                : null;
    }
}
