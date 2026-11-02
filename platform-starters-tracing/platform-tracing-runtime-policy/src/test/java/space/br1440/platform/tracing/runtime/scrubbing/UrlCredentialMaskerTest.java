package space.br1440.platform.tracing.runtime.scrubbing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UrlCredentialMaskerTest {

    @ParameterizedTest
    @MethodSource("messages")
    void masksCredentialsAndPreservesDiagnosticText(String message, String expected) {
        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isEqualTo(expected);
        assertThat(UrlCredentialMasker.mask(Objects.requireNonNull(result))).isEqualTo(result);
    }

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of("Connection refused", "Connection refused"),
                Arguments.of("", ""),
                Arguments.of("https://host/path", "https://host/path"),
                Arguments.of("https://host:8443/path", "https://host:8443/path"),
                Arguments.of("https://user:secret@host/path", "https://***@host/path"),
                Arguments.of("http://user@host/", "http://***@host/"),
                Arguments.of("https://user:first@second@host/", "https://***@host/"),
                Arguments.of("https://host/?token=secret&limit=20", "https://host/?token=***&limit=***"),
                Arguments.of("https://host/?flag&empty=&token=a=b=c&&other=z", "https://host/?flag&empty=&token=***&&other=***"),
                Arguments.of("https://host/?=secret", "https://host/?=***"),
                Arguments.of("https://host/?token=sec", "https://host/?token=***"),
                Arguments.of("https://host/?token=", "https://host/?token="),
                Arguments.of("https://host/?token=secret#section", "https://host/?token=***#***"),
                Arguments.of("https://host/#fragment?literal=value", "https://host/#***"),
                Arguments.of("https://host/#access_token=secret&token_type=bearer", "https://host/#***"),
                Arguments.of("https://host/#access_token%3Dencoded-secret", "https://host/#***"),
                Arguments.of("https://host/#eyJhbGciOiJIUzI1NiJ9", "https://host/#***"),
                Arguments.of("https://host/#section", "https://host/#***"),
                Arguments.of("https://host/#", "https://host/#"),
                Arguments.of("https://host/#opaquehttps://next/path", "https://host/#***"),
                Arguments.of("https://host/?AKIAsecret", "https://host/?AKIAsecret"),
                Arguments.of("HTTPS://u:p@host/?token=secret", "HTTPS://***@host/?token=***"),
                Arguments.of("ftp://u:p@host/?token=secret", "ftp://***@host/?token=***"),
                Arguments.of("custom+https://u:p@host/?token=secret", "custom+https://***@host/?token=***"),
                Arguments.of("Failed jdbc:postgresql://app:secret@db:5432/orders?password=secret", "Failed jdbc:postgresql://***@db:5432/orders?password=***"),
                Arguments.of("redis://:secret@cache:6379/0", "redis://***@cache:6379/0"),
                Arguments.of("://user:secret@host", "://***@host"),
                Arguments.of("https://u%40x:p%3Aq@host/?token=a%26b%3Dc", "https://***@host/?token=***"),
                Arguments.of("https://[2001:db8::1]", "https://[2001:db8::1]"),
                Arguments.of("http://[::1]/x", "http://[::1]/x"),
                Arguments.of("http://[::1]:8080/x", "http://[::1]:8080/x"),
                Arguments.of("https://u:p@[::1]:8443/?q=secret", "https://***@[::1]:8443/?q=***"),
                Arguments.of("Connection to http://host:8080: refused", "Connection to http://host:8080: refused"),
                Arguments.of("Connection to http://host: refused", "Connection to http://host: refused"),
                Arguments.of("https://user:", "https://user:"),
                Arguments.of("Failed https://user:secret@host: retry", "Failed https://***@host: retry"),
                Arguments.of("Failed https://host/?token=secret: retry", "Failed https://host/?token=***: retry"),
                Arguments.of("https://host/?token=:", "https://host/?token=***"),
                Arguments.of("Failed (https://host/?token=secret). Retry.", "Failed (https://host/?token=***). Retry."),
                Arguments.of("Failed [https://[::1]/?token=secret]", "Failed [https://[::1]/?token=***]"),
                Arguments.of("Failed 'https://host/?token=secret' again", "Failed 'https://host/?token=***' again"),
                Arguments.of("Failed \"https://host/?token=secret\" again", "Failed \"https://host/?token=***\" again"),
                Arguments.of("https://host/?token=sec'ret", "https://host/?token=***"),
                Arguments.of("https://host/?token=sec)ret", "https://host/?token=***"),
                Arguments.of("https://host/?token=(secret)", "https://host/?token=***"),
                Arguments.of("https://host/?token=first;second", "https://host/?token=***"),
                Arguments.of("https://host/?token=.)", "https://host/?token=***"),
                Arguments.of("https://h/path;https://user:secret@h/path", "https://h/path;https://***@h/path"),
                Arguments.of("https://h/#fragment,https://user:secret@h/?token=secret", "https://h/#***,https://***@h/?token=***"),
                Arguments.of("https://h/#access_token=secret,https://user:secret@h/", "https://h/#***,https://***@h/"),
                Arguments.of("https://h/#fragment'https://user:secret@h/", "https://h/#***'https://***@h/"),
                Arguments.of("https://h/?redirect=https://user:secret@target/path?token=secret", "https://h/?redirect=***"),
                Arguments.of("https://h/?flag;https://user:secret@host/path", "https://h/?flag;https://***@host/path"),
                Arguments.of("https://h/?next&https://user:secret@host/path", "https://h/?next&https://***@host/path"),
                Arguments.of("First https://u:p@a/?x=1, then http://b/?y=2!", "First https://***@a/?x=***, then http://b/?y=***"),
                Arguments.of("https://host/?token=***", "https://host/?token=***"),
                Arguments.of("http://host ?token=S3cret", "http://host ?token=S3cret"),
                Arguments.of("http://host\u00A0?token=S3cret", "http://host\u00A0?token=***"),
                Arguments.of("http://user\u00A0:S3cret@host", "http://***@host"),
                Arguments.of("http://h?token=S3\u0000cret", "http://h?token=***"),
                Arguments.of("http://host?token=\"S3cret\"", "http://host?token=***"),
                Arguments.of("http://host?token=sec\"ret", "http://host?token=***"),
                Arguments.of("http://host?token=sec<ret", "http://host?token=***"),
                Arguments.of("http://host?token=sec`ret", "http://host?token=***"),
                Arguments.of("http://user:12345", "http://user:12345")
        );
    }

    @ParameterizedTest
    @MethodSource("ambiguousMessages")
    void dropsAmbiguousAuthority(String message) {
        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isNull();
    }

    static Stream<String> ambiguousMessages() {
        return Stream.of(
                "http://user:secret",
                "Failed https://user:secret: retry",
                "https://user:123456",
                "https://user:/secret@host",
                "https://[2001:db8::1",
                "https://[::1]x",
                "https://host:bad/path",
                "http://[user:S3cret]",
                "http://[user:S3cret]:80",
                "http://[user:123456]",
                "http://a@user:S3cret",
                "http://a@[::1]:S3cret",
                "http://user:S3cret://host",
                "http://user:123456://host",
                "http://host:/path"
        );
    }

    @Test
    void endsAuthorityAtFirstSlashEvenBeforeAt() {
        // given
        String message = "http://user:123/x@host";

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    void handlesDenseSeparatorsWithinScanLimit() {
        // given
        String message = "a://".repeat(1_024);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(message).hasSize(4_096);
        assertThat(result).isSameAs(message);
    }

    @Test
    void rejectsOversizedInputWithoutReturningItsPrefix() {
        // given
        String message = "https://user:" + "s".repeat(4_096) + "@host/";

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isNull();
    }

    @Test
    void acceptsInputAtScanLimit() {
        // given
        String message = "x".repeat(4_096);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    void handlesManyShortParametersWithinScanLimit() {
        // given
        String message = "https://host/?" + "a=s&".repeat(500);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isEqualTo("https://host/?" + "a=***&".repeat(500));
        assertThat(UrlCredentialMasker.mask(Objects.requireNonNull(result))).isEqualTo(result);
    }

    @Test
    void rejectsMaskExpansionBeyondScanLimit() {
        // given
        String message = "https://host/?" + "a=s&".repeat(1_000);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isNull();
    }

    @Test
    void preservesReferenceWhenCredentialsAreAlreadyMasked() {
        // given
        String message = "https://***@host/?token=***#***";

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isSameAs(message);
    }
}
