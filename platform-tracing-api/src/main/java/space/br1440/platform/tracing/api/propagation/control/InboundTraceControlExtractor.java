package space.br1440.platform.tracing.api.propagation.control;

import jakarta.annotation.Nullable;

/**
 * Извлекает параметры управления трассировкой из входящих платформенных заголовков.
 * <p>
 * Реализация: {@code space.br1440.platform.tracing.core.propagation.control.DefaultInboundTraceControlExtractor}.
 */
public interface InboundTraceControlExtractor {

    /**
     * Парсит три входящих платформенных заголовка и возвращает {@link InboundTraceControl}.
     *
     * @param traceOn   значение заголовка {@code X-Trace-On} (или {@code null})
     * @param qaTrace   значение заголовка {@code X-Qa-Trace} (или {@code null})
     * @param requestId значение заголовка {@code X-Request-Id} (или {@code null})
     * @return распарсенный контекст входящей трассировки; никогда не {@code null}
     */
    InboundTraceControl fromHeaders(
            @Nullable String traceOn,
            @Nullable String qaTrace,
            @Nullable String requestId);
}
