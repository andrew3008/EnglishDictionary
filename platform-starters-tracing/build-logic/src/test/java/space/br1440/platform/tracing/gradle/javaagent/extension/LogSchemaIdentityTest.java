package space.br1440.platform.tracing.gradle.javaagent.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogSchemaIdentityTest {

    private static final String VALID_SHA256 = "43e9a42933fc6e0aecb579167866da1cc953a407aae6186de47c906fb34b758d";
    private static final String VALID_PROPERTIES = """
            schema.hashAlgorithm=PLATFORM_LOG_SCHEMA_SHA256_V1
            schema.name=platform-log-event-v1
            schema.version=1
            schema.sha256=%s
            """.formatted(VALID_SHA256);

    @Test
    void valid_identity_is_parsed() {
        // when
        LogSchemaIdentity identity = LogSchemaIdentity.parse(VALID_PROPERTIES, "test-source");

        // then
        assertThat(identity).isEqualTo(new LogSchemaIdentity(1, VALID_SHA256));
    }

    @Test
    void comments_and_empty_lines_are_ignored() {
        // given
        String content = "# log schema identity\n\n" + VALID_PROPERTIES;

        // when
        LogSchemaIdentity identity = LogSchemaIdentity.parse(content, "test-source");

        // then
        assertThat(identity).isEqualTo(new LogSchemaIdentity(1, VALID_SHA256));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "schema.hashAlgorithm",
            "schema.name",
            "schema.version",
            "schema.sha256"
    })
    void missing_required_property_is_rejected(String key) {
        // given
        String content = VALID_PROPERTIES.replaceFirst(
                "(?m)^" + Pattern.quote(key) + "=.*\\R",
                ""
        );

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "missing required property " + key);
    }

    @Test
    void uppercase_sha256_is_rejected() {
        // given
        String content = withValue("schema.sha256", VALID_SHA256.toUpperCase(Locale.ROOT));

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "schema.sha256 must be 64 lowercase hex characters");
    }

    @Test
    void sha256_with_wrong_length_is_rejected() {
        // given
        String content = withValue("schema.sha256", VALID_SHA256.substring(1));

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "schema.sha256 must be 64 lowercase hex characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "schema.name=platform\\u0041",
            "schema.n\\u0061me=platform-log-event-v1",
            "schema.name=platform\\"
    })
    void escape_sequence_is_rejected(String line) {
        // given
        String content = withLine("schema.name", line);

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "expected strict 'key=value' line");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "schema.name: platform-log-event-v1",
            "schema.name platform-log-event-v1",
            " schema.name=platform-log-event-v1",
            "schema.name=platform-log-event-v1 "
    })
    void non_strict_separator_or_whitespace_is_rejected(String line) {
        // given
        String content = withLine("schema.name", line);

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "expected strict 'key=value' line");
    }

    @Test
    void untrimmed_value_is_rejected() {
        // given
        String content = withLine("schema.name", "schema.name= platform-log-event-v1");

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "schema.name must be a trimmed non-blank value");
    }

    @Test
    void duplicate_property_is_rejected() {
        // given
        String content = VALID_PROPERTIES + "schema.version=2\n";

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "duplicate property schema.version");
    }

    @Test
    void unexpected_property_is_rejected() {
        // given
        String content = VALID_PROPERTIES + "schema.owner=platform\n";

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "unexpected property schema.owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "01", "1.0", "abc", "99999999999"})
    void non_canonical_positive_integer_version_is_rejected(String version) {
        // given
        String content = withValue("schema.version", version);

        // when
        Throwable failure = parseFailure(content);

        // then
        assertRejected(failure, "schema.version must be a positive integer");
    }

    private static String withValue(String key, String value) {
        return withLine(key, key + "=" + value);
    }

    private static String withLine(String key, String replacement) {
        List<String> lines = VALID_PROPERTIES.lines()
                .map(line -> line.startsWith(key + "=") ? replacement : line)
                .toList();
        return String.join("\n", lines) + "\n";
    }

    private static Throwable parseFailure(String content) {
        return catchThrowable(() -> LogSchemaIdentity.parse(content, "test-source"));
    }

    private static void assertRejected(Throwable failure, String message) {
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageStartingWith("test-source: ")
                .hasMessageContaining(message);
    }
}
