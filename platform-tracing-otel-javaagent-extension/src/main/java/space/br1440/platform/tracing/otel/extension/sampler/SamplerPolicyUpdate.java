package space.br1440.platform.tracing.otel.extension.sampler;

import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.core.sampling.properties.SamplingPolicyPropertiesValidator;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and builds the next {@link SamplerState} for atomic runtime policy updates (PR-6D).
 * Side-effect-free: safe to invoke from CAS {@code tryUpdate} builders after pre-validation.
 * <p>
 * Доменная валидация делегирована единому core-валидатору {@link SamplingPolicyPropertiesValidator}; здесь
 * остаётся только wire/update-shape проверка: parity параллельных массивов prefix/value, которую нельзя
 * выразить на логическом конфиге.
 */
final class SamplerPolicyUpdate {

    private SamplerPolicyUpdate() {
    }

    static void validateDomain(
            double defaultRatio,
            String[] droppedRoutes,
            String[] forceRecordValues,
            String[] routeRatioPrefixes,
            double[] routeRatioValues) {
        // Wire-shape: parity параллельных массивов проверяем до доменной валидации.
        validateRouteRatioArrayShape(routeRatioPrefixes, routeRatioValues);
        // Доменные инварианты — единый core-валидатор (границы ratio, drop/force, blank-префиксы, лимиты).
        SamplingPolicyPropertiesValidator.validate(new SamplingPolicyProperties(
                true,
                defaultRatio,
                droppedRoutes == null ? null : Arrays.asList(droppedRoutes),
                forceRecordValues == null ? null : new HashSet<>(Arrays.asList(forceRecordValues)),
                toValidationRatioMap(routeRatioPrefixes, routeRatioValues)));
    }

    static SamplerState buildNext(
            SamplerState previous,
            boolean enabled,
            double defaultRatio,
            String[] droppedRoutes,
            String[] forceRecordValues,
            String[] routeRatioPrefixes,
            double[] routeRatioValues,
            String source) {
        return new SamplerState(
                enabled,
                toDropPathList(droppedRoutes),
                toForceValueSet(forceRecordValues),
                toRouteRatioMap(routeRatioPrefixes, routeRatioValues),
                defaultRatio,
                previous.version() + 1,
                Instant.now(),
                normalizeSource(source));
    }

    static String normalizeSource(String source) {
        if (Strings.isBlank(source)) {
            return "JMX";
        }
        return source.trim();
    }

    /** Wire/update-shape проверка: оба массива присутствуют и одинаковой длины (parity). */
    private static void validateRouteRatioArrayShape(String[] routeRatioPrefixes, double[] routeRatioValues) {
        if (routeRatioPrefixes == null && routeRatioValues == null) {
            return;
        }
        if (routeRatioPrefixes == null || routeRatioValues == null) {
            throw new IllegalArgumentException("routeRatioPrefixes and routeRatioValues must both be present");
        }
        if (routeRatioPrefixes.length != routeRatioValues.length) {
            throw new IllegalArgumentException("routeRatioPrefixes length must match routeRatioValues length");
        }
    }

    /**
     * Собирает логическую map route-ratio для доменной валидации. Ключи берутся «сырыми» (могут быть
     * null/blank) — это намеренно: проверку blank/null делает {@link SamplingPolicyPropertiesValidator}.
     */
    private static Map<String, Double> toValidationRatioMap(String[] routeRatioPrefixes, double[] routeRatioValues) {
        if (routeRatioPrefixes == null || routeRatioPrefixes.length == 0) {
            return Map.of();
        }
        Map<String, Double> ratios = new HashMap<>(routeRatioPrefixes.length);
        for (int i = 0; i < routeRatioPrefixes.length; i++) {
            ratios.put(routeRatioPrefixes[i], routeRatioValues[i]);
        }
        return ratios;
    }

    private static List<String> toDropPathList(String[] droppedRoutes) {
        if (droppedRoutes == null || droppedRoutes.length == 0) {
            return List.of();
        }
        return List.copyOf(Arrays.asList(droppedRoutes));
    }

    private static Set<String> toForceValueSet(String[] forceRecordValues) {
        if (forceRecordValues == null || forceRecordValues.length == 0) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(forceRecordValues));
    }

    private static Map<String, Double> toRouteRatioMap(String[] routeRatioPrefixes, double[] routeRatioValues) {
        if (routeRatioPrefixes == null || routeRatioPrefixes.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Double> ratios = new HashMap<>(routeRatioPrefixes.length);
        for (int i = 0; i < routeRatioPrefixes.length; i++) {
            ratios.put(routeRatioPrefixes[i].trim(), routeRatioValues[i]);
        }
        return ratios;
    }
}
