package space.br1440.platform.tracing.autoconfigure.metrics.utils;

import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Nullable;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class CoalescingObservationMetricReader {

    public static double readMetric(Map<String, Long> metrics, String key) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(key, "key");

        Long value = metrics.get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }

    public static double readTriStateMetric(@Nullable Map<String, Long> metrics, String key) {
        Objects.requireNonNull(key, "key");

        if (metrics == null) {
            return Double.NaN;
        }

        Long value = metrics.get(key);
        return (value != null) ? value.doubleValue() : 0.0;
    }
}
