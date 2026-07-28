package space.br1440.platform.tracing.autoconfigure.configuration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;

/**
 * Локальная management-only HTTP-поверхность управления восемью Category A leaves.
 */
@RestController
@RequestMapping(
        path = "${management.endpoints.web.base-path:/actuator}/tracingControl",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
final class TracingActuatorControlController {

    private final SpringActuatorTracingPolicyAdapter adapter;

    TracingActuatorControlController(SpringActuatorTracingPolicyAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @PostMapping("/sampling/enabled")
    CommandResponse setSamplingEnabled(@RequestBody BooleanRequest request) {
        return execute(() -> adapter.setSamplingEnabled(request.value()));
    }

    @PostMapping("/sampling/ratio")
    CommandResponse setSamplingRatio(@RequestBody RatioRequest request) {
        return execute(() -> adapter.setSamplingRatio(request.value()));
    }

    @PutMapping("/sampling/route-ratios")
    CommandResponse replaceSamplingRouteRatios(
            @RequestBody RouteRatiosRequest request) {
        return execute(() -> adapter.replaceSamplingRouteRatios(request.value()));
    }

    @PostMapping("/validation/enabled")
    CommandResponse setValidationEnabled(@RequestBody BooleanRequest request) {
        return execute(() -> adapter.setValidationEnabled(request.value()));
    }

    @PostMapping("/validation/strict")
    CommandResponse setValidationStrict(@RequestBody BooleanRequest request) {
        return execute(() -> adapter.setValidationStrict(request.value()));
    }

    @PostMapping("/exporter/enabled")
    CommandResponse setExporterEnabled(@RequestBody BooleanRequest request) {
        return execute(() -> adapter.setExporterEnabled(request.value()));
    }

    @PostMapping("/propagation/trace-control-headers")
    CommandResponse setTraceControlHeadersEnabled(
            @RequestBody BooleanRequest request) {
        return execute(() -> adapter.setTraceControlHeadersEnabled(request.value()));
    }

    @PostMapping("/diagnostics/log-level")
    CommandResponse setDiagnosticsLogLevel(@RequestBody LogLevelRequest request) {
        return execute(() -> adapter.setDiagnosticsLogLevel(request.value()));
    }

    private static CommandResponse execute(
            Supplier<TracingPolicyDeliveryResult> command) {
        try {
            return CommandResponse.from(command.get());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid tracing control request",
                    exception);
        }
    }

    record CommandResponse(
            String outcome,
            String attemptId,
            String messageCode,
            long acceptedSequence) {

        private static CommandResponse from(TracingPolicyDeliveryResult result) {
            return new CommandResponse(
                    result.outcome().name(),
                    result.attemptId(),
                    result.messageCode(),
                    result.acceptedSequence());
        }
    }

    static final class BooleanRequest {

        private final boolean value;

        @JsonCreator
        BooleanRequest(
                @JsonProperty("value")
                @JsonDeserialize(using = StrictBooleanDeserializer.class)
                Boolean value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        boolean value() {
            return value;
        }

        @JsonAnySetter
        void rejectUnknownField(String field, JsonNode ignored) {
            throw new IllegalArgumentException("Unknown request field: " + field);
        }
    }

    static final class RatioRequest {

        private final double value;

        @JsonCreator
        RatioRequest(
                @JsonProperty("value")
                @JsonDeserialize(using = StrictDoubleDeserializer.class)
                Double value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        double value() {
            return value;
        }

        @JsonAnySetter
        void rejectUnknownField(String field, JsonNode ignored) {
            throw new IllegalArgumentException("Unknown request field: " + field);
        }
    }

    static final class RouteRatiosRequest {

        private final Map<String, Double> value;

        @JsonCreator
        RouteRatiosRequest(
                @JsonProperty("value")
                @JsonDeserialize(using = StrictRouteRatiosDeserializer.class)
                Map<String, Double> value) {
            this.value = new LinkedHashMap<>(Objects.requireNonNull(value, "value"));
        }

        Map<String, Double> value() {
            return value;
        }

        @JsonAnySetter
        void rejectUnknownField(String field, JsonNode ignored) {
            throw new IllegalArgumentException("Unknown request field: " + field);
        }
    }

    static final class LogLevelRequest {

        private final OtelAgentLogLevel value;

        @JsonCreator
        LogLevelRequest(
                @JsonProperty("value")
                @JsonDeserialize(using = StrictLogLevelDeserializer.class)
                OtelAgentLogLevel value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        OtelAgentLogLevel value() {
            return value;
        }

        @JsonAnySetter
        void rejectUnknownField(String field, JsonNode ignored) {
            throw new IllegalArgumentException("Unknown request field: " + field);
        }
    }

    public static final class StrictBooleanDeserializer
            extends JsonDeserializer<Boolean> {

        @Override
        public Boolean deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) {
                return true;
            }
            if (parser.hasToken(JsonToken.VALUE_FALSE)) {
                return false;
            }
            return context.reportInputMismatch(
                    Boolean.class,
                    "value must be a JSON boolean");
        }
    }

    public static final class StrictDoubleDeserializer
            extends JsonDeserializer<Double> {

        @Override
        public Double deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)
                    || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return parser.getDoubleValue();
            }
            return context.reportInputMismatch(
                    Double.class,
                    "value must be a JSON number");
        }
    }

    public static final class StrictRouteRatiosDeserializer
            extends JsonDeserializer<Map<String, Double>> {

        @Override
        public Map<String, Double> deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) {
                return context.reportInputMismatch(
                        Map.class,
                        "value must be a JSON object");
            }
            Map<String, Double> routes = new TreeMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String route = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (valueToken != JsonToken.VALUE_NUMBER_INT
                        && valueToken != JsonToken.VALUE_NUMBER_FLOAT) {
                    return context.reportInputMismatch(
                            Map.class,
                            "route ratios must contain numeric values");
                }
                if (routes.size() >= CompleteTracingPolicySnapshot.MAX_ROUTE_ENTRIES) {
                    return context.reportInputMismatch(
                            Map.class,
                            "route ratios exceed the supported entry limit");
                }
                if (routes.put(route, parser.getDoubleValue()) != null) {
                    return context.reportInputMismatch(
                            Map.class,
                            "route ratios contain a duplicate route");
                }
            }
            return routes;
        }
    }

    public static final class StrictLogLevelDeserializer
            extends JsonDeserializer<OtelAgentLogLevel> {

        @Override
        public OtelAgentLogLevel deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(
                        OtelAgentLogLevel.class,
                        "value must be a JSON string");
            }
            String token = parser.getText();
            try {
                return OtelAgentLogLevel.valueOf(token);
            } catch (IllegalArgumentException exception) {
                return context.reportInputMismatch(
                        OtelAgentLogLevel.class,
                        "unsupported diagnostics log level: %s",
                        token);
            }
        }
    }
}
