package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;

import java.util.Map;

/**
 * HTTP-only Actuator control endpoint with eight typed Category A write operations.
 * <p>
 * Uses {@code @WebEndpoint} to restrict exposure to HTTP only.
 * NOT exposed via JMX. The private JMX writer remains the sole JMX mutation surface.
 * <p>
 * HTTP routes (each operation has a unique path via @Selector):
 * <pre>
 *   POST /actuator/tracingControl/sampling/enabled
 *   POST /actuator/tracingControl/sampling/ratio
 *   PUT  /actuator/tracingControl/sampling/route-ratios
 *   POST /actuator/tracingControl/validation/enabled
 *   POST /actuator/tracingControl/validation/strict
 *   POST /actuator/tracingControl/exporter/enabled
 *   POST /actuator/tracingControl/propagation/trace-control-headers-enabled
 *   POST /actuator/tracingControl/diagnostics/log-level
 * </pre>
 * Physically absent (bean not registered) outside ACTUATOR + LOCAL posture.
 */
@WebEndpoint(id = "tracingControl")
public final class TracingActuatorControlEndpoint {

    private final SpringActuatorTracingPolicyAdapter adapter;

    public TracingActuatorControlEndpoint(SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = adapter;
    }

    @WriteOperation
    public Map<String, Object> samplingEnabled(@Selector String segment, boolean enabled) {
        if (!"enabled".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setSamplingEnabled(enabled);
        return Map.of("outcome", r.name(), "leaf", "sampling.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> samplingRatio(@Selector String segment, double ratio) {
        if (!"ratio".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setSamplingRatio(ratio);
        return Map.of("outcome", r.name(), "leaf", "sampling.ratio", "value", ratio);
    }

    @WriteOperation
    public Map<String, Object> samplingRouteRatios(@Selector String segment, Map<String, Double> routeRatios) {
        if (!"route-ratios".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.replaceSamplingRouteRatios(routeRatios);
        return Map.of("outcome", r.name(), "leaf", "sampling.route-ratios",
                "size", routeRatios != null ? routeRatios.size() : 0);
    }

    @WriteOperation
    public Map<String, Object> validationEnabled(@Selector String segment, boolean enabled) {
        if (!"enabled".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setValidationEnabled(enabled);
        return Map.of("outcome", r.name(), "leaf", "validation.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> validationStrict(@Selector String segment, boolean strict) {
        if (!"strict".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setValidationStrict(strict);
        return Map.of("outcome", r.name(), "leaf", "validation.strict", "value", strict);
    }

    @WriteOperation
    public Map<String, Object> exporterEnabled(@Selector String segment, boolean enabled) {
        if (!"enabled".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setExporterEnabled(enabled);
        return Map.of("outcome", r.name(), "leaf", "exporter.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> traceControlHeadersEnabled(@Selector String segment, boolean enabled) {
        if (!"trace-control-headers-enabled".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setTraceControlHeadersEnabled(enabled);
        return Map.of("outcome", r.name(), "leaf", "propagation.trace-control-headers.enabled", "value", enabled);
    }

    @WriteOperation
    public Map<String, Object> diagnosticsLogLevel(@Selector String segment, String level) {
        if (!"log-level".equals(segment)) return Map.of("outcome", "NOT_FOUND");
        TracingPolicyDeliveryResult r = adapter.setDiagnosticsLogLevel(level);
        return Map.of("outcome", r.name(), "leaf", "diagnostics.log-level",
                "value", level != null ? level : "null");
    }
}
