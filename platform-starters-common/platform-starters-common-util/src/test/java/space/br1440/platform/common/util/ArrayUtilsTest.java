package space.br1440.platform.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ArrayUtilsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("isNullOrEmptyCases")
    void isNullOrEmpty_returnsExpectedResult(String caseName, boolean expected, String[] values) {
        // when
        boolean actual = ArrayUtils.isNullOrEmpty(values);

        // then
        assertThat(actual).as(caseName).isEqualTo(expected);
    }

    static Stream<Arguments> isNullOrEmptyCases() {
        return Stream.of(
                arguments("null", true, null),
                arguments("empty", true, new String[0]),
                arguments("singleton", false, new String[] {"a"}),
                arguments("null element", false, new String[] {null})
        );
    }
}
