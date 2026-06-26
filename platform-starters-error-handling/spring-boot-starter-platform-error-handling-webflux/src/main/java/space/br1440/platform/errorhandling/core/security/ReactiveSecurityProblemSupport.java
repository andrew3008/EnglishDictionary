package space.br1440.platform.errorhandling.core.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;

/**
 * Мост между Spring Security WebFlux и единым pipeline обработки ошибок.
 *
 * <p>В отличие от Servlet-модуля, где {@code SecurityProblemSupport} использует
 * {@code HandlerExceptionResolver} для перенаправления, в WebFlux можно напрямую
 * делегировать в {@link ReactiveGlobalExceptionHandler}, поскольку он реализует
 * {@code WebExceptionHandler} и принимает любой {@code Throwable}.
 *
 * <h2>Интеграция</h2>
 * <pre>{@code
 * @Bean
 * public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
 *                                                      ReactiveSecurityProblemSupport problemSupport) {
 *     return http
 *         .exceptionHandling(ex -> ex
 *             .authenticationEntryPoint(problemSupport)
 *             .accessDeniedHandler(problemSupport))
 *         .build();
 * }
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
public class ReactiveSecurityProblemSupport implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ReactiveGlobalExceptionHandler handler;

    @Override
    @NonNull
    public Mono<Void> commence(@NonNull ServerWebExchange exchange,
                               @NonNull AuthenticationException ex) {
        log.debug("Security: Authentication failure on [{} {}]: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                ex.getMessage());

        return handler.handle(exchange, ex);
    }

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange,
                             @NonNull AccessDeniedException ex) {
        log.debug("Security: Access denied on [{} {}]: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                ex.getMessage());

        return handler.handle(exchange, ex);
    }
}
