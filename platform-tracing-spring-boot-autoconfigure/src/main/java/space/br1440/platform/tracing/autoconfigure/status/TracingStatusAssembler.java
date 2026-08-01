package space.br1440.platform.tracing.autoconfigure.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.opentelemetry.api.GlobalOpenTelemetry;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedPolicyProjection;
import space.br1440.platform.tracing.autoconfigure.control.policy.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionDescriptor;
import space.br1440.platform.tracing.autoconfigure.agent.AgentExtensionObserver;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingModeDiagnostics;

/**
 * Собирает status read-model из независимых источников истины без write-capability.
 */
public final class TracingStatusAssembler {

    private final TraceOperations traceOperations;
    private final TracingBootstrapSettings bootstrapSettings;
    private final PlatformTracingJmxClient jmxClient;
    private final AcceptedTracingPolicyReader acceptedPolicyReader;
    private final TracingModeDiagnostics startupDiagnostics;
    private final Supplier<AgentExtensionDescriptor> agentDescriptorSupplier;

    public TracingStatusAssembler(
            TraceOperations traceOperations,
            TracingBootstrapSettings bootstrapSettings,
            PlatformTracingJmxClient jmxClient,
            AcceptedTracingPolicyReader acceptedPolicyReader,
            TracingModeDiagnostics startupDiagnostics) {
        this(
                traceOperations,
                bootstrapSettings,
                jmxClient,
                acceptedPolicyReader,
                startupDiagnostics,
                () -> new AgentExtensionObserver().observe(
                        !bootstrapSettings.tracingEnabled(),
                        startupDiagnostics.agentDetected(),
                        false,
                        GlobalOpenTelemetry.isSet()));
    }

    TracingStatusAssembler(
            TraceOperations traceOperations,
            TracingBootstrapSettings bootstrapSettings,
            PlatformTracingJmxClient jmxClient,
            AcceptedTracingPolicyReader acceptedPolicyReader,
            TracingModeDiagnostics startupDiagnostics,
            Supplier<AgentExtensionDescriptor> agentDescriptorSupplier) {
        this.traceOperations = traceOperations;
        this.bootstrapSettings = bootstrapSettings;
        this.jmxClient = jmxClient;
        this.acceptedPolicyReader = acceptedPolicyReader;
        this.startupDiagnostics = startupDiagnostics;
        this.agentDescriptorSupplier = agentDescriptorSupplier;
    }

    public TracingStatusSnapshot assemble() {
        AgentExtensionDescriptor descriptor = agentDescriptorSupplier.get();
        return new TracingStatusSnapshot(
                TracingStatusSnapshot.CURRENT_SCHEMA_VERSION,
                startupDiagnostics.mode().name(),
                bootstrapSettings.authority().name(),
                agentSection(descriptor),
                runtimeSection(),
                policyStatus(descriptor));
    }

    private Map<String, Object> agentSection(AgentExtensionDescriptor descriptor) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("state", descriptor.state().name());
        agent.put("markerVisible", descriptor.agentMarkerVisible());
        agent.put("readinessEndpointPresent", descriptor.readinessEndpointPresent());
        agent.put("extensionVersion", descriptor.extensionVersion());
        agent.put("protocolVersion", descriptor.protocolVersion());
        agent.put("lifecycle", descriptor.lifecycle());
        agent.put("failureCode", descriptor.failureCode());
        agent.put("capabilities", descriptor.capabilities());
        return agent;
    }

    private Map<String, Object> runtimeSection() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("implementation", traceOperations.getClass().getName());
        runtime.put("currentTraceId", traceOperations.traceContext().traceId().orElse(null));
        runtime.put("currentSpanId", traceOperations.traceContext().spanId().orElse(null));
        runtime.put("domainMBeansAvailable", jmxClient.allMBeansAvailable());
        runtime.put("sampling", samplingSection());
        runtime.put("validation", validationSection());
        runtime.put("scrubbing", scrubbingSection());
        runtime.put("export", jmxClient.getExportMetrics().orElseGet(Map::of));
        runtime.put("processorErrorsTotal", jmxClient.getProcessorErrorsTotal().orElse(null));
        runtime.put("processorErrorsByName", jmxClient.getProcessorErrorsByName());
        runtime.put("configurationReload", jmxClient.getConfigReloadMetrics());
        return runtime;
    }

    private Map<String, Object> samplingSection() {
        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("enabled", jmxClient.isSamplerEnabled().orElse(null));
        sampling.put("ratio", jmxClient.getCurrentRatio().orElse(null));
        sampling.put("routeRatioCount", jmxClient.getLiveRouteRatioCount().orElse(null));
        sampling.put("configVersion", jmxClient.getSamplingConfigVersion().orElse(null));
        sampling.put("configSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        sampling.put("invalidConfigCount", jmxClient.getInvalidConfigCount().orElse(null));
        sampling.put("decisions", jmxClient.getSamplerDecisionCounts());
        return sampling;
    }

    private Map<String, Object> validationSection() {
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("enabled", jmxClient.isValidationEnabled().orElse(null));
        validation.put("strict", jmxClient.isValidationStrict().orElse(null));
        validation.put("strictRuntimeAllowed", jmxClient.isValidationStrictRuntimeAllowed().orElse(null));
        validation.put("configVersion", jmxClient.getValidationConfigVersion().orElse(null));
        validation.put("configSource", jmxClient.getValidationConfigLastUpdatedSource().orElse(null));
        return validation;
    }

    private Map<String, Object> scrubbingSection() {
        Map<String, Object> scrubbing = new LinkedHashMap<>();
        scrubbing.put("enabled", jmxClient.isScrubbingEnabled().orElse(null));
        scrubbing.put("configVersion", jmxClient.getScrubbingConfigVersion().orElse(null));
        scrubbing.put("configSource", jmxClient.getScrubbingConfigLastUpdatedSource().orElse(null));
        Map<String, Long> metrics = jmxClient.getScrubbingMetrics();
        scrubbing.put("ruleCount", metrics.isEmpty() ? null : metrics.get("rules.loaded"));
        return scrubbing;
    }

    private TracingStatusSnapshot.PolicyStatus policyStatus(AgentExtensionDescriptor descriptor) {
        PolicyAvailabilityReason absentReason = absentPolicyReason(descriptor.state());
        if (absentReason != null) {
            return TracingStatusSnapshot.PolicyStatus.absent(absentReason);
        }

        Optional<AcceptedPolicyProjection> projection = acceptedPolicyReader == null
                ? Optional.empty()
                : acceptedPolicyReader.currentProjection();
        return projection
                .map(value -> TracingStatusSnapshot.PolicyStatus.available(
                        value.agentReadiness(), value.policy()))
                .orElseGet(() -> TracingStatusSnapshot.PolicyStatus.absent(
                        PolicyAvailabilityReason.POLICY_NOT_ACCEPTED));
    }

    private PolicyAvailabilityReason absentPolicyReason(AgentRuntimeState runtimeState) {
        if (!bootstrapSettings.tracingEnabled()) {
            return PolicyAvailabilityReason.TRACING_DISABLED;
        }
        if (bootstrapSettings.authority() == TracingConfigurationAuthority.HELM) {
            return PolicyAvailabilityReason.AUTHORITY_HAS_NO_ACCEPTED_POLICY;
        }
        if (runtimeState != AgentRuntimeState.AGENT_READY) {
            return PolicyAvailabilityReason.AGENT_UNAVAILABLE;
        }
        return null;
    }
}
