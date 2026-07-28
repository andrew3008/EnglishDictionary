package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

@RestControllerEndpoint(id = "tracingControl")
final class TracingActuatorControlEndpoint {

    private final SpringActuatorTracingPolicyAdapter adapter;

    TracingActuatorControlEndpoint(SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @PostMapping("/setSamplingEnabled")
    TracingPolicyDeliveryResult setSamplingEnabled(@RequestBody BooleanValue request) {
        return adapter.setSamplingEnabled(request.requiredValue());
    }

    @PostMapping("/setSamplingRatio")
    TracingPolicyDeliveryResult setSamplingRatio(@RequestBody RatioValue request) {
        return adapter.setSamplingRatio(request.requiredValue());
    }

    @PostMapping("/replaceSamplingRouteRatios")
    TracingPolicyDeliveryResult replaceSamplingRouteRatios(
            @RequestBody RouteRatiosValue request) {
        return adapter.replaceSamplingRouteRatios(request.requiredValue());
    }

    @PostMapping("/setValidationEnabled")
    TracingPolicyDeliveryResult setValidationEnabled(@RequestBody BooleanValue request) {
        return adapter.setValidationEnabled(request.requiredValue());
    }

    @PostMapping("/setValidationStrict")
    TracingPolicyDeliveryResult setValidationStrict(@RequestBody BooleanValue request) {
        return adapter.setValidationStrict(request.requiredValue());
    }

    @PostMapping("/setExporterEnabled")
    TracingPolicyDeliveryResult setExporterEnabled(@RequestBody BooleanValue request) {
        return adapter.setExporterEnabled(request.requiredValue());
    }

    @PostMapping("/setTraceControlHeadersEnabled")
    TracingPolicyDeliveryResult setTraceControlHeadersEnabled(
            @RequestBody BooleanValue request) {
        return adapter.setTraceControlHeadersEnabled(request.requiredValue());
    }

    @PostMapping("/setDiagnosticsLogLevel")
    TracingPolicyDeliveryResult setDiagnosticsLogLevel(@RequestBody LogLevelValue request) {
        return adapter.setDiagnosticsLogLevel(request.requiredValue());
    }

    static final class BooleanValue {

        private Boolean value;

        public Boolean getValue() {
            return value;
        }

        public void setValue(JsonNode value) {
            if (value == null || !value.isBoolean()) {
                throw new IllegalArgumentException("value must be a JSON boolean");
            }
            this.value = value.booleanValue();
        }

        boolean requiredValue() {
            return Objects.requireNonNull(value, "value");
        }

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }

    static final class RatioValue {

        private Double value;

        public Double getValue() {
            return value;
        }

        public void setValue(JsonNode value) {
            if (value == null || !value.isNumber()) {
                throw new IllegalArgumentException("value must be a JSON number");
            }
            this.value = value.doubleValue();
        }

        double requiredValue() {
            return Objects.requireNonNull(value, "value");
        }

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }

    static final class RouteRatiosValue {

        private Map<String, Double> value;

        public Map<String, Double> getValue() {
            return value;
        }

        public void setValue(JsonNode value) {
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("value must be a JSON object");
            }
            Map<String, Double> routes = new TreeMap<>();
            value.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNumber()) {
                    throw new IllegalArgumentException(
                            "route ratio must be a JSON number: " + entry.getKey());
                }
                routes.put(entry.getKey(), entry.getValue().doubleValue());
            });
            this.value = new LinkedHashMap<>(routes);
        }

        Map<String, Double> requiredValue() {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(value, "value")));
        }

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }

    static final class LogLevelValue {

        private OtelAgentLogLevel value;

        public OtelAgentLogLevel getValue() {
            return value;
        }

        public void setValue(JsonNode value) {
            if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException("value must be a JSON string");
            }
            this.value = OtelAgentLogLevel.valueOf(value.textValue());
        }

        OtelAgentLogLevel requiredValue() {
            return Objects.requireNonNull(value, "value");
        }

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown request field: " + name);
        }
    }
}
