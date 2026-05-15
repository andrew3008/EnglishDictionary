package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReasonCodeUtils")
class ReasonCodeUtilsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("conversionCases")
    @DisplayName("toReasonCode() конвертирует FQCN в UPPER_SNAKE_CASE")
    void toReasonCode_convertsCorrectly(Class<?> exceptionClass, String expectedCode) {
        // when
        String result = ReasonCodeUtils.toReasonCode(exceptionClass);

        // then
        assertThat(result).isEqualTo(expectedCode);
    }

    static Stream<Arguments> conversionCases() {
        return Stream.of(
                Arguments.of(IllegalArgumentException.class, "ILLEGAL_ARGUMENT"),
                Arguments.of(IllegalStateException.class, "ILLEGAL_STATE"),
                Arguments.of(NullPointerException.class, "NULL_POINTER"),
                Arguments.of(ConnectException.class, "CONNECT"),
                Arguments.of(SocketTimeoutException.class, "SOCKET_TIMEOUT"),
                Arguments.of(UnknownHostException.class, "UNKNOWN_HOST"),
                Arguments.of(IOException.class, "IO"),
                Arguments.of(RuntimeException.class, "RUNTIME"),
                Arguments.of(UnsupportedOperationException.class, "UNSUPPORTED_OPERATION"),
                Arguments.of(ClassCastException.class, "CLASS_CAST"),
                Arguments.of(ArithmeticException.class, "ARITHMETIC"),
                Arguments.of(IndexOutOfBoundsException.class, "INDEX_OUT_OF_BOUNDS"),
                Arguments.of(SecurityException.class, "SECURITY"),
                Arguments.of(InterruptedException.class, "INTERRUPTED")
        );
    }
}
