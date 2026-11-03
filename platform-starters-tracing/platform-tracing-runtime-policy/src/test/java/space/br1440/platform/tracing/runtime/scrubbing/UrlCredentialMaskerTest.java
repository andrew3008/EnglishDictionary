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
                Arguments.of("http://h/user:pass@http://evil.com", "http://h/user:pass@http://evil.com"),
                Arguments.of("http://h/user:pass@http://u:secret@evil.com", "http://h/user:pass@http://***@evil.com"),
                Arguments.of("https://host/?token=secret&limit=20", "https://host/?token=***&limit=***"),
                Arguments.of("https://host/?flag&empty=&token=a=b=c&&other=z", "https://host/?***&empty=&token=***&&other=***"),
                Arguments.of("https://host/?=secret", "https://host/?=***"),
                Arguments.of("https://host/?token=sec", "https://host/?token=***"),
                Arguments.of("https://host/?token=", "https://host/?token="),
                Arguments.of("https://host/?token=secret#section", "https://host/?token=***#***"),
                Arguments.of("https://host/#fragment?literal=value", "https://host/#***"),
                Arguments.of("https://host/#access_token=secret&token_type=bearer", "https://host/#***"),
                Arguments.of("https://host/#access_token%3Dencoded-secret", "https://host/#***"),
                Arguments.of("https://host/#eyJhbGciOiJIUzI1NiJ9", "https://host/#***"),
                Arguments.of("https://host/#section", "https://host/#***"),
                Arguments.of("https://h/#,https://example.com/SENTINEL", "https://h/#***"),
                Arguments.of("https://h/#x;https://example.com/SENTINEL", "https://h/#***"),
                Arguments.of("https://h/?flag;https://example.com/SENTINEL", "https://h/?***"),
                Arguments.of("https://h/?&https://example.com/SENTINEL", "https://h/?&***"),
                Arguments.of("https://h/#secret:;.,'", "https://h/#***"),
                Arguments.of("https://h/(#SENTINEL))", "https://h/(#***"),
                Arguments.of("https://h/(path?token=SENTINEL))", "https://h/(path?token=***"),
                Arguments.of("(https://h/(#SENTINEL)))", "(https://h/(#***)"),
                Arguments.of("https://u:(@h/path))", "https://***@h/path))"),
                Arguments.of("https://host/#...", "https://host/#***"),
                Arguments.of("https://host/?...", "https://host/?***"),
                Arguments.of("https://host/?empty=&...", "https://host/?empty=&***"),
                Arguments.of("Failed (https://host/#...). Retry.", "Failed (https://host/#***). Retry."),
                Arguments.of("Failed 'https://host/?...' again", "Failed 'https://host/?***' again"),
                Arguments.of("https://host/#", "https://host/#"),
                Arguments.of("https://host/#opaquehttps://next/path", "https://host/#***"),
                Arguments.of("https://host/?AKIAsecret", "https://host/?***"),
                Arguments.of("https://host/?access_token%3Dsecret", "https://host/?***"),
                Arguments.of("https://host/?token%3DSENTINEL=public", "https://host/?***"),
                Arguments.of("https://host/?token%253DSENTINEL=public", "https://host/?***"),
                Arguments.of("https://h/?SENTINEL://target/path", "https://h/?***"),
                Arguments.of("https://h/?SENTINELhttps://next/", "https://h/?***"),
                Arguments.of("https://h/?SENTINEL://target/path?x=y", "https://h/?***"),
                Arguments.of("https://h/?SENTINELhttps://target/path?x=y", "https://h/?***"),
                Arguments.of("https://h/?SENTINEL%3A%2F%2Ftarget/path?x=y", "https://h/?***"),
                Arguments.of("https://h/?flag;SENTINEL://target/path", "https://h/?***"),
                Arguments.of("https://host/?first&second&&", "https://host/?***&***&&"),
                Arguments.of("HTTP: connection refused", "HTTP: connection refused"),
                Arguments.of("Invalid path: //server/share", "Invalid path: //server/share"),
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
                Arguments.of("Failed https://host/?token=secret: retry", "Failed https://host/?token=*** retry"),
                Arguments.of("https://host/?token=:", "https://host/?token=***"),
                Arguments.of("Failed (https://host/?token=secret). Retry.", "Failed (https://host/?token=***). Retry."),
                Arguments.of("Failed [https://[::1]/?token=secret]", "Failed [https://[::1]/?token=***]"),
                Arguments.of("Failed 'https://host/?token=secret' again", "Failed 'https://host/?token=***' again"),
                Arguments.of("Failed \"https://host/?token=secret\" again", "Failed \"https://host/?token=***\" again"),
                Arguments.of("Failed {https://host/?token=secret}", "Failed {https://host/?token=***}"),
                Arguments.of("Failed {https://host/#...}.", "Failed {https://host/#***}."),
                Arguments.of("Failed https://host/?token=secret' again", "Failed https://host/?token=*** again"),
                Arguments.of("https://user:secret@host'", "https://***@host'"),
                Arguments.of("https://host/?token=sec'ret", "https://host/?token=***"),
                Arguments.of("https://host/?token=sec)ret", "https://host/?token=***"),
                Arguments.of("https://host/?token=(secret)", "https://host/?token=***"),
                Arguments.of("https://host/?token=first;second", "https://host/?token=***"),
                Arguments.of("https://host/?token=.)", "https://host/?token=***"),
                Arguments.of("https://h/path;https://user:secret@h/path", "https://h/path;https://***@h/path"),
                Arguments.of("https://h/#fragment,https://user:secret@h/?token=secret", "https://h/#***"),
                Arguments.of("https://h/#access_token=secret,https://user:secret@h/", "https://h/#***"),
                Arguments.of("https://h/#fragment'https://user:secret@h/", "https://h/#***"),
                Arguments.of("https://h/#...;https://user:SENTINEL@h/", "https://h/#***"),
                Arguments.of("https://h/?redirect=https://user:secret@target/path?token=secret", "https://h/?redirect=***"),
                Arguments.of("https://h/?flag;https://user:secret@host/path", "https://h/?***"),
                Arguments.of("https://h/?next&https://user:secret@host/path", "https://h/?***&***"),
                Arguments.of("First https://u:p@a/?x=1, then http://b/?y=2!", "First https://***@a/?x=*** then http://b/?y=***"),
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
                "http://host:/path",
                "https://",
                "https:///path",
                "https://?token=secret",
                "https://user:secret@/path",
                "https://user%3Asecret%40host/path",
                "https://host%2Fuser:secret/path",
                "https:/user:secret@host/path",
                "HTTPS:user:secret@host",
                "https:",
                "https://host\\secret/path",
                "http:\u001C//user:secret@host",
                "http:\t//user:secret@host",
                "HTTPS: //user:secret@host",
                "ftp:\uFEFF//user:secret@host",
                "jdbc:postgresql:\u200B//app:secret@db",
                "redis:\u00A0//:secret@cache",
                "jdbc:\t//h?token=secret&ftp://:pw@host'",
                "http://host\u001C?token=secret"
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
    void dropsDenseIncompleteSchemesWithinScanLimit() {
        // given
        String message = "a://".repeat(1_024);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(message).hasSize(4_096);
        assertThat(result).isNull();
    }

    @Test
    void scansManyAdjacentUrlsWithinScanLimit() {
        // given
        String message = "a://h;".repeat(682);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(message).hasSize(4_092);
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
    void rejectsExpansionOfDenseOpaqueParameters() {
        // given
        String message = "https://h/?" + "a&".repeat(2_042);

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(message).hasSize(4_095);
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

    @Test
    void acceptsMaskedResultAtScanLimit() {
        // given
        String message = "x".repeat(4_079) + " https://h/?q=s";

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(result).isEqualTo("x".repeat(4_079) + " https://h/?q=***").hasSize(4_096);
    }

    @Test
    void rejectsMaskedResultBeyondScanLimit() {
        // given
        String message = "x".repeat(4_080) + " https://h/?q=s";

        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        assertThat(message).hasSizeLessThan(4_096);
        assertThat(result).isNull();
    }

    @ParameterizedTest
    @MethodSource("secretMessages")
    void neverReturnsSentinelFromAnAmbiguousOrSecretUrl(String message) {
        // when
        String result = UrlCredentialMasker.mask(message);

        // then
        if (result != null) {
            assertThat(result).doesNotContain("SENTINEL");
            assertThat(UrlCredentialMasker.mask(result)).isEqualTo(result);
        }
    }

    static Stream<String> secretMessages() {
        return Stream.of(
                "https://user:SENTINEL@host/path",
                "HTTPS://host/?token=SENTINEL&empty=&SENTINEL",
                "https://host/?token%3DSENTINEL",
                "https://host/?token%3DSENTINEL=public",
                "https://host/?token%253DSENTINEL=public",
                "https://h/?SENTINEL://target/path",
                "https://h/?SENTINELhttps://next/",
                "https://host/#SENTINEL",
                "https://user%3ASENTINEL%40host/path",
                "https:/user:SENTINEL@host/path",
                "https://user:SENTINEL",
                "https://?token=SENTINEL",
                "https://first/?token=SENTINEL;https://second/#SENTINEL"
        );
    }
}
