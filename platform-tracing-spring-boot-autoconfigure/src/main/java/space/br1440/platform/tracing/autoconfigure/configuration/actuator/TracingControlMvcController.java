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
 * Architectural note: This is a {@code @RestController} on the management port,
 * not a full Spring Boot Actuator {@code @WebEndpoint}. This design was chosen
 * because Boot's {@code @WebEndpoint} + {@code @Selector} model cannot produce
 * eight unambiguous HTTP paths. The trade-off: standard Actuator endpoint
 * governance ({@code management.endpoint.<id>.access}, {@code EndpointRequest}
 * security DSL, endpoint-specific enable/disable) does not apply.
 * Exposure is governed by: authority=ACTUATOR AND platform.environment=LOCAL
 * (enforced at both registration-time via {@code @ConditionalOnProperty} and
 * startup-time via {@code TracingConfigurationAuthorityGuard.validate()}).
 * <p>
 * This decision requires explicit committee approval as
 * "management-context local control API" before production promotion.
 * <p>
 * Mapping strategy: the class-level {@code @RequestMapping} carries the full
 * absolute prefix (management base-path + {@code /tracingControl}), injected
 * via {@code @Value}. Method-level annotations use only short relative
 * sub-paths. This avoids instance-field SpEL in annotation values, which is
 * not a supported Spring MVC mapping mechanism and would cause routes to be
 * either un-registered or registered as literals.
 * <p>
 * Routes (relative to management server root):
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
@RequestMapping("#{@tracingControlMvcControllerPathProvider.basePath}")
public final class TracingControlMvcController {

    private final SpringActuatorTracingPolicyAdapter adapter;

    public TracingControlMvcController(
            SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = adapter;
    }

    @PostMapping("/sampling/enabled")
    public ResponseEntity<Map<String, Object>> setSamplingEnabled(
            @RequestBody SetSamplingEnabledRequest req) {
        return outcome(adapter.setSamplingEnabled(req.enabled()), "sampling.enabled", req.enabled());
    }

    @PostMapping("/sampling/ratio")
    public ResponseEntity<Map<String, Object>> setSamplingRatio(
            @RequestBody SetSamplingRatioRequest req) {
        return outcome(adapter.setSamplingRatio(req.ratio()), "sampling.ratio", req.ratio());
    }

    @PutMapping("/sampling/route-ratios")
    public ResponseEntity<Map<String, Object>> replaceSamplingRouteRatios(
            @RequestBody ReplaceSamplingRouteRatiosRequest req) {
        return outcome(adapter.replaceSamplingRouteRatios(req.routeRatios()),
                "sampling.route-ratios",
                req.routeRatios() != null ? req.routeRatios().size() : 0);
    }

    @PostMapping("/validation/enabled")
    public ResponseEntity<Map<String, Object>> setValidationEnabled(
            @RequestBody SetValidationEnabledRequest req) {
        return outcome(adapter.setValidationEnabled(req.enabled()), "validation.enabled", req.enabled());
    }

    @PostMapping("/validation/strict")
    public ResponseEntity<Map<String, Object>> setValidationStrict(
            @RequestBody SetValidationStrictRequest req) {
        return outcome(adapter.setValidationStrict(req.strict()), "validation.strict", req.strict());
    }

    @PostMapping("/exporter/enabled")
    public ResponseEntity<Map<String, Object>> setExporterEnabled(
            @RequestBody SetExporterEnabledRequest req) {
        return outcome(adapter.setExporterEnabled(req.enabled()), "exporter.enabled", req.enabled());
    }

    @PostMapping("/propagation/trace-control-headers-enabled")
    public ResponseEntity<Map<String, Object>> setTraceControlHeadersEnabled(
            @RequestBody SetTraceControlHeadersEnabledRequest req) {
        return outcome(adapter.setTraceControlHeadersEnabled(req.enabled()),
                "propagation.trace-control-headers.enabled", req.enabled());
    }

    @PostMapping("/diagnostics/log-level")
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
