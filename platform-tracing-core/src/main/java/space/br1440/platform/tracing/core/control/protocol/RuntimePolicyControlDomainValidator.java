package space.br1440.platform.tracing.core.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyPropertiesValidator;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RuntimePolicyControlDomainValidator {

    private RuntimePolicyControlDomainValidator() {
    }

    public static TracingControlDomainValidationResult validate(Map<String, Object> normalizedPayload) {
        try {
            SamplingPolicyPropertiesValidator.validate(toSamplingPolicyProperties(normalizedPayload));
            return TracingControlDomainValidationResult.success();
        } catch (IllegalArgumentException ex) {
            return TracingControlDomainValidationResult.invalid(ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static SamplingPolicyProperties toSamplingPolicyProperties(Map<String, Object> payload) {
        double defaultRatio = valueOrDefault(payload.get(TracingControlProtocolKeys.SAMPLING_RATIO), 1.0d);
        String[] droppedRoutes = (String[]) payload.get(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES);
        String[] forceValues = (String[]) payload.get(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES);
        Map<String, Double> routeRatios =
                (Map<String, Double>) payload.get(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS);

        return new SamplingPolicyProperties(
                true,
                defaultRatio,
                droppedRoutes == null ? null : Arrays.asList(droppedRoutes),
                forceValues == null ? null : Set.copyOf(Arrays.asList(forceValues)),
                routeRatios == null ? null : new LinkedHashMap<>(routeRatios));
    }

    private static double valueOrDefault(Object value, double fallback) {
        return value instanceof Double d ? d : fallback;
    }
}
