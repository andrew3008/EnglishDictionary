package space.br1440.platform.tracing.autoconfigure.errorhandling;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.function.Supplier;

/**
 * Поставщик снимка {@link RequestTraceContextSnapshot} для последующего маппинга в модель ошибок
 * ({@code RequestContext} в {@code web-error-model}) на стороне error-handling-стартеров.
 * <p>
 * Назначение: модуль трассировки регистрирует bean {@code Supplier<RequestTraceContextSnapshot>}
 * с именем {@code platformRequestTraceContextSnapshotSupplier}; авто-конфигурация error-handling
 * оборачивает его в {@code Supplier<RequestContext>} для {@code @ControllerAdvice}.
 * <p>
 * Источники данных по приоритету:
 * <ol>
 *   <li>{@code Span.current().getSpanContext()} — если активный span валиден, оттуда берутся
 *       {@code traceId} и {@code spanId} в шестнадцатеричном виде.</li>
 *   <li>MDC по ключу {@link TracingMdcKeys#CORRELATION_ID} — единый платформенный ключ
 *       корреляции (snake_case), выставляемый фильтрами логирования и error-handling.</li>
 * </ol>
 * <p>
 * <b>Контракт (non-blocking):</b> метод {@link #get()} никогда не выбрасывает исключений.
 * Любые сбои чтения OTel-контекста или MDC логируются на уровне {@code DEBUG} и приводят к
 * корректному fallback'у — {@link RequestTraceContextSnapshot} с {@code traceId=null}, {@code spanId=null}
 * и доступным значением {@code correlationId} из MDC.
 * <p>
 * Поведение при отключённом tracing ({@code platform.tracing.enabled=false}): {@code Span.current()}
 * возвращает {@code Span.getInvalid()}, supplier отдаёт {@link RequestTraceContextSnapshot} с
 * {@code correlationId} из MDC и пустыми идентификаторами span/trace. Это ожидаемое поведение —
 * errorhandling-стартер всё равно получает корректный контекст по корреляции.
 */
public final class RequestTraceContextSnapshotSupplier implements Supplier<RequestTraceContextSnapshot> {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceContextSnapshotSupplier.class);

    @Override
    public RequestTraceContextSnapshot get() {
        // correlationId читаем первым делом и независимо от состояния OTel-контекста:
        // он должен попасть в снимок даже если активного span нет.
        String correlationId = readMdcSafely(TracingMdcKeys.CORRELATION_ID);
        try {
            SpanContext ctx = Span.current().getSpanContext();
            if (ctx.isValid()) {
                return new RequestTraceContextSnapshot(correlationId, ctx.getTraceId(), ctx.getSpanId());
            }
        } catch (RuntimeException e) {
            // Ошибка трассировки не должна валить запрос пользователя.
            // Возможные источники: повреждённый ContextStorage, проблемы Thread Context,
            // некорректная замена Storage-обёртки в редких тестовых сценариях.
            log.debug("Не удалось прочитать SpanContext при формировании RequestTraceContextSnapshot", e);
        }
        return new RequestTraceContextSnapshot(correlationId, null, null);
    }

    /**
     * Безопасное чтение MDC: возвращает {@code null} при любом RuntimeException,
     * чтобы исключить даже теоретическую возможность обвалить пользовательский запрос
     * через сторонний MDC adapter.
     */
    private static String readMdcSafely(String key) {
        try {
            return MDC.get(key);
        } catch (RuntimeException e) {
            log.debug("Не удалось прочитать MDC ключ {}", key, e);
            return null;
        }
    }
}
