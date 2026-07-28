package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Secret-safe read model конфигурации трассировки с явным владельцем authoritative truth.
 */
public final class TracingConfigurationReadModel {

    private static final String AGENT_PROJECTION_PENDING =
            "AUTHORITATIVE_AGENT_PROJECTION_PENDING_P6";
    private static final String AGENT_PROJECTION_UNAVAILABLE =
            "AUTHORITATIVE_AGENT_PROJECTION_UNAVAILABLE";

    private final TracingConfigurationAuthority authority;
    private final AcceptedTracingPolicyReader acceptedReader;
    private final TracingConfigurationReadiness readiness;
    private final TracingPolicyDeliveryDiagnostics deliveryDiagnostics;

    TracingConfigurationReadModel(
            TracingConfigurationAuthority authority,
            AcceptedTracingPolicyReader acceptedReader,
            TracingConfigurationReadiness readiness,
            TracingPolicyDeliveryDiagnostics deliveryDiagnostics) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.acceptedReader = acceptedReader;
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.deliveryDiagnostics = Objects.requireNonNull(
                deliveryDiagnostics,
                "deliveryDiagnostics");
    }

    public Map<String, Object> read() {
        if (acceptedReader == null) {
            return unavailable(AGENT_PROJECTION_PENDING);
        }
        try {
            return available(acceptedReader.current());
        } catch (RuntimeException exception) {
            return unavailable(AGENT_PROJECTION_UNAVAILABLE);
        }
    }

    private Map<String, Object> available(AcceptedTracingPolicyView accepted) {
        CompleteTracingPolicySnapshot policy = accepted.policy();
        Map<String, Object> result = base("AVAILABLE");
        result.put("truthSource", "AGENT_ACCEPTED_STATE");
        result.put("runtimeInstanceId", accepted.runtimeInstanceId());
        result.put("acceptedSequence", accepted.acceptedSequence());
        result.put("acceptedPolicyDigest", accepted.payloadDigest());

        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("enabled", policy.samplingEnabled());
        sampling.put("ratio", policy.samplingRatio());
        sampling.put("routeRatioCount", policy.samplingRouteRatios().size());
        sampling.put("routeRatiosDisclosed", false);
        result.put("sampling", sampling);
        result.put("validation", Map.of(
                "enabled", policy.validationEnabled(),
                "strict", policy.validationStrict()));
        result.put("exporter", Map.of("enabled", policy.exporterEnabled()));
        result.put("traceControlHeaders", Map.of(
                "enabled", policy.traceControlHeadersEnabled()));
        result.put("diagnostics", Map.of(
                "logLevel", policy.diagnosticsLogLevel().name()));
        result.put("lastApply", Map.of(
                "outcome", accepted.lastApplyOutcome(),
                "attemptId", accepted.lastAttemptId(),
                "failureCode", accepted.lastFailureCode()));
        result.put("structuralDrift", accepted.structuralDrift());
        result.put("restartRequired", accepted.restartRequired());
        return result;
    }

    private Map<String, Object> unavailable(String reason) {
        Map<String, Object> result = base("UNAVAILABLE");
        result.put("truthSource", "AGENT_PROJECTION");
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> base(String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("authority", authority.name());
        TracingConfigurationReadiness.Snapshot readinessSnapshot = readiness.current();
        result.put("applicationReadiness", Map.of(
                "state", readinessSnapshot.state().name(),
                "messageCode", readinessSnapshot.messageCode(),
                "acceptedSequence", readinessSnapshot.acceptedSequence()));
        TracingPolicyDeliveryDiagnostics.Snapshot delivery = deliveryDiagnostics.current();
        result.put("delivery", Map.of(
                "attemptId", delivery.attemptId(),
                "source", delivery.source(),
                "sourceRevision", delivery.sourceRevision(),
                "outcome", delivery.outcome(),
                "messageCode", delivery.messageCode(),
                "acceptedSequence", delivery.acceptedSequence(),
                "timestamp", delivery.timestamp().toString()));
        return result;
    }
}
