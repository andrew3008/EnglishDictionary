package space.br1440.platform.errorhandling.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.exception.datasource.DataSourceUnavailableException;
import space.br1440.platform.errorhandling.exception.datasource.ObjectNotFoundException;
import space.br1440.platform.errorhandling.exception.datasource.PlatformDataAccessException;
import space.br1440.platform.errorhandling.exception.datasource.ResourceNotFoundException;
import space.br1440.platform.errorhandling.exception.inconsistency.ConcurrentOperationException;
import space.br1440.platform.errorhandling.exception.inconsistency.DataIntegrityViolationException;
import space.br1440.platform.errorhandling.exception.inconsistency.DuplicateKeyException;
import space.br1440.platform.errorhandling.exception.inconsistency.NonUniqueResultException;
import space.br1440.platform.errorhandling.exception.integration.AccessDeniedException;
import space.br1440.platform.errorhandling.exception.integration.AuthenticationRequiredException;
import space.br1440.platform.errorhandling.exception.integration.CircuitBreakerOpenException;
import space.br1440.platform.errorhandling.exception.integration.ExternalServiceBadRequestException;
import space.br1440.platform.errorhandling.exception.integration.ExternalServiceException;
import space.br1440.platform.errorhandling.exception.integration.ExternalServiceUnauthorizedException;
import space.br1440.platform.errorhandling.exception.integration.ExternalServiceUnavailableException;
import space.br1440.platform.errorhandling.exception.integration.RateLimitExceededException;
import space.br1440.platform.errorhandling.exception.integration.UpstreamTimeoutException;
import space.br1440.platform.errorhandling.exception.internal.ApiNotImplementedException;
import space.br1440.platform.errorhandling.exception.internal.DataConversionException;
import space.br1440.platform.errorhandling.exception.internal.InternalServerException;
import space.br1440.platform.errorhandling.exception.internal.MessageProcessingException;
import space.br1440.platform.errorhandling.exception.internal.RuntimeConfigurationException;
import space.br1440.platform.errorhandling.exception.datasource.DataSourceException;
import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.exception.httpstatus.ForbiddenException;
import space.br1440.platform.errorhandling.exception.httpstatus.GatewayTimeoutException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotFoundException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotImplementedException;
import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.exception.httpstatus.TooManyRequestsException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnauthorizedException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnprocessableException;
import space.br1440.platform.errorhandling.exception.inconsistency.InconsistencyException;
import space.br1440.platform.errorhandling.exception.integration.IntegrationException;
import space.br1440.platform.errorhandling.exception.validation.ValidationException;
import space.br1440.platform.errorhandling.exception.validation.ConstraintViolationException;
import space.br1440.platform.errorhandling.exception.validation.FieldConstraintException;
import space.br1440.platform.errorhandling.exception.validation.InvalidRequestException;
import space.br1440.platform.errorhandling.exception.validation.UnprocessableEntityException;
import space.br1440.platform.errorhandling.http.DefaultHttpStatusResolver;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты трёхосной модели исключений: HTTP-статус вычисляет {@link DefaultHttpStatusResolver},
 * а не метод на классе исключения.
 */
@DisplayName("Exception hierarchy — three-axis contract")
class ExceptionHierarchyTest {

    private static final DefaultHttpStatusResolver RESOLVER = new DefaultHttpStatusResolver();

    static Stream<Arguments> httpStatusContracts() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("denied"), HttpStatus.FORBIDDEN, ForbiddenException.class, IntegrationException.class),
                Arguments.of(new ApiNotImplementedException("later"), HttpStatus.NOT_IMPLEMENTED, NotImplementedException.class, null),

                Arguments.of(new ResourceNotFoundException("Order", "42"), HttpStatus.NOT_FOUND, NotFoundException.class, DataSourceException.class),
                Arguments.of(new ObjectNotFoundException("key"), HttpStatus.NOT_FOUND, NotFoundException.class, DataSourceException.class),

                Arguments.of(new PlatformDataAccessException("SQL"), HttpStatus.INTERNAL_SERVER_ERROR, null, DataSourceException.class),
                Arguments.of(new DataSourceUnavailableException("pool"), HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class, DataSourceException.class),
                Arguments.of(new UnprocessableEntityException("semantic"), HttpStatus.UNPROCESSABLE_ENTITY, UnprocessableException.class, ValidationException.class),

                Arguments.of(new ExternalServiceException("svc", "err"), HttpStatus.BAD_GATEWAY, null, IntegrationException.class),
                Arguments.of(new ExternalServiceUnavailableException("svc"), HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class, IntegrationException.class),
                Arguments.of(new ExternalServiceBadRequestException("svc", "bad"), HttpStatus.BAD_REQUEST, BadRequestException.class, IntegrationException.class),
                Arguments.of(new ExternalServiceUnauthorizedException("svc", "401"), HttpStatus.UNAUTHORIZED, UnauthorizedException.class, IntegrationException.class),

                Arguments.of(new AuthenticationRequiredException("no token"), HttpStatus.UNAUTHORIZED, UnauthorizedException.class, IntegrationException.class),
                Arguments.of(new RateLimitExceededException("quota"), HttpStatus.TOO_MANY_REQUESTS, TooManyRequestsException.class, IntegrationException.class),
                Arguments.of(new UpstreamTimeoutException("svc", "timeout"), HttpStatus.GATEWAY_TIMEOUT, GatewayTimeoutException.class, IntegrationException.class),
                Arguments.of(new CircuitBreakerOpenException("svc"), HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class, IntegrationException.class),

                Arguments.of(new ConcurrentOperationException("calc", "res-1"), HttpStatus.CONFLICT, ConflictException.class, InconsistencyException.class),
                Arguments.of(new DataIntegrityViolationException("fk"), HttpStatus.CONFLICT, ConflictException.class, InconsistencyException.class),
                Arguments.of(new DuplicateKeyException("channel", "code", "CH-1"), HttpStatus.CONFLICT, ConflictException.class, InconsistencyException.class),
                Arguments.of(new NonUniqueResultException("many"), HttpStatus.CONFLICT, ConflictException.class, InconsistencyException.class),

                Arguments.of(new InvalidRequestException("bad param"), HttpStatus.BAD_REQUEST, BadRequestException.class, ValidationException.class),
                Arguments.of(new FieldConstraintException("email", "invalid"), HttpStatus.BAD_REQUEST, BadRequestException.class, ValidationException.class),
                Arguments.of(new ConstraintViolationException("constraint"), HttpStatus.BAD_REQUEST, BadRequestException.class, ValidationException.class),

                Arguments.of(new DataConversionException("JSON", "Avro", "parse error"), HttpStatus.INTERNAL_SERVER_ERROR, null, null),
                Arguments.of(new MessageProcessingException("inventory-events", "deserialization failed"), HttpStatus.INTERNAL_SERVER_ERROR, null, null),
                Arguments.of(new RuntimeConfigurationException("Feature X is disabled"), HttpStatus.INTERNAL_SERVER_ERROR, null, null),
                Arguments.of(new InternalServerException("oops"), HttpStatus.INTERNAL_SERVER_ERROR, null, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("httpStatusContracts")
    @DisplayName("concrete exception -> ожидаемый HTTP и маркеры (семантика / HTTP)")
    void declaresExpectedStatusViaResolver(AbstractErrorResponseException exception,
                                           HttpStatus expectedStatus,
                                           Class<?> expectedHttpMarkerOrNull,
                                           Class<?> expectedSemanticMarkerOrNull) {
        assertThat(RESOLVER.resolve(exception))
                .as("%s -> %s", exception.getClass().getSimpleName(), expectedStatus)
                .isEqualTo(expectedStatus);

        if (expectedHttpMarkerOrNull != null) {
            assertThat(exception).isInstanceOf(expectedHttpMarkerOrNull);
        }
        if (expectedSemanticMarkerOrNull != null) {
            assertThat(exception).isInstanceOf(expectedSemanticMarkerOrNull);
        }
    }

    @Nested
    @DisplayName("AbstractErrorResponseException base contract")
    class BaseClass {

        @Test
        void allConcreteExceptions_carryStructuredErrorEntry() {
            List<AbstractErrorResponseException> all = List.of(
                    new InternalServerException("err"),
                    new AccessDeniedException("denied"),
                    new ApiNotImplementedException("later"),
                    new ResourceNotFoundException("T", "1"),
                    new ObjectNotFoundException("k"),
                    new PlatformDataAccessException("sql"),
                    new DataSourceUnavailableException("pool"),
                    new UnprocessableEntityException("semantic"),
                    new ExternalServiceException("svc", "err"),
                    new ExternalServiceUnavailableException("svc"),
                    new ExternalServiceBadRequestException("svc", "bad"),
                    new ExternalServiceUnauthorizedException("svc", "401"),
                    new ConcurrentOperationException("calc", "res"),
                    new DataIntegrityViolationException("fk"),
                    new DuplicateKeyException("r", "k", "v"),
                    new NonUniqueResultException("many"),
                    new InvalidRequestException("bad"),
                    new FieldConstraintException("f", "c"),
                    new ConstraintViolationException("constraint"),
                    new AuthenticationRequiredException("no token"),
                    new RateLimitExceededException("quota"),
                    new UpstreamTimeoutException("svc", "timeout"),
                    new CircuitBreakerOpenException("svc"),
                    new DataConversionException("JSON", "Avro", "err"),
                    new MessageProcessingException("topic", "err"),
                    new RuntimeConfigurationException("misconfigured")
            );

            for (var ex : all) {
                assertThat(ex).isInstanceOf(AbstractErrorResponseException.class);
                assertThat(ex).isInstanceOf(RuntimeException.class);
                assertThat(ex.getErrorEntry()).isNotNull();
                assertThat(ex.getErrorEntry().getErrorCode()).isNotBlank();
                assertThat(ex.getErrorEntry().getMessage()).isNotBlank();
                assertThat(RESOLVER.resolve(ex)).isNotNull();
            }
        }

        @Test
        void getMessage_isCleanFormattedTemplate_withoutErrorCodeSuffix() {
            var ex = new ResourceNotFoundException("Spacecraft", "42");

            assertThat(ex.getMessage())
                    .as("getMessage() must NOT contain [errorCode=...] suffix; errorCode is exposed as a separate DTO field")
                    .doesNotContain("[errorCode=")
                    .doesNotContain("errorCode=");
        }

        @Test
        void getMessage_returnsFormattedTemplateExactly() {
            var ex = new ExternalServiceException("svc", "boom");

            assertThat(ex.getMessage())
                    .as("getMessage() must return exactly the formatted MESSAGE_TEMPLATE -- nothing prepended/appended")
                    .isEqualTo("External service svc returned an error: boom");

            assertThat(ex.getErrorEntry().getErrorCode())
                    .as("errorCode is exposed via ErrorEntry, not via getMessage() suffix; "
                            + "value is auto-derived from the runtime class name by ReasonCodeUtils")
                    .isEqualTo("EXTERNAL_SERVICE");
        }

        @Test
        void causeChain_isPreserved() {
            var root = new IllegalStateException("root cause");

            var ex = new InternalServerException("wrapped", root);

            assertThat(ex.getCause()).isSameAs(root);
            assertThat(ex.getMessage()).contains("wrapped");
        }
    }

    @Nested
    @DisplayName("ExternalServiceException — расширяемая точка интеграции")
    class IntegrationHierarchy {

        @Test
        void badRequest_extendsExternalServiceException() {
            var ex = new ExternalServiceBadRequestException("svc", "bad body");

            assertThat(ex).isInstanceOf(ExternalServiceException.class);
            assertThat(RESOLVER.resolve(ex)).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void unauthorized_extendsExternalServiceException() {
            var ex = new ExternalServiceUnauthorizedException("svc", "token expired");

            assertThat(ex).isInstanceOf(ExternalServiceException.class);
            assertThat(RESOLVER.resolve(ex)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
