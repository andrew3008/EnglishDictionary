package space.br1440.platform.tracing.otel.javaagent.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireUtf8Meter;

class JmxWireUtf8MeterTest {

    @ParameterizedTest
    @MethodSource("wellFormedSamples")
    void well_formed_text_matches_jdk_utf8_byte_length(String value, int expectedBytes) {
        // given
        // when
        long measured = JmxWireUtf8Meter.utf8Length(value);

        // then
        assertThat(measured).isEqualTo(expectedBytes);
        assertThat(measured).isEqualTo(value.getBytes(StandardCharsets.UTF_8).length);
    }

    @ParameterizedTest
    @MethodSource("unpairedSurrogateSamples")
    void unpaired_surrogates_count_as_three_bytes(String value, int expectedBytes) {
        // given
        // when
        long measured = JmxWireUtf8Meter.utf8Length(value);

        // then
        assertThat(measured).isEqualTo(expectedBytes);
    }

    private static Stream<Arguments> wellFormedSamples() {
        return Stream.of(
                Arguments.of("", 0),
                Arguments.of("ascii", 5),
                Arguments.of("я", 2),
                Arguments.of("€", 3),
                Arguments.of("\uD83D\uDE00", 4)
        );
    }

    private static Stream<Arguments> unpairedSurrogateSamples() {
        return Stream.of(
                Arguments.of("\uD83D", 3),
                Arguments.of("\uDE00", 3),
                Arguments.of("\uD83D\uD83D", 6)
        );
    }
}
