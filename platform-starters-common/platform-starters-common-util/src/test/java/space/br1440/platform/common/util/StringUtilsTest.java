package space.br1440.platform.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StringUtilsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("isNullOrEmptyCases")
    void isNullOrEmpty_returnsExpectedResult(String value, boolean expected) {
        // when
        boolean actual = StringUtils.isNullOrEmpty(value);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("isNullOrBlankCases")
    void isNullOrBlank_returnsExpectedResult(String value, boolean expected) {
        // when
        boolean actual = StringUtils.isNullOrBlank(value);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("hasTextCases")
    void hasText_returnsExpectedResult(String value, boolean expected) {
        // when
        boolean actual = StringUtils.hasText(value);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> isNullOrEmptyCases() {
        return Stream.of(
                arguments(null, true),
                arguments("", true),
                arguments("   ", false),
                arguments("\t", false),
                arguments("\n", false),
                arguments("x", false),
                arguments(" x ", false),
                arguments("\u00A0", false)
        );
    }

    static Stream<Arguments> isNullOrBlankCases() {
        return Stream.of(
                arguments(null, true),
                arguments("", true),
                arguments("   ", true),
                arguments("\t", true),
                arguments("\n", true),
                arguments("\t\n ", true),
                arguments("x", false),
                arguments(" x ", false),
                arguments("\u00A0", false)
        );
    }

    static Stream<Arguments> hasTextCases() {
        return Stream.of(
                arguments(null, false),
                arguments("", false),
                arguments("   ", false),
                arguments("\t", false),
                arguments("\n", false),
                arguments("\t\n ", false),
                arguments("x", true),
                arguments(" x ", true),
                arguments("\u00A0", true)
        );
    }
}
