package space.br1440.platform.errorhandling.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOError;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReasonCodeUtils")
class ReasonCodeUtilsTest {

    @Nested
    @DisplayName("toReasonCode")
    class ToReasonCode {

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("space.br1440.platform.errorhandling.utils.ReasonCodeUtilsTest#exceptionClassToReasonCode")
        @DisplayName("strips 'Exception' suffix and converts CamelCase to UPPER_SNAKE_CASE")
        void convertsClassName(Class<?> exceptionClass, String expectedCode) {
            assertThat(ReasonCodeUtils.toReasonCode(exceptionClass)).isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("class named exactly 'Exception' keeps the name (guard: length > suffix.length)")
        void classNamedExactlyException_keepsName() {
            assertThat(ReasonCodeUtils.toReasonCode(Exception.class)).isEqualTo("EXCEPTION");
        }

        @Test
        @DisplayName("class without 'Exception' suffix converts as-is")
        void classWithoutSuffix_convertsAsIs() {
            assertThat(ReasonCodeUtils.toReasonCode(Error.class)).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("class with acronym-like segments splits correctly")
        void acronymSegments_splitCorrectly() {
            assertThat(ReasonCodeUtils.toReasonCode(IOError.class)).isEqualTo("IOERROR");
        }
    }

    static Stream<Arguments> exceptionClassToReasonCode() {
        return Stream.of(
                Arguments.of(ResourceNotFoundTestException.class, "RESOURCE_NOT_FOUND_TEST"),
                Arguments.of(DuplicateKeyTestException.class, "DUPLICATE_KEY_TEST"),
                Arguments.of(InternalServerTestException.class, "INTERNAL_SERVER_TEST"),
                Arguments.of(CircuitBreakerOpenTestException.class, "CIRCUIT_BREAKER_OPEN_TEST"),
                Arguments.of(ExternalServiceUnavailableTestException.class, "EXTERNAL_SERVICE_UNAVAILABLE_TEST"),
                Arguments.of(RuntimeException.class, "RUNTIME"),
                Arguments.of(IllegalArgumentException.class, "ILLEGAL_ARGUMENT"),
                Arguments.of(NullPointerException.class, "NULL_POINTER")
        );
    }

    private static class ResourceNotFoundTestException extends RuntimeException {}
    private static class DuplicateKeyTestException extends RuntimeException {}
    private static class InternalServerTestException extends RuntimeException {}
    private static class CircuitBreakerOpenTestException extends RuntimeException {}
    private static class ExternalServiceUnavailableTestException extends RuntimeException {}
}
