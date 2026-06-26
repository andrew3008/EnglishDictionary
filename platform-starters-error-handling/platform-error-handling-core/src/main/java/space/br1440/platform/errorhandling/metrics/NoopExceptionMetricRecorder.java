package space.br1440.platform.errorhandling.metrics;

import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricRecorder;
import space.br1440.platform.errorhandling.extension.metrics.ExceptionMetricSnapshot;

/**
 * No-op реализация {@link ExceptionMetricRecorder}.
 *
 * <p>Используется когда Micrometer отсутствует в classpath.
 * Все вызовы — пустые операции с нулевым overhead.
 */
public final class NoopExceptionMetricRecorder implements ExceptionMetricRecorder {

    @Override
    public void recordHandled(@NonNull ExceptionMetricSnapshot snapshot) {
    }

    @Override
    public void recordUnhandled(@NonNull ExceptionMetricSnapshot snapshot) {
    }
}
