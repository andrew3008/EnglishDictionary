package space.br1440.platform.errorhandling.mvc.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * <h2>Назначение</h2>
 * <p>Дополнение к structured-логам: метрика даёт быструю агрегацию error rate без
 * необходимости парсить логи в Kibana. Полезно для SLO-дашбордов и алертинга
 * (например, «error_handling_total с http_status=5xx за последние 5 минут > N»).
 *
 * <h2>Cardinality</h2>
 * <p>Все labels — fixed cardinality:
 * <ul>
 *   <li>{@code error_code} — конечный набор canon-кодов
 *       (см. {@code HttpStatusToErrorCodeMapper}).</li>
 *   <li>{@code http_status} — числовой статус, ограничен RFC 9110 §15.</li>
 *   <li>{@code domain} — обычно {@code spring.application.name}, реже — per-strategy override;
 *   в production практически статичен.</li>
 * </ul>
 * <p>{@code correlationId} исключён намеренно: уникальный per-request идентификатор создаёт
 * неограниченную кардинальность тегов, что ведёт к неконтролируемому росту временны́х рядов
 * и деградации любой Time Series Database (Prometheus, VictoriaMetrics и аналогов).
 * Micrometer перед отправкой метрики строит её идентификатор из всех тегов.
 * Если добавить тег correlationId, то каждый уникальный correlation ID порождает новый временной ряд.
 */
public final class MicrometerExceptionMetricsRecorder implements ExceptionObservabilityHook {

    public static final String METRIC_NAME = "error_handling_total";

    private static final String DOMAIN_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public MicrometerExceptionMetricsRecorder(@NonNull MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onException(@NonNull Exception exception,
                            @NonNull ErrorEntry entry,
                            @NonNull String correlationId) {
        Counter.builder(METRIC_NAME)
                .description("Total HTTP exceptions handled by br1440 error-handling pipeline")
                .tag("error_code", entry.getErrorCode())
                .tag("http_status", String.valueOf(entry.getHttpStatus().value()))
                .tag("domain", resolveDomain(entry))
                .register(registry)
                .increment();
    }

    @NonNull
    private static String resolveDomain(@NonNull ErrorEntry entry) {
        String domain = entry.getDomain();
        return StringUtils.hasText(domain) ? domain : DOMAIN_UNKNOWN;
    }
}
