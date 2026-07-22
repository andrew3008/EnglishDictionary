package space.br1440.platform.tracing.autoconfigure.servicename;

import io.opentelemetry.api.trace.Span;
import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Поставщик логического имени upstream-сервиса, вызов которого завершился ошибкой
 * в рамках текущего запроса.
 * <p>
 * Назначение: внешний errorhandling-стартер потребителя инжектирует
 * {@code Supplier<Optional<String>> platformRemoteServiceNameProvider} и использует его
 * значение для заполнения поля {@code domain} cause-уровневого DTO ошибки
 * ({@code space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO#domain}).
 * <p>
 * Источник: {@link RemoteServiceNameResolver} (MDC → contributed sources → trace-scoped mirror).
 * Запись MDC выполняется {@link space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceMdc}
 * из {@code EnrichingSpanProcessor} при завершении ERROR'ного CLIENT-span'а.
 * <p>
 * Бин создаётся через {@link space.br1440.platform.tracing.autoconfigure.ServiceNameProviderAutoConfiguration};
 * прямое создание экземпляра вне тестов не поддерживается.
 * <p>
 * <b>Контракт §37 (non-blocking):</b> метод {@link #get()} никогда не выбрасывает исключений
 * и не выполняет блокирующих операций.
 */
public final class PlatformRemoteServiceNameProvider implements Supplier<Optional<String>> {

    private final RemoteServiceNameResolver resolver;

    public PlatformRemoteServiceNameProvider(RemoteServiceNameResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Optional<String> get() {
        String traceId = safeCurrentTraceId();
        return traceId != null ? resolver.resolve(traceId) : resolver.resolve();
    }

    private static String safeCurrentTraceId() {
        try {
            var spanContext = Span.current().getSpanContext();
            return spanContext.isValid() ? spanContext.getTraceId() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
