package space.br1440.platform.errorhandling.core.dispatcher;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * Сборщик {@link ExceptionMetricSnapshot} — единая точка истины для метрики
 * любого транспорта (MVC / WebFlux / gRPC).
 *
 * <h2>Что делает</h2>
 * <p>Извлекает корневой cause через {@link CauseChainNavigator} (для метрики
 * полезен именно root cause: он отражает реальную природу сбоя — DB connection
 * loss, OOM, etc.), упаковывает имя стратегии, тип исключения, HTTP-статус,
 * длительность обработки, error-code, путь и rootCauseType в неизменяемый
 * {@link ExceptionMetricSnapshot}.
 */
@UtilityClass
public final class MetricSnapshotFactory {

    @NonNull
    public static ExceptionMetricSnapshot build(@NonNull String strategyName,
                                                @NonNull Exception exception,
                                                @NonNull ErrorEntry entry,
                                                long durationNanos,
                                                @NonNull String requestPath) {
        Throwable rootCause = CauseChainNavigator.findDeepestCause(exception);
        String rootCauseType = rootCauseType(exception, rootCause);

        return new ExceptionMetricSnapshot(
                strategyName,
                exception.getClass().getName(),
                entry.getHttpStatus().value(),
                durationNanos,
                entry.getErrorCode(),
                requestPath,
                rootCauseType
        );
    }

    @Nullable
    private static String rootCauseType(@NonNull Exception exception, @NonNull Throwable rootCause) {
        return (rootCause != exception) ? rootCause.getClass().getName() : null;
    }
}
