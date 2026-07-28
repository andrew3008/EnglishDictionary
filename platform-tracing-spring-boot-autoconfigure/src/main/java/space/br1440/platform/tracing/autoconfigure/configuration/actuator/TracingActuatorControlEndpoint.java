package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;

import java.util.Map;

/**
 * Actuator control endpoint with exactly eight typed Category A write operations.
 * <p>
 * Endpoint ID: "tracingControl".
 * Physically absent (bean not registered) outside ACTUATOR + LOCAL posture.
 * Wildcard endpoint exposure does NOT create writes outside this posture.
 * <p>
 * Mutation surface: ACTUATOR + LOCAL only.
 * In SHARED or PRODUCTION posture: startup fail-fast via {@link space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthorityGuard}.
 * In HELM or CONFIG_SERVER authority: this bean is not registered.
 */
@Endpoint(id = "tracingControl")
public final class TracingActuatorControlEndpoint {

    private final SpringActuatorTracingPolicyAdapter adapter;

    public TracingActuatorControlEndpoint(SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = adapter;
    }

    @WriteOperation
    public Map<String, Object> setSamplingEnabled(boolean enabled) {
        TracingPolicyDeliveryResult result = adapter.setSamplingEnabled(enabled);
        return Map.of("outcome", result.name(), "leaf", "sampling.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> setSamplingRatio(double ratio) {
        TracingPolicyDeliveryResult result = adapter.setSamplingRatio(ratio);
        return Map.of("outcome", result.name(), "leaf", "sampling.ratio", "value", ratio);
    }

    @WriteOperation
    public Map<String, Object> replaceSamplingRouteRatios(Map<String, Double> routeRatios) {
        if (routeRatios == null) {
            return Map.of("outcome", TracingPolicyDeliveryResult.REJECTED.name(),
                    "leaf", "sampling.route-ratios", "reason", "null payload");
        }
        TracingPolicyDeliveryResult result = adapter.replaceSamplingRouteRatios(routeRatios);
        return Map.of("outcome", result.name(), "leaf", "sampling.route-ratios", "size", routeRatios.size());
    }

    @WriteOperation
    public Map<String, Object> setValidationEnabled(boolean enabled) {
        TracingPolicyDeliveryResult result = adapter.setValidationEnabled(enabled);
        return Map.of("outcome", result.name(), "leaf", "validation.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> setValidationStrict(boolean strict) {
        TracingPolicyDeliveryResult result = adapter.setValidationStrict(strict);
        return Map.of("outcome", result.name(), "leaf", "validation.strict", "value", strict);
    }

    @WriteOperation
    public Map<String, Object> setExporterEnabled(boolean enabled) {
        TracingPolicyDeliveryResult result = adapter.setExporterEnabled(enabled);
        return Map.of("outcome", result.name(), "leaf", "exporter.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> setTraceControlHeadersEnabled(boolean enabled) {
        TracingPolicyDeliveryResult result = adapter.setTraceControlHeadersEnabled(enabled);
        return Map.of("outcome", result.name(), "leaf", "propagation.trace-control-headers.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> setDiagnosticsLogLevel(String level) {
        TracingPolicyDeliveryResult result = adapter.setDiagnosticsLogLevel(level);
        return Map.of("outcome", result.name(), "leaf", "diagnostics.log-level", "value",
                level != null ? level : "null");
    }
}
