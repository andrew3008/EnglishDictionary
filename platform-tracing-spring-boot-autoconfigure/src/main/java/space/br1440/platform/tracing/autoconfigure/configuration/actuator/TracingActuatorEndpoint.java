package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyReader;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedTracingPolicyView;

import java.util.Map;

/**
 * Secret-safe read-only Actuator endpoint for accepted tracing policy projection.
 * <p>
 * Endpoint ID: "tracing" (or "tracingPolicy" if "tracing" is reserved).
 * No write operations. No Spring property substitution as effective truth.
 * No raw route values without approved disclosure contract.
 * No stack traces. No secrets.
 * <p>
 * This endpoint depends only on {@link AcceptedTracingPolicyReader},
 * not on the full {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicySnapshotClient}.
 */
@Endpoint(id = "tracingPolicy")
public final class TracingActuatorEndpoint {

    private final AcceptedTracingPolicyReader reader;

    public TracingActuatorEndpoint(AcceptedTracingPolicyReader reader) {
        this.reader = reader;
    }

    @ReadOperation
    public Map<String, Object> tracingPolicy() {
        return reader.current()
                .map(this::toView)
                .orElseGet(() -> Map.of("status", "AGENT_UNAVAILABLE"));
    }

    private Map<String, Object> toView(AcceptedTracingPolicyView v) {
        return Map.of(
                "runtimeInstanceId", v.runtimeInstanceId(),
                "acceptedSequence", v.acceptedSequence(),
                "policyDigest", v.policyDigest(),
                "agentReady", v.agentReady(),
                "latestApplyOutcome", v.latestApplyOutcome(),
                "policy", Map.of(
                        "sampling.enabled", v.samplingEnabled(),
                        "sampling.ratio", v.samplingRatio(),
                        "sampling.route-ratios.count", v.samplingRouteRatiosCount(),
                        "validation.enabled", v.validationEnabled(),
                        "validation.strict", v.validationStrict(),
                        "exporter.enabled", v.exporterEnabled(),
                        "propagation.trace-control-headers.enabled", v.traceControlHeadersEnabled(),
                        "diagnostics.log-level", v.diagnosticsLogLevel()
                )
        );
    }
}
