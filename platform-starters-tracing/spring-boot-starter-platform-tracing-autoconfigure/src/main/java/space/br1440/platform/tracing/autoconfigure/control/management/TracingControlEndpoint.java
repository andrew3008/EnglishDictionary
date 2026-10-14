package space.br1440.platform.tracing.autoconfigure.control.management;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.lang.Nullable;

import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;

@WebEndpoint(id = "tracingControl")
public final class TracingControlEndpoint {

    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_UNPROCESSABLE_ENTITY = 422;
    private static final String INVALID_VALUE = "INVALID_VALUE";

    private final TracingSettingsMutationService mutationService;

    public TracingControlEndpoint(TracingSettingsMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @WriteOperation
    public WebEndpointResponse<Map<String, Object>> update(@Selector String group,
                                                           @Selector String setting,
                                                           @Nullable Boolean enabled,
                                                           @Nullable Double ratio,
                                                           @Nullable String routeRatios,
                                                           @Nullable String level) {
        return switch (group + "/" + setting) {
            case "sampling/enabled" -> apply(
                    "sampling.enabled", "enabled", enabled,
                    mutationService::setSamplingEnabled, value -> value
            );

            case "sampling/ratio" -> apply(
                    "sampling.ratio", "ratio", ratio,
                    mutationService::setSamplingRatio, value -> value
            );

            case "sampling/route-ratios" -> applyRouteRatios(routeRatios);

            case "validation/level" -> apply(
                    "validation.level", "level", level,
                    mutationService::setValidationLevel, value -> value
            );

            case "exporter/enabled" -> apply(
                    "exporter.enabled", "enabled", enabled,
                    mutationService::setExporterEnabled, value -> value
            );

            case "propagation/trace-control-headers-enabled" -> apply(
                    "propagation.trace-control-headers.enabled", "enabled", enabled,
                    mutationService::setTraceControlHeadersEnabled, value -> value
            );

            case "diagnostics/log-level" -> apply(
                    "diagnostics.log-level", "level", level,
                    mutationService::setDiagnosticsLogLevel, value -> value
            );

            default -> unsupported(group, setting);
        };
    }

    private WebEndpointResponse<Map<String, Object>> applyRouteRatios(@Nullable String encodedRouteRatios) {
        if (encodedRouteRatios == null) {
            return missing("sampling.route-ratios", "routeRatios");
        }

        Map<String, Double> routeRatios;
        try {
            routeRatios = parseRouteRatios(encodedRouteRatios);
        } catch (IllegalArgumentException invalidInput) {
            return rejected(
                    "sampling.route-ratios",
                    "routeRatios must contain a JSON object with numeric values",
                    WebEndpointResponse.STATUS_BAD_REQUEST
            );
        }

        return apply("sampling.route-ratios", "routeRatios", routeRatios,
                mutationService::replaceSamplingRouteRatios, Map::size);
    }

    private static Map<String, Double> parseRouteRatios(String encodedRouteRatios) {
        JsonParser parser = JsonParserFactory.getJsonParser();

        Map<String, Object> parsed;
        try {
            parsed = parser.parseMap(encodedRouteRatios);
        } catch (RuntimeException invalidJson) {
            throw new IllegalArgumentException("routeRatios must be a JSON object", invalidJson);
        }

        Map<String, Double> routeRatios = new LinkedHashMap<>(parsed.size());
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (!(entry.getValue() instanceof Number ratio)) {
                throw new IllegalArgumentException("routeRatios values must be numeric");
            }

            routeRatios.put(entry.getKey(), ratio.doubleValue());
        }
        return routeRatios;
    }

    private static <T> WebEndpointResponse<Map<String, Object>> apply(String leaf,
                                                                      String parameter,
                                                                      @Nullable T value,
                                                                      Function<T, SettingsDelivery> mutation,
                                                                      Function<T, Object> responseValue) {
        if (value == null) {
            return missing(leaf, parameter);
        }

        SettingsDelivery delivery;
        try {
            delivery = mutation.apply(value);
        } catch (IllegalArgumentException invalidCandidate) {
            return rejected(leaf, invalidCandidate.getMessage(), STATUS_UNPROCESSABLE_ENTITY);
        }

        int status = switch (delivery.outcome()) {
            case APPLIED, UNCHANGED -> WebEndpointResponse.STATUS_OK;
            case REJECTED -> STATUS_UNPROCESSABLE_ENTITY;
            case STALE -> STATUS_CONFLICT;
            case FAILED -> WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE;
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", delivery.outcome().name());
        body.put("leaf", leaf);
        body.put("value", responseValue.apply(value));
        body.put("attemptId", delivery.attemptId());
        body.put("messageCode", delivery.messageCode());
        return new WebEndpointResponse<>(body, status);
    }

    private static WebEndpointResponse<Map<String, Object>> missing(String leaf, String parameter) {
        return rejected(
                leaf,
                "Required request parameter is missing: " + parameter,
                WebEndpointResponse.STATUS_BAD_REQUEST
        );
    }

    private static WebEndpointResponse<Map<String, Object>> unsupported(String group, String setting) {
        return rejected(
                group + "." + setting,
                "Unsupported tracing control operation",
                WebEndpointResponse.STATUS_NOT_FOUND
        );
    }

    private static WebEndpointResponse<Map<String, Object>> rejected(String leaf,
                                                                     String error,
                                                                     int status) {
        return new WebEndpointResponse<>(Map.of(
                "outcome", TracingSettingsDeliveryResult.REJECTED.name(),
                "leaf", leaf,
                "messageCode", INVALID_VALUE,
                "error", error), status
        );
    }
}
