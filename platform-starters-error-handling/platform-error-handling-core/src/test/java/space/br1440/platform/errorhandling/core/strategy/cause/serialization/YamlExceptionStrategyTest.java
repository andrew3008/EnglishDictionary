package space.br1440.platform.errorhandling.core.strategy.cause.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class YamlExceptionStrategyTest {

    private YamlExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new YamlExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    class SnippetSanitization {

        @Test
        void redacts_password_value() {
            // given
            String input = "    password: mySecret123";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("mySecret123"));
            assertTrue(result.contains("password:"));
        }

        @Test
        void redacts_password_with_equals_sign() {
            // when
            String result = YamlExceptionStrategy.sanitizeSnippet("PASSWORD=s3cret!");

            // then
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("s3cret!"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "  secret: abc123xyz",
                "  token: eyJhbGciOiJIUzI1NiJ9",
                "  api_key: AKIAIOSFODNN7EXAMPLE",
                "  api-key: AKIAIOSFODNN7EXAMPLE",
                "  apiKey: mykey123",
                "  access_key: AKIA12345",
                "  access-key: AKIA12345",
                "  private_key: -----BEGIN RSA",
                "  private-key: -----BEGIN RSA",
                "  credentials: admin:pass",
                "  credential: admin:pass",
                "  auth_token: Bearer xyz",
                "  auth-token: Bearer xyz",
                "  auth_key: k3y!",
                "  auth_secret: s3cr3t",
                "  bearer: eyJhbGci",
                "  jwt: eyJhbGci",
                "  connection_string: Server=myServer",
                "  connection-string: Server=myServer",
                "  jdbc_url: jdbc:postgresql://host/db",
                "  jdbc-url: jdbc:postgresql://host/db",
                "  dsn: postgres://user:pass@host",
                "  encryption_key: AES256key",
                "  encryption-key: AES256key",
                "  client_secret: cs_12345",
                "  client-secret: cs_12345",
                "  signing_key: sign123",
                "  signing-key: sign123",
                "  passwd: p@ssw0rd",
                "  pwd: p@ssw0rd"
        })
        void redacts_sensitive_key_value(String input) {
            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("[REDACTED]"), "Should redact value for: " + input);
        }

        @Test
        void preserves_non_sensitive_key() {
            // given
            String input = "    hostname: my-server.example.com";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertEquals("    hostname: my-server.example.com", result);
        }

        @Test
        void preserves_position_indicator() {
            // given
            String input = "    password: mySecret123\n        ^";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("^"), "Position indicator should be preserved");
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("mySecret123"));
        }

        @Test
        void strips_control_characters() {
            // given
            String input = "key:\u0001\u0002 value\u0003";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertFalse(result.contains("\u0001"));
            assertFalse(result.contains("\u0002"));
            assertFalse(result.contains("\u0003"));
            assertTrue(result.contains("key:"));
        }

        @Test
        void preserves_tabs_and_newlines() {
            // given
            String input = "\tkey: value\n\t  ^";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("\t"));
            assertTrue(result.contains("\n"));
        }

        @Test
        void strips_del_character() {
            // given
            String input = "key: value\u007F";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertFalse(result.contains("\u007F"));
        }

        @Test
        void truncates_long_snippet() {
            // given
            String longSnippet = "x".repeat(200);

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(longSnippet);

            // then
            assertTrue(result.length() <= YamlExceptionStrategy.MAX_SNIPPET_LENGTH + " [TRUNCATED]".length());
            assertTrue(result.contains("[TRUNCATED]"));
        }

        @Test
        void case_insensitive_key_matching() {
            // given
            String input = "    PASSWORD: mySecret123";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("mySecret123"));
        }

        @Test
        void redacts_entire_value_after_key_to_end_of_line() {
            // given
            String input = "    password: my secret value with spaces # comment";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("my secret"));
            assertFalse(result.contains("comment"));
        }

        @Test
        void handles_quoted_values() {
            // given
            String input = "    password: \"mySecret123\"";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertTrue(result.contains("[REDACTED]"));
            assertFalse(result.contains("mySecret123"));
        }

        @Test
        void handles_empty_string() {
            // when / then
            String result = YamlExceptionStrategy.sanitizeSnippet("");
            assertNotNull(result);
        }

        @Test
        void handles_only_whitespace() {
            // when / then
            String result = YamlExceptionStrategy.sanitizeSnippet("   ");
            assertNotNull(result);
        }

        @Test
        void multiple_sensitive_lines() {
            // given
            String input = "password: secret1\ntoken: secret2";

            // when
            String result = YamlExceptionStrategy.sanitizeSnippet(input);

            // then
            assertFalse(result.contains("secret1"));
            assertFalse(result.contains("secret2"));
        }
    }

    @Nested
    class SupportsCause {

        @Test
        void supports_yamlException() {
            // given
            Object ex = instantiate("org.yaml.snakeyaml.error.YAMLException", "test");
            assumeTrue(ex != null, "SnakeYAML not on classpath");

            // when / then
            assertTrue(strategy.supportsCause((Throwable) ex));
        }

        @Test
        void does_not_support_generic_exception() {
            // when / then
            assertFalse(strategy.supportsCause(new RuntimeException("not yaml")));
        }

        @Test
        void does_not_support_ioException() {
            // when / then
            assertFalse(strategy.supportsCause(new java.io.IOException("not yaml")));
        }
    }

    @Nested
    class ExtractCauseDetails {

        private final YamlExceptionStrategy strategy =
                new YamlExceptionStrategy(new StackHintExtractor(true, List.of()));

        @Test
        void yamlException_includes_exceptionClass_in_diagnostic() {
            // given
            Object ex = instantiate("org.yaml.snakeyaml.error.YAMLException", "test error");
            assumeTrue(ex != null, "SnakeYAML not on classpath");

            // when
            RootCauseResolution details = strategy.extractCauseDetails((Throwable) ex);

            // then
            assertDiagnostic(details, "exceptionClass", "org.yaml.snakeyaml.error.YAMLException");
        }

        @Test
        void yamlException_includes_causeMessage_truncated() {
            // given
            String longMessage = "X".repeat(201);
            Object ex = instantiate("org.yaml.snakeyaml.error.YAMLException", longMessage);
            assumeTrue(ex != null, "SnakeYAML not on classpath");

            // when
            RootCauseResolution details = strategy.extractCauseDetails((Throwable) ex);

            // then
            String causeMsg = diagnostic(details).get("causeMessage");
            assertNotNull(causeMsg);
            assertTrue(causeMsg.contains("[TRUNCATED]"));
        }

        @Test
        void wrappedException_includes_rootCauseClass() {
            // given
            Object inner = instantiate("org.yaml.snakeyaml.error.YAMLException", "inner");
            assumeTrue(inner != null, "SnakeYAML not on classpath");

            RuntimeException wrapper = new RuntimeException("wrapper", (Throwable) inner);

            Object outer = instantiateWithCause(
                    "org.yaml.snakeyaml.error.YAMLException", "outer", wrapper);
            assumeTrue(outer != null, "SnakeYAML constructor with cause not available");

            // when
            RootCauseResolution details = strategy.extractCauseDetails((Throwable) outer);

            // then
            assertTrue(diagnostic(details).containsKey("rootCauseClass"));
        }

        @Test
        void domain_is_serialization() {
            // given
            Object ex = instantiate("org.yaml.snakeyaml.error.YAMLException", "test");
            assumeTrue(ex != null, "SnakeYAML not on classpath");

            // when
            RootCauseResolution details = strategy.extractCauseDetails((Throwable) ex);

            // then
            ErrorInfoDetail info = details.details().stream()
                    .filter(ErrorInfoDetail.class::isInstance)
                    .map(ErrorInfoDetail.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals("serialization", info.getDomain());
        }

        @Test
        void includes_stackHint() {
            // given
            Object ex = instantiate("org.yaml.snakeyaml.error.YAMLException", "test");
            assumeTrue(ex != null, "SnakeYAML not on classpath");

            // when
            RootCauseResolution details = strategy.extractCauseDetails((Throwable) ex);

            // then
            assertTrue(diagnostic(details).containsKey("stackHint"));
        }
    }

    @Nested
    class PatternCompilation {

        @Test
        void sensitiveKeyPattern_matches_sensitive_keys() {
            Stream.of(
                    "password: secret123",
                    "PASSWORD: secret123",       // case-insensitive
                    "api_key: abc",              // underscore separator
                    "api-key: abc",              // dash separator
                    "apikey: abc",               // no separator
                    "token=Bearer eyJ",          // = delimiter
                    "jwt: eyJhbGc",
                    "connection_string: jdbc:postgresql://host/db"
            ).forEach(input ->
                    assertTrue(
                            YamlExceptionStrategy.SENSITIVE_KEY_VALUE.matcher(input).find(),
                            "Expected match for: " + input
                    )
            );
        }

        @Test
        void sensitiveKeyPattern_redacts_value_preserves_key() {
            String result = YamlExceptionStrategy.SENSITIVE_KEY_VALUE
                    .matcher("password: secret123")
                    .replaceAll("$1[REDACTED]");
            assertEquals("password: [REDACTED]", result);
        }

        @Test
        void controlCharsPattern_compiles_and_matches() {
            // when / then
            assertTrue(YamlExceptionStrategy.CONTROL_CHARS.matcher("\u0001").find());
        }

        @Test
        void sensitiveKeyPattern_does_not_match_normal_keys() {
            Stream.of(
                    "hostname: server",
                    "port: 8080",
                    "enabled: true",
                    "timeout: 30s",
                    // граничные случаи:
                    "tokenizer: some-value",     // ключ начинается с "token" но не равен ему
                    "passwords_count: 42",       // "password" как prefix составного ключа
                    "api_key_description: text"  // "api_key" как prefix
            ).forEach(input ->
                    assertFalse(
                            YamlExceptionStrategy.SENSITIVE_KEY_VALUE.matcher(input).find(),
                            "Expected no match for: " + input
                    )
            );
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static Object instantiate(String className, String message) {
        try {
            Class<?> clazz = Class.forName(className);
            Constructor<?> ctor = clazz.getConstructor(String.class);
            return ctor.newInstance(message);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static Object instantiateWithCause(String className, String message, Throwable cause) {
        try {
            Class<?> clazz = Class.forName(className);
            Constructor<?> ctor = clazz.getConstructor(String.class, Throwable.class);
            return ctor.newInstance(message, cause);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> diagnostic(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> clientMeta(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getClientMetadata)
                .findFirst()
                .orElseThrow();
    }

    private static String reason(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertDiagnostic(RootCauseResolution details, String key, String expected) {
        assertEquals(expected, diagnostic(details).get(key),"diagnosticMetadata[" + key + "]");
    }

    private static void assertClientMeta(RootCauseResolution details, String key, String expected) {
        assertEquals(expected, clientMeta(details).get(key),"clientMetadata[" + key + "]");
    }

    @SuppressWarnings("SameParameterValue")
    private static void assertReason(RootCauseResolution details, String expected) {
        assertEquals(expected, reason(details));
    }

}
