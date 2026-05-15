package space.br1440.platform.errorhandling.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
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
import space.br1440.platform.errorhandling.exception.validation.ConstraintViolationException;
import space.br1440.platform.errorhandling.exception.validation.FieldConstraintException;
import space.br1440.platform.errorhandling.exception.validation.InvalidRequestException;
import space.br1440.platform.errorhandling.exception.validation.UnprocessableEntityException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultHttpStatusResolver")
class DefaultHttpStatusResolverTest {

    private final DefaultHttpStatusResolver resolver = new DefaultHttpStatusResolver();

    static Stream<Arguments> statusCases() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("no"), HttpStatus.FORBIDDEN),
                Arguments.of(new ResourceNotFoundException("T", "id"), HttpStatus.NOT_FOUND),
                Arguments.of(new ObjectNotFoundException("key"), HttpStatus.NOT_FOUND),
                Arguments.of(new ConcurrentOperationException("op", "res"), HttpStatus.CONFLICT),
                Arguments.of(new DuplicateKeyException("r", "k", "v"), HttpStatus.CONFLICT),
                Arguments.of(new DataIntegrityViolationException("fk"), HttpStatus.CONFLICT),
                Arguments.of(new NonUniqueResultException("many"), HttpStatus.CONFLICT),
                Arguments.of(new UnprocessableEntityException("semantic"), HttpStatus.UNPROCESSABLE_ENTITY),
                Arguments.of(new InvalidRequestException("bad"), HttpStatus.BAD_REQUEST),
                Arguments.of(new ApiNotImplementedException("later"), HttpStatus.NOT_IMPLEMENTED),
                Arguments.of(new DataSourceUnavailableException("pool exhausted"), HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(new ExternalServiceUnavailableException("dep"), HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(new ExternalServiceException("ext", "boom"), HttpStatus.BAD_GATEWAY),
                Arguments.of(new ExternalServiceBadRequestException("ext", "400 body"), HttpStatus.BAD_REQUEST),
                Arguments.of(new PlatformDataAccessException("SQL error"), HttpStatus.INTERNAL_SERVER_ERROR),
                Arguments.of(new InternalServerException("oops"), HttpStatus.INTERNAL_SERVER_ERROR),
                Arguments.of(new AuthenticationRequiredException("no token"), HttpStatus.UNAUTHORIZED),
                Arguments.of(new RateLimitExceededException("quota exceeded"), HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(new UpstreamTimeoutException("svc", "timeout"), HttpStatus.GATEWAY_TIMEOUT),
                Arguments.of(new CircuitBreakerOpenException("svc"), HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(new DataConversionException("JSON", "Avro", "parse error"), HttpStatus.INTERNAL_SERVER_ERROR),
                Arguments.of(new MessageProcessingException("inventory-events", "deserialization failed"), HttpStatus.INTERNAL_SERVER_ERROR),
                Arguments.of(new RuntimeConfigurationException("Feature X is disabled"), HttpStatus.INTERNAL_SERVER_ERROR),
                Arguments.of(new ExternalServiceUnauthorizedException("svc", "token expired"), HttpStatus.UNAUTHORIZED),
                Arguments.of(new FieldConstraintException("email", "invalid format"), HttpStatus.BAD_REQUEST),
                Arguments.of(new ConstraintViolationException("constraint broken"), HttpStatus.BAD_REQUEST),
                Arguments.of(ExternalServiceException.proxiedUpstream("404", "{\"x\":1}", new RuntimeException("upstream")),
                        HttpStatus.NOT_FOUND)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("statusCases")
    @DisplayName("resolve(AbstractErrorResponseException) — ожидаемый HTTP-статус")
    void resolvesExpectedStatus(AbstractErrorResponseException ex, HttpStatus expected) {
        assertThat(resolver.resolve(ex)).isEqualTo(expected);
    }
}
