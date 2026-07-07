package space.br1440.platform.tracing.autoconfigure.servicename;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.opentelemetry.api.trace.Span;
import space.br1440.platform.tracing.api.mdc.RemoteServiceContextReaders;
import space.br1440.platform.tracing.api.mdc.RemoteServiceTraceMirror;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

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
 * Источник: MDC-ключ {@link TracingMdcKeys#REMOTE_SERVICE}, заполняемый
 * {@link space.br1440.platform.tracing.api.mdc.RemoteServiceMdc} из {@code EnrichingSpanProcessor}
 * при завершении ERROR'ного CLIENT-span'а. Атрибут {@code platform.remote.service} на span'е
 * проставляется тем же процессором.
 * <p>
 * MDC-ключ имеет sticky-семантику в рамках HTTP-запроса и очищается в outermost response-фильтрах.
 * <p>
 * <b>WebFlux:</b> ThreadLocal MDC не гарантирован при async client на другом Reactor scheduler.
 * Fallback: trace-scoped mirror ({@link RemoteServiceTraceMirror}) и зарегистрированные
 * {@link RemoteServiceContextReaders} — см. {@code docs/decisions/ADR-remote-service-mdc-webflux.md}.
 * <p>
 * Если ключ отсутствует или пуст — {@link Optional#empty()}; вызывающий errorhandling
 * корректно интерпретирует это как «upstream неизвестен» и подставляет sentinel
 * {@code DOMAIN_UNSPECIFIED} в DTO.
 * <p>
 * <b>Контракт §37 (non-blocking):</b> метод {@link #get()} никогда не выбрасывает исключений
 * и не выполняет блокирующих операций.
 */
public final class PlatformRemoteServiceNameProvider implements Supplier<Optional<String>> {

    private static final Logger log = LoggerFactory.getLogger(PlatformRemoteServiceNameProvider.class);

    @Override
    public Optional<String> get() {
        try {
            String fromMdc = MDC.get(TracingMdcKeys.REMOTE_SERVICE);
            if (fromMdc != null && !fromMdc.isBlank()) {
                return Optional.of(fromMdc);
            }
        } catch (RuntimeException e) {
            // §37: ошибка чтения MDC не должна срывать формирование ответа об ошибке.
            log.debug("Не удалось прочитать MDC ключ {}: {}", TracingMdcKeys.REMOTE_SERVICE, e.getMessage());
        }

        Optional<String> fromReaders = RemoteServiceContextReaders.readFirst();
        if (fromReaders.isPresent()) {
            return fromReaders;
        }

        try {
            String traceId = Span.current().getSpanContext().getTraceId();
            return RemoteServiceTraceMirror.get(traceId);
        } catch (RuntimeException e) {
            log.debug("Не удалось прочитать trace-scoped mirror remote service: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
