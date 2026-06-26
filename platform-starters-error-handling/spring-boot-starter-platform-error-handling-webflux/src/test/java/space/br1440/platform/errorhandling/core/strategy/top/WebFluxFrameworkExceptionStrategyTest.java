package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebFluxFrameworkExceptionStrategyTest {

    private WebFluxFrameworkExceptionStrategy strategy;
    private ErrorHandlingContext context;

    @BeforeEach
    void setUp() {
        strategy = new WebFluxFrameworkExceptionStrategy(
                new CauseChainResolver(Collections.emptyList()));
        context = ErrorHandlingContext.of(new ErrorHandlingProperties(), "/test", new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("supports()")
    class Supports {

        @Test
        void supports_ResponseStatusException() {
            // given
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertTrue(supported);
        }

        @Test
        void does_not_support_generic_exception() {
            // given
            RuntimeException ex = new RuntimeException("generic");

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertFalse(supported);
        }

        @Test
        void supports_subtype_MethodNotAllowedException() {
            // given
            MethodNotAllowedException ex = new MethodNotAllowedException("POST", List.of());

            // when
            boolean supported = strategy.supports(ex);

            // then
            assertTrue(supported);
        }
    }

    @Nested
    @DisplayName("handle() status mapping")
    class HandleStatusMapping {

        @Test
        void maps_404_not_found() {
            // given
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.NOT_FOUND, entry.getHttpStatus());
            assertEquals("NOT_FOUND", entry.getErrorCode());
            assertEquals("Resource not found", entry.getMessage());
        }

        @Test
        void maps_400_bad_request() {
            // given
            ServerWebInputException ex = new ServerWebInputException("Invalid input");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.BAD_REQUEST, entry.getHttpStatus());
            assertEquals("INVALID_ARGUMENT", entry.getErrorCode());
        }

        @Test
        void maps_405_method_not_allowed() {
            // given
            MethodNotAllowedException ex = new MethodNotAllowedException("DELETE", List.of());

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, entry.getHttpStatus());
            assertEquals("INVALID_ARGUMENT", entry.getErrorCode());
        }

        @Test
        void maps_415_unsupported_media_type() {
            // given
            UnsupportedMediaTypeStatusException ex =
                    new UnsupportedMediaTypeStatusException("text/xml is not supported");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, entry.getHttpStatus());
            assertEquals("INVALID_ARGUMENT", entry.getErrorCode());
        }

        @Test
        void maps_406_not_acceptable() {
            // given
            NotAcceptableStatusException ex = new NotAcceptableStatusException(List.of());

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.NOT_ACCEPTABLE, entry.getHttpStatus());
            assertEquals("INVALID_ARGUMENT", entry.getErrorCode());
        }

        @Test
        void maps_500_internal_server_error() {
            // given
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal failure");

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, entry.getHttpStatus());
            assertEquals("INTERNAL", entry.getErrorCode());
        }

        @Test
        void uses_reason_phrase_when_reason_is_null() {
            // given
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

            // when
            ErrorEntry entry = strategy.handle(ex, context);

            // then
            assertEquals("Not Found", entry.getMessage());
        }
    }

    @Nested
    @DisplayName("logPolicy()")
    class LogPolicyTests {

        @Test
        @DisplayName("Sprint 2 (H2): 404 (ResponseStatusException) → WARN")
        void notFound_404_uses_WARN() {
            // given
            ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND);
            ErrorEntry entry = strategy.handle(notFound, context);

            // when
            LogPolicy policy = strategy.logPolicy(notFound, entry);

            // then
            assertEquals(LogPolicy.WARN, policy);
        }

        @Test
        @DisplayName("405 (явный routing) — всегда DEBUG")
        void method_not_allowed_uses_DEBUG() {
            // given
            MethodNotAllowedException ex = new MethodNotAllowedException("PUT", List.of());
            ErrorEntry entry = strategy.handle(ex, context);

            // when
            LogPolicy policy = strategy.logPolicy(ex, entry);

            // then
            assertEquals(LogPolicy.DEBUG, policy);
        }

        @Test
        @DisplayName("415 (явный routing) — всегда DEBUG")
        void unsupported_media_type_uses_DEBUG() {
            // given
            UnsupportedMediaTypeStatusException ex =
                    new UnsupportedMediaTypeStatusException("text/xml is not supported");
            ErrorEntry entry = strategy.handle(ex, context);

            // when
            LogPolicy policy = strategy.logPolicy(ex, entry);

            // then
            assertEquals(LogPolicy.DEBUG, policy);
        }

        @Test
        @DisplayName("406 (явный routing) — всегда DEBUG")
        void not_acceptable_uses_DEBUG() {
            // given
            NotAcceptableStatusException ex = new NotAcceptableStatusException(List.of());
            ErrorEntry entry = strategy.handle(ex, context);

            // when
            LogPolicy policy = strategy.logPolicy(ex, entry);

            // then
            assertEquals(LogPolicy.DEBUG, policy);
        }

        @Test
        void client_4xx_non_routing_uses_WARN() {
            // given
            ResponseStatusException badRequest = new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad");
            ErrorEntry entry = strategy.handle(badRequest, context);

            // when
            LogPolicy policy = strategy.logPolicy(badRequest, entry);

            // then
            assertEquals(LogPolicy.WARN, policy);
        }

        @Test
        void server_5xx_uses_ERROR() {
            // given
            ResponseStatusException serverError =
                    new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failure");
            ErrorEntry entry = strategy.handle(serverError, context);

            // when
            LogPolicy policy = strategy.logPolicy(serverError, entry);

            // then
            assertEquals(LogPolicy.ERROR, policy);
        }
    }
}
