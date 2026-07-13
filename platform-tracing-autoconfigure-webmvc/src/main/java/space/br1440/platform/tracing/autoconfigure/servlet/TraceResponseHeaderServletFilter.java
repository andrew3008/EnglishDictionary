package space.br1440.platform.tracing.autoconfigure.servlet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import space.br1440.platform.tracing.api.TraceOperations;
import org.slf4j.MDC;
import io.opentelemetry.api.trace.Span;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import space.br1440.platform.tracing.api.mdc.RemoteServiceMdc;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.io.IOException;
import java.util.Optional;

/**
 * Сервлет-фильтр, добавляющий correlation/trace заголовки в HTTP-ответ.
 * <p>
 * {@code X-Request-Id} (имя — {@code platform.tracing.response.header-name}) — это
 * <b>edge-stable correlation id</b> (НЕ trace-id, см. ADR-request-id-correlation-id): входящий
 * валидируется и переиспользуется, при отсутствии генерируется UUIDv4. Авторитетный trace-id
 * возвращается отдельно в {@link PlatformHeaders#X_TRACE_ID} (только для валидного span context).
 * Инвариант: {@code request_id != trace_id}.
 *
 * <h3>Тайминг записи заголовков: capture-before-chain</h3>
 * Идентификатор trace'а захватывается в локальную переменную <b>до</b> вызова
 * {@code filterChain.doFilter}, заголовок выставляется в блоке {@code finally} <b>после</b>
 * завершения цепочки фильтров. Это industry-стандарт, реализованный в Spring Framework
 * {@code ServerHttpObservationFilter} (#30632) и Spring Cloud Sleuth {@code TraceFilter}.
 * <p>
 * <b>Почему именно так, а не до {@code doFilter}:</b> OpenTelemetry Java Agent инструментирует
 * Tomcat servlet lifecycle байткодом и закрывает SERVER span при выходе из
 * {@code HttpServlet.service()} — раньше, чем управление возвращается в caller'а
 * {@code filterChain.doFilter}. После {@code doFilter} {@code Span.current()} становится
 * invalid (или родительским propagation-span'ом). Поэтому захват trace context производится
 * <i>при входе</i> в фильтр, когда контекст гарантированно валиден, а запись заголовка
 * откладывается до завершения обработки — чтобы заголовок отражал именно тот span,
 * который обработал запрос.
 *
 * <h3>Ограничение для async-dispatch (W3)</h3>
 * Trace-заголовки гарантированы для <b>синхронных</b> HTTP-ответов. При Spring MVC async
 * dispatch ({@code DeferredResult}, {@code Callable}, {@code WebAsyncManager}) ответ может
 * быть ещё не committed, но заголовки уже locked внутри контейнера — {@code setHeader()}
 * молча игнорируется. Для async endpoints используйте OpenTelemetry Java Agent response
 * propagation либо явный callback до commit'а.
 */
public class TraceResponseHeaderServletFilter extends OncePerRequestFilter {

    private final TraceOperations traceOperations;
    private final TracingProperties properties;

    public TraceResponseHeaderServletFilter(TraceOperations traceOperations, TracingProperties properties) {
        this.traceOperations = traceOperations;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final Optional<String> capturedTraceId = safeCurrentTraceId();

        // X-Request-Id = edge-stable correlation id (НЕ trace-id): входящий валидируется и
        // переиспользуется (forward unchanged), при отсутствии/невалидности — генерируется UUIDv4.
        final TracingProperties.Propagation.Mdc mdcConfig = properties.getPropagation().getMdc();
        final String incomingRequestId = request.getHeader(
                properties.getPropagation().getPlatformHeaders().getRequestIdHeader());
        final String correlationId = RequestIdSupport.resolve(incomingRequestId);

        if (mdcConfig.isPutRequestId()) {
            MDC.put(mdcConfig.getRequestIdKey(), correlationId);
        }
        try {
            // platform.request_id в текущий span (только при валидном контексте).
            Span span = Span.current();
            if (span.getSpanContext().isValid()) {
                span.setAttribute(PlatformAttributes.PLATFORM_REQUEST_ID, correlationId);
            }
        } catch (RuntimeException ignored) {
            // Ошибки трассировки не влияют на обработку запроса.
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            capturedTraceId.ifPresentOrElse(RemoteServiceMdc::clearForTrace, RemoteServiceMdc::clear);

            if (mdcConfig.isPutRequestId()) {
                MDC.remove(mdcConfig.getRequestIdKey());
            }

            if (!response.isCommitted()) {
                try {
                    // X-Request-Id = correlation id (всегда присутствует — сгенерирован при отсутствии входящего).
                    if (properties.getResponse().isExposeRequestIdHeader()) {
                        response.setHeader(properties.getResponse().getHeaderName(), correlationId);
                    }
                    // X-Trace-Id = trace-id, только для валидного span context (не all-zeros).
                    capturedTraceId.ifPresent(traceId -> response.setHeader(PlatformHeaders.X_TRACE_ID, traceId));
                } catch (RuntimeException ignored) {
                    // Любые ошибки трассировки не должны влиять на обработку запроса.
                }
            }
        }
    }

    /**
     * Защищённое получение текущего trace-id: исключения трассировки не должны прерывать обработку.
     */
    private Optional<String> safeCurrentTraceId() {
        try {
            return traceOperations.traceContext().traceId();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
