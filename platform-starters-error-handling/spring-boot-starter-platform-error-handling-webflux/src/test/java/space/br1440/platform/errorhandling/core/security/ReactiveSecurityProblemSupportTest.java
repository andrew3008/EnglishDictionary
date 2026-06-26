package space.br1440.platform.errorhandling.core.security;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import reactor.test.StepVerifier;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.http.ErrorMediaTypes;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.properties.LoggingStrategy;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;
import space.br1440.platform.errorhandling.webflux.testing.handler.ReactiveGlobalExceptionHandlerTestFixture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReactiveSecurityProblemSupport")
class ReactiveSecurityProblemSupportTest {

    private ReactiveSecurityProblemSupport support;

    @BeforeEach
    void setUp() {
        ErrorHandlingProperties properties = new ErrorHandlingProperties();
        properties.setLoggingStrategy(LoggingStrategy.CATEGORY);

        TopExceptionHandlingStrategy securityStrategy = new TopExceptionHandlingStrategy() {
            @Override
            public boolean supports(@NotNull Exception exception) {
                return exception instanceof AuthenticationException
                        || exception instanceof AccessDeniedException;
            }

            @Override
            public @NotNull ErrorEntry handle(@NotNull Exception exception, @NotNull ErrorHandlingContext context) {
                if (exception instanceof AccessDeniedException) {
                    ErrorInfoDetail denied = ErrorInfoDetail.builder()
                            .reason("PERMISSION_DENIED")
                            .build();
                    return ErrorEntry.builder()
                            .httpStatus(HttpStatus.FORBIDDEN)
                            .errorCode("PERMISSION_DENIED")
                            .message("Access denied")
                            .details(List.of(denied))
                            .build();
                }
                ErrorInfoDetail unauth = ErrorInfoDetail.builder()
                        .reason("UNAUTHENTICATED")
                        .build();
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.UNAUTHORIZED)
                        .errorCode("UNAUTHENTICATED")
                        .message("Authentication required")
                        .details(List.of(unauth))
                        .build();
            }

            @Override
            public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
                return LogPolicy.DEBUG;
            }

            @Override
            public int getOrder() {
                return 200;
            }
        };

        UnknownExceptionHandler unknownHandler = new UnknownExceptionHandler() {
            @Override
            public @NotNull ErrorEntry handle(@NotNull Exception exception, @NotNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode(CanonicalErrors.UNKNOWN_INTERNAL_ERROR)
                        .message("Unexpected error")
                        .build();
            }

            @Override
            public @NotNull LogPolicy logPolicy(@NotNull Exception exception, @NotNull ErrorEntry errorEntry) {
                return LogPolicy.ERROR;
            }
        };

        ReactiveGlobalExceptionHandler handler = ReactiveGlobalExceptionHandlerTestFixture.builder()
                .properties(properties)
                .strategies(List.of(securityStrategy))
                .unknownHandler(unknownHandler)
                .applicationName("test-app")
                .build();

        support = new ReactiveSecurityProblemSupport(handler);
    }

    @Test
    @DisplayName("commence() delegates AuthenticationException -> 401 with problem+json")
    void commence_returns_401_for_authentication_exception() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/secure").build()
        );

        // when
        StepVerifier.create(support.commence(exchange, new BadCredentialsException("Invalid credentials")))
                .verifyComplete();

        // then
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                exchange.getResponse().getHeaders().getContentType());

        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("commence() with InsufficientAuthenticationException -> 401")
    void commence_returns_401_for_insufficient_authentication() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/protected").build()
        );

        // when
        StepVerifier.create(support.commence(exchange,
                        new InsufficientAuthenticationException("Full authentication required")))
                .verifyComplete();

        // then
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("handle() delegates AccessDeniedException -> 403 with problem+json")
    void handle_returns_403_for_access_denied() {
        // given
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin").build()
        );

        // when
        StepVerifier.create(support.handle(exchange, new AccessDeniedException("Forbidden")))
                .verifyComplete();

        // then
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(ErrorMediaTypes.ERROR_RESPONSE_MEDIA_TYPE,
                exchange.getResponse().getHeaders().getContentType());

        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("Both endpoints set X-Correlation-Id header")
    void responses_include_correlation_id() {
        // given
        MockServerWebExchange exchange1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Correlation-Id", "sec-corr-1")
                        .build()
        );

        // when
        StepVerifier.create(support.commence(exchange1, new BadCredentialsException("bad")))
                .verifyComplete();

        // then
        assertEquals("sec-corr-1",
                exchange1.getResponse().getHeaders().getFirst("X-Correlation-Id"));

        // given
        MockServerWebExchange exchange2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header("X-Correlation-Id", "sec-corr-2")
                        .build());

        // when
        StepVerifier.create(support.handle(exchange2, new AccessDeniedException("denied")))
                .verifyComplete();

        // then
        assertEquals("sec-corr-2",
                exchange2.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }
}
