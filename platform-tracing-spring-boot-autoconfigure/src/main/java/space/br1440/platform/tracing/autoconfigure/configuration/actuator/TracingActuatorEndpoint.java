package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedPolicyLeaves;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedPolicyProjection;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.AgentReadinessView;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingApplyDiagnostics;

import java.util.HashMap;
import java.util.Map;

/**
 * Secret-safe read-only Actuator endpoint.
 * <p>
 * Technology-neutral read endpoint. Uses {@code @Endpoint} (not {@code @WebEndpoint})
 * because read-only diagnostic exposure via JMX is acceptable; only the mutation
 * surface must be restricted to HTTP.
 * <p>
 * Depends only on {@link AcceptedTracingPolicyReader}. No write capability.
 * Assembles response from three separate truth-category projections.
 * <p>
 * Apply diagnostics rendering:
 * <ul>
 *   <li>{@link TracingApplyDiagnostics#isAbsent()} → {@code {"available": false}}
 *       — diagnostics are not exposed by the PROPOSED_PRIV_R07 complete-read path.</li>
 *   <li>Otherwise → {@code {"available": true, "policyDigest": ..., "latestApplyOutcome": ...}}</li>
 * </ul>
 */
@Endpoint(id = "tracingPolicy")
public final class TracingActuatorEndpoint {

    private final AcceptedTracingPolicyReader reader;

    public TracingActuatorEndpoint(AcceptedTracingPolicyReader reader) {
        this.reader = reader;
    }

    @ReadOperation
    public Map<String, Object> tracingPolicy() {
        return reader.currentProjection()
                .map(this::toResponse)
                .orElseGet(() -> Map.of("status", "AGENT_UNAVAILABLE"));
    }

    private Map<String, Object> toResponse(AcceptedPolicyProjection p) {
        AgentReadinessView readiness = p.agentReadiness();
        AcceptedPolicyLeaves leaves   = p.policy();
        TracingApplyDiagnostics diag  = p.diagnostics();
        return Map.of(
                "agent", Map.of(
                        "runtimeInstanceId", readiness.runtimeInstanceId(),
                        "acceptedSequence",  readiness.acceptedSequence(),
                        "ready",             readiness.agentReady()
                ),
                "policy", Map.of(
                        "sampling.enabled",                          leaves.samplingEnabled(),
                        "sampling.ratio",                            leaves.samplingRatio(),
                        "sampling.route-ratios.count",               leaves.samplingRouteRatiosCount(),
                        "validation.enabled",                        leaves.validationEnabled(),
                        "validation.strict",                         leaves.validationStrict(),
                        "exporter.enabled",                          leaves.exporterEnabled(),
                        "propagation.trace-control-headers.enabled", leaves.traceControlHeadersEnabled(),
                        "diagnostics.log-level",                     leaves.diagnosticsLogLevel()
                ),
                "applyDiagnostics", renderDiagnostics(diag)
        );
    }

    private static Map<String, Object> renderDiagnostics(TracingApplyDiagnostics diag) {
        if (diag.isAbsent()) {
            return Map.of("available", false);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("available", true);
        diag.policyDigest().ifPresent(v -> result.put("policyDigest", v));
        diag.latestApplyOutcome().ifPresent(v -> result.put("latestApplyOutcome", v));
        return result;
    }
}
