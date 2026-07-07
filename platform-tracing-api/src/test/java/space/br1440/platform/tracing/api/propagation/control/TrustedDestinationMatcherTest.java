package space.br1440.platform.tracing.api.propagation.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TrustedDestinationMatcher: host hardening и label-aware glob")
class TrustedDestinationMatcherTest {

    @Test
    @DisplayName("точное совпадение хоста")
    void exactHostMatch() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("api.internal"));
        assertThat(m.isTrusted("api.internal")).isTrue();
        assertThat(m.isTrusted("API.INTERNAL")).isTrue(); // case-insensitive
        assertThat(m.isTrusted("api.internal.")).isTrue(); // trailing dot (FQDN)
    }

    @Test
    @DisplayName("* соответствует ровно одному label'у, не нескольким")
    void singleLabelWildcard() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("*.trusted.com"));
        assertThat(m.isTrusted("a.trusted.com")).isTrue();
        assertThat(m.isTrusted("a.b.trusted.com")).isFalse(); // overly-broad trust исключён
    }

    @Test
    @DisplayName("** соответствует одному и более label'ам")
    void multiLabelWildcard() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("**.trusted.com"));
        assertThat(m.isTrusted("a.trusted.com")).isTrue();
        assertThat(m.isTrusted("a.b.trusted.com")).isTrue();
    }

    @Test
    @DisplayName("обходные кейсы allowlist отклоняются (DENY)")
    void bypassAttemptsDenied() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("trusted.com", "*.trusted.com"));
        assertThat(m.isTrusted("trusted.com.evil.com")).isFalse();
        assertThat(m.isTrusted("eviltrusted.com")).isFalse();
        assertThat(m.isTrusted("trusted.com:8443")).isFalse();        // порт -> невалидный host
        assertThat(m.isTrusted("trusted.com@evil.com")).isFalse();     // userinfo -> '@' недопустим
        assertThat(m.isTrusted("trusted.com%00.evil.com")).isFalse();  // null-byte
        assertThat(m.isTrusted("trusted.com\r\nHost: evil")).isFalse(); // CRLF
    }

    @Test
    @DisplayName("IP-литералы запрещены по умолчанию")
    void ipLiteralsDeniedByDefault() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("*", "**", "127.0.0.1"));
        assertThat(m.isTrusted("127.0.0.1")).isFalse();
        assertThat(m.isTrusted("[::1]")).isFalse(); // IPv6-скобки -> невалидный host
    }

    @Test
    @DisplayName("null/blank host -> DENY")
    void nullOrBlankDenied() {
        TrustedDestinationMatcher m = new TrustedDestinationMatcher(List.of("*.trusted.com"));
        assertThat(m.isTrusted(null)).isFalse();
        assertThat(m.isTrusted("  ")).isFalse();
    }

    @Test
    @DisplayName("Kafka-топики: case-sensitive, * — любая последовательность")
    void kafkaTopics() {
        TrustedDestinationMatcher m = TrustedDestinationMatcher.forKafkaTopics(List.of("orders.*", "billing_events"));
        assertThat(m.isTrusted("orders.v1")).isTrue();
        assertThat(m.isTrusted("orders.v1.dlq")).isTrue();
        assertThat(m.isTrusted("billing_events")).isTrue(); // '_' допустим в топиках
        assertThat(m.isTrusted("Orders.v1")).isFalse();     // регистр значим
        assertThat(m.isTrusted("payments")).isFalse();
    }
}
