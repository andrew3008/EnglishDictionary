package space.br1440.platform.tracing.api.control.protocol.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocolVersion")
class TracingControlProtocolVersionTest {

    @ParameterizedTest(name = "{0} -> major {1}")
    @MethodSource("parseableVersions")
    @DisplayName("parse returns present for parseable values")
    void parseReturnsPresent(String label, Object raw, int expectedMajor) {
        Optional<TracingControlProtocolVersion> parsed = TracingControlProtocolVersion.parse(raw);

        assertThat(parsed)
                .as("parse(%s) for %s", raw, label)
                .isPresent();
        assertThat(parsed.orElseThrow().major()).isEqualTo(expectedMajor);
    }

    @ParameterizedTest(name = "{0} -> empty")
    @MethodSource("unparseableVersions")
    @DisplayName("parse returns empty for unparseable values")
    void parseReturnsEmpty(String label, Object raw) {
        assertThat(TracingControlProtocolVersion.parse(raw))
                .as("parse(%s) for %s", raw, label)
                .isEmpty();
    }

    @Test
    @DisplayName("major accessor returns constructor value")
    void majorAccessor() {
        assertThat(new TracingControlProtocolVersion(1).major()).isEqualTo(1);
    }

    @Test
    @DisplayName("equals and hashCode use major only")
    void equalsAndHashCode() {
        TracingControlProtocolVersion first = new TracingControlProtocolVersion(1);
        TracingControlProtocolVersion sameMajor = new TracingControlProtocolVersion(1);
        TracingControlProtocolVersion differentMajor = new TracingControlProtocolVersion(2);

        assertThat(first).isEqualTo(sameMajor);
        assertThat(first.hashCode()).isEqualTo(sameMajor.hashCode());
        assertThat(first).isNotEqualTo(differentMajor);
    }

    private static Stream<Arguments> parseableVersions() {
        return Stream.of(
                Arguments.of("Integer 1", 1, 1),
                Arguments.of("Long 1L", 1L, 1),
                Arguments.of("String \"1\"", "1", 1),
                Arguments.of("String \" 1 \"", " 1 ", 1),
                Arguments.of("Integer 2", 2, 2),
                Arguments.of("Long 2L", 2L, 2),
                Arguments.of("String \"2\"", "2", 2));
    }

    private static Stream<Arguments> unparseableVersions() {
        return Stream.of(
                Arguments.of("String empty", ""),
                Arguments.of("String abc", "abc"),
                Arguments.of("null", null),
                Arguments.of("arbitrary Object", new Object()));
    }
}
