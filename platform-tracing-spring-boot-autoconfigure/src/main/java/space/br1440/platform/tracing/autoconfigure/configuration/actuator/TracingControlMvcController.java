package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;

import java.util.Map;

/**
 * HTTP-only management controller with eight distinct request paths.
 * Registered exclusively in the management WebApplicationContext via
 * {@link TracingControlManagementContextConfiguration} (discovered through
 * META-INF/spring/ManagementContextConfiguration.imports).
 * <p>
 * Architectural note: This is a @RestController on the management port, not a
 * full Spring Boot Actuator @WebEndpoint. This design was chosen because Boot's
 * @WebEndpoint + @Selector model cannot produce eight unambiguous HTTP paths
 * (all map to the same POST /actuator/tracingControl/{segment} predicate).
 * The trade-off: standard Actuator endpoint governance (management.endpoint.<id>.access,
 * EndpointRequest security DSL, endpoint-specific enable/disable) does not apply.
 * Exposure is governed by: authority=ACTUATOR AND platform.environment=LOCAL
 * (enforced at both registration-time via @ConditionalOnProperty and startup-time
 * via TracingConfigurationAuthorityGuard.validate()).
 * <p>
 * Base path compliance: path prefix is injected from
 * management.endpoints.web.base-path (default /actuator) so that consumers
 * who customise the management base path get correct routes.
 * <p>
 * Routes (relative to management base path):
 * <pre>
 *   POST {base}/tracingControl/sampling/enabled
 *   POST {base}/tracingControl/sampling/ratio
 *   PUT  {base}/tracingControl/sampling/route-ratios
 *   POST {base}/tracingControl/validation/enabled
 *   POST {base}/tracingControl/validation/strict
 *   POST {base}/tracingControl/exporter/enabled
 *   POST {base}/tracingControl/propagation/trace-control-headers-enabled
 *   POST {base}/tracingControl/diagnostics/log-level
 * </pre>
 */
@RestController
public final class TracingControlMvcController {

    private final SpringActuatorTracingPolicyAdapter adapter;
    private final String base;

    public TracingControlMvcController(
            SpringActuatorTracingPolicyAdapter adapter,
            @Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        this.adapter = adapter;
        // Normalise: ensure single leading slash, no trailing slash
        String b = basePath.trim();
        if (!b.startsWith("/")) b = "/" + b;
        if (b.endsWith("/") && b.length() > 1) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/sampling/enabled'}")
    public ResponseEntity<Map<String, Object>> setSamplingEnabled(
            @RequestBody SetSamplingEnabledRequest req) {
        return outcome(adapter.setSamplingEnabled(req.enabled()), "sampling.enabled", req.enabled());
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/sampling/ratio'}")
    public ResponseEntity<Map<String, Object>> setSamplingRatio(
            @RequestBody SetSamplingRatioRequest req) {
        return outcome(adapter.setSamplingRatio(req.ratio()), "sampling.ratio", req.ratio());
    }

    @PutMapping("#{tracingControlMvcController.base + '/tracingControl/sampling/route-ratios'}")
    public ResponseEntity<Map<String, Object>> replaceSamplingRouteRatios(
            @RequestBody ReplaceSamplingRouteRatiosRequest req) {
        return outcome(adapter.replaceSamplingRouteRatios(req.routeRatios()),
                "sampling.route-ratios",
                req.routeRatios() != null ? req.routeRatios().size() : 0);
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/validation/enabled'}")
    public ResponseEntity<Map<String, Object>> setValidationEnabled(
            @RequestBody SetValidationEnabledRequest req) {
        return outcome(adapter.setValidationEnabled(req.enabled()), "validation.enabled", req.enabled());
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/validation/strict'}")
    public ResponseEntity<Map<String, Object>> setValidationStrict(
            @RequestBody SetValidationStrictRequest req) {
        return outcome(adapter.setValidationStrict(req.strict()), "validation.strict", req.strict());
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/exporter/enabled'}")
    public ResponseEntity<Map<String, Object>> setExporterEnabled(
            @RequestBody SetExporterEnabledRequest req) {
        return outcome(adapter.setExporterEnabled(req.enabled()), "exporter.enabled", req.enabled());
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/propagation/trace-control-headers-enabled'}")
    public ResponseEntity<Map<String, Object>> setTraceControlHeadersEnabled(
            @RequestBody SetTraceControlHeadersEnabledRequest req) {
        return outcome(adapter.setTraceControlHeadersEnabled(req.enabled()),
                "propagation.trace-control-headers.enabled", req.enabled());
    }

    @PostMapping("#{tracingControlMvcController.base + '/tracingControl/diagnostics/log-level'}")
    public ResponseEntity<Map<String, Object>> setDiagnosticsLogLevel(
            @RequestBody SetDiagnosticsLogLevelRequest req) {
        return outcome(adapter.setDiagnosticsLogLevel(req.level()),
                "diagnostics.log-level", req.level() != null ? req.level() : "null");
    }

    private ResponseEntity<Map<String, Object>> outcome(
            TracingPolicyDeliveryResult result, String leaf, Object value) {
        int status = switch (result) {
            case APPLIED, UNCHANGED -> 200;
            case REJECTED -> 422;
            case STALE    -> 409;
            case FAILED   -> 503;
        };
        return ResponseEntity.status(status).body(Map.of(
                "outcome", result.name(), "leaf", leaf, "value", value));
    }
}
