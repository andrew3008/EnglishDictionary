package space.br1440.platform.tracing.autoconfigure.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsReadModel;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.SafeExporterValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorCompositeValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorWatchdogValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing.ScrubbingValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

public final class TracingStatusAssembler {

    private final TraceOperations traceOperations;
    private final TracingRuntime tracingRuntime;
    private final TracingBootstrapSettings bootstrapSettings;
    private final PlatformTracingJmxClient jmxClient;
    private final AcceptedTracingSettingsReader acceptedSettingsReader;
    private final Supplier<TracingDeploymentDiagnostics> agentDiagnosticsSupplier;
    private final AgentReadinessCoherenceGuard coherenceGuard;

    public TracingStatusAssembler(TraceOperations traceOperations,
                                  TracingRuntime tracingRuntime,
                                  TracingBootstrapSettings bootstrapSettings,
                                  PlatformTracingJmxClient jmxClient,
                                  AcceptedTracingSettingsReader acceptedSettingsReader,
                                  TracingDeploymentDiagnostics startupDiagnostics,
                                  AgentReadinessCoherenceGuard coherenceGuard) {
        this(traceOperations,
                tracingRuntime,
                bootstrapSettings,
                jmxClient,
                acceptedSettingsReader,
                () -> startupDiagnostics,
                coherenceGuard);
    }

    TracingStatusAssembler(TraceOperations traceOperations,
                           TracingRuntime tracingRuntime,
                           TracingBootstrapSettings bootstrapSettings,
                           PlatformTracingJmxClient jmxClient,
                           AcceptedTracingSettingsReader acceptedSettingsReader,
                           Supplier<TracingDeploymentDiagnostics> agentDiagnosticsSupplier,
                           AgentReadinessCoherenceGuard coherenceGuard) {
        this.traceOperations = traceOperations;
        this.tracingRuntime = tracingRuntime;
        this.bootstrapSettings = bootstrapSettings;
        this.jmxClient = jmxClient;
        this.acceptedSettingsReader = acceptedSettingsReader;
        this.agentDiagnosticsSupplier = agentDiagnosticsSupplier;
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
    }

    public TracingStatusSnapshot assemble() {
        TracingDeploymentDiagnostics diagnostics = agentDiagnosticsSupplier.get();
        if (diagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            return assembleTrusted(diagnostics);
        }

        try {
            return coherenceGuard.readCoherently(() -> assembleTrusted(diagnostics));
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            return incoherentSnapshot(diagnostics, coherenceFailure.failureCode());
        }
    }

    private TracingStatusSnapshot assembleTrusted(TracingDeploymentDiagnostics diagnostics) {
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

    private TracingStatusSnapshot incoherentSnapshot(TracingDeploymentDiagnostics diagnostics,
                                                      String failureCode) {
        return new TracingStatusSnapshot(
                TracingStatusSnapshot.CURRENT_SCHEMA_VERSION,
                bootstrapSettings.tracingEnabled(),
                bootstrapSettings.authority().name(),
                agentSection(diagnostics, AgentRuntimeState.EXTENSION_INCOMPATIBLE, failureCode),
                Map.of(),
                TracingStatusSnapshot.SettingsStatus.absent(SettingsAbsenceReason.AGENT_UNAVAILABLE),
                List.of()
        );
    }

    private Map<String, Object> agentSection(TracingDeploymentDiagnostics diagnostics) {
        return agentSection(
                diagnostics,
                diagnostics.runtimeState(),
                diagnostics.agentReadiness().lifecycleState().failureCode()
        );
    }

    private Map<String, Object> agentSection(TracingDeploymentDiagnostics diagnostics,
                                             AgentRuntimeState runtimeState,
                                             String failureCode) {
        TracingDeploymentDiagnostics.AgentReadiness readiness = diagnostics.agentReadiness();
        TracingDeploymentDiagnostics.ContractIdentity contractIdentity = readiness.contractIdentity();
        TracingDeploymentDiagnostics.ReadinessAnchor readinessAnchor = readiness.readinessAnchor();
        TracingDeploymentDiagnostics.LifecycleState lifecycleState = readiness.lifecycleState();
        TracingDeploymentDiagnostics.DistributionIdentity distributionIdentity =
                readiness.distributionIdentity();
        TracingDeploymentDiagnostics.ClassloaderTopology classloaderTopology =
                readiness.classloaderTopology();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("state", runtimeState.name());
        agent.put("markerVisible", diagnostics.agentMarkerVisible());
        agent.put("readinessEndpointPresent", readiness.endpointPresent());
        agent.put("contractId", contractIdentity.contractId());
        agent.put("schemaDigest", contractIdentity.schemaDigest());
        agent.put("runtimeEpoch", readinessAnchor.runtimeEpoch());
        agent.put("evidenceGeneration", readinessAnchor.evidenceGeneration());
        agent.put("batchId", readinessAnchor.batchId());
        agent.put("lifecycle", lifecycleState.lifecycle());
        agent.put("failureCode", failureCode);
        agent.put("capabilities", lifecycleState.capabilities());
        agent.put("missingRequired", lifecycleState.missingRequired());
        agent.put("agentDistributionSha256", distributionIdentity.agentDistributionSha256());
        agent.put("upstreamAgentVersion", distributionIdentity.upstreamAgentVersion());
        agent.put("extensionVersion", distributionIdentity.extensionVersion());
        agent.put("extensionDefiningLoader", classloaderTopology.extensionDefiningLoader());
        agent.put("sdkDefiningLoader", classloaderTopology.sdkDefiningLoader());
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
        runtime.put("watchdog", watchdogSection());
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

    private Map<String, Object> watchdogSection() {
        Optional<ProcessorMetricsObservation> observation = jmxClient.processorMetrics().observation();
        Optional<ProcessorWatchdogValues> values = observation
                .flatMap(processorMetrics -> presentValues(processorMetrics.watchdog()));

        Map<String, Object> watchdog = new LinkedHashMap<>();
        // Возраст снимка идёт первым: без него счётчики ниже нельзя интерпретировать.
        watchdog.put("capture", captureSection(observation.map(ProcessorMetricsObservation::capture)));
        watchdog.put("present", values.isPresent());
        watchdog.put("forcedSpanCloses", values.map(ProcessorWatchdogValues::forcedSpanCloses).orElse(null));
        watchdog.put("forcedTraceCloses", values.map(ProcessorWatchdogValues::forcedTraceCloses).orElse(null));
        watchdog.put("activeSpanCount", values.map(ProcessorWatchdogValues::activeSpanCount).orElse(null));
        watchdog.put("activeTraceCount", values.map(ProcessorWatchdogValues::activeTraceCount).orElse(null));
        watchdog.put("trackedTraceCount", values.map(ProcessorWatchdogValues::trackedTraceCount).orElse(null));
        watchdog.put("trackedTraceCapacity", values.map(ProcessorWatchdogValues::trackedTraceCapacity).orElse(null));
        watchdog.put("trackingEvictedTotal", values.map(ProcessorWatchdogValues::trackingEvictedTotal).orElse(null));
        watchdog.put("trackedSpanCapacity", values.map(ProcessorWatchdogValues::trackedSpanCapacity).orElse(null));
        watchdog.put("trackedSpanPerTraceCapacity", values.map(ProcessorWatchdogValues::trackedSpanPerTraceCapacity).orElse(null));
        watchdog.put("spansRejectedPerTraceTotal", values.map(ProcessorWatchdogValues::spansRejectedPerTraceTotal).orElse(null));
        watchdog.put("spansRejectedGlobalTotal", values.map(ProcessorWatchdogValues::spansRejectedGlobalTotal).orElse(null));
        watchdog.put("spanLedgerInvariantViolationTotal", values.map(ProcessorWatchdogValues::spanLedgerInvariantViolationTotal).orElse(null));
        return watchdog;
    }

    private static Map<String, Object> captureSection(Optional<ObservationCapture> capture) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("agentInstanceId", capture.map(ObservationCapture::agentInstanceId).orElse(null));
        section.put("sequence", capture.map(ObservationCapture::sequence).orElse(null));
        section.put("startedAt", capture.map(ObservationCapture::startedAtEpochMilli).orElse(null));
        section.put("completedAt", capture.map(ObservationCapture::completedAtEpochMilli).orElse(null));
        return Collections.unmodifiableMap(section);
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

    private static Map<String, Long> ownerValuesOrEmpty(JmxOwnerState<Map<String, Long>> state) {
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
                .map(value -> TracingStatusSnapshot.SettingsStatus.available(value.acceptedRevision(), value.summary()))
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
