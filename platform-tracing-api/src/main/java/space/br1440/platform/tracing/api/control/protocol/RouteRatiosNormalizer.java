package space.br1440.platform.tracing.api.control.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RouteRatiosNormalizer {

    private RouteRatiosNormalizer() {
    }

    static Map<String, Double> normalize(String key,
                                         Object value,
                                         List<TracingControlProtocolViolation> violations) {
        if (!(value instanceof Map<?, ?> raw)) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "invalid wire type",
                    TracingControlProtocolFieldType.ROUTE_RATIOS_MAP.name(),
                    FieldTypeSupport.typeName(value),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return null;
        }

        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String routeKey)) {
                violations.add(FieldTypeSupport.violation(
                        key,
                        "routeRatios map keys must be String",
                        "Map<String,Double>",
                        FieldTypeSupport.typeName(entry.getKey()),
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                return null;
            }

            Object routeValue = entry.getValue();
            if (routeValue instanceof Enum<?>) {
                violations.add(FieldTypeSupport.violation(
                        key,
                        "enum instance rejected in routeRatios",
                        "Double",
                        FieldTypeSupport.typeName(routeValue),
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                return null;
            }

            Double ratio = FieldTypeSupport.validateDouble(key + "." + routeKey, routeValue, violations);
            if (ratio == null) {
                return null;
            }

            normalized.put(routeKey, ratio);
        }

        return normalized;
    }
}
