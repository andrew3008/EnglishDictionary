package space.br1440.platform.tracing.otel.propagation.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobTrustedDestinationMatcher")
class GlobTrustedDestinationMatcherTest {

    @Nested
    @DisplayName("hostMode: exact match")
    class HostExact {

        @Test
        void exactHostMatches() {
            var matcher = hostMatcher(List.of("api.example.com"));
            assertThat(matcher.isTrusted("api.example.com")).isTrue();
        }

        @Test
        void differentHostDoesNotMatch() {
            var matcher = hostMatcher(List.of("api.example.com"));
            assertThat(matcher.isTrusted("other.example.com")).isFalse();
        }

        @Test
        void uppercaseInputIsNormalised() {
            var matcher = hostMatcher(List.of("api.example.com"));
            assertThat(matcher.isTrusted("API.EXAMPLE.COM")).isTrue();
        }

        @Test
        void trailingDotInInputIsStripped() {
            var matcher = hostMatcher(List.of("api.example.com"));
            assertThat(matcher.isTrusted("api.example.com.")).isTrue();
        }

        @Test
        void trailingDotInGlobIsStripped() {
            var matcher = hostMatcher(List.of("api.example.com."));
            assertThat(matcher.isTrusted("api.example.com")).isTrue();
        }

        @Test
        void leadingAndTrailingWhitespaceInInputIsStripped() {
            var matcher = hostMatcher(List.of("api.example.com"));
            assertThat(matcher.isTrusted("  api.example.com  ")).isTrue();
        }
    }

    @Nested
    @DisplayName("hostMode: single-label wildcard (*)")
    class HostSingleWildcard {

        @Test
        void singleWildcardMatchesOneLabelSubdomain() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted("foo.example.com")).isTrue();
        }

        @Test
        void singleWildcardDoesNotMatchTwoLevels() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted("a.b.example.com")).isFalse();
        }

        @Test
        void singleWildcardDoesNotMatchApexWithoutSubdomain() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted("example.com")).isFalse();
        }

        @Test
        void singleWildcardRequiresAtLeastOneChar() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted(".example.com")).isFalse();
        }

        @Test
        void singleWildcardDoesNotMatchDotInLabel() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted("foo.bar.example.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("hostMode: multi-label wildcard (**)")
    class HostMultiWildcard {

        @Test
        void doubleWildcardMatchesOneLevelDeep() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("foo.example.com")).isTrue();
        }

        @Test
        void doubleWildcardMatchesTwoLevelsDeep() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("a.b.example.com")).isTrue();
        }

        @Test
        void doubleWildcardMatchesThreeLevelsDeep() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("a.b.c.example.com")).isTrue();
        }

        @Test
        void doubleWildcardDoesNotMatchApexWithoutSubdomain() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("example.com")).isFalse();
        }

        @Test
        void doubleWildcardDoesNotMatchUnrelatedDomain() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("evil.com")).isFalse();
        }

        @Test
        void doubleWildcardDoesNotMatchPartialSuffix() {
            var matcher = hostMatcher(List.of("**.example.com"));
            assertThat(matcher.isTrusted("notexample.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("hostMode: multiple patterns (OR semantics)")
    class HostMultiplePatterns {

        @Test
        void matchesIfAnyPatternMatches() {
            var matcher = hostMatcher(List.of("api.example.com", "grpc.example.com"));
            assertThat(matcher.isTrusted("api.example.com")).isTrue();
            assertThat(matcher.isTrusted("grpc.example.com")).isTrue();
        }

        @Test
        void noMatchIfNoPatternsMatch() {
            var matcher = hostMatcher(List.of("api.example.com", "grpc.example.com"));
            assertThat(matcher.isTrusted("other.example.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("hostMode: IP literal protection")
    class IpLiteralProtection {

        @Test
        void ipV4IsRejectedWhenNotAllowed() {
            var matcher = hostMatcher(List.of("*"), false);
            assertThat(matcher.isTrusted("192.168.1.1")).isFalse();
        }

        @Test
        void ipV4IsAcceptedWhenExplicitlyAllowed() {
            var matcher = hostMatcher(List.of("192.168.1.1"), true);
            assertThat(matcher.isTrusted("192.168.1.1")).isTrue();
        }

        @Test
        void ipV4WildcardIsRejectedEvenWithAllowedFlag() {
            var matcher = hostMatcher(List.of("*"), false);
            assertThat(matcher.isTrusted("10.0.0.1")).isFalse();
        }

        @ParameterizedTest(name = "IPv6 [{0}] → fail-closed (rejected)")
        @ValueSource(strings = {"::1", "2001:db8::1", "[::1]", "fe80::1%eth0"})
        void ipV6IsAlwaysRejectedFailClosed(String ipv6) {
            var matcher = hostMatcher(List.of("*"), true);
            assertThat(matcher.isTrusted(ipv6)).isFalse();
        }
    }

    @Nested
    @DisplayName("hostMode: canonicalization")
    class HostCanonicalization {

        @ParameterizedTest(name = "invalid host [{0}] → rejected")
        @NullSource
        @ValueSource(strings = {"", "  ", "host_name.com", "host name.com", "фoo.com", "host!.com"})
        void invalidHostIsRejected(String host) {
            var matcher = hostMatcher(List.of("*"));
            assertThat(matcher.isTrusted(host)).isFalse();
        }

        @Test
        void underscoreInHostIsRejected() {
            var matcher = hostMatcher(List.of("my_service.internal"));
            assertThat(matcher.isTrusted("my_service.internal")).isFalse();
        }

        @Test
        void hyphenInHostIsAccepted() {
            var matcher = hostMatcher(List.of("my-service.example.com"));
            assertThat(matcher.isTrusted("my-service.example.com")).isTrue();
        }

        @Test
        void numericSubdomainIsAccepted() {
            var matcher = hostMatcher(List.of("*.example.com"));
            assertThat(matcher.isTrusted("123.example.com")).isTrue();
        }
    }

    @Nested
    @DisplayName("topicMode: Kafka topic matching")
    class TopicMode {

        @Test
        void exactTopicMatches() {
            var matcher = topicMatcher(List.of("payments.events"));
            assertThat(matcher.isTrusted("payments.events")).isTrue();
        }

        @Test
        void wildcardMatchesAnyTopic() {
            var matcher = topicMatcher(List.of("payments.*"));
            assertThat(matcher.isTrusted("payments.events")).isTrue();
            assertThat(matcher.isTrusted("payments.commands")).isTrue();
        }

        @Test
        void wildcardDoesNotMatchOtherNamespace() {
            var matcher = topicMatcher(List.of("payments.*"));
            assertThat(matcher.isTrusted("orders.events")).isFalse();
        }

        @Test
        void wildcardMatchesUnderscoreInTopic() {
            var matcher = topicMatcher(List.of("payments.*"));
            assertThat(matcher.isTrusted("payments.dead_letter")).isTrue();
        }

        @Test
        void wildcardDoesNotMatchNewline() {
            var matcher = topicMatcher(List.of("*"));
            assertThat(matcher.isTrusted("topic\ninjected")).isFalse();
        }

        @Test
        void caseSensitiveInTopicMode() {
            var matcher = topicMatcher(List.of("Payments.Events"));
            assertThat(matcher.isTrusted("payments.events")).isFalse();
            assertThat(matcher.isTrusted("Payments.Events")).isTrue();
        }

        @ParameterizedTest(name = "invalid topic [{0}] → rejected")
        @NullSource
        @ValueSource(strings = {"", "  "})
        void invalidTopicIsRejected(String topic) {
            var matcher = topicMatcher(List.of("*"));
            assertThat(matcher.isTrusted(topic)).isFalse();
        }
    }

    @Nested
    @DisplayName("construction: edge cases")
    class Construction {

        @Test
        void nullGlobListProducesNoTrustedDestinations() {
            var matcher = new GlobTrustedDestinationMatcher(null, true, false);
            assertThat(matcher.isTrusted("api.example.com")).isFalse();
        }

        @Test
        void emptyGlobListProducesNoTrustedDestinations() {
            var matcher = new GlobTrustedDestinationMatcher(List.of(), true, false);
            assertThat(matcher.isTrusted("api.example.com")).isFalse();
        }

        @Test
        void nullGlobsInListAreSkipped() {
            var matcher = new GlobTrustedDestinationMatcher(
                    java.util.Arrays.asList(null, "api.example.com", null), true, false);
            assertThat(matcher.isTrusted("api.example.com")).isTrue();
        }

        @Test
        void blankGlobsInListAreSkipped() {
            var matcher = new GlobTrustedDestinationMatcher(
                    List.of("   ", "api.example.com"), true, false);
            assertThat(matcher.isTrusted("api.example.com")).isTrue();
        }
    }

    @Nested
    @DisplayName("security: bypass attempts")
    class SecurityBoundary {

        @Test
        void regexSpecialCharsInGlobAreEscaped() {
            // точка в имени хоста не должна матчить произвольный символ
            var matcher = hostMatcher(List.of("api.example.com"));
            // 'apiXexampleYcom' — точки заменены на произвольные символы
            assertThat(matcher.isTrusted("apiXexampleYcom")).isFalse();
        }

        @Test
        void dollarSignInTopicGlobIsEscaped() {
            var matcher = topicMatcher(List.of("pay$ments"));
            assertThat(matcher.isTrusted("payments")).isFalse();
            assertThat(matcher.isTrusted("pay$ments")).isTrue();
        }

        @Test
        void partialPrefixHostDoesNotMatch() {
            var matcher = hostMatcher(List.of("example.com"));
            assertThat(matcher.isTrusted("evil.example.com")).isFalse();
            assertThat(matcher.isTrusted("evilexample.com")).isFalse();
        }

        @Test
        void partialSuffixHostDoesNotMatch() {
            var matcher = hostMatcher(List.of("example.com"));
            assertThat(matcher.isTrusted("example.com.evil")).isFalse();
        }

        @Test
        void newlineInjectionInTopicIsRejected() {
            var matcher = topicMatcher(List.of("payments.*"));
            assertThat(matcher.isTrusted("payments.events\nX-Injected: true")).isFalse();
        }
    }

    private static GlobTrustedDestinationMatcher hostMatcher(List<String> globs) {
        return new GlobTrustedDestinationMatcher(globs, true, false);
    }

    private static GlobTrustedDestinationMatcher hostMatcher(List<String> globs, boolean allowIpLiterals) {
        return new GlobTrustedDestinationMatcher(globs, true, allowIpLiterals);
    }

    private static GlobTrustedDestinationMatcher topicMatcher(List<String> globs) {
        return new GlobTrustedDestinationMatcher(globs, false, false);
    }
}
