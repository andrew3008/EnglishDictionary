package space.br1440.platform.errorhandling.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.core.util.CorrelationIdResolver;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.mvc.internal.ErrorMdcContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;

import java.io.IOException;

/**
 * Мост между Spring Security и единым pipeline обработки ошибок RFC 9457.
 *
 * <h2>Проблема</h2>
 * <p>
 * Spring Security перехватывает {@link AuthenticationException} и {@link AccessDeniedException}
 * в {@code ExceptionTranslationFilter} — на уровне фильтров, <b>до</b> {@code DispatcherServlet}.
 * Эти исключения <b>никогда</b> не достигают {@code @ExceptionHandler} и, следовательно, не обрабатываются {@code GlobalExceptionHandler}.
 * <p>
 * Без моста Security-ошибки возвращаются в стандартном формате Spring Boot ({@code /error}, Whitelabel Error Page),
 * нарушая единообразие API.
 *
 * <h2>Решение</h2>
 * <p>
 * {@code SecurityProblemSupport} реализует оба интерфейса Security-контракта и <b>перенаправляет</b> исключения
 * через {@link HandlerExceptionResolver} обратно в {@code GlobalExceptionHandler} → {@code SecurityExceptionStrategy}.
 * Результат: единый {@code ErrorEntryDTO} в формате {@code application/problem+json} для всех ошибок — как из контроллеров,
 * так и из security-фильтров.
 *
 * <h2>Поток обработки</h2>
 * <pre>
 *   Security Filter Chain
 *        │
 *        ▼
 *   ExceptionTranslationFilter
 *   ├── AuthenticationException ──► SecurityProblemSupport.commence()
 *   └── AccessDeniedException   ──► SecurityProblemSupport.handle()
 *                                          │
 *                                          ▼
 *                              HandlerExceptionResolver (composite)
 *                                          │
 *                                          ▼
 *                              ExceptionHandlerExceptionResolver
 *                                          │
 *                                          ▼
 *                              GlobalExceptionHandler.handleException()
 *                                          │
 *                                          ▼
 *                              SecurityExceptionStrategy
 *                                          │
 *                                          ▼
 *                              ResponseEntity<ErrorEntryDTO>
 *                              Content-Type: application/problem+json
 * </pre>
 *
 * <h2>Fallback</h2>
 * <p>
 * Если {@link HandlerExceptionResolver} не смог обработать исключение
 * (например, при startup-ошибках или неправильной конфигурации),
 * компонент записывает минимальный {@code application/problem+json}
 * ответ <b>напрямую</b> в {@link HttpServletResponse}.
 *
 * <h2>Интеграция</h2>
 * <p>
 * Зарегистрируйте в вашей конфигурации Spring Security:
 * <pre>{@code
 * @Bean
 * public SecurityFilterChain securityFilterChain(
 *         HttpSecurity http,
 *         SecurityProblemSupport problemSupport) throws Exception {
 *     http
 *         .exceptionHandling(ex -> ex
 *             .authenticationEntryPoint(problemSupport)
 *             .accessDeniedHandler(problemSupport))
 *         ...;
 *     return http.build();
 * }
 * }</pre>
 *
 * <p>Или используйте {@link ProblemSupportConfigurer} для декларативного подключения:
 * <pre>{@code
 * http.with(problemSupportConfigurer, Customizer.withDefaults())
 * }</pre>
 */
@Slf4j
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String CHARSET_UTF8 = "UTF-8";

    /**
     * Минимальный fallback JSON при недоступности HandlerExceptionResolver.
     * Структура соответствует {@link ErrorEntryDTO}: {@code httpCode}, {@code message}, {@code details}.
     */
    private static final String FALLBACK_401_TEMPLATE =
            "{\"httpCode\":401,\"message\":\"Authentication required\",\"details\":[{\"type\":\"ERROR_INFO\",\"reason\":\"UNAUTHENTICATED\",\"domain\":\"security\"}]}";

    private static final String FALLBACK_403_TEMPLATE =
            "{\"httpCode\":403,\"message\":\"Access denied\",\"details\":[{\"type\":\"ERROR_INFO\",\"reason\":\"PERMISSION_DENIED\",\"domain\":\"security\"}]}";

    private final HandlerExceptionResolver resolver;
    private final CorrelationIdResolver correlationIdResolver;

    public SecurityProblemSupport(@NonNull HandlerExceptionResolver resolver) {
        this(
                resolver,
                new CorrelationIdResolver(
                        new ErrorHandlingProperties.CorrelationId(),
                        true
                )
        );
    }

    public SecurityProblemSupport(@NonNull HandlerExceptionResolver resolver,
                                  @NonNull CorrelationIdResolver correlationIdResolver) {
        this.resolver = resolver;
        this.correlationIdResolver = correlationIdResolver;
    }

    @Override
    public void commence(@NonNull HttpServletRequest request,
                         @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException exception) throws IOException {
        log.debug("Security: Authentication failure on [{} {}]: {}",
                LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()),
                LogSanitizer.sanitize(exception.getMessage()));
        delegate(request, response, exception, HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void handle(@NonNull HttpServletRequest request,
                       @NonNull HttpServletResponse response,
                       @NonNull AccessDeniedException exception) throws IOException {
        log.debug("Security: Access denied on [{} {}]: {}",
                LogSanitizer.sanitize(request.getMethod()),
                LogSanitizer.sanitize(request.getRequestURI()),
                LogSanitizer.sanitize(exception.getMessage()));

        delegate(request, response, exception, HttpServletResponse.SC_FORBIDDEN);
    }

    private void delegate(@NonNull HttpServletRequest request,
                          @NonNull HttpServletResponse response,
                          @NonNull RuntimeException exception,
                          int fallbackStatus) throws IOException {
        String correlationId = correlationIdResolver.resolveFromRequest(request);

        try (ErrorMdcContext ignored = ErrorMdcContext.open(correlationId, exception)) {
            ModelAndView result = resolver.resolveException(request, response, null, exception);
            if (result != null) {
                log.trace("Security exception [{}] delegated successfully to HandlerExceptionResolver",
                        exception.getClass().getName());
                return;
            }

            log.warn(
                    "HandlerExceptionResolver could not handle security exception [{}] on [{} {}]. "
                            + "Writing fallback response with status {}.",
                    exception.getClass().getName(),
                    LogSanitizer.sanitize(request.getMethod()),
                    LogSanitizer.sanitize(request.getRequestURI()),
                    fallbackStatus);
            writeFallbackResponse(response, fallbackStatus, correlationId);
        } catch (Exception resolverError) {
            log.error(
                    "Critical failure during security exception handling. "
                            + "Original: [{}], Handler error: [{}]",
                    exception.getClass().getName(),
                    LogSanitizer.sanitize(resolverError.getMessage()),
                    resolverError);
            writeFallbackResponse(response, fallbackStatus, correlationId);
        }
    }

    private void writeFallbackResponse(@NonNull HttpServletResponse response,
                                       int status,
                                       @NonNull String correlationId) throws IOException {
        if (response.isCommitted()) {
            log.warn("Response already committed; cannot write security fallback error.");
            return;
        }

        String body = (status == HttpServletResponse.SC_UNAUTHORIZED)
                ? FALLBACK_401_TEMPLATE
                : FALLBACK_403_TEMPLATE;

        response.setStatus(status);
        response.setContentType(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE_STRING);
        response.setCharacterEncoding(CHARSET_UTF8);
        response.setHeader(ErrorHandlingHeaders.X_CORRELATION_ID, correlationId);
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
