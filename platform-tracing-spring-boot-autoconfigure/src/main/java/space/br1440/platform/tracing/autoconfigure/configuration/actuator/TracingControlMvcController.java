package space.br1440.platform.tracing.autoconfigure.configuration.actuator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingPolicyDeliveryResult;

import java.util.Map;

/**
 * HTTP-only management controller with eight explicit, distinct request paths.
 * Registered exclusively in the management WebApplicationContext via
 * {@link TracingControlManagementContextConfiguration}.
 * <p>
 * Physical absence outside ACTUATOR+LOCAL is guaranteed by the conditional
 * in TracingActuatorAutoConfiguration: both properties must match for the bean to exist.
 * <p>
 * HTTP routes (all unique):
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
 * NOT exposed via JMX. Spring MVC on management port only.
 */
@RestController
@RequestMapping("/actuator/tracingControl")
public final class TracingControlMvcController {

    private final SpringActuatorTracingPolicyAdapter adapter;

    public TracingControlMvcController(SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = adapter;
    }

    @PostMapping("/sampling/enabled")
    public ResponseEntity<Map<String, Object>> setSamplingEnabled(
            @RequestBody SetSamplingEnabledRequest req) {
        TracingPolicyDeliveryResult r = adapter.setSamplingEnabled(req.enabled());
        return outcome(r, "sampling.enabled", req.enabled());
    }

    @PostMapping("/sampling/ratio")
    public ResponseEntity<Map<String, Object>> setSamplingRatio(
            @RequestBody SetSamplingRatioRequest req) {
        TracingPolicyDeliveryResult r = adapter.setSamplingRatio(req.ratio());
        return outcome(r, "sampling.ratio", req.ratio());
    }

    @PutMapping("/sampling/route-ratios")
    public ResponseEntity<Map<String, Object>> replaceSamplingRouteRatios(
            @RequestBody ReplaceSamplingRouteRatiosRequest req) {
        TracingPolicyDeliveryResult r = adapter.replaceSamplingRouteRatios(req.routeRatios());
        return outcome(r, "sampling.route-ratios",
                req.routeRatios() != null ? req.routeRatios().size() : 0);
    }

    @PostMapping("/validation/enabled")
    public ResponseEntity<Map<String, Object>> setValidationEnabled(
            @RequestBody SetValidationEnabledRequest req) {
        TracingPolicyDeliveryResult r = adapter.setValidationEnabled(req.enabled());
        return outcome(r, "validation.enabled", req.enabled());
    }

    @PostMapping("/validation/strict")
    public ResponseEntity<Map<String, Object>> setValidationStrict(
            @RequestBody SetValidationStrictRequest req) {
        TracingPolicyDeliveryResult r = adapter.setValidationStrict(req.strict());
        return outcome(r, "validation.strict", req.strict());
    }

    @PostMapping("/exporter/enabled")
    public ResponseEntity<Map<String, Object>> setExporterEnabled(
            @RequestBody SetExporterEnabledRequest req) {
        TracingPolicyDeliveryResult r = adapter.setExporterEnabled(req.enabled());
        return outcome(r, "exporter.enabled", req.enabled());
    }

    @PostMapping("/propagation/trace-control-headers-enabled")
    public ResponseEntity<Map<String, Object>> setTraceControlHeadersEnabled(
            @RequestBody SetTraceControlHeadersEnabledRequest req) {
        TracingPolicyDeliveryResult r = adapter.setTraceControlHeadersEnabled(req.enabled());
        return outcome(r, "propagation.trace-control-headers.enabled", req.enabled());
    }

    @PostMapping("/diagnostics/log-level")
    public ResponseEntity<Map<String, Object>> setDiagnosticsLogLevel(
            @RequestBody SetDiagnosticsLogLevelRequest req) {
        TracingPolicyDeliveryResult r = adapter.setDiagnosticsLogLevel(req.level());
        return outcome(r, "diagnostics.log-level",
                req.level() != null ? req.level() : "null");
    }

    private ResponseEntity<Map<String, Object>> outcome(
            TracingPolicyDeliveryResult result, String leaf, Object value) {
        Map<String, Object> body = Map.of(
                "outcome", result.name(),
                "leaf", leaf,
                "value", value
        );
        int status = switch (result) {
            case APPLIED, UNCHANGED -> 200;
            case REJECTED -> 422;
            case STALE -> 409;
            case FAILED -> 503;
        };
        return ResponseEntity.status(status).body(body);
    }
}
