package space.br1440.platform.tracing.runtime.scrubbing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DiagnosticTextSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "", "Connection refused", "SQLSTATE 08006: connection closed", "Timeout after 200 ms",
            "Password was not provided", "token=", "Bearer", "Basic", "<redacted>",
            "https://host/path", "https://***@host.example/?token=***#***", "password=***",
            "password=***\n", "user@example..com", "eyJx.a"
    })
    void preservesSafeTextAndReference(String text) {
        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isSameAs(text);
        assertThat(DiagnosticTextSanitizer.mask(result)).isSameAs(result);
    }

    @ParameterizedTest
    @MethodSource("urls")
    void preservesPartialUrlMasking(String text, String expected) {
        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(expected);
        assertThat(DiagnosticTextSanitizer.mask(result)).isSameAs(result);
    }

    static Stream<Arguments> urls() {
        return Stream.of(
                Arguments.of("https://user:CANARY@host.example/path", "https://***@host.example/path"),
                Arguments.of("https://host/?password=CANARY&token=CANARY#CANARY",
                        "https://host/?password=***&token=***#***"),
                Arguments.of("https://host/?token=***suffix&mode=CANARY", "https://host/?token=***&mode=***"),
                Arguments.of("https://host/?token=***@CANARY", "https://host/?token=***"),
                Arguments.of("https://host/?token='***'CANARY", "https://host/?token=***"),
                Arguments.of("https://host/#access_token=CANARY", "https://host/#***"),
                Arguments.of("https://host/?token=eyJx.payload.signature", "https://host/?token=***"),
                Arguments.of("https://host/?email=user@example.com", "https://host/?email=***"),
                Arguments.of("https://host/?token%3DCANARY", "https://host/?***"),
                Arguments.of("Failed jdbc:postgresql://user:CANARY@db/orders?password=CANARY",
                        "Failed jdbc:postgresql://***@db/orders?password=***"),
                Arguments.of("First https://user:CANARY@a/path then https://b/?q=CANARY",
                        "First https://***@a/path then https://b/?q=***")
        );
    }

    @ParameterizedTest
    @MethodSource("sensitiveLabels")
    void redactsSensitiveAssignments(String label) {
        // given
        String text = "Operation failed: " + label + "=CANARY";

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(DiagnosticTextSanitizer.REDACTED);
        assertThat(DiagnosticTextSanitizer.mask(result)).isSameAs(result);
    }

    static Stream<String> sensitiveLabels() {
        return Stream.of(
                "password", "passwd", "pwd", "secret", "client-secret", "token", "access-token",
                "refresh-token", "api-key", "session-id", "private-key", "ssn", "social-security-number",
                "first-name", "last-name", "full-name", "given-name", "family-name", "preferred-username",
                "email", "phone", "passport", "card-number"
        ).flatMap(label -> Stream.of(label, label.replace('-', '_'), label.replace('-', '.'))).distinct();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PASSWORD: CANARY", "ClientSecret=CANARY", "accessToken=CANARY", "firstName: CANARY",
            "{\"password\":\"CANARY\"}", "{'api_key': 'CANARY'}", "\"full.name\" : \"CANARY\"",
            "{\\\"password\\\":\\\"CANARY\\\"}", "SELECT * FROM users WHERE ssn='123'",
            "Authorization: Bearer CANARY", "proxy-Authorization: Basic Q0FOQVJZ",
            "Bearer CANARY", "bearer\tCANARY", "BASIC Q0FOQVJZ", "Cookie: session=CANARY",
            "Set-Cookie: arbitrary=CANARY; HttpOnly", "eyJx.payload.signature", "eyJx.payload.",
            "Contact user@example.com", "Request failed\nuser@example.com\nRetry", "user@example.xn--p1ai",
            "-----BEGIN PRIVATE KEY-----\nCANARY", "-----BEGIN RSA PRIVATE KEY-----\nCANARY",
            "-----BEGIN EC PRIVATE KEY-----\nCANARY", "-----BEGIN OPENSSH PRIVATE KEY-----\nCANARY",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----\nCANARY", "-----BEGIN DSA PRIVATE KEY-----\nCANARY",
            "https://user:CANARY@host/ password=SECOND_CANARY",
            "https://host/path/user@example.com", "https://host/path/eyJx.payload.signature",
            "https://service.example/?token=secret eyJhbGciOiJIUzI1NiJ9.e30.signature",
            "Contact o'neil@example.com. Failed https://user:secret@service.example/path",
            "Failed https://user:secret@service.example/path. Contact user{1}@example.com.",
            "Failed https://user:secret@service.example/path. Contact ***@example.com."
    })
    void redactsResidualSensitiveForms(String text) {
        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(DiagnosticTextSanitizer.REDACTED);
        assertThat(DiagnosticTextSanitizer.mask(result)).isSameAs(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password=***suffix", "password=***@CANARY", "password='***'", "password=\"***\"",
            "password=***\\CANARY", "password=***&CANARY", "password=***#CANARY",
            "password=*** CANARY", "password=***\r\nCANARY", "password=***'CANARY",
            "password=***!", "password=***)", "password=***;CANARY", "password=***:CANARY",
            "https://host/?ok=*** password=***&CANARY", "https://host/?ok=***\npassword=***#CANARY",
            "https://host/path password=***&CANARY", "'https://host/?ok=***' password=***#CANARY",
            "Failed https://host/?token=*** retry", "Failed (https://host/?token=***)"
    })
    void redactsMarkersWithoutProvenValueBoundary(String text) {
        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(DiagnosticTextSanitizer.REDACTED);
    }

    @ParameterizedTest
    @ValueSource(chars = {'\'', '"', '`', '<', '>', '(', ')', '[', ']', '{', '}', '\\', ';', ','})
    void masksQuoteAndPunctuationInsideUrlQuery(char separator) {
        // given
        String text = "https://h/?x=***" + separator + "password=***&CANARY";

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo("https://h/?x=***&***");
        assertThat(DiagnosticTextSanitizer.mask(result)).isSameAs(result);
    }

    @ParameterizedTest
    @ValueSource(chars = {' ', '\t', '\r', '\n'})
    void doesNotExtendUrlMarkerExemptionBeyondTextDelimiter(char separator) {
        // given
        String text = "https://h/?x=***" + separator + "password=***&CANARY";

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(DiagnosticTextSanitizer.REDACTED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"https:/user:CANARY@host", "https://user:CANARY", "https://?token=CANARY"})
    void dropsNullOrAmbiguousUrls(String text) {
        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {4_095, 4_096, 4_097})
    void boundsInputInUtf16Units(int length) {
        // given
        String text = "\uD83D\uDE00".repeat(length / 2) + "x".repeat(length % 2);

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(text).hasSize(length);
        if (length > DiagnosticTextSanitizer.MAX_TEXT_LENGTH) {
            assertThat(result).isNull();
        } else {
            assertThat(result).isSameAs(text);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4_079, 4_080})
    void boundsOutputAfterUrlExpansion(int prefixLength) {
        // given
        String text = "x".repeat(prefixLength) + " https://h/?q=s";

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        if (prefixLength == 4_079) {
            assertThat(result).isEqualTo("x".repeat(prefixLength) + " https://h/?q=***").hasSize(4_096);
        } else {
            assertThat(result).isNull();
        }
    }

    @Test
    void examinesSensitiveLabelBeyondFacadeMessageLimit() {
        // given
        String text = "x".repeat(510) + " password=CANARY";

        // when
        String result = DiagnosticTextSanitizer.mask(text);

        // then
        assertThat(result).isEqualTo(DiagnosticTextSanitizer.REDACTED);
    }
}
