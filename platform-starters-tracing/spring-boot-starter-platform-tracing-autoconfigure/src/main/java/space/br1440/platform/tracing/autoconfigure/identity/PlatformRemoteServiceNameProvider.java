package space.br1440.platform.tracing.autoconfigure.identity;

import java.util.Optional;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceNameResolver;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import lombok.RequiredArgsConstructor;

/**
 * Поставщик логического имени upstream-сервиса, вызов которого завершился ошибкой в рамках текущего запроса.
 * <p>
 * Назначение: внешний errorhandling-стартер потребителя инжектирует
 * {@code Supplier<Optional<String>> platformRemoteServiceNameProvider} и использует его
 * значение для заполнения поля {@code domain} cause-уровневого DTO ошибки
 * ({@code space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO#domain}).
 */
@RequiredArgsConstructor
public final class PlatformRemoteServiceNameProvider implements Supplier<Optional<String>> {

    private final RemoteServiceNameResolver resolver;

    @Override
    public Optional<String> get() {
        String traceId = safeCurrentTraceId();
        Optional<String> remoteService = (traceId == null) ? resolver.resolve() : resolver.resolve(traceId);
        remoteService.ifPresent(RemoteServiceMdcBoundarySupport::project);
        return remoteService;
    }

    private static String safeCurrentTraceId() {
        try {
            SpanContext spanContext = Span.current().getSpanContext();
            return spanContext.isValid() ? spanContext.getTraceId() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
