package space.br1440.platform.tracing.autoconfigure.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

public final class TracingStatusAssembler {

    private final TraceOperations traceOperations;
    private final TracingRuntime tracingRuntime;
    private final TracingBootstrapSettings bootstrapSettings;
    private final PlatformTracingJmxClient jmxClient;
    private final AcceptedTracingSettingsReader acceptedSettingsReader;
    private final Supplier<TracingDeploymentDiagnostics> agentDiagnosticsSupplier;

    public TracingStatusAssembler(TraceOperations traceOperations,
                                  TracingRuntime tracingRuntime,
                                  TracingBootstrapSettings bootstrapSettings,
                                  PlatformTracingJmxClient jmxClient,
                                  AcceptedTracingSettingsReader acceptedSettingsReader,
                                  TracingDeploymentDiagnostics startupDiagnostics) {
        this(traceOperations,
                tracingRuntime,
                bootstrapSettings,
                jmxClient,
                acceptedSettingsReader,
                () -> startupDiagnostics);
    }

    TracingStatusAssembler(TraceOperations traceOperations,
                           TracingRuntime tracingRuntime,
                           TracingBootstrapSettings bootstrapSettings,
                           PlatformTracingJmxClient jmxClient,
                           AcceptedTracingSettingsReader acceptedSettingsReader,
                           Supplier<TracingDeploymentDiagnostics> agentDiagnosticsSupplier) {
        this.traceOperations = traceOperations;
        this.tracingRuntime = tracingRuntime;
        this.bootstrapSettings = bootstrapSettings;
        this.jmxClient = jmxClient;
        this.acceptedSettingsReader = acceptedSettingsReader;
        this.agentDiagnosticsSupplier = agentDiagnosticsSupplier;
    }

    public TracingStatusSnapshot assemble() {
        TracingDeploymentDiagnostics diagnostics = agentDiagnosticsSupplier.get();
        return new TracingStatusSnapshot(
                TracingStatusSnapshot.CURRENT_SCHEMA_VERSION,
                bootstrapSettings.tracingEnabled(),
                bootstrapSettings.authority().name(),
                agentSection(diagnostics),
                runtimeSection(),
                settingsStatus(diagnostics),
                jmxContractDiagnosticsSection()
        );
    }

    private Map<String, Object> agentSection(TracingDeploymentDiagnostics diagnostics) {
        TracingDeploymentDiagnostics.AgentReadiness readiness = diagnostics.agentReadiness();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("state", diagnostics.runtimeState().name());
        agent.put("markerVisible", diagnostics.agentMarkerVisible());
        agent.put("readinessEndpointPresent", readiness.endpointPresent());
        agent.put("extensionVersion", readiness.extensionVersion());
        agent.put("protocolVersion", readiness.protocolVersion());
        agent.put("lifecycle", readiness.lifecycle());
        agent.put("failureCode", readiness.failureCode());
        agent.put("capabilities", readiness.capabilities());
        return agent;
    }

    private Map<String, Object> runtimeSection() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("implementation", traceOperations.getClass().getName());
        runtime.put("mode", tracingRuntime.state().mode().name());
        runtime.put("reason", tracingRuntime.state().reason().orElse(null));
        runtime.put("currentTraceId", traceOperations.traceContext().traceId().orElse(null));
        runtime.put("currentSpanId", traceOperations.traceContext().spanId().orElse(null));
        runtime.put("domainMBeansAvailable", jmxClient.registrationSnapshot().allRegistered());
        runtime.put("sampling", samplingSection());
        runtime.put("validation", validationSection());
        runtime.put("scrubbing", scrubbingSection());
        runtime.put("export", exportSection());
        runtime.put("processorErrorsTotal", processorErrorsTotal());
        runtime.put("processorErrorsByName", processorErrorsByName());
        return runtime;
    }

    private Map<String, Object> samplingSection() {
        Optional<SamplingObservation> observation = jmxClient.sampling().observation();

        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("enabled", observation.map(SamplingObservation::samplerEnabled).orElse(null));
        sampling.put("ratio", observation.map(SamplingObservation::samplingRatio).orElse(null));
        sampling.put("routeRatioCount", observation.map(SamplingObservation::liveRouteRatioCount).orElse(null));
        sampling.put("configVersion", null);
        sampling.put("configSource", observation.map(SamplingObservation::configLastUpdatedSource).orElse(null));
        sampling.put("invalidConfigCount", jmxClient.diagnostics().observation()
                .map(DiagnosticsObservation::invalidConfigCount)
                .orElse(null));
        sampling.put("decisions", observation.map(o -> ownerValuesOrEmpty(o.decisionCounts())).orElseGet(Map::of));
        return sampling;
    }

    private Map<String, Object> validationSection() {
        Optional<ValidationValues> values = jmxClient.validation().observation()
                .flatMap(observation -> presentValues(observation.owner()));

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("enabled", values.map(ValidationValues::enabled).orElse(null));
        validation.put("strict", values.map(ValidationValues::strict).orElse(null));
        validation.put("strictRuntimeAllowed", values.map(ValidationValues::strictRuntimeAllowed).orElse(null));
        validation.put("violationsTotal", values.map(ValidationValues::violationsTotal).orElse(null));
        validation.put("configVersion", values.map(ValidationValues::configVersion).orElse(null));
        validation.put("configSource", values.map(ValidationValues::configLastUpdatedSource).orElse(null));
        return validation;
    }

    private Map<String, Object> scrubbingSection() {
        Optional<ScrubbingValues> values = jmxClient.scrubbing().observation()
                .flatMap(observation -> presentValues(observation.owner()));

        Map<String, Object> scrubbing = new LinkedHashMap<>();
        scrubbing.put("enabled", values.map(ScrubbingValues::enabled).orElse(null));
        scrubbing.put("configVersion", values.map(ScrubbingValues::policyVersion).orElse(null));
        scrubbing.put("configSource", null);
        scrubbing.put("ruleCount", values.map(ScrubbingValues::rulesLoaded).orElse(null));
        return scrubbing;
    }

    private Map<String, Object> exportSection() {
        return jmxClient.export().observation().map(observation -> {
            Optional<ExportProcessorValues> processor = presentValues(observation.processor());
            Optional<SafeExporterValues> exporter = presentValues(observation.exporter());

            Map<String, Object> export = new LinkedHashMap<>();
            export.put("queueCapacity", processor.map(ExportProcessorValues::queueCapacity).orElse(null));
            export.put("queueSize", processor.map(ExportProcessorValues::queueSize).orElse(null));
            export.put("queueUtilization", processor
                    .map(p -> p.queueCapacity() > 0 ? (double) p.queueSize() / p.queueCapacity() : 0.0)
                    .orElse(null));

            export.put("lifecycleState", processor.map(p -> p.lifecycleState().name()).orElse(null));
            export.put("droppedOverflow", processor.map(ExportProcessorValues::droppedOverflowTotal).orElse(null));
            export.put("droppedAfterShutdown", processor.map(ExportProcessorValues::droppedAfterShutdownTotal).orElse(null));
            export.put("droppedAfterFailure", processor.map(ExportProcessorValues::droppedAfterFailureTotal).orElse(null));
            export.put("discardedQueued", processor.map(ExportProcessorValues::discardedQueuedTotal).orElse(null));
            export.put("failures", processor.map(ExportProcessorValues::failuresTotal).orElse(null));
            export.put("shutdownFailures", processor.map(ExportProcessorValues::shutdownFailuresTotal).orElse(null));
            export.put("timeouts", processor.map(ExportProcessorValues::timeoutsTotal).orElse(null));
            export.put("safeExporter", exporter.map(SafeExporterValues::metrics).orElseGet(Map::of));
            return export;
        }).orElseGet(Map::of);
    }

    private Optional<Long> processorErrorsTotal() {
        return jmxClient.processorMetrics().observation()
                .flatMap(o -> presentValues(o.composite()))
                .map(ProcessorCompositeValues::errorsTotal);
    }

    private Map<String, Long> processorErrorsByName() {
        return jmxClient.processorMetrics().observation()
                .flatMap(o -> presentValues(o.composite()))
                .map(ProcessorCompositeValues::errorsByName)
                .orElseGet(Map::of);
    }

    private static <T> Optional<T> presentValues(JmxOwnerState<T> state) {
        return state instanceof JmxOwnerState.Present<T> present ? Optional.of(present.values()) : Optional.empty();
    }

    private static <T> Map<String, Long> ownerValuesOrEmpty(JmxOwnerState<Map<String, Long>> state) {
        return presentValues(state).orElseGet(Map::of);
    }

    private List<Map<String, Object>> jmxContractDiagnosticsSection() {
        List<JmxDiagnosticSnapshot> snapshots = jmxClient.jmxContractDiagnostics();
        List<Map<String, Object>> points = new ArrayList<>(snapshots.size());
        for (JmxDiagnosticSnapshot snapshot : snapshots) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("point", snapshot.point().name());
            point.put("category", snapshot.category().name());
            point.put("kind", snapshot.kind().name());
            point.put("lifetimeCount", snapshot.lifetimeCount());
            point.put("suppressedWarnCount", snapshot.suppressedWarnCount());
            point.put("firstSeen", snapshot.firstSeenAt());
            point.put("lastSeen", snapshot.lastSeenAt());
            point.put("lastAttemptAt", snapshot.lastAttemptAt());
            point.put("unrecovered", snapshot.unrecovered());
            points.add(Collections.unmodifiableMap(point));
        }
        return Collections.unmodifiableList(points);
    }

    private TracingStatusSnapshot.SettingsStatus settingsStatus(TracingDeploymentDiagnostics diagnostics) {
        SettingsAbsenceReason absentReason = absentSettingsReason(diagnostics.runtimeState());
        if (absentReason != null) {
            return TracingStatusSnapshot.SettingsStatus.absent(absentReason);
        }

        Optional<AcceptedSettingsReadModel> readModel = (acceptedSettingsReader == null)
                ? Optional.empty()
                : acceptedSettingsReader.currentReadModel();
        return readModel
                .map(value -> TracingStatusSnapshot.SettingsStatus.available(value.agentReadiness(), value.leaves()))
                .orElseGet(() -> TracingStatusSnapshot.SettingsStatus.absent(SettingsAbsenceReason.SETTINGS_NOT_ACCEPTED));
    }

    private SettingsAbsenceReason absentSettingsReason(AgentRuntimeState runtimeState) {
        if (!bootstrapSettings.tracingEnabled()) {
            return SettingsAbsenceReason.TRACING_DISABLED;
        }

        if (bootstrapSettings.authority() == TracingConfigurationAuthority.HELM) {
            return SettingsAbsenceReason.AUTHORITY_HAS_NO_ACCEPTED_SETTINGS;
        }

        if (runtimeState != AgentRuntimeState.AGENT_READY) {
            return SettingsAbsenceReason.AGENT_UNAVAILABLE;
        }

        return null;
    }
}
