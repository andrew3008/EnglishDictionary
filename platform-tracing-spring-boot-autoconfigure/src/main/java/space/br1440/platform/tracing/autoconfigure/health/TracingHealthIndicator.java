package space.br1440.platform.tracing.autoconfigure.health;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;

/**
 * Health indicator платформенного модуля трассировки.
 * <p>
 * Возвращает статус {@code UP} при наличии полноценной реализации {@link TraceOperations} и
 * инициализированного {@link OpenTelemetry}; статус {@code OUT_OF_SERVICE} при использовании
 * безоперационной заглушки {@link NoopTraceOperations}, что обычно означает отсутствие подключённого
 * Java Agent'а или ошибку инициализации.
 * <p>
 * Health indicator не отправляет данные в Collector и не влияет на производительность приложения.
 */
public class TracingHealthIndicator extends AbstractHealthIndicator {

    private final TraceOperations traceOperations;

    public TracingHealthIndicator(TraceOperations traceOperations) {
        this.traceOperations = traceOperations;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean noop = traceOperations instanceof NoopTraceOperations;
        OpenTelemetry global;
        try {
            global = GlobalOpenTelemetry.get();
        } catch (RuntimeException e) {
            // Получение GlobalOpenTelemetry может выбросить только RuntimeException (см. контракт OTel API);
            // ловим именно его, чтобы не маскировать ошибки JVM (Error и checked-исключения недоступны здесь).
            global = null;
        }

        builder.withDetail("platformTracingRuntime", traceOperations.getClass().getName());
        builder.withDetail("globalOpenTelemetryInitialized", global != null);

        if (noop || global == null) {
            builder.outOfService();
            builder.withDetail("reason", noop
                    ? "Платформенный фасад работает в безоперационном режиме (NoopTraceOperations)"
                    : "GlobalOpenTelemetry не инициализирован");
            return;
        }

        builder.up();
    }
}
