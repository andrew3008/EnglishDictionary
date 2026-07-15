package space.br1440.platform.tracing.api.propagation.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Извлечение {@link InboundTraceControl} из сырых значений carrier (HTTP/Kafka заголовков).
 * <p>
 * Каноническая реализация — {@code DefaultInboundTraceControlExtractor} в {@code platform-tracing-core}.
 * Реализации обязаны быть thread-safe и stateless.
 */
public interface InboundTraceControlExtractor {

    /**
     * Парсит три входящих платформенных заголовка и возвращает {@link InboundTraceControl}.
     * Все параметры допускают {@code null} — отсутствующий заголовок эквивалентен {@code null}.
     *
     * @param traceOn    значение заголовка {@code X-Trace-On}; {@code null} если отсутствует
     * @param qaTrace    значение заголовка {@code X-Qa-Trace}; {@code null} если отсутствует
     * @param requestId  значение заголовка {@code X-Request-Id}; {@code null} если отсутствует
     * @return распарсенный контекст; никогда не {@code null}
     */
    @Nonnull
    InboundTraceControl fromHeaders(
            @Nullable String traceOn,
            @Nullable String qaTrace,
            @Nullable String requestId);
}
