package space.br1440.platform.errorhandling.extension.metrics;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Objects;

public record ExceptionMetricSnapshot(@NonNull String strategyName,
                                      @NonNull String exceptionType,
                                      int httpStatusCode,
                                      long durationNanos,
                                      @Nullable String errorCode,
                                      @Nullable String requestPath,
                                      @Nullable String rootCauseType) {

    public ExceptionMetricSnapshot {
        Objects.requireNonNull(strategyName, "strategyName must not be null");
        Objects.requireNonNull(exceptionType, "exceptionType must not be null");
    }

    /**
     * Серия HTTP-статуса в формате {@code "2xx"}, {@code "4xx"}, {@code "5xx"}.
     * Используется как low-cardinality тег Micrometer для группировки метрик по классу статуса.
     */
    @NonNull
    public String httpStatusClass() {
        return (httpStatusCode / 100) + "xx";
    }

}
