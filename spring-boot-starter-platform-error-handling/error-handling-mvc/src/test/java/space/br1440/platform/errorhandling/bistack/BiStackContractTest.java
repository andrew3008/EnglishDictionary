package space.br1440.platform.errorhandling.bistack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.top.SpringMvcExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.top.WebFluxFrameworkExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Bi-stack contract: MVC vs WebFlux")
class BiStackContractTest {

    private final ErrorHandlingProperties properties = new ErrorHandlingProperties();
    private final SpringMvcExceptionStrategy mvcStrategy = new SpringMvcExceptionStrategy(new CauseChainResolver(Collections.emptyList()));
    private final WebFluxFrameworkExceptionStrategy webfluxStrategy = new WebFluxFrameworkExceptionStrategy(new CauseChainResolver(Collections.emptyList()));

    private final ErrorHandlingContext context = ErrorHandlingContext.of(
            properties,
            "/api/test",
            new StackHintExtractor(false, List.of())
    );

    @ParameterizedTest(name = "{0}: top errorCode и granular reason идентичны")
    @MethodSource("biStackEquivalentCases")
    @DisplayName("Эквивалентные сценарии дают одинаковые errorCode и granular reason")
    void equivalentScenarios_produceIdenticalContract(String name,
                                                      Exception mvcException,
                                                      Exception webfluxException,
                                                      HttpStatus expectedStatus,
                                                      String expectedTopErrorCode,
                                                      String expectedGranularReason) {
        // when
        ErrorEntry mvcEntry = mvcStrategy.handle(mvcException, context);
        ErrorEntry webfluxEntry = webfluxStrategy.handle(webfluxException, context);

        // then
        assertThat(mvcEntry.getHttpStatus())
                .as("MVC %s status", name)
                .isEqualTo(expectedStatus);
        assertThat(webfluxEntry.getHttpStatus())
                .as("WebFlux %s status", name)
                .isEqualTo(expectedStatus);

        assertThat(mvcEntry.getErrorCode())
                .as("MVC %s errorCode", name)
                .isEqualTo(expectedTopErrorCode);
        assertThat(webfluxEntry.getErrorCode())
                .as("WebFlux %s errorCode", name)
                .isEqualTo(expectedTopErrorCode);

        if (expectedGranularReason != null) {
            assertGranularReason(mvcEntry, "MVC " + name, expectedGranularReason);
            assertGranularReason(webfluxEntry, "WebFlux " + name, expectedGranularReason);
        }
    }

    static Stream<Arguments> biStackEquivalentCases() {
        return Stream.of(
                Arguments.of(
                        "405 Method Not Allowed",
                        new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"),
                        new MethodNotAllowedException("PATCH", List.of(org.springframework.http.HttpMethod.GET)),
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "INVALID_ARGUMENT",
                        "METHOD_NOT_ALLOWED"
                ),
                Arguments.of(
                        "415 Unsupported Media Type",
                        new org.springframework.web.HttpMediaTypeNotSupportedException(
                                org.springframework.http.MediaType.TEXT_XML,
                                List.of(org.springframework.http.MediaType.APPLICATION_JSON)),
                        new UnsupportedMediaTypeStatusException(
                                org.springframework.http.MediaType.TEXT_XML,
                                List.of(org.springframework.http.MediaType.APPLICATION_JSON)),
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "INVALID_ARGUMENT",
                        "UNSUPPORTED_MEDIA_TYPE"
                ),
                Arguments.of(
                        "406 Not Acceptable",
                        new org.springframework.web.HttpMediaTypeNotAcceptableException("not acceptable"),
                        new NotAcceptableStatusException(
                                List.of(org.springframework.http.MediaType.APPLICATION_JSON)),
                        HttpStatus.NOT_ACCEPTABLE,
                        "INVALID_ARGUMENT",
                        "NOT_ACCEPTABLE"
                )
        );
    }

    @Test
    @DisplayName("Generic 503 ResponseStatusException — оба стека дают UNAVAILABLE без granular")
    void serviceUnavailable_consistentMapping() {
        // given
        var mvcException = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var webfluxException = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        // when
        ErrorEntry mvcEntry = mvcStrategy.handle(mvcException, context);
        ErrorEntry webfluxEntry = webfluxStrategy.handle(webfluxException, context);

        // then
        assertThat(mvcEntry.getErrorCode()).isEqualTo("UNAVAILABLE");
        assertThat(webfluxEntry.getErrorCode()).isEqualTo("UNAVAILABLE");
        assertThat(mvcEntry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(webfluxEntry.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static void assertGranularReason(ErrorEntry entry, String label, String expected) {
        assertThat(entry.getDetails())
                .as("%s — должно быть header + granular detail", label)
                .hasSizeGreaterThanOrEqualTo(2);

        ErrorInfoDetail granular = (ErrorInfoDetail) entry.getDetails().get(1);
        assertThat(granular.getReason())
                .as("%s granular reason", label)
                .isEqualTo(expected);
    }
}
