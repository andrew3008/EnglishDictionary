package space.br1440.platform.tracing.autoconfigure.servlet.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.ReplaceSamplingRouteRatiosRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetDiagnosticsLogLevelRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetExporterEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetSamplingEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetSamplingRatioRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetTraceControlHeadersEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetValidationEnabledRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyManagementRequests.SetValidationStrictRequest;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingPolicyMutationService;

import java.util.Map;

/**
 * HTTP-only management controller with eight distinct request paths.
 * Registered exclusively in the management WebApplicationContext via
 * {@link TracingControlManagementContextConfiguration} (discovered through
 * META-INF/spring/ManagementContextConfiguration.imports).
 * <p>
 * The class-level {@code @RequestMapping("/tracingControl")} is a relative
 * path. The absolute prefix (management base-path) is applied at registration
 * time by {@link TracingControlManagementContextConfiguration} via Spring Boot's
 * {@code management.endpoints.web.base-path} injected into the factory method
 * and passed to the constructor. This avoids bean-SpEL, instance-field SpEL,
 * and separate path-provider infrastructure beans — all of which introduce
 * child-context lifecycle ordering problems.
 * <p>
 * Architectural decision (requires committee approval before production):
 * This is a {@code @RestController} on the management port, not a
 * {@code @WebEndpoint}. Boot's {@code @WebEndpoint + @Selector} model cannot
 * produce eight unambiguous HTTP paths. Trade-off: standard Actuator endpoint
 * governance does not apply. Exposure is governed by:
 * authority=ACTUATOR AND platform.environment=LOCAL.
 * <p>
 * Routes (relative to management base-path root):
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
@RequestMapping("/tracingControl")
public final class TracingControlMvcController {

    private final TracingPolicyMutationService mutationService;

    public TracingControlMvcController(TracingPolicyMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping("/sampling/enabled")
    public ResponseEntity<Map<String, Object>> setSamplingEnabled(
            @RequestBody SetSamplingEnabledRequest req) {
        return outcome(mutationService.setSamplingEnabled(req.enabled()), "sampling.enabled", req.enabled());
    }

    @PostMapping("/sampling/ratio")
    public ResponseEntity<Map<String, Object>> setSamplingRatio(
            @RequestBody SetSamplingRatioRequest req) {
        return outcome(mutationService.setSamplingRatio(req.ratio()), "sampling.ratio", req.ratio());
    }

    @PutMapping("/sampling/route-ratios")
    public ResponseEntity<Map<String, Object>> replaceSamplingRouteRatios(
            @RequestBody ReplaceSamplingRouteRatiosRequest req) {
        return outcome(mutationService.replaceSamplingRouteRatios(req.routeRatios()),
                "sampling.route-ratios",
                req.routeRatios() != null ? req.routeRatios().size() : 0);
    }

    @PostMapping("/validation/enabled")
    public ResponseEntity<Map<String, Object>> setValidationEnabled(
            @RequestBody SetValidationEnabledRequest req) {
        return outcome(mutationService.setValidationEnabled(req.enabled()), "validation.enabled", req.enabled());
    }

    @PostMapping("/validation/strict")
    public ResponseEntity<Map<String, Object>> setValidationStrict(
            @RequestBody SetValidationStrictRequest req) {
        return outcome(mutationService.setValidationStrict(req.strict()), "validation.strict", req.strict());
    }

    @PostMapping("/exporter/enabled")
    public ResponseEntity<Map<String, Object>> setExporterEnabled(
            @RequestBody SetExporterEnabledRequest req) {
        return outcome(mutationService.setExporterEnabled(req.enabled()), "exporter.enabled", req.enabled());
    }

    @PostMapping("/propagation/trace-control-headers-enabled")
    public ResponseEntity<Map<String, Object>> setTraceControlHeadersEnabled(
            @RequestBody SetTraceControlHeadersEnabledRequest req) {
        return outcome(mutationService.setTraceControlHeadersEnabled(req.enabled()),
                "propagation.trace-control-headers.enabled", req.enabled());
    }

    @PostMapping("/diagnostics/log-level")
    public ResponseEntity<Map<String, Object>> setDiagnosticsLogLevel(
            @RequestBody SetDiagnosticsLogLevelRequest req) {
        return outcome(mutationService.setDiagnosticsLogLevel(req.level()),
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
