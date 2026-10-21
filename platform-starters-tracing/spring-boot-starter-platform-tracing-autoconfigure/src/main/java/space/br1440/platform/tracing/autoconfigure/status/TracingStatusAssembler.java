package space.br1440.platform.tracing.autoconfigure.status;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessSnapshot;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

public final class TracingStatusAssembler {

    private final TraceOperations traceOperations;
    private final TracingRuntime tracingRuntime;
    private final TracingBootstrapSettings bootstrapSettings;
    private final PlatformTracingJmxClient jmxClient;
    private final TracingDeploymentDiagnostics diagnostics;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final SettingsStatusResolver settingsResolver;

    public TracingStatusAssembler(TraceOperations traceOperations,
                                  TracingRuntime tracingRuntime,
                                  TracingBootstrapSettings bootstrapSettings,
                                  PlatformTracingJmxClient jmxClient,
                                  AcceptedSettingsStatusReader acceptedSettingsReader,
                                  TracingDeploymentDiagnostics diagnostics,
                                  AgentReadinessCoherenceGuard coherenceGuard) {
        this.traceOperations = requireNonNull(traceOperations, "traceOperations");
        this.tracingRuntime = requireNonNull(tracingRuntime, "tracingRuntime");
        this.bootstrapSettings = requireNonNull(bootstrapSettings, "bootstrapSettings");
        this.jmxClient = requireNonNull(jmxClient, "jmxClient");
        this.diagnostics = requireNonNull(diagnostics, "diagnostics");
        this.coherenceGuard = requireNonNull(coherenceGuard, "coherenceGuard");
        this.settingsResolver = new SettingsStatusResolver(
                requireNonNull(acceptedSettingsReader, "acceptedSettingsReader"),
                coherenceGuard
        );
    }

    public TracingStatusSnapshot assemble() {
        if (diagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            return snapshot();
        }

        try {
            return coherenceGuard.readCoherently(this::snapshot);
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            coherenceGuard.recordTrustFailure(coherenceFailure.failureCode());
            return incoherentSnapshot(coherenceFailure.failureCode());
        }
    }

    private TracingStatusSnapshot snapshot() {
        SettingsStatusResolver.Resolution settings = settings();
        String agentFailureCode = StringUtils.hasText(settings.agentFailureCode())
                ? settings.agentFailureCode()
                : lifecycleFailureCode();

        return new TracingStatusSnapshot(
                bootstrapSettings.tracingEnabled(),
                bootstrapSettings.controlChannel(),
                agentSection(diagnostics.runtimeState(), agentFailureCode),
                RuntimeStatusSections.runtime(traceOperations, tracingRuntime, jmxClient),
                settings.status(),
                RuntimeStatusSections.jmxContractDiagnostics(jmxClient)
        );
    }

    private SettingsStatusResolver.Resolution settings() {
        if (!bootstrapSettings.tracingEnabled()) {
            return SettingsStatusResolver.Resolution.tracingDisabled();
        }

        if (diagnostics.runtimeState() != AgentRuntimeState.AGENT_READY) {
            return SettingsStatusResolver.Resolution.agentUnavailable(effectiveFailureCode(lifecycleFailureCode()));
        }

        SettingsStatusResolver.Resolution resolved = settingsResolver.resolve();
        if (resolved.status().reason() == SettingsAbsenceReason.AGENT_UNAVAILABLE) {
            return SettingsStatusResolver.Resolution.agentUnavailable(
                    effectiveFailureCode(resolved.agentFailureCode()));
        }
        return resolved;
    }

    private TracingStatusSnapshot incoherentSnapshot(String failureCode) {
        return new TracingStatusSnapshot(
                bootstrapSettings.tracingEnabled(),
                bootstrapSettings.controlChannel(),
                agentSection(AgentRuntimeState.EXTENSION_INCOMPATIBLE, effectiveFailureCode(failureCode)),
                Map.of(),
                TracingStatusSnapshot.SettingsStatus.absent(SettingsAbsenceReason.AGENT_UNAVAILABLE),
                List.of()
        );
    }

    private Map<String, Object> agentSection(AgentRuntimeState runtimeState, String failureCode) {
        StatusSection<Void> section = StatusSection.create()
                .value("state", runtimeState.name())
                .value("markerVisible", diagnostics.agentMarkerVisible());

        return switch (diagnostics.agentReadiness()) {
            case TracingDeploymentDiagnostics.EndpointAbsent ignored ->
                    unobservedSection(section, false, failureCode);

            case TracingDeploymentDiagnostics.SnapshotUnreadable ignored ->
                    unobservedSection(section, true, failureCode);

            case TracingDeploymentDiagnostics.Observed observed ->
                    observedSection(section, observed.snapshot(), failureCode);
        };
    }

    private static Map<String, Object> unobservedSection(StatusSection<Void> section,
                                                         boolean endpointPresent,
                                                         String failureCode) {
        return section
                .value("readinessEndpointPresent", endpointPresent)
                .value("failureCode", failureCode)
                .build();
    }

    private static Map<String, Object> observedSection(StatusSection<Void> section,
                                                       AgentReadinessSnapshot snapshot,
                                                       String failureCode) {
        AgentReadinessSnapshot.ContractIdentity contract = snapshot.contractIdentity();
        AgentReadinessSnapshot.AnchorIdentity anchor = snapshot.anchorIdentity();
        AgentReadinessSnapshot.LifecycleState lifecycle = snapshot.lifecycleState();
        AgentReadinessSnapshot.DistributionIdentity distribution = snapshot.distributionIdentity();
        AgentReadinessSnapshot.ClassloaderTopology classloaders = snapshot.classloaderTopology();

        return section
                .value("readinessEndpointPresent", true)
                .value("contractId", contract.contractId())
                .value("schemaDigest", contract.schemaDigest())
                .value("agentInstanceId", anchor.agentInstanceId())
                .value("evidenceGeneration", anchor.evidenceGeneration())
                .value("batchId", anchor.batchId())
                .value("lifecycle", lifecycle.lifecycle())
                .value("failureCode", failureCode)
                .value("capabilities", lifecycle.capabilities())
                .value("missingRequired", lifecycle.missingRequired())
                .value("agentDistributionSha256", distribution.agentDistributionSha256())
                .value("upstreamAgentVersion", distribution.upstreamAgentVersion())
                .value("extensionVersion", distribution.extensionVersion())
                .value("extensionDefiningLoader", classloaders.extensionDefiningLoader())
                .value("sdkDefiningLoader", classloaders.sdkDefiningLoader())
                .build();
    }

    private String lifecycleFailureCode() {
        return diagnostics.agentReadiness().failureCode();
    }

    private String effectiveFailureCode(String failureCode) {
        if (StringUtils.hasText(failureCode)) {
            return failureCode;
        }

        String terminalFailureCode = coherenceGuard.terminalFailureCode();
        return StringUtils.hasText(terminalFailureCode)
                ? terminalFailureCode
                : AgentReadinessCoherenceGuard.ANCHOR_NOT_PINNED;
    }
}
