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

import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;

@WebEndpoint(id = "tracingControl")
public final class TracingControlEndpoint {

    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_UNPROCESSABLE_ENTITY = 422;

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
            return new WebEndpointResponse<>(Map.of(
                    "outcome", TracingSettingsDeliveryResult.REJECTED.name(),
                    "leaf", "sampling.route-ratios",
                    "error", "routeRatios must contain a JSON object with numeric values"),
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
                                                                      Function<T, TracingSettingsDeliveryResult> mutation,
                                                                      Function<T, Object> responseValue) {
        if (value == null) {
            return missing(leaf, parameter);
        }

        TracingSettingsDeliveryResult result = mutation.apply(value);
        int status = switch (result) {
            case APPLIED, UNCHANGED -> WebEndpointResponse.STATUS_OK;
            case REJECTED -> STATUS_UNPROCESSABLE_ENTITY;
            case STALE -> STATUS_CONFLICT;
            case FAILED -> WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE;
        };

        return new WebEndpointResponse<>(Map.of(
                "outcome", result.name(),
                "leaf", leaf,
                "value", responseValue.apply(value)), status
        );
    }

    private static WebEndpointResponse<Map<String, Object>> missing(String leaf, String parameter) {
        return new WebEndpointResponse<>(Map.of(
                "outcome", TracingSettingsDeliveryResult.REJECTED.name(),
                "leaf", leaf,
                "error", "Required request parameter is missing: " + parameter),
                WebEndpointResponse.STATUS_BAD_REQUEST
        );
    }

    private static WebEndpointResponse<Map<String, Object>> unsupported(String group, String setting) {
        return new WebEndpointResponse<>(Map.of(
                "outcome", TracingSettingsDeliveryResult.REJECTED.name(),
                "leaf", group + "." + setting,
                "error", "Unsupported tracing control operation"),
                WebEndpointResponse.STATUS_NOT_FOUND
        );
    }
}
